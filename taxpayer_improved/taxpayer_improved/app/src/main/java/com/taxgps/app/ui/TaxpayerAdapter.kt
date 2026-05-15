package com.taxgps.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.taxgps.app.R
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ItemTaxpayerBinding
import com.taxgps.app.utils.LocationHelper

/**
 * Adapter محسّن باستخدام PagingDataAdapter
 *
 * v3 - Paging 3:
 * بدلاً من ListAdapter (يحمّل الكل دفعة واحدة)
 * نستخدم PagingDataAdapter الذي:
 * - يطلب الصفحات تلقائياً من PagingSource عند التمرير
 * - يدير الذاكرة بكفاءة (يُحرر الصفحات البعيدة)
 * - يدعم DiffUtil لتحديث ذكي
 */
class TaxpayerAdapter(
    private val onClick: (Taxpayer) -> Unit
) : PagingDataAdapter<Taxpayer, TaxpayerAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Taxpayer>() {
            override fun areItemsTheSame(old: Taxpayer, new: Taxpayer) = old.id == new.id
            override fun areContentsTheSame(old: Taxpayer, new: Taxpayer) = old == new
        }
    }

    inner class ViewHolder(val binding: ItemTaxpayerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Taxpayer) = with(binding) {
            val ctx = root.context

            tvName.text = item.name

            tvTaxNumber.text = buildString {
                if (item.recordNumber > 0) append("سجل: ${item.recordNumber}")
                if (item.accessDecisionNo.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append("قرار: ${item.accessDecisionNo}")
                }
                if (item.taxNumber.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append("ض: ${item.taxNumber}")
                }
                if (isEmpty()) append("بدون رقم سجل")
            }

            tvPhone.text = buildString {
                if (item.activityType.isNotBlank()) append(item.activityType)
                if (item.address.isNotBlank()) {
                    if (isNotEmpty()) append(" - ")
                    append(item.address)
                }
                if (isEmpty()) {
                    if (item.phone.isNotBlank()) append("الهاتف: ${item.phone}")
                    else append("بدون تفاصيل")
                }
            }

            // شريحة النوع (قديم / جديد)
            if (item.isOld()) {
                tvTypeChip.text = Taxpayer.TYPE_OLD
                tvTypeChip.setBackgroundResource(R.drawable.bg_chip_old)
                tvTypeChip.setTextColor(ctx.getColor(R.color.oldType))
                typeBar.setBackgroundColor(ctx.getColor(R.color.oldType))
            } else {
                tvTypeChip.text = Taxpayer.TYPE_NEW
                tvTypeChip.setBackgroundResource(R.drawable.bg_chip_new)
                tvTypeChip.setTextColor(ctx.getColor(R.color.newType))
                typeBar.setBackgroundColor(ctx.getColor(R.color.newType))
            }

            // حالة الموقع
            if (item.hasLocation()) {
                tvLocationStatus.text = "📍 موقع محدد"
                tvLocationStatus.setTextColor(ctx.getColor(R.color.success))
                tvAccuracy.text = item.accuracy?.let { "±${it.toInt()}م" } ?: ""
                tvAccuracy.setTextColor(
                    item.accuracy?.let { LocationHelper.getAccuracyColor(it) }
                        ?: ctx.getColor(R.color.textSecondary)
                )
            } else {
                tvLocationStatus.text = "📍 لا يوجد موقع"
                tvLocationStatus.setTextColor(ctx.getColor(R.color.textSecondary))
                tvAccuracy.text = ""
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemTaxpayerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // getItem(position) قد تُعيد null أثناء التحميل
        getItem(position)?.let { holder.bind(it) }
    }
}
