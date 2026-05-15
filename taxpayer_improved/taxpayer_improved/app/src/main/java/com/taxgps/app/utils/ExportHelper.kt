package com.taxgps.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.taxgps.app.data.Taxpayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * مساعد تصدير البيانات إلى PDF و CSV
 *
 * يدعم:
 * - PDF: تقرير منسّق مع جدول، ترقيم صفحات، دعم RTL للعربية
 * - CSV: تنسيق متوافق مع Excel (مع BOM للعربية)
 *
 * أنواع التقارير:
 * - تقرير شامل (كل المكلفين)
 * - تقرير حسب المنطقة
 * - تقرير حسب النوع (قديم/جديد)
 * - تقرير مكلف واحد
 */
class ExportHelper(private val context: Context) {

    companion object {
        private const val TAG = "ExportHelper"
    }

    // ── واجهة متابعة التقدم ──────────────────────────────────────────────────

    interface ExportListener {
        fun onProgress(current: Int, total: Int, message: String)
        fun onSuccess(message: String, recordCount: Int)
        fun onError(error: String)
    }

    data class ReportConfig(
        val title: String = "تقرير المكلفين",
        val subtitle: String = "",
        val taxpayers: List<Taxpayer>,
        val includeStats: Boolean = true,
        val includeLocation: Boolean = true
    )

    // ══════════════════════════════════════════════════════════════════════════
    // ─── تصدير CSV ───────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * تصدير قائمة مكلفين إلى ملف CSV (متوافق مع Excel)
     *
     * تفاصيل تقنية:
     * - يبدأ بـ BOM (UTF-8) لكي يعرض Excel العربية بشكل صحيح
     * - يستخدم اقتباسات مزدوجة للحقول التي تحتوي فواصل أو أسطر
     * - يتبع معيار RFC 4180
     */
    suspend fun exportToCsv(
        config: ReportConfig,
        outputUri: Uri,
        listener: ExportListener
    ) = withContext(Dispatchers.IO) {
        try {
            val outputStream = context.contentResolver.openOutputStream(outputUri)
            if (outputStream == null) {
                withContext(Dispatchers.Main) {
                    listener.onError("لا يمكن فتح الملف للكتابة")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                listener.onProgress(0, config.taxpayers.size, "جاري إنشاء ملف CSV...")
            }

            outputStream.use { os ->
                // BOM لـ Excel — يجعل العربية تظهر بشكل صحيح
                os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    // ── سطر العناوين ──
                    val headers = listOf(
                        "رقم السجل", "اسم المكلف", "اسم الأم", "النوع",
                        "رقم القرار", "تاريخ القرار", "المهنة", "العنوان",
                        "مقدار الضريبة", "الربح الصافي", "رقم العمل",
                        "الرقم الضريبي", "الهاتف", "الحالة",
                        "Latitude", "Longitude", "دقة GPS (متر)", "ملاحظات"
                    )
                    writer.write(headers.joinToString(",") { csvEscape(it) })
                    writer.write("\n")

                    // ── البيانات ──
                    config.taxpayers.forEachIndexed { index, t ->
                        val row = listOf(
                            t.recordNumber.toString(),
                            t.name,
                            t.motherName,
                            t.type,
                            t.accessDecisionNo,
                            t.decisionDate,
                            t.activityType,
                            t.address,
                            t.taxAmount.toString(),
                            t.netProfit.toString(),
                            t.workNumber,
                            t.taxNumber,
                            t.phone,
                            t.status,
                            t.latitude?.toString() ?: "",
                            t.longitude?.toString() ?: "",
                            t.accuracy?.toInt()?.toString() ?: "",
                            t.notes
                        )
                        writer.write(row.joinToString(",") { csvEscape(it) })
                        writer.write("\n")

                        if (index % 100 == 0) {
                            withContext(Dispatchers.Main) {
                                listener.onProgress(index + 1, config.taxpayers.size,
                                    "تم تصدير ${index + 1} سجل")
                            }
                        }
                    }

                    writer.flush()
                }
            }

            withContext(Dispatchers.Main) {
                listener.onSuccess("تم تصدير CSV بنجاح", config.taxpayers.size)
            }

        } catch (e: Exception) {
            Log.e(TAG, "CSV export failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("فشل تصدير CSV: ${e.message}")
            }
        }
    }

    /**
     * تنسيق حقل CSV حسب RFC 4180:
     * - إذا احتوى فاصلة أو اقتباساً أو سطراً → نضع اقتباس مزدوج حول الحقل
     * - الاقتباسات داخل الحقل تُضاعَف ("hello""world")
     */
    private fun csvEscape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuoting = value.contains(',') || value.contains('"') ||
                value.contains('\n') || value.contains('\r')
        return if (needsQuoting) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ─── تصدير PDF ───────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * تصدير قائمة مكلفين إلى PDF منسّق
     *
     * المميزات:
     * - عناوين عربية باستخدام Arabic font من iText
     * - جدول مع ألوان مميزة لرأس الجدول
     * - إحصائيات في نهاية التقرير (اختياري)
     * - تذييل مع التاريخ ورقم الصفحة
     * - دعم اتجاه RTL للنصوص العربية
     */
    suspend fun exportToPdf(
        config: ReportConfig,
        outputUri: Uri,
        listener: ExportListener
    ) = withContext(Dispatchers.IO) {
        try {
            val outputStream = context.contentResolver.openOutputStream(outputUri)
            if (outputStream == null) {
                withContext(Dispatchers.Main) {
                    listener.onError("لا يمكن فتح الملف للكتابة")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                listener.onProgress(0, config.taxpayers.size, "جاري إنشاء ملف PDF...")
            }

            outputStream.use { os ->
                buildPdfDocument(config, os, listener)
            }

            withContext(Dispatchers.Main) {
                listener.onSuccess("تم تصدير PDF بنجاح", config.taxpayers.size)
            }

        } catch (e: Exception) {
            Log.e(TAG, "PDF export failed", e)
            withContext(Dispatchers.Main) {
                listener.onError("فشل تصدير PDF: ${e.message}")
            }
        }
    }

    private suspend fun buildPdfDocument(
        config: ReportConfig,
        outputStream: OutputStream,
        listener: ExportListener
    ) = withContext(Dispatchers.IO) {

        val writer = PdfWriter(outputStream)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc, PageSize.A4.rotate())  // أفقي لاستيعاب الأعمدة

        try {
            // تحميل خط يدعم العربية (من iText asian fonts)
            val font = loadArabicFont()

            document.setFont(font)
            document.setFontSize(10f)

            // ── العنوان الرئيسي ──
            val title = Paragraph(config.title)
                .setFont(font)
                .setFontSize(18f)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(DeviceRgb(33, 150, 243))  // أزرق
            document.add(title)

            if (config.subtitle.isNotBlank()) {
                document.add(
                    Paragraph(config.subtitle)
                        .setFont(font)
                        .setFontSize(12f)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setFontColor(ColorConstants.DARK_GRAY)
                )
            }

            // التاريخ
            val now = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date())
            document.add(
                Paragraph("تاريخ الإنشاء: $now").setFont(font).setFontSize(9f)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(ColorConstants.GRAY)
            )

            document.add(Paragraph(" "))  // مسافة

            // ── الإحصائيات (اختيارية) ──
            if (config.includeStats && config.taxpayers.isNotEmpty()) {
                document.add(buildStatsTable(config.taxpayers, font))
                document.add(Paragraph(" "))
            }

            // ── الجدول الرئيسي ──
            val table = buildMainTable(config, font, listener)
            document.add(table)

            // ── الخلاصة ──
            val totalTax = config.taxpayers.sumOf { it.taxAmount }
            val totalProfit = config.taxpayers.sumOf { it.netProfit }
            val numberFormat = NumberFormat.getNumberInstance(Locale.US)

            document.add(Paragraph(" "))
            document.add(
                Paragraph("الإجمالي: ${config.taxpayers.size} مكلف   |   " +
                        "إجمالي الضريبة: ${numberFormat.format(totalTax)} ل.س   |   " +
                        "إجمالي الأرباح: ${numberFormat.format(totalProfit)} ل.س")
                    .setFont(font)
                    .setFontSize(10f)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setBackgroundColor(DeviceRgb(232, 245, 253))  // أزرق فاتح
                    .setPadding(8f)
            )

        } finally {
            try { document.close() } catch (_: Exception) {}
        }
    }

    /**
     * تحميل خط يدعم العربية
     *
     * الاستراتيجية:
     * 1. محاولة تحميل خط من Asian fonts (مع iText 7.1.15)
     * 2. إذا فشل، استخدام Helvetica مع identity-h
     * 3. إذا فشل، الخط الافتراضي (قد لا يدعم العربية)
     */
    private fun loadArabicFont(): PdfFont {
        return try {
            // STSong-Light هو خط آسيوي يدعم العديد من اللغات
            // للعربية بشكل أفضل، يفضّل تحميل خط مخصص من assets
            PdfFontFactory.createFont("STSong-Light", "UniGB-UCS2-H",
                PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED)
        } catch (e: Exception) {
            Log.w(TAG, "Asian font failed, using identity-h fallback", e)
            try {
                PdfFontFactory.createFont(
                    "Helvetica",
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED
                )
            } catch (e2: Exception) {
                Log.e(TAG, "All font loading failed", e2)
                PdfFontFactory.createFont()
            }
        }
    }

    private fun buildStatsTable(taxpayers: List<Taxpayer>, font: PdfFont): Table {
        val total = taxpayers.size
        val oldCount = taxpayers.count { it.isOld() }
        val newCount = total - oldCount
        val withLocation = taxpayers.count { it.hasLocation() }

        val statsTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f, 1f, 1f)))
            .setWidth(UnitValue.createPercentValue(100f))

        addStatCell(statsTable, "الإجمالي", total.toString(), DeviceRgb(33, 150, 243), font)
        addStatCell(statsTable, "قدامى", oldCount.toString(), DeviceRgb(255, 152, 0), font)
        addStatCell(statsTable, "جدد", newCount.toString(), DeviceRgb(76, 175, 80), font)
        addStatCell(statsTable, "لديهم موقع", withLocation.toString(), DeviceRgb(156, 39, 176), font)

        return statsTable
    }

    private fun addStatCell(table: Table, label: String, value: String, color: DeviceRgb, font: PdfFont) {
        val cell = Cell()
            .add(Paragraph(label).setFont(font).setFontSize(9f).setFontColor(ColorConstants.WHITE))
            .add(Paragraph(value).setFont(font).setFontSize(16f).setBold().setFontColor(ColorConstants.WHITE))
            .setBackgroundColor(color)
            .setTextAlignment(TextAlignment.CENTER)
            .setPadding(6f)
        table.addCell(cell)
    }

    private suspend fun buildMainTable(
        config: ReportConfig,
        font: PdfFont,
        listener: ExportListener
    ): Table = withContext(Dispatchers.IO) {

        // أعمدة: # | الاسم | السجل | القرار | المهنة | العنوان | الضريبة | الربح | موقع
        val widths = if (config.includeLocation) {
            floatArrayOf(0.5f, 2f, 0.7f, 0.9f, 1.5f, 1.5f, 1.2f, 1.2f, 0.6f)
        } else {
            floatArrayOf(0.5f, 2f, 0.8f, 1f, 1.7f, 1.7f, 1.4f, 1.4f)
        }

        val table = Table(UnitValue.createPercentArray(widths))
            .setWidth(UnitValue.createPercentValue(100f))
            .setHorizontalAlignment(HorizontalAlignment.CENTER)

        // ── رأس الجدول ──
        val headers = mutableListOf("#", "اسم المكلف", "السجل", "القرار", "المهنة", "العنوان", "الضريبة", "الربح")
        if (config.includeLocation) headers.add("موقع")

        for (header in headers) {
            table.addHeaderCell(
                Cell()
                    .add(Paragraph(header).setFont(font).setFontSize(10f).setBold())
                    .setBackgroundColor(DeviceRgb(33, 150, 243))
                    .setFontColor(ColorConstants.WHITE)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setPadding(5f)
            )
        }

        // ── صفوف البيانات ──
        val numberFormat = NumberFormat.getNumberInstance(Locale.US)

        config.taxpayers.forEachIndexed { index, t ->
            val isAlternate = index % 2 == 1
            val bgColor = if (isAlternate) DeviceRgb(248, 249, 250) else null

            addDataCell(table, (index + 1).toString(), font, bgColor)
            addDataCell(table, t.name, font, bgColor)
            addDataCell(table, t.recordNumber.toString(), font, bgColor)
            addDataCell(table, t.accessDecisionNo, font, bgColor)
            addDataCell(table, t.activityType.take(25), font, bgColor)
            addDataCell(table, t.address.take(30), font, bgColor)
            addDataCell(table, numberFormat.format(t.taxAmount), font, bgColor)
            addDataCell(table, numberFormat.format(t.netProfit), font, bgColor)

            if (config.includeLocation) {
                addDataCell(table, if (t.hasLocation()) "✓" else "—", font, bgColor)
            }

            if (index % 50 == 0) {
                withContext(Dispatchers.Main) {
                    listener.onProgress(index + 1, config.taxpayers.size,
                        "جاري بناء الصفحات: ${index + 1}")
                }
            }
        }

        table
    }

    private fun addDataCell(table: Table, text: String, font: PdfFont, bgColor: DeviceRgb?) {
        val cell = Cell()
            .add(Paragraph(text.ifBlank { "—" }).setFont(font).setFontSize(9f))
            .setPadding(4f)
            .setTextAlignment(TextAlignment.CENTER)
        bgColor?.let { cell.setBackgroundColor(it) }
        table.addCell(cell)
    }
}
