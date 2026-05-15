package com.taxgps.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * مدير مفتاح تشفير قاعدة البيانات
 *
 * كيف يعمل؟
 * ─────────────────────────────────────────────────────────────────────
 * 1. عند أول تشغيل: يولّد مفتاحاً عشوائياً قوياً (32 byte = 256 bit)
 * 2. يحفظه في EncryptedSharedPreferences (محمي بـ Android Keystore)
 * 3. Android Keystore: مساحة آمنة على مستوى الـ hardware (TEE)
 *    حتى لو حصل أحدهم على ملفات التطبيق، لا يستطيع استخراج المفتاح
 * 4. عند كل استخدام لاحق: يقرأ المفتاح من المخزن الآمن
 *
 * طبقات الأمان:
 * ─────────────────────────────────────────────────────────────────────
 * Layer 1: قاعدة البيانات مشفّرة بـ AES-256 (SQLCipher)
 * Layer 2: مفتاح التشفير محفوظ في EncryptedSharedPreferences
 * Layer 3: مفتاح EncryptedSharedPreferences محفوظ في Android Keystore
 * Layer 4: Android Keystore محمي بـ hardware TEE (في الأجهزة الحديثة)
 *
 * النتيجة: حتى لو حصل المهاجم على ملف القاعدة، لا يستطيع فتحه إطلاقاً.
 */
object EncryptionKeyManager {

    private const val TAG = "EncryptionKeyManager"
    private const val PREFS_NAME = "tax_secure_prefs"
    private const val KEY_DB_PASSPHRASE = "db_passphrase_b64"
    private const val PASSPHRASE_LENGTH_BYTES = 32  // 256 bit

    /**
     * الحصول على مفتاح تشفير قاعدة البيانات
     *
     * - أول مرة: يولّده ويحفظه
     * - باقي المرات: يقرأه من المخزن الآمن
     *
     * @return ByteArray بطول 32 byte يُمرَّر مباشرة لـ SQLCipher
     */
    @Synchronized
    fun getOrCreateDbKey(context: Context): ByteArray {
        val prefs = getSecurePrefs(context)

        // محاولة قراءة المفتاح الموجود
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            try {
                val bytes = android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
                if (bytes.size == PASSPHRASE_LENGTH_BYTES) {
                    Log.d(TAG, "Using existing DB passphrase")
                    return bytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode existing key, generating new one", e)
            }
        }

        // توليد مفتاح جديد
        Log.i(TAG, "Generating new DB passphrase")
        val newKey = generateSecureKey()
        val encoded = android.util.Base64.encodeToString(newKey, android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_DB_PASSPHRASE, encoded).apply()

        return newKey
    }

    /**
     * حذف مفتاح التشفير (للتأكد من اختبار الترقية، أو عند إلغاء التطبيق)
     * تحذير: هذا يجعل قاعدة البيانات غير قابلة للقراءة بشكل دائم!
     */
    fun deleteDbKey(context: Context) {
        getSecurePrefs(context).edit().remove(KEY_DB_PASSPHRASE).apply()
        Log.w(TAG, "DB passphrase deleted - database is now inaccessible!")
    }

    /**
     * فحص ما إذا كان المفتاح موجوداً مسبقاً
     */
    fun hasDbKey(context: Context): Boolean {
        return getSecurePrefs(context).contains(KEY_DB_PASSPHRASE)
    }

    // ── دوال داخلية ──────────────────────────────────────────────────────────

    /**
     * بناء EncryptedSharedPreferences مع MasterKey محفوظ في Keystore
     */
    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback نادر جداً: إذا فشل Keystore (أجهزة قديمة جداً أو تالفة)
            // نستخدم SharedPreferences عادية. الأمان أقل لكن التطبيق يستمر بالعمل.
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, using fallback", e)
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * توليد مفتاح عشوائي قوي بطول 256 bit
     * يستخدم SecureRandom (مصدر عشوائية مناسب للتشفير)
     */
    private fun generateSecureKey(): ByteArray {
        val key = ByteArray(PASSPHRASE_LENGTH_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }
}
