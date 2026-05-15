package com.taxgps.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * مستقبل أزرار الإشعار
 *
 * عندما يضغط المستخدم زر "إنهاء الجولة" في الإشعار،
 * يصل البث هنا فنرسل أمر STOP للـ Service.
 */
class TourActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TourActionReceiver"
        const val ACTION_STOP = "com.taxgps.app.action.STOP_TOUR"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_STOP -> {
                // أرسل أمر STOP للـ Service
                val stopIntent = Intent(context, TourTrackingService::class.java).apply {
                    action = TourTrackingService.ACTION_STOP_TOUR
                }
                context.startService(stopIntent)
            }
        }
    }
}
