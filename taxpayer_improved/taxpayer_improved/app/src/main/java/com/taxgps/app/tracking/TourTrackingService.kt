package com.taxgps.app.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.taxgps.app.R
import com.taxgps.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground Service لتتبّع الجولة في الخلفية
 *
 * يعمل حتى لو أغلق المستخدم التطبيق.
 * يُظهر إشعاراً دائماً مع زر "إنهاء" + معلومات الجولة الحالية.
 */
class TourTrackingService : Service() {

    companion object {
        private const val TAG = "TourTrackingService"
        private const val NOTIFICATION_ID = 1042
        private const val CHANNEL_ID = "tour_tracking_channel"

        // Actions
        const val ACTION_START_TOUR = "com.taxgps.app.action.START_TOUR"
        const val ACTION_STOP_TOUR = "com.taxgps.app.action.STOP_TOUR"
        const val ACTION_RESUME_TOUR = "com.taxgps.app.action.RESUME_TOUR"

        // معدل التحديث (ms) - افتراضي 5 ثوانٍ
        private const val LOCATION_INTERVAL_MS = 5_000L
        private const val LOCATION_FASTEST_MS = 3_000L

        /**
         * بدء الخدمة من Activity
         */
        fun startTour(context: Context, tourName: String = "") {
            val intent = Intent(context, TourTrackingService::class.java).apply {
                action = ACTION_START_TOUR
                putExtra("tour_name", tourName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTour(context: Context) {
            val intent = Intent(context, TourTrackingService::class.java).apply {
                action = ACTION_STOP_TOUR
            }
            context.startService(intent)
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var stateObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TOUR -> {
                val tourName = intent.getStringExtra("tour_name") ?: ""
                handleStart(tourName)
            }
            ACTION_STOP_TOUR -> {
                handleStop()
            }
            ACTION_RESUME_TOUR -> {
                handleResume()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        // START_STICKY: إذا قتل النظام الخدمة، يُعيد إنشاءها (دون redeliver intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stopLocationUpdates()
        stateObserverJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── معالجة Actions ───────────────────────────────────────────────────────

    private fun handleStart(tourName: String) {
        // ابدأ Foreground Service فوراً (إلزامي خلال 5 ثوان من startForegroundService)
        startForeground(NOTIFICATION_ID, buildNotification("جاري بدء الجولة..."))

        scope.launch {
            try {
                val tour = TourTrackingManager.startTour(this@TourTrackingService, tourName)
                Log.i(TAG, "Tour started: ${tour.id}")
                startLocationUpdates()
                observeState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tour", e)
                stopSelf()
            }
        }
    }

    private fun handleResume() {
        startForeground(NOTIFICATION_ID, buildNotification("جاري استئناف الجولة..."))

        scope.launch {
            try {
                val tour = TourTrackingManager.resumeActiveTourIfAny(this@TourTrackingService)
                if (tour == null) {
                    Log.w(TAG, "No active tour to resume")
                    stopSelf()
                    return@launch
                }
                Log.i(TAG, "Tour resumed: ${tour.id}")
                startLocationUpdates()
                observeState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume tour", e)
                stopSelf()
            }
        }
    }

    private fun handleStop() {
        scope.launch {
            try {
                stopLocationUpdates()
                TourTrackingManager.endTour(this@TourTrackingService)
                Log.i(TAG, "Tour ended successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error ending tour", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ── مراقبة الحالة وتحديث الإشعار ─────────────────────────────────────────

    private fun observeState() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            TourTrackingManager.state.collect { state ->
                if (state.isActive) {
                    val gpsLabel = when (state.gpsStatus) {
                        TourTrackingManager.GpsStatus.STRONG -> "📡 GPS قوي"
                        TourTrackingManager.GpsStatus.WEAK -> "📡 GPS ضعيف"
                        TourTrackingManager.GpsStatus.LOST -> "📡 GPS مفقود"
                        else -> "📡 جاري البحث..."
                    }
                    val text = "${state.pointsCollected} نقطة • " +
                            "${state.distanceMeters.toInt()} م • $gpsLabel"
                    updateNotification(text)
                }
            }
        }
    }

    // ── تحديثات الموقع ───────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing FINE_LOCATION permission")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_MS)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    onNewLocation(location)
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.i(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start location updates", e)
            stopSelf()
        }
    }

    private fun onNewLocation(location: Location) {
        scope.launch {
            try {
                TourTrackingManager.onLocationUpdate(this@TourTrackingService, location)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing location", e)
            }
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        Log.i(TAG, "Location updates stopped")
    }

    // ── الإشعار ─────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تتبّع الجولات",
                NotificationManager.IMPORTANCE_LOW  // بدون صوت
            ).apply {
                description = "إشعار دائم أثناء تسجيل جولة ميدانية"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Pending intent لفتح التطبيق عند الضغط على الإشعار
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openPending = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        // Pending intent لزر "إنهاء"
        val stopIntent = Intent(this, TourActionReceiver::class.java).apply {
            action = TourActionReceiver.ACTION_STOP
        }
        val stopPending = PendingIntent.getBroadcast(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جولة TaxGPS نشطة")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إنهاء الجولة",
                stopPending
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
