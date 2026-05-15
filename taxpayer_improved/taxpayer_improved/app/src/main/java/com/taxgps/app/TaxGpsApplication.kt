package com.taxgps.app

import android.app.Application
import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import org.osmdroid.config.Configuration

/**
 * Application class للتطبيق
 *
 * المهام:
 * - تحميل مكتبة SQLCipher الأصلية مبكراً (قبل أي استعلام)
 * - تهيئة OSMDroid configuration
 * - إعداد User-Agent للخرائط
 */
class TaxGpsApplication : Application() {

    companion object {
        private const val TAG = "TaxGpsApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // تحميل SQLCipher مبكراً (آمن للاستدعاء عدة مرات)
        try {
            DatabaseHelper.initSqlCipher()
            Log.i(TAG, "SQLCipher initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load SQLCipher", e)
            // التطبيق سيُعطّل عند محاولة فتح القاعدة لاحقاً
            // لكن لا نُعطّله هنا حتى نسمح بعرض رسالة خطأ مفهومة
        }

        // تهيئة OSMDroid — مطلوب قبل استخدام MapView
        Configuration.getInstance().apply {
            load(this@TaxGpsApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            // مسار التخزين المؤقت لبلاطات الخرائط
            osmdroidTileCache = cacheDir.resolve("osmdroid_tiles")
        }
    }
}
