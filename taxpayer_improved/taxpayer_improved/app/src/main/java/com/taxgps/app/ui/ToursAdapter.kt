package com.taxgps.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taxgps.app.data.Tour
import com.taxgps.app.databinding.ItemTourBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter لعرض قائمة الجولات
 */
class ToursAdapter(
    private val onClick: (Tour) -> Unit,
    private val onLongClick: (Tour) -> Unit
) : ListAdapter<Tour, ToursAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Tour>() {
            override fun areItemsTheSame(o: Tour, n: Tour) = o.id == n.id
            override fun areContentsTheSame(o: Tour, n: Tour) = o == n
        }
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    inner class VH(val binding: ItemTourBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tour: Tour) = with(binding) {
            tvName.text = tour.name
            tvActiveBadge.visibility = if (tour.isActive()) View.VISIBLE else View.GONE

            tvDate.text = buildString {
                append(DATE_FMT.format(Date(tour.startedAt)))
                tour.endedAt?.let {
                    append(" → ")
                    append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)))
                }
            }

            tvDuration.text = "⏱ ${tour.formattedDuration()}"
            tvDistance.text = "📏 ${tour.formattedDistance()}"
            tvShops.text = "🏪 ${tour.taxpayerCount} محل"

            root.setOnClickListener { onClick(tour) }
            root.setOnLongClickListener { onLongClick(tour); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemTourBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
