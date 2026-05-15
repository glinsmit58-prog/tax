package com.taxgps.app.ui

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.StreetSegment
import com.taxgps.app.data.Tour
import com.taxgps.app.data.TrackPoint
import com.taxgps.app.databinding.ActivityTourMapBinding
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * شاشة عرض الجولة على خريطة (مع Heatmap)
 *
 * أوضاع العرض:
 * 1. عرض جولة محددة (EXTRA_TOUR_ID): مسار الجولة بلون واحد + المحلات
 * 2. عرض كل الجولات (EXTRA_SHOW_ALL): Heatmap بألوان حسب التكرار
 */
class TourMapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOUR_ID = "extra_tour_id"
        const val EXTRA_SHOW_ALL = "extra_show_all_tours"
    }

    private lateinit var binding: ActivityTourMapBinding
    private var tourId: Long = -1
    private var showAll: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))

        binding = ActivityTourMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        tourId = intent.getLongExtra(EXTRA_TOUR_ID, -1)
        showAll = intent.getBooleanExtra(EXTRA_SHOW_ALL, false)

        setupMap()
        loadData()
    }

    private fun setupMap() {
        with(binding.mapView) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)

            overlays.add(ScaleBarOverlay(this).apply {
                setCentred(true)
                setScaleBarOffset(200, 10)
            })
        }
    }

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE
        val db = DatabaseHelper.getInstance(this)

        lifecycleScope.launch {
            try {
                if (showAll) {
                    drawHeatmap(db)
                } else if (tourId != -1L) {
                    drawSingleTour(db, tourId)
                }
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * رسم Heatmap من جميع الـ StreetSegments
     */
    private suspend fun drawHeatmap(db: DatabaseHelper) {
        val segments = db.getAllSegmentsAsync()
        if (segments.isEmpty()) {
            binding.tvEmptyMessage.text = "لا توجد بيانات تتبّع بعد.\nابدأ جولة أولاً."
            binding.tvEmptyMessage.visibility = View.VISIBLE
            return
        }

        // ربط الـ segments المتقاربة بخطوط (visualize كنقاط دائرية للبساطة)
        val points = mutableListOf<GeoPoint>()
        for (seg in segments) {
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(seg.centerLat, seg.centerLon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "${seg.visitCount} زيارة"
                snippet = "آخر زيارة: ${formatTimestamp(seg.lastVisitAt)}"
                // أيقونة دائرية ملوّنة (نستخدم default + tint عبر setIcon لاحقاً للبساطة هنا)
            }
            binding.mapView.overlays.add(marker)
            points.add(GeoPoint(seg.centerLat, seg.centerLon))
        }

        // رسم خطوط ربط بين الـ segments المتقاربة (تمثيل تقريبي للشوارع)
        drawSegmentConnections(segments)

        // عرض دليل الألوان
        binding.legendCard.visibility = View.VISIBLE

        zoomToBounds(points)
        binding.mapView.invalidate()
    }

    /**
     * ربط الـ segments المتقاربة بخطوط لتشكّل "شوارع"
     */
    private fun drawSegmentConnections(segments: List<StreetSegment>) {
        // ربط كل segment مع أقرب 2 segments (إذا كانت < 30 متر منها)
        val maxDistanceMeters = 30.0
        for (i in segments.indices) {
            val a = segments[i]
            val nearby = segments
                .filterIndexed { idx, b -> idx != i && distanceMeters(a, b) < maxDistanceMeters }
                .sortedBy { distanceMeters(a, it) }
                .take(2)

            for (b in nearby) {
                val polyline = Polyline(binding.mapView).apply {
                    addPoint(GeoPoint(a.centerLat, a.centerLon))
                    addPoint(GeoPoint(b.centerLat, b.centerLon))

                    val avgVisits = (a.visitCount + b.visitCount) / 2
                    val combined = StreetSegment(visitCount = avgVisits)
                    outlinePaint.apply {
                        color = combined.getHeatColor()
                        strokeWidth = combined.getHeatStrokeWidth()
                        style = Paint.Style.STROKE
                        isAntiAlias = true
                        alpha = 200
                    }
                }
                binding.mapView.overlays.add(polyline)
            }
        }
    }

    /**
     * رسم جولة واحدة (مسار + محلات)
     */
    private suspend fun drawSingleTour(db: DatabaseHelper, tourId: Long) {
        val tour = db.getTourByIdAsync(tourId) ?: return
        val points = db.getTrackPointsForTourAsync(tourId)

        if (points.isEmpty()) {
            binding.tvEmptyMessage.text = "هذه الجولة لا تحتوي نقاط مسجّلة"
            binding.tvEmptyMessage.visibility = View.VISIBLE
            return
        }

        binding.toolbar.title = tour.name

        // رسم المسار الرئيسي (نقاط المشي الدقيقة فقط)
        val walkingPoints = points
            .filter { it.type == TrackPoint.TYPE_WALKING && it.isAccurate }
            .map { GeoPoint(it.latitude, it.longitude) }

        if (walkingPoints.size >= 2) {
            val polyline = Polyline(binding.mapView).apply {
                setPoints(walkingPoints)
                outlinePaint.apply {
                    color = Color.parseColor("#1E88E5")
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }
            }
            binding.mapView.overlays.add(polyline)
        }

        // علامة بداية المسار
        if (walkingPoints.isNotEmpty()) {
            Marker(binding.mapView).apply {
                position = walkingPoints.first()
                title = "بداية الجولة"
                snippet = formatTimestamp(tour.startedAt)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(this)
            }

            Marker(binding.mapView).apply {
                position = walkingPoints.last()
                title = "نهاية المسار المسجّل"
                snippet = tour.endedAt?.let { formatTimestamp(it) } ?: "(جولة نشطة)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(this)
            }
        }

        // محلات تم زيارتها
        val shopVisits = points.filter { it.type == TrackPoint.TYPE_SHOP_VISIT }
        for (visit in shopVisits) {
            Marker(binding.mapView).apply {
                position = GeoPoint(visit.latitude, visit.longitude)
                title = "محل #${visit.taxpayerId ?: ""}"
                snippet = formatTimestamp(visit.timestamp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(this)
            }
        }

        // علامات GPS lost (دخول محل بدون توثيق)
        val gpsLostMarkers = points.filter { it.type == TrackPoint.TYPE_GPS_LOST }
        for (lost in gpsLostMarkers) {
            Marker(binding.mapView).apply {
                position = GeoPoint(lost.latitude, lost.longitude)
                title = "GPS مفقود"
                snippet = "ربما داخل مبنى - ${formatTimestamp(lost.timestamp)}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.mapView.overlays.add(this)
            }
        }

        // عرض إحصائيات الجولة في الـ overlay
        binding.statsCard.visibility = View.VISIBLE
        binding.tvStatsPoints.text = "${points.size} نقطة"
        binding.tvStatsDistance.text = tour.formattedDistance()
        binding.tvStatsDuration.text = tour.formattedDuration()
        binding.tvStatsShops.text = "${shopVisits.size} محل"

        zoomToBounds(walkingPoints)
        binding.mapView.invalidate()
    }

    private fun zoomToBounds(points: List<GeoPoint>) {
        if (points.isEmpty()) return

        if (points.size == 1) {
            binding.mapView.controller.setCenter(points[0])
            binding.mapView.controller.setZoom(18.0)
            return
        }

        val box = BoundingBox(
            points.maxOf { it.latitude },
            points.maxOf { it.longitude },
            points.minOf { it.latitude },
            points.minOf { it.longitude }
        )
        binding.mapView.post {
            binding.mapView.zoomToBoundingBox(box, true, 80)
        }
    }

    private fun distanceMeters(a: StreetSegment, b: StreetSegment): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.centerLat - a.centerLat)
        val dLon = Math.toRadians(b.centerLon - a.centerLon)
        val ah = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(a.centerLat)) * Math.cos(Math.toRadians(b.centerLat)) *
                Math.sin(dLon / 2).let { it * it }
        return 2 * earthRadius * Math.atan2(Math.sqrt(ah), Math.sqrt(1 - ah))
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }
}
