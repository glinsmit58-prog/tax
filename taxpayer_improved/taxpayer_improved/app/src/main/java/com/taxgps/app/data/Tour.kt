package com.taxgps.app.data

/**
 * نموذج الجولة الميدانية
 *
 * الجولة = مجموعة نقاط GPS مسجّلة بين بدء وإنهاء يدويين.
 * مرتبطة بالمكلفين الذين أُضيفوا أثناءها.
 */
data class Tour(
    val id: Long = 0,
    val name: String = "",                   // اسم اختياري (مثل: "جولة القطيلبية - الصباح")
    val startedAt: Long = 0,                 // وقت البدء (ms)
    val endedAt: Long? = null,               // وقت الإنهاء (null = مازالت نشطة)
    val pointCount: Int = 0,                 // عدد نقاط المسار
    val taxpayerCount: Int = 0,              // عدد المحلات التي زِيرت
    val distanceMeters: Float = 0f,          // المسافة المقطوعة (تقدير)
    val notes: String = "",                  // ملاحظات
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isActive(): Boolean = endedAt == null

    fun durationMs(): Long {
        val end = endedAt ?: System.currentTimeMillis()
        return end - startedAt
    }

    fun durationMinutes(): Long = durationMs() / 60_000L

    fun formattedDuration(): String {
        val minutes = durationMinutes()
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 -> "${hours}س ${mins}د"
            else -> "${mins}د"
        }
    }

    fun formattedDistance(): String = when {
        distanceMeters < 1000 -> "${distanceMeters.toInt()} م"
        else -> String.format("%.2f كم", distanceMeters / 1000)
    }
}
