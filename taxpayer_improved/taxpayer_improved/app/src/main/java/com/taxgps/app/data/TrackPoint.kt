package com.taxgps.app.data

/**
 * نقطة على مسار الجولة
 *
 * كل نقطة تمثل قراءة GPS واحدة أثناء الجولة.
 * نوع النقطة يميّز بين:
 * - WALKING: المستخدم يمشي (نقطة مسار عادية)
 * - INSIDE_SHOP: المستخدم داخل محل (GPS غير موثوق، نتجاهل التغييرات)
 * - SHOP_VISIT: علامة دخول/خروج محل (مرتبطة بمكلف)
 */
data class TrackPoint(
    val id: Long = 0,
    val tourId: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = TYPE_WALKING,
    val taxpayerId: Long? = null,           // إذا type=SHOP_VISIT
    val streetSegmentId: Long? = null,      // الشارع المُكتشف (للدمج)
    val isAccurate: Boolean = true          // false = قراءة ضعيفة (>25م)
) {
    companion object {
        const val TYPE_WALKING = "walking"
        const val TYPE_INSIDE_SHOP = "inside_shop"
        const val TYPE_SHOP_VISIT = "shop_visit"
        const val TYPE_GPS_LOST = "gps_lost"

        // عتبات الدقة
        const val ACCURACY_THRESHOLD_GOOD = 15f       // قراءة ممتازة
        const val ACCURACY_THRESHOLD_ACCEPTABLE = 25f // قراءة مقبولة
        const val ACCURACY_THRESHOLD_REJECT = 50f     // ترفض القراءة فوقها
    }
}

/**
 * نموذج تجميع النقاط المتقاربة في "شارع منطقي"
 *
 * عندما يمشي المستخدم في نفس الشارع مرتين، النقاط متقاربة جغرافياً.
 * نُجمّع النقاط المتقاربة (<10 متر) في "segment" واحد لتفادي التكرار البصري
 * وتمكين الـ Heatmap (لون أكثر = مرور أكثر).
 */
data class StreetSegment(
    val id: Long = 0,
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0,
    val visitCount: Int = 1,                // كم مرة تم المرور
    val firstVisitAt: Long = System.currentTimeMillis(),
    val lastVisitAt: Long = System.currentTimeMillis(),
    val averageAccuracy: Float = 0f
) {
    /**
     * لون التمثيل البصري (Heatmap) حسب عدد الزيارات:
     * - 1 مرة: رمادي فاتح
     * - 2-3: أزرق
     * - 4-6: أخضر
     * - 7+: أحمر (منطقة مكثّفة)
     */
    fun getHeatColor(): Int = when {
        visitCount >= 7 -> android.graphics.Color.parseColor("#E53935") // أحمر
        visitCount >= 4 -> android.graphics.Color.parseColor("#43A047") // أخضر
        visitCount >= 2 -> android.graphics.Color.parseColor("#1E88E5") // أزرق
        else -> android.graphics.Color.parseColor("#9E9E9E")            // رمادي
    }

    fun getHeatStrokeWidth(): Float = when {
        visitCount >= 7 -> 8f
        visitCount >= 4 -> 6f
        visitCount >= 2 -> 4f
        else -> 3f
    }
}
