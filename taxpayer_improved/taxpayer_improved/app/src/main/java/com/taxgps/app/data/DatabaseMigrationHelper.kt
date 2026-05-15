package com.taxgps.app.data

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * مساعد ترقية قاعدة البيانات من غير مشفّرة → مشفّرة
 *
 * ─────────────────────────────────────────────────────────────────────
 * المشكلة:
 * المستخدم لديه قاعدة بيانات قديمة غير مشفّرة (من نسخة v7 وما قبل).
 * بعد التحديث لـ v8، نحتاج لتشفير البيانات الموجودة بدون فقدانها.
 *
 * الحل (Encrypt-in-place):
 * 1. فحص: هل الملف موجود وغير مشفّر؟
 * 2. إذا نعم: نسخه إلى ملف جديد مشفّر باستخدام SQL ATTACH
 * 3. حذف القديم، استبداله بالجديد
 *
 * هذه العملية تتم مرة واحدة فقط — في أول تشغيل بعد التحديث.
 * ─────────────────────────────────────────────────────────────────────
 */
object DatabaseMigrationHelper {

    private const val TAG = "DBMigration"

    /**
     * إن وُجدت قاعدة بيانات قديمة غير مشفّرة، نشفّرها مع الحفاظ على البيانات.
     *
     * @return true إذا تمت الترقية بنجاح أو لم تكن مطلوبة
     *         false إذا فشلت الترقية (يحتاج تدخل يدوي)
     */
    fun encryptLegacyDatabaseIfNeeded(
        context: Context,
        dbName: String,
        passphrase: ByteArray
    ): Boolean {
        val dbFile = context.getDatabasePath(dbName)

        // إن لم يكن الملف موجوداً، فهذه قاعدة جديدة - لا حاجة للترقية
        if (!dbFile.exists()) {
            Log.d(TAG, "No existing database, will create new encrypted DB")
            return true
        }

        // فحص: هل القاعدة مشفّرة بالفعل؟
        if (isAlreadyEncrypted(dbFile, passphrase)) {
            Log.d(TAG, "Database is already encrypted")
            return true
        }

        Log.i(TAG, "Found unencrypted legacy database, starting encryption migration...")

        return try {
            performEncryptionMigration(context, dbFile, passphrase)
            Log.i(TAG, "Encryption migration completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encryption migration FAILED", e)
            // النسخة الاحتياطية تبقى، يمكن للمستخدم استعادتها يدوياً
            false
        }
    }

    /**
     * فحص ما إذا كانت القاعدة مشفّرة بالفعل بالمفتاح المُعطى
     *
     * المنطق: نحاول فتحها بالمفتاح. إذا نجح القراءة من sqlite_master
     * فهي مشفّرة بنفس المفتاح. إذا فشلت، فهي إما غير مشفّرة أو بمفتاح آخر.
     */
    private fun isAlreadyEncrypted(dbFile: File, passphrase: ByteArray): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            // محاولة قراءة sqlite_master للتأكد من القراءة الفعلية
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                cursor.moveToFirst()
            }
            true
        } catch (e: Exception) {
            // فشلت بالمفتاح → غير مشفّرة (أو مشفّرة بمفتاح آخر)
            false
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    /**
     * تنفيذ عملية تشفير القاعدة القديمة
     *
     * الخطوات:
     * 1. إنشاء نسخة احتياطية من القاعدة الأصلية (احتياط)
     * 2. فتح القاعدة القديمة (غير مشفّرة)
     * 3. ATTACH قاعدة جديدة مشفّرة بالمفتاح
     * 4. نسخ الجداول والبيانات: sqlcipher_export()
     * 5. إغلاق وإعادة تسمية الملفات
     */
    private fun performEncryptionMigration(
        context: Context,
        dbFile: File,
        passphrase: ByteArray
    ) {
        val backupFile = File(dbFile.parentFile, "${dbFile.name}.legacy_backup")
        val tempEncryptedFile = File(dbFile.parentFile, "${dbFile.name}.tmp_encrypted")

        // ── الخطوة 1: نسخة احتياطية من الملف الأصلي ──
        if (!backupFile.exists()) {
            dbFile.copyTo(backupFile, overwrite = false)
            Log.i(TAG, "Backup created: ${backupFile.name}")
        }

        // حذف ملف temp إن وُجد من محاولة سابقة فاشلة
        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()

        // ── الخطوة 2: فتح القاعدة القديمة (بدون كلمة سر) ──
        // SQLCipher يدعم فتح قواعد plain SQLite بمفتاح فارغ
        var oldDb: SQLiteDatabase? = null
        try {
            oldDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                ByteArray(0),  // مفتاح فارغ = قاعدة غير مشفّرة
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null
            )

            // ── الخطوة 3: ATTACH قاعدة مشفّرة جديدة ──
            // نستخدم rawExecSQL لأن المفتاح يحتاج تنسيق سداسي عشري
            val keyHex = passphrase.toHex()
            oldDb.rawExecSQL(
                "ATTACH DATABASE '${tempEncryptedFile.absolutePath}' AS encrypted KEY \"x'$keyHex'\""
            )

            // ── الخطوة 4: نسخ كل البيانات ──
            // sqlcipher_export() ينسخ الـ schema والبيانات معاً
            oldDb.rawExecSQL("SELECT sqlcipher_export('encrypted')")

            // نسخ user_version (مهم لـ onUpgrade)
            val userVersionCursor = oldDb.rawQuery("PRAGMA user_version", null)
            val userVersion = if (userVersionCursor.moveToFirst()) userVersionCursor.getInt(0) else 0
            userVersionCursor.close()

            oldDb.rawExecSQL("PRAGMA encrypted.user_version = $userVersion")

            // ── الخطوة 5: فصل القاعدة المشفّرة ──
            oldDb.rawExecSQL("DETACH DATABASE encrypted")

            Log.i(TAG, "Data exported to encrypted DB (user_version=$userVersion)")
        } finally {
            try { oldDb?.close() } catch (_: Exception) {}
        }

        // ── الخطوة 6: استبدال القديم بالجديد ──
        if (!tempEncryptedFile.exists() || tempEncryptedFile.length() == 0L) {
            throw IllegalStateException("Encrypted file was not created properly")
        }

        // التحقق أن القاعدة الجديدة فعلاً مشفّرة وقابلة للقراءة
        if (!isAlreadyEncrypted(tempEncryptedFile, passphrase)) {
            tempEncryptedFile.delete()
            throw IllegalStateException("New encrypted file failed verification")
        }

        // استبدال الملف
        if (!dbFile.delete()) {
            throw IllegalStateException("Could not delete original DB file")
        }
        if (!tempEncryptedFile.renameTo(dbFile)) {
            throw IllegalStateException("Could not rename encrypted file")
        }

        // حذف ملفات WAL/SHM المرتبطة بالقاعدة القديمة
        File(dbFile.parentFile, "${dbFile.name}-journal").delete()
        File(dbFile.parentFile, "${dbFile.name}-wal").delete()
        File(dbFile.parentFile, "${dbFile.name}-shm").delete()

        Log.i(TAG, "Migration complete. Backup kept at: ${backupFile.name}")
    }

    /**
     * تحويل ByteArray إلى String hex (للاستخدام في PRAGMA key)
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
