package com.taxgps.app.tracking

import android.content.Context
import android.net.Uri
import android.util.Log
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Tour
import com.taxgps.app.data.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تصدير الجولات إلى صيغة KML
 *
 * KML = Keyhole Markup Language (تنسيق Google Earth و OSM)
 * يمكن فتحه في:
 * - Google Earth (سطح المكتب والموبايل)
 * - Google My Maps
 * - OsmAnd
 * - Maps.me
 *
 * الناتج: ملف XML يحتوي:
 * - LineString لكل جولة (المسار)
 * - Placemark لكل محل تم زيارته (مع الاسم)
 * - ألوان مختلفة لكل جولة
 */
object TourExportHelper {

    private const val TAG = "TourExportHelper"

    // ألوان جذّابة لتمييز الجولات على الخريطة (KML format: AABBGGRR)
    private val TOUR_COLORS = listOf(
        "ff0000ff",  // أحمر
        "ffff0000",  // أزرق
        "ff00ff00",  // أخضر
        "ff00ffff",  // أصفر
        "ffff00ff",  // وردي
        "ffff8800",  // برتقالي
        "ff8800ff",  // بنفسجي
        "ff00aa00"   // أخضر داكن
    )

    /**
     * تصدير جولة واحدة إلى KML
     */
    suspend fun exportTour(context: Context, tour: Tour, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = DatabaseHelper.getInstance(context)
            val points = db.getTrackPointsForTourAsync(tour.id)

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(buildKml(listOf(tour to points)))
                }
            }
            Log.i(TAG, "Exported tour ${tour.id}: ${points.size} points")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }

    /**
     * تصدير جميع الجولات إلى KML واحد
     */
    suspend fun exportAllTours(context: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val db = DatabaseHelper.getInstance(context)
            val tours = db.getAllToursAsync()
            val tourPairs = tours.map { tour ->
                tour to db.getTrackPointsForTourAsync(tour.id)
            }

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(buildKml(tourPairs))
                }
            }

            val totalPoints = tourPairs.sumOf { it.second.size }
            Log.i(TAG, "Exported ${tours.size} tours, $totalPoints points total")
            tours.size to totalPoints
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            0 to 0
        }
    }

    /**
     * بناء محتوى KML من قائمة جولات + نقاطها
     */
    private fun buildKml(tours: List<Pair<Tour, List<TrackPoint>>>): String {
        val sb = StringBuilder()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("""<Document>""")
        sb.appendLine("""  <name>جولات TaxGPS</name>""")
        sb.appendLine("""  <description>تصدير المسارات والمحلات المُزارة</description>""")

        // تعريف الأنماط للألوان المختلفة
        for ((index, color) in TOUR_COLORS.withIndex()) {
            sb.appendLine("""  <Style id="tour_$index">""")
            sb.appendLine("""    <LineStyle>""")
            sb.appendLine("""      <color>$color</color>""")
            sb.appendLine("""      <width>4</width>""")
            sb.appendLine("""    </LineStyle>""")
            sb.appendLine("""  </Style>""")
        }

        // نمط أيقونة المحلات
        sb.appendLine("""  <Style id="shopStyle">""")
        sb.appendLine("""    <IconStyle>""")
        sb.appendLine("""      <Icon><href>http://maps.google.com/mapfiles/kml/shapes/shopping.png</href></Icon>""")
        sb.appendLine("""    </IconStyle>""")
        sb.appendLine("""  </Style>""")

        // كل جولة
        for ((tourIndex, pair) in tours.withIndex()) {
            val (tour, points) = pair
            val colorStyle = "tour_${tourIndex % TOUR_COLORS.size}"

            sb.appendLine("""  <Folder>""")
            sb.appendLine("""    <name>${escapeXml(tour.name)}</name>""")
            sb.appendLine("""    <description>""")
            sb.appendLine("""      <![CDATA[""")
            sb.appendLine("""      بدأت: ${dateFmt.format(Date(tour.startedAt))}<br/>""")
            tour.endedAt?.let {
                sb.appendLine("""      انتهت: ${dateFmt.format(Date(it))}<br/>""")
            }
            sb.appendLine("""      المدة: ${tour.formattedDuration()}<br/>""")
            sb.appendLine("""      المسافة: ${tour.formattedDistance()}<br/>""")
            sb.appendLine("""      المحلات المسجّلة: ${tour.taxpayerCount}""")
            sb.appendLine("""      ]]>""")
            sb.appendLine("""    </description>""")

            // المسار (LineString) - فقط نقاط المشي الدقيقة
            val walkingPoints = points.filter {
                it.type == TrackPoint.TYPE_WALKING && it.isAccurate
            }
            if (walkingPoints.isNotEmpty()) {
                sb.appendLine("""    <Placemark>""")
                sb.appendLine("""      <name>المسار</name>""")
                sb.appendLine("""      <styleUrl>#$colorStyle</styleUrl>""")
                sb.appendLine("""      <LineString>""")
                sb.appendLine("""        <tessellate>1</tessellate>""")
                sb.appendLine("""        <coordinates>""")
                for (p in walkingPoints) {
                    sb.appendLine("""          ${p.longitude},${p.latitude},0""")
                }
                sb.appendLine("""        </coordinates>""")
                sb.appendLine("""      </LineString>""")
                sb.appendLine("""    </Placemark>""")
            }

            // المحلات المُزارة (Placemarks)
            val shopVisits = points.filter { it.type == TrackPoint.TYPE_SHOP_VISIT }
            for (visit in shopVisits) {
                sb.appendLine("""    <Placemark>""")
                sb.appendLine("""      <name>محل #${visit.taxpayerId ?: ""}</name>""")
                sb.appendLine("""      <description>تم تسجيله أثناء الجولة</description>""")
                sb.appendLine("""      <styleUrl>#shopStyle</styleUrl>""")
                sb.appendLine("""      <Point>""")
                sb.appendLine("""        <coordinates>${visit.longitude},${visit.latitude},0</coordinates>""")
                sb.appendLine("""      </Point>""")
                sb.appendLine("""    </Placemark>""")
            }

            sb.appendLine("""  </Folder>""")
        }

        sb.appendLine("""</Document>""")
        sb.appendLine("""</kml>""")
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
