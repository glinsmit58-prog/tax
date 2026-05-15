package com.taxgps.app.data

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * مصدر بيانات Paging 3 للمكلفين
 *
 * كيف يعمل Paging 3؟
 * ─────────────────────────────────────────────────────────────────────
 * 1. RecyclerView يطلب الصفحة الأولى (50 سجل) عند بدء التشغيل
 * 2. عند تمرير المستخدم لقرب نهاية القائمة، يُطلب تلقائياً الصفحة التالية
 * 3. يستمر التحميل التدريجي حتى نهاية البيانات
 * 4. لا يوجد حد أقصى — يمكن عرض ملايين السجلات بدون بطء
 *
 * الفائدة الرئيسية:
 * - استهلاك ذاكرة ثابت (50 سجل في الذاكرة في كل لحظة)
 * - بدء سريع جداً (لا ينتظر تحميل كل البيانات)
 * - تجربة مستخدم سلسة بدون "freeze"
 */
class TaxpayerPagingSource(
    private val db: DatabaseHelper,
    private val searchQuery: String = "",
    private val typeFilter: String = ""
) : PagingSource<Int, Taxpayer>() {

    companion object {
        private const val TAG = "TaxpayerPagingSource"
    }

    /**
     * تحميل صفحة واحدة من البيانات
     *
     * params.key = رقم الصفحة (null = الصفحة الأولى = 0)
     * params.loadSize = عدد العناصر المطلوبة (يُحدده Paging library)
     */
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Taxpayer> {
        val page = params.key ?: 0
        val pageSize = params.loadSize
        val offset = page * pageSize

        return try {
            Log.v(TAG, "Loading page=$page, size=$pageSize, offset=$offset, query='$searchQuery'")

            val taxpayers = db.getAllTaxpayersAsync(
                filter = searchQuery,
                typeFilter = typeFilter,
                limit = pageSize,
                offset = offset
            )

            // تحديد المفاتيح:
            // prevKey = null إذا كنا في الصفحة 0
            // nextKey = null إذا لم تعد هناك بيانات (انتهت)
            val prevKey = if (page == 0) null else page - 1
            val nextKey = if (taxpayers.size < pageSize) null else page + 1

            LoadResult.Page(
                data = taxpayers,
                prevKey = prevKey,
                nextKey = nextKey
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error loading page $page", e)
            LoadResult.Error(e)
        }
    }

    /**
     * تحديد المفتاح للتحديث (refresh)
     * يُستخدم عندما تتغير البيانات (إضافة/حذف) ويحتاج Paging لإعادة التحميل
     */
    override fun getRefreshKey(state: PagingState<Int, Taxpayer>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
