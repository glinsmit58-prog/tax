package com.taxgps.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.data.TaxpayerPagingSource
import com.taxgps.app.data.TaxpayerStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * ViewModel للشاشة الرئيسية — مع دعم Paging 3
 *
 * v5 - Paging 3:
 * ─────────────────────────────────────────────────────────────────────
 * بدلاً من LiveData<List<Taxpayer>> مع LIMIT 500 (يقطع البيانات)
 * نستخدم Flow<PagingData<Taxpayer>> الذي يُحمّل تلقائياً عند التمرير.
 *
 * المزايا:
 * - عرض جميع المكلفين بدون حد أقصى
 * - استهلاك ذاكرة ثابت (50 سجل في الذاكرة)
 * - بدء فوري (الصفحة الأولى تُحمّل سريعاً)
 * - تحديث تلقائي عند تغيير البحث/الفلترة
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TaxpayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = DatabaseHelper.getInstance(application)

    // ── حالة البحث والفلترة (StateFlow) ──────────────────────────────────────
    private val searchQueryFlow = MutableStateFlow("")
    private val typeFilterFlow = MutableStateFlow("")

    // ── تيار Paging — يتحدث تلقائياً عند تغيير البحث ─────────────────────────
    val taxpayersPaging: Flow<PagingData<Taxpayer>> = combine(
        searchQueryFlow.debounce(400),  // 400ms debounce
        typeFilterFlow                   // فلترة فورية
    ) { query, type -> query to type }
        .flatMapLatest { (query, type) ->
            Pager(
                config = PagingConfig(
                    pageSize = 50,              // 50 سجل لكل صفحة
                    prefetchDistance = 10,      // ابدأ تحميل الصفحة التالية قبل 10 عناصر من النهاية
                    enablePlaceholders = false, // لا تعرض placeholders
                    initialLoadSize = 50        // الصفحة الأولى = 50 (نفس pageSize)
                ),
                pagingSourceFactory = {
                    TaxpayerPagingSource(db, query, type)
                }
            ).flow
        }
        .cachedIn(viewModelScope)  // التخزين المؤقت يحفظ البيانات أثناء تدوير الشاشة

    // ── الإحصائيات (منفصلة عن القائمة) ──────────────────────────────────────
    private val _stats = MutableLiveData<TaxpayerStats>()
    val stats: LiveData<TaxpayerStats> = _stats

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    init {
        loadStats()
    }

    // ── تحديث البحث والفلترة ─────────────────────────────────────────────────

    fun onSearchQueryChanged(query: String) {
        searchQueryFlow.value = query.trim()
    }

    fun onTypeFilterChanged(type: String) {
        typeFilterFlow.value = type
    }

    fun refresh() {
        loadStats()
        // قائمة Paging تُحدَّث تلقائياً عبر adapter.refresh() من Activity
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                _stats.value = db.getStatsAsync()
            } catch (e: Exception) {
                // لا نظهر خطأ الإحصائيات للمستخدم
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
