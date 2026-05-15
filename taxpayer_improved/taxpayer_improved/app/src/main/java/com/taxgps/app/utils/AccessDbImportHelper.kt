package com.taxgps.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * مستورد ملفات Microsoft Access (.accdb / .mdb)
 *
 * الإصلاحات الرئيسية (v2):
 * ─────────────────────────────────────────────────────────────────────
 * 1. قراءة الملف بأجزاء (Chunked Streaming) بدلاً من تحميله كاملاً في الذاكرة
 *    → يمنع OutOfMemoryError على ملفات Access الكبيرة (100+ ميغا)
 * 2. حد أقصى 20 ميغابايت لحجم الملف المقبول — ملفات Access الأكبر
 *    يجب تصديرها إلى CSV أولاً
 * 3. معالجة أخطاء محسّنة مع رسائل واضحة للمستخدم
 * 4. إلغاء العملية ممكن في أي وقت (coroutine cancellation)
 *
 * ملاحظة مهمة:
 * ─────────────────────────────────────────────────────────────────────
 * ملفات Access تستخدم تنسيق Jet/ACE الثنائي المعقد. القراءة المباشرة
 * بتحليل النص ليست موثوقة 100%. الطريقة الأفضل هي:
 * - تصدير البيانات من Access إلى CSV ثم استيرادها
 * - أو استخدام مكتبة Jackcess (Java) لقراءة الملف بشكل صحيح
 *
 * بنية الجدول المتوقعة (سجلات_الدخل_المقطوع):
 * السجل | اسم المكلف | اسم الأم | رقم القرار | تاريخ القرار |
 * الملاحظات | المهنة | العنوان | مقدار الضريبة | رقم العمل | الربح الصافي
 */
class AccessDbImportHelper(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "AccessDbImport"
        private const val BATCH_SIZE = 100
        private const val PROGRESS_INTERVAL = 50
        private const val MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024  // 20 MB حد أقصى
        private const val CHUNK_SIZE = 512 * 1024  // 512 KB per chunk for streaming
    }

    // ── واجهة التقدم ─────────────────────────────────────────────────────────

    interface ImportListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onFinished(result: ImportResult)
        fun onError(error: String)
    }

    data class ImportResult(
        val added: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
        val errors: Int = 0
    ) {
        val total get() = added + updated + skipped + errors
    }

    // ── الاستيراد الرئيسي ─────────────────────────────────────────────────────

    /**
     * استيراد ملف Access (.accdb) من URI
     *
     * الاستراتيجية المحسّنة:
     * 1. التحقق من حجم الملف أولاً
     * 2. قراءة الملف بأجزاء (chunks) لتجنب OOM
     * 3. استخراج النصوص العربية من كل جزء
     * 4. تجميع السجلات وإدخالها على دفعات
     */
    suspend fun importFromUri(
        uri: Uri,
        listener: ImportListener,
        clearExisting: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            // ── الخطوة 1: فتح الملف والتحقق من الحجم ──
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (fileDescriptor == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف") }
                return@withContext
            }

            val fileSize = fileDescriptor.statSize
            fileDescriptor.close()

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                withContext(Dispatchers.Main) {
                    listener.onError(
                        "حجم الملف كبير جداً (${fileSize / 1024 / 1024} ميغابايت).\n\n" +
                        "الحد الأقصى المسموح: ${MAX_FILE_SIZE_BYTES / 1024 / 1024} ميغابايت.\n\n" +
                        "الحل: صدّر البيانات من Access إلى ملف CSV ثم استورده."
                    )
                }
                return@withContext
            }

            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف") }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                listener.onProgress(0, 0, "جاري تحليل الملف...")
            }

            if (clearExisting) {
                db.deleteAllAsync()
                Log.i(TAG, "Existing data cleared")
            }

            // ── الخطوة 2: قراءة واستخراج السجلات بأجزاء ──
            val records = extractRecordsStreaming(inputStream, fileSize, listener)
            Log.i(TAG, "Extracted ${records.size} unique records")

            if (records.isEmpty()) {
                withContext(Dispatchers.Main) {
                    listener.onError(
                        "لم يتم العثور على سجلات في الملف.\n\n" +
                        "الأسباب المحتملة:\n" +
                        "• الملف ليس بصيغة Access صالحة\n" +
                        "• الجدول لا يحتوي على أسماء عربية\n\n" +
                        "الحل: صدّر البيانات من Access إلى CSV واستورده من خيار 'استيراد CSV'"
                    )
                }
                return@withContext
            }

            // ── الخطوة 3: إدخال السجلات في قاعدة البيانات ──
            val result = insertRecords(records, listener)

            withContext(Dispatchers.Main) {
                listener.onFinished(result)
            }

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during import", e)
            withContext(Dispatchers.Main) {
                listener.onError(
                    "الملف كبير جداً على ذاكرة الجهاز.\n\n" +
                    "الحل: صدّر البيانات من Access إلى ملف CSV ثم استورده."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("خطأ أثناء الاستيراد: ${e.message}")
            }
        }
    }

    // ── استخراج السجلات بقراءة متدفقة (Streaming) ─────────────────────────────

    /**
     * بدلاً من تحميل الملف كاملاً، نقرأه بأجزاء (chunks)
     * ونستخرج النصوص من كل جزء مع تداخل (overlap) لتجنب قطع السجلات
     */
    private suspend fun extractRecordsStreaming(
        inputStream: InputStream,
        fileSize: Long,
        listener: ImportListener
    ): List<AccessRecord> = withContext(Dispatchers.IO) {

        val allRecords = mutableListOf<AccessRecord>()
        val buffered = BufferedInputStream(inputStream, CHUNK_SIZE)
        var totalBytesRead = 0L
        var chunkIndex = 0

        // overlap: نحتفظ بآخر 1KB من الجزء السابق لتجنب قطع نص بين جزأين
        val overlapSize = 1024
        var previousTail = ByteArray(0)

        try {
            while (isActive) {
                val chunk = ByteArray(CHUNK_SIZE)
                val bytesRead = buffered.read(chunk)
                if (bytesRead <= 0) break

                totalBytesRead += bytesRead
                chunkIndex++

                // دمج ذيل الجزء السابق مع بداية هذا الجزء
                val combined = if (previousTail.isNotEmpty()) {
                    previousTail + chunk.copyOf(bytesRead)
                } else {
                    chunk.copyOf(bytesRead)
                }

                // استخراج النصوص من هذا الجزء
                extractFromChunk(combined, allRecords)

                // حفظ آخر overlap bytes للجزء التالي
                previousTail = if (bytesRead > overlapSize) {
                    chunk.copyOfRange(bytesRead - overlapSize, bytesRead)
                } else {
                    chunk.copyOf(bytesRead)
                }

                // تحديث التقدم
                if (chunkIndex % 2 == 0) {
                    val percent = if (fileSize > 0) ((totalBytesRead * 100) / fileSize).toInt() else 0
                    withContext(Dispatchers.Main) {
                        listener.onProgress(
                            percent, 100,
                            "جاري تحليل الملف... $percent% (${allRecords.size} سجل)"
                        )
                    }
                }
            }
        } finally {
            try { buffered.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
        }

        // إزالة التكرارات
        val unique = allRecords
            .groupBy { "${it.recordNumber}_${it.name.take(10)}" }
            .map { (_, group) -> group.maxByOrNull { it.completeness() } ?: group.first() }
            .sortedBy { it.recordNumber }

        Log.i(TAG, "Total unique records after dedup: ${unique.size}")
        unique
    }

    /**
     * استخراج السجلات من جزء (chunk) من البيانات الثنائية
     * نجرب عدة ترميزات: UTF-16LE (الأصلي لـ Access) ثم UTF-8 ثم Windows-1256
     */
    private fun extractFromChunk(data: ByteArray, records: MutableList<AccessRecord>) {
        // محاولة 1: UTF-16LE (التنسيق الداخلي لملفات Access)
        try {
            val text16 = String(data, Charset.forName("UTF-16LE"))
            extractFromText(text16, records)
        } catch (e: Exception) {
            Log.v(TAG, "UTF-16LE chunk parse failed: ${e.message}")
        }

        // محاولة 2: UTF-8
        try {
            val text8 = String(data, Charsets.UTF_8)
            extractFromText(text8, records)
        } catch (e: Exception) {
            Log.v(TAG, "UTF-8 chunk parse failed: ${e.message}")
        }

        // محاولة 3: Windows-1256 (ترميز عربي شائع في ملفات Access القديمة)
        try {
            val textAr = String(data, Charset.forName("windows-1256"))
            extractFromText(textAr, records)
        } catch (e: Exception) {
            Log.v(TAG, "Windows-1256 chunk parse failed: ${e.message}")
        }
    }

    /**
     * استخراج السجلات من نص — أنماط regex موسّعة
     */
    private fun extractFromText(text: String, records: MutableList<AccessRecord>) {
        // نمط 1: اسم عربي + رقم + تاريخ + أرقام + (حديث|دورة) + سنة + مهنة + عنوان
        val pattern1 = Regex(
            """([\u0600-\u06FF\s]{4,50}?)(\d{1,5})\s{0,5}(\d{1,2}[/\\]\d{1,2}[/\\]\d{4})(\d{2,15})(حديث|دورة)\s+(\d{4})([\u0600-\u06FF\s\-]{2,40}?)(القطيلبية|الصليب|الدالية|طوق جبلة|قرى المركز|قرى مركز|قر المركز|سيانو|عين شقاق|عرب الملك|مفرق العقيبة)"""
        )

        for (match in pattern1.findAll(text)) {
            try {
                val name = match.groupValues[1].trim()
                val recordNum = match.groupValues[2].trim().toIntOrNull() ?: 0
                val date = match.groupValues[3].trim()
                val numbers = match.groupValues[4].trim()
                val noteType = match.groupValues[5].trim()
                val noteYear = match.groupValues[6].trim()
                val profession = match.groupValues[7].trim()
                val address = match.groupValues[8].trim()
                val parsed = parseNumbers(numbers)

                if (name.length >= 4 && recordNum > 0) {
                    val exists = records.any { it.recordNumber == recordNum && it.name.take(8) == name.take(8) }
                    if (!exists) {
                        records.add(AccessRecord(recordNum, name, "", "", date,
                            "$noteType $noteYear", profession, address,
                            parsed.taxAmount, parsed.workNumber, parsed.netProfit))
                    }
                }
            } catch (e: Exception) { /* skip malformed match */ }
        }

        // نمط 2: اسم عربي + رقم سجل + تاريخ + نص عربي إضافي
        val pattern2 = Regex(
            """([\u0600-\u06FF][\u0600-\u06FF\s]{3,45}?)\s+(\d{1,5})\s+(\d{1,2}[/\\]\d{1,2}[/\\]\d{4})\s+([\u0600-\u06FF\s]{2,30})"""
        )

        for (match in pattern2.findAll(text)) {
            try {
                val name = match.groupValues[1].trim()
                val recordNum = match.groupValues[2].trim().toIntOrNull() ?: 0
                val date = match.groupValues[3].trim()
                val extra = match.groupValues[4].trim()

                if (name.length >= 4 && recordNum > 0) {
                    val exists = records.any { it.recordNumber == recordNum && it.name.take(8) == name.take(8) }
                    if (!exists) {
                        records.add(AccessRecord(recordNum, name, "", "", date,
                            "", extra, "", 0, "", 0))
                    }
                }
            } catch (e: Exception) { /* skip */ }
        }

        // نمط 3: أسماء عربية + رقم + تاريخ (أوسع)
        val pattern3 = Regex(
            """([\u0600-\u06FF][\u0600-\u06FF\s]{5,40})\s+(\d{1,5})\s+(\d{1,2}[/\\]\d{1,2}[/\\]\d{4})"""
        )

        for (match in pattern3.findAll(text)) {
            try {
                val name = match.groupValues[1].trim()
                val recordNum = match.groupValues[2].trim().toIntOrNull() ?: 0
                val date = match.groupValues[3].trim()

                if (name.length >= 5 && recordNum > 0 && !name.contains("جدول") && !name.contains("سجلات")) {
                    val exists = records.any { it.recordNumber == recordNum && it.name.take(8) == name.take(8) }
                    if (!exists) {
                        records.add(AccessRecord(recordNum, name, "", "", date,
                            "", "", "", 0, "", 0))
                    }
                }
            } catch (e: Exception) { /* skip */ }
        }
    }

    // ── إدخال السجلات على دفعات ──────────────────────────────────────────────

    private suspend fun insertRecords(
        records: List<AccessRecord>,
        listener: ImportListener
    ): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var updated = 0
        var skipped = 0
        var errors = 0
        val batch = mutableListOf<Taxpayer>()

        for ((index, record) in records.withIndex()) {
            if (!isActive) break

            try {
                val taxpayer = record.toTaxpayer() ?: run {
                    skipped++
                    continue
                }

                // فحص التكرار
                val existing = db.findByNameAndRecordAsync(taxpayer.name, taxpayer.recordNumber)
                if (existing != null) {
                    db.updateTaxpayerAsync(existing.copy(
                        motherName      = taxpayer.motherName.ifBlank { existing.motherName },
                        accessDecisionNo = taxpayer.accessDecisionNo.ifBlank { existing.accessDecisionNo },
                        decisionDate    = taxpayer.decisionDate.ifBlank { existing.decisionDate },
                        taxAmount       = if (taxpayer.taxAmount > 0) taxpayer.taxAmount else existing.taxAmount,
                        workNumber      = taxpayer.workNumber.ifBlank { existing.workNumber },
                        netProfit       = if (taxpayer.netProfit > 0) taxpayer.netProfit else existing.netProfit,
                        activityType    = taxpayer.activityType.ifBlank { existing.activityType },
                        address         = taxpayer.address.ifBlank { existing.address },
                        notes           = taxpayer.notes.ifBlank { existing.notes }
                    ))
                    updated++
                } else {
                    batch.add(taxpayer)
                    if (batch.size >= BATCH_SIZE) {
                        db.insertBatchAsync(batch)
                        added += batch.size
                        batch.clear()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error at record $index: ${e.message}")
                errors++
            }

            // تحديث التقدم
            if (index % PROGRESS_INTERVAL == 0) {
                withContext(Dispatchers.Main) {
                    listener.onProgress(index + 1, records.size,
                        "جاري الإدخال: ${index + 1} من ${records.size}")
                }
            }
        }

        // إدخال آخر دفعة
        if (batch.isNotEmpty()) {
            db.insertBatchAsync(batch)
            added += batch.size
        }

        ImportResult(added, updated, skipped, errors)
    }

    // ── تحليل الأرقام ─────────────────────────────────────────────────────────

    private data class ParsedNumbers(
        val taxAmount: Long,
        val workNumber: String,
        val netProfit: Long
    )

    private fun parseNumbers(numbers: String): ParsedNumbers {
        if (numbers.length < 6) return ParsedNumbers(0, "", 0)

        return try {
            when {
                numbers.length >= 15 -> {
                    val profit = numbers.takeLast(6).toLongOrNull() ?: 0
                    val tax = numbers.take(4).toLongOrNull() ?: 0
                    val work = numbers.drop(4).dropLast(6)
                    ParsedNumbers(tax, work, profit)
                }
                numbers.length >= 10 -> {
                    val profit = numbers.takeLast(6).toLongOrNull() ?: 0
                    val tax = numbers.take(3).toLongOrNull() ?: 0
                    val work = numbers.drop(3).dropLast(6)
                    ParsedNumbers(tax, work, profit)
                }
                else -> {
                    ParsedNumbers(numbers.toLongOrNull() ?: 0, "", 0)
                }
            }
        } catch (e: Exception) {
            ParsedNumbers(0, "", 0)
        }
    }

    // ── نماذج مساعدة ─────────────────────────────────────────────────────────

    data class AccessRecord(
        val recordNumber: Int,
        val name: String,
        val motherName: String,
        val decisionNo: String,
        val decisionDate: String,
        val notes: String,
        val profession: String,
        val address: String,
        val taxAmount: Long,
        val workNumber: String,
        val netProfit: Long
    ) {
        /** درجة اكتمال السجل (للترجيح عند التكرار) */
        fun completeness(): Int {
            var score = 0
            if (name.isNotBlank()) score += 2
            if (motherName.isNotBlank()) score += 1
            if (decisionNo.isNotBlank()) score += 1
            if (decisionDate.isNotBlank()) score += 1
            if (notes.isNotBlank()) score += 1
            if (profession.isNotBlank()) score += 1
            if (address.isNotBlank()) score += 1
            if (taxAmount > 0) score += 1
            if (netProfit > 0) score += 1
            return score
        }

        fun toTaxpayer(): Taxpayer? {
            if (name.isBlank() || name.length < 3) return null

            val type = when {
                notes.contains("حديث") -> Taxpayer.TYPE_NEW
                else -> Taxpayer.TYPE_OLD
            }

            return Taxpayer(
                recordNumber     = recordNumber,
                name             = name,
                motherName       = motherName,
                accessDecisionNo = decisionNo,
                decisionDate     = decisionDate,
                notes            = notes,
                activityType     = profession,
                address          = address,
                taxAmount        = taxAmount,
                workNumber       = workNumber,
                netProfit        = netProfit,
                type             = type,
                status           = Taxpayer.STATUS_ACTIVE
            )
        }
    }

    // ── استيراد CSV كبديل ─────────────────────────────────────────────────────

    /**
     * استيراد من ملف CSV مُصدَّر من Access
     * (بديل أفضل وأوثق من قراءة .accdb مباشرة)
     */
    suspend fun importFromCsv(
        uri: Uri,
        listener: ImportListener,
        clearExisting: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                withContext(Dispatchers.Main) { listener.onError("لا يمكن فتح الملف") }
                return@withContext
            }

            if (clearExisting) db.deleteAllAsync()

            val importHelper = ImportHelper(db)
            importHelper.importFromCsv(inputStream, object : ImportHelper.ImportListener {
                override fun onProgress(current: Int, estimated: Int, added: Int, updated: Int) {
                    listener.onProgress(current, estimated, "جاري الاستيراد: $current سجل")
                }
                override fun onFinished(result: ImportHelper.ImportResult) {
                    listener.onFinished(ImportResult(result.added, result.updated, result.skipped, result.errors))
                }
                override fun onError(error: String) {
                    listener.onError(error)
                }
            })
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError("خطأ: ${e.message}")
            }
        }
    }
}
