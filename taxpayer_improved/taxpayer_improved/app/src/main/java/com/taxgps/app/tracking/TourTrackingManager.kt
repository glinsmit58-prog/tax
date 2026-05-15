package com.taxgps.app.tracking

import android.content.Context
import android.location.Location
import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.StreetSegment
import com.taxgps.app.data.Tour
import com.taxgps.app.data.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * مدير منطق الجولة — Singleton
 *
 * المسؤوليات:
 * 1. بدء وإنهاء الجولات
 * 2. معالجة قراءات GPS الواردة (فلترة + دمج مع segments موجودة)
 * 3. كشف "GPS lost" (المستخدم دخل محل)
 * 4. حساب المسافة المقطوعة
 * 5. توفير الحالة الحالية للـ UI عبر StateFlow
 *
 * يعمل في الخلفية (في الـ Service) لكن منطق الحالة هنا.
 */
object TourTrackingManager {

    private const val TAG = "TourTrackingManager"

    // عتبات الفلترة (بالأمتار)
    private const val MIN_DISTANCE_BETWEEN_POINTS = 3.0    // أقل مسافة لإضافة نقطة جديدة
    private const val SEGMENT_MERGE_RADIUS = 10.0          // النقاط ضمن 10 متر تُعتبر نفس الـ segment
    private const val GPS_LOST_THRESHOLD_MS = 30_000L      // 30 ثانية بدون قراءة جيدة = داخل محل

    // ── الحالة الحالية ────────────────────────────────────────────────────────

    data class TrackingState(
        val isActive: Boolean = false,
        val currentTour: Tour? = null,
        val pointsCollected: Int = 0,
        val distanceMeters: Float = 0f,
        val lastLocation: Location? = null,
        val gpsStatus: GpsStatus = GpsStatus.UNKNOWN
    )

    enum class GpsStatus {
        UNKNOWN,    // لم نستقبل أي قراءة بعد
        STRONG,     // قراءة دقيقة (<15 متر)
        WEAK,       // قراءة ضعيفة (15-50 متر)
        LOST        // ضاع GPS (داخل محل غالباً)
    }

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state

    // ── حالة داخلية ──────────────────────────────────────────────────────────

    private var lastAcceptedLocation: Location? = null
    private var lastAccurateReadingTime: Long = 0
    private var pointsBuffer = mutableListOf<TrackPoint>()
    private var gpsLostMarkerInserted = false

    // ── دورة حياة الجولة ─────────────────────────────────────────────────────

    /**
     * بدء جولة جديدة
     * يحفظ سجل Tour في قاعدة البيانات ويُعيد المعرّف
     */
    suspend fun startTour(context: Context, name: String = ""): Tour {
        val db = DatabaseHelper.getInstance(context)

        // تأكد من عدم وجود جولة نشطة (لو خرج التطبيق فجأة)
        val existing = db.getActiveTourAsync()
        if (existing != null) {
            Log.w(TAG, "Found active tour, resuming it: ${existing.id}")
            _state.value = TrackingState(
                isActive = true,
                currentTour = existing,
                pointsCollected = existing.pointCount
            )
            return existing
        }

        val now = System.currentTimeMillis()
        val finalName = name.ifBlank {
            "جولة ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.getDefault()).format(java.util.Date(now))}"
        }

        val tour = Tour(
            name = finalName,
            startedAt = now,
            createdAt = now
        )
        val id = db.insertTourAsync(tour)
        val saved = tour.copy(id = id)

        // إعادة تعيين الحالة الداخلية
        lastAcceptedLocation = null
        lastAccurateReadingTime = now
        pointsBuffer.clear()
        gpsLostMarkerInserted = false

        _state.value = TrackingState(
            isActive = true,
            currentTour = saved,
            pointsCollected = 0,
            distanceMeters = 0f
        )

        Log.i(TAG, "Tour started: id=$id, name='$finalName'")
        return saved
    }

    /**
     * إنهاء الجولة الحالية
     * يحفظ بقية النقاط في الـ buffer ويُحدّث سجل Tour
     */
    suspend fun endTour(context: Context): Tour? {
        val state = _state.value
        val tour = state.currentTour ?: return null
        val db = DatabaseHelper.getInstance(context)

        // حفظ النقاط المتبقية في الـ buffer
        flushBuffer(context)

        val endTime = System.currentTimeMillis()
        db.endTourAsync(
            tourId = tour.id,
            endTime = endTime,
            distance = state.distanceMeters,
            points = state.pointsCollected,
            taxpayers = tour.taxpayerCount
        )

        // إعادة تعيين الحالة
        _state.value = TrackingState()
        lastAcceptedLocation = null
        pointsBuffer.clear()

        Log.i(TAG, "Tour ended: id=${tour.id}, points=${state.pointsCollected}, " +
                "distance=${state.distanceMeters}m")

        return db.getTourByIdAsync(tour.id)
    }

    // ── معالجة قراءات GPS ────────────────────────────────────────────────────

    /**
     * استقبال قراءة GPS جديدة من الـ Service
     * يطبّق الفلترة، الدمج، كشف GPS lost
     */
    suspend fun onLocationUpdate(context: Context, location: Location) {
        val state = _state.value
        if (!state.isActive) {
            Log.w(TAG, "Received location while inactive, ignoring")
            return
        }
        val tour = state.currentTour ?: return

        val now = System.currentTimeMillis()

        // ── 1. تحديد جودة GPS ──
        val gpsStatus = when {
            location.accuracy < TrackPoint.ACCURACY_THRESHOLD_GOOD -> GpsStatus.STRONG
            location.accuracy < TrackPoint.ACCURACY_THRESHOLD_ACCEPTABLE -> GpsStatus.WEAK
            location.accuracy < TrackPoint.ACCURACY_THRESHOLD_REJECT -> GpsStatus.WEAK
            else -> GpsStatus.LOST
        }

        // ── 2. كشف "ضاع GPS لفترة طويلة" → المستخدم داخل محل ──
        val timeSinceLastAccurate = now - lastAccurateReadingTime
        val gpsLost = gpsStatus == GpsStatus.LOST ||
                (lastAcceptedLocation != null && timeSinceLastAccurate > GPS_LOST_THRESHOLD_MS)

        if (gpsLost && !gpsLostMarkerInserted) {
            // إدراج علامة "GPS lost" مرة واحدة فقط
            val lost = TrackPoint(
                tourId = tour.id,
                latitude = lastAcceptedLocation?.latitude ?: location.latitude,
                longitude = lastAcceptedLocation?.longitude ?: location.longitude,
                accuracy = location.accuracy,
                timestamp = now,
                type = TrackPoint.TYPE_GPS_LOST,
                isAccurate = false
            )
            pointsBuffer.add(lost)
            gpsLostMarkerInserted = true
            _state.value = state.copy(gpsStatus = GpsStatus.LOST)
            Log.d(TAG, "GPS lost marker inserted")
            return
        }

        // ── 3. رفض القراءات السيئة جداً ──
        if (location.accuracy > TrackPoint.ACCURACY_THRESHOLD_REJECT) {
            Log.v(TAG, "Rejected reading: accuracy=${location.accuracy}")
            _state.value = state.copy(gpsStatus = gpsStatus)
            return
        }

        // ── 4. إذا كان لدينا موقع سابق، تحقق من المسافة الدنيا ──
        val prevLocation = lastAcceptedLocation
        if (prevLocation != null) {
            val distance = haversineMeters(
                prevLocation.latitude, prevLocation.longitude,
                location.latitude, location.longitude
            )
            if (distance < MIN_DISTANCE_BETWEEN_POINTS) {
                // المستخدم لم يتحرك بعد - تجاهل
                _state.value = state.copy(gpsStatus = gpsStatus, lastLocation = location)
                return
            }
        }

        // ── 5. القراءة جيدة - أعد تعيين علامة GPS lost ──
        if (gpsLostMarkerInserted) {
            Log.d(TAG, "GPS recovered after being lost")
            gpsLostMarkerInserted = false
        }
        lastAccurateReadingTime = now

        // ── 6. ابحث/أنشئ Street Segment ──
        val db = DatabaseHelper.getInstance(context)
        val segmentId = findOrCreateSegment(db, location.latitude, location.longitude, location.accuracy, now)

        // ── 7. أنشئ TrackPoint وأضفها للـ buffer ──
        val isAccurate = location.accuracy <= TrackPoint.ACCURACY_THRESHOLD_ACCEPTABLE
        val point = TrackPoint(
            tourId = tour.id,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = now,
            type = TrackPoint.TYPE_WALKING,
            streetSegmentId = segmentId,
            isAccurate = isAccurate
        )
        pointsBuffer.add(point)

        // ── 8. حساب المسافة الإضافية ──
        val addedDistance = if (prevLocation != null) {
            haversineMeters(
                prevLocation.latitude, prevLocation.longitude,
                location.latitude, location.longitude
            ).toFloat()
        } else 0f

        lastAcceptedLocation = location

        // ── 9. حدّث الحالة (UI) ──
        _state.value = state.copy(
            pointsCollected = state.pointsCollected + 1,
            distanceMeters = state.distanceMeters + addedDistance,
            lastLocation = location,
            gpsStatus = gpsStatus
        )

        // ── 10. كل 10 نقاط، اكتب إلى DB (لتقليل I/O) ──
        if (pointsBuffer.size >= 10) {
            flushBuffer(context)
        }
    }

    /**
     * كتابة النقاط المخزّنة في الـ buffer إلى DB
     */
    suspend fun flushBuffer(context: Context) {
        if (pointsBuffer.isEmpty()) return
        val db = DatabaseHelper.getInstance(context)
        val toFlush = pointsBuffer.toList()
        pointsBuffer.clear()
        db.insertTrackPointsBatchAsync(toFlush)
        Log.v(TAG, "Flushed ${toFlush.size} points to DB")
    }

    /**
     * تسجيل زيارة محل (مرتبطة بمكلف)
     * تُستدعى عند إضافة مكلف جديد أثناء الجولة
     */
    suspend fun recordShopVisit(context: Context, taxpayerId: Long, location: Location) {
        val state = _state.value
        if (!state.isActive) return
        val tour = state.currentTour ?: return

        val visit = TrackPoint(
            tourId = tour.id,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = System.currentTimeMillis(),
            type = TrackPoint.TYPE_SHOP_VISIT,
            taxpayerId = taxpayerId,
            isAccurate = location.accuracy <= TrackPoint.ACCURACY_THRESHOLD_ACCEPTABLE
        )

        DatabaseHelper.getInstance(context).insertTrackPointAsync(visit)

        // تحديث عدد المكلفين في الجولة
        val tourUpdated = tour.copy(taxpayerCount = tour.taxpayerCount + 1)
        DatabaseHelper.getInstance(context).updateTourAsync(tourUpdated)
        _state.value = state.copy(currentTour = tourUpdated)

        Log.i(TAG, "Shop visit recorded: taxpayerId=$taxpayerId")
    }

    /**
     * استئناف جولة موجودة (عند إعادة تشغيل الـ Service)
     */
    suspend fun resumeActiveTourIfAny(context: Context): Tour? {
        val db = DatabaseHelper.getInstance(context)
        val active = db.getActiveTourAsync() ?: return null

        _state.value = TrackingState(
            isActive = true,
            currentTour = active,
            pointsCollected = active.pointCount,
            distanceMeters = active.distanceMeters
        )
        Log.i(TAG, "Resumed active tour: ${active.id}")
        return active
    }

    // ── المساعدات الداخلية ───────────────────────────────────────────────────

    /**
     * البحث عن Street Segment قريب أو إنشاء واحد جديد
     */
    private suspend fun findOrCreateSegment(
        db: DatabaseHelper,
        lat: Double,
        lon: Double,
        accuracy: Float,
        time: Long
    ): Long {
        // البحث في صندوق محيط ~10 متر
        // 1 درجة عرض ≈ 111 كم → 10 متر ≈ 0.00009 درجة
        val nearby = db.findNearbySegmentAsync(lat, lon, 0.00009)

        return if (nearby != null) {
            // segment موجود - زِد عدد الزيارات
            db.incrementSegmentVisitAsync(nearby.id, time)
            nearby.id
        } else {
            // أنشئ segment جديد
            val newSeg = StreetSegment(
                centerLat = lat,
                centerLon = lon,
                visitCount = 1,
                firstVisitAt = time,
                lastVisitAt = time,
                averageAccuracy = accuracy
            )
            db.insertSegmentAsync(newSeg)
        }
    }

    /**
     * حساب المسافة بين نقطتين بصيغة Haversine (بالأمتار)
     *
     * صيغة مبسطة دقيقة بما يكفي لمسافات صغيرة (<100 كم).
     */
    private fun haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        val c = 2 * Math.atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }
}
