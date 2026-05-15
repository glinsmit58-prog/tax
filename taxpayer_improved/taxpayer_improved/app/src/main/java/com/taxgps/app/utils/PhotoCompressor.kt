package com.taxgps.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * مساعد ضغط الصور
 *
 * المشكلة:
 * ─────────────────────────────────────────────────────────────────────
 * كاميرات الهواتف الحديثة تنتج صوراً بحجم 5-10 ميغابايت لكل صورة.
 * حفظ 100 صورة = 500-1000 ميغابايت!
 *
 * الحل:
 * ─────────────────────────────────────────────────────────────────────
 * 1. تصغير أبعاد الصورة إلى max 1280px (يكفي لمحلات تجارية)
 * 2. ضغط JPEG بجودة 80% (جودة بصرية ممتازة)
 * 3. الحفاظ على Orientation الصحيح (تصحيح EXIF)
 *
 * النتيجة:
 * - من 5MB إلى 200-400KB لكل صورة
 * - توفير 95% من المساحة
 * - تحميل أسرع في الواجهة
 */
object PhotoCompressor {

    private const val TAG = "PhotoCompressor"

    // الإعدادات الافتراضية
    private const val MAX_DIMENSION = 1280       // أقصى عرض/ارتفاع بالبكسل
    private const val JPEG_QUALITY = 80          // جودة JPEG (0-100)

    /**
     * ضغط صورة من ملف موجود
     *
     * @param sourceFile الملف الأصلي (سيُستبدل بالنسخة المضغوطة)
     * @return الملف نفسه بعد الضغط
     */
    suspend fun compressFile(sourceFile: File): File = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext sourceFile
        }

        val originalSize = sourceFile.length()

        try {
            // ── الخطوة 1: قراءة أبعاد الصورة بدون تحميلها كاملة ──
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Invalid image: ${sourceFile.name}")
                return@withContext sourceFile
            }

            // ── الخطوة 2: حساب inSampleSize للتصغير الأولي ──
            val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)

            // ── الخطوة 3: تحميل الصورة بالحجم المُصغّر ──
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, loadOptions)
                ?: run {
                    Log.w(TAG, "Failed to decode: ${sourceFile.name}")
                    return@withContext sourceFile
                }

            // ── الخطوة 4: تصحيح Orientation حسب EXIF ──
            bitmap = fixOrientation(bitmap, sourceFile.absolutePath)

            // ── الخطوة 5: تصغير دقيق إلى MAX_DIMENSION ──
            if (bitmap.width > MAX_DIMENSION || bitmap.height > MAX_DIMENSION) {
                val ratio = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
                val newWidth = (bitmap.width * ratio).toInt()
                val newHeight = (bitmap.height * ratio).toInt()
                val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (resized != bitmap) {
                    bitmap.recycle()
                    bitmap = resized
                }
            }

            // ── الخطوة 6: حفظ بصيغة JPEG مضغوطة ──
            FileOutputStream(sourceFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }
            bitmap.recycle()

            val newSize = sourceFile.length()
            val savings = if (originalSize > 0) {
                ((originalSize - newSize) * 100 / originalSize).toInt()
            } else 0

            Log.i(TAG, "Compressed ${sourceFile.name}: " +
                    "${originalSize / 1024}KB → ${newSize / 1024}KB (saved $savings%)")

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during compression - keeping original", e)
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed - keeping original", e)
        }

        sourceFile
    }

    /**
     * ضغط صورة من URI ونسخها إلى ملف وجهة
     *
     * يُستخدم عند اختيار صورة من المعرض
     */
    suspend fun compressFromUri(
        context: Context,
        sourceUri: Uri,
        destFile: File
    ): File = withContext(Dispatchers.IO) {
        try {
            // نسخ المحتوى أولاً
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }

            // ثم ضغطه
            compressFile(destFile)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress from URI", e)
            destFile
        }
    }

    // ── دوال مساعدة ──────────────────────────────────────────────────────────

    /**
     * حساب inSampleSize لتقليل استهلاك الذاكرة عند تحميل الصورة
     *
     * Android يضاعف القسمة (1, 2, 4, 8...) — كل زيادة تُقلّل الذاكرة لـ 1/4
     */
    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val maxOriginal = maxOf(width, height)

        while (maxOriginal / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * تصحيح اتجاه الصورة حسب بيانات EXIF
     *
     * الكاميرات تحفظ الصورة كما هي، وتضع علامة في EXIF تشير للاتجاه الصحيح.
     * بدون هذا التصحيح، الصور قد تظهر مقلوبة 90° أو 180°.
     */
    private fun fixOrientation(bitmap: Bitmap, filePath: String): Bitmap {
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotation == 0f) return bitmap

            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated

        } catch (e: Exception) {
            Log.w(TAG, "Failed to fix orientation: ${e.message}")
            bitmap
        }
    }

    /**
     * إحصائيات الضغط (للعرض)
     */
    fun getCompressionInfo(): String =
        "ضغط الصور: حد أقصى ${MAX_DIMENSION}px، جودة ${JPEG_QUALITY}%"
}
