package com.taxgps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Tour
import com.taxgps.app.databinding.ActivityToursBinding
import com.taxgps.app.tracking.TourExportHelper
import kotlinx.coroutines.launch

/**
 * شاشة عرض قائمة الجولات السابقة + خيار الـ heatmap
 */
class ToursActivity : AppCompatActivity() {

    private lateinit var binding: ActivityToursBinding
    private lateinit var adapter: ToursAdapter
    private var tourPendingExport: Tour? = null

    private val exportSingleTourLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri: Uri? ->
        uri?.let { exportSingleTour(it) }
    }

    private val exportAllToursLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri: Uri? ->
        uri?.let { exportAllTours(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToursBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ToursAdapter(
            onClick = { tour -> openTourDetail(tour) },
            onLongClick = { tour -> showTourActions(tour) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabHeatmap.setOnClickListener {
            // عرض كل الجولات على Heatmap
            Intent(this, TourMapActivity::class.java).also {
                it.putExtra(TourMapActivity.EXTRA_SHOW_ALL, true)
                startActivity(it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadTours()
    }

    private fun loadTours() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val db = DatabaseHelper.getInstance(this@ToursActivity)
            val tours = db.getAllToursAsync()
            adapter.submitList(tours)
            binding.tvEmpty.visibility = if (tours.isEmpty()) View.VISIBLE else View.GONE
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun openTourDetail(tour: Tour) {
        Intent(this, TourMapActivity::class.java).also {
            it.putExtra(TourMapActivity.EXTRA_TOUR_ID, tour.id)
            startActivity(it)
        }
    }

    private fun showTourActions(tour: Tour) {
        val options = if (tour.isActive()) {
            arrayOf("عرض على الخريطة", "تصدير KML")
        } else {
            arrayOf("عرض على الخريطة", "تصدير KML", "حذف الجولة")
        }

        AlertDialog.Builder(this)
            .setTitle(tour.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openTourDetail(tour)
                    1 -> {
                        tourPendingExport = tour
                        val timestamp = java.text.SimpleDateFormat(
                            "yyyyMMdd_HHmm", java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        exportSingleTourLauncher.launch("tour_${tour.id}_${timestamp}.kml")
                    }
                    2 -> if (!tour.isActive()) confirmDelete(tour)
                }
            }
            .show()
    }

    private fun confirmDelete(tour: Tour) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.tour_confirm_delete_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val db = DatabaseHelper.getInstance(this@ToursActivity)
                    db.deleteTourAsync(tour.id)
                    Toast.makeText(this@ToursActivity, "تم حذف الجولة", Toast.LENGTH_SHORT).show()
                    loadTours()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportSingleTour(uri: Uri) {
        val tour = tourPendingExport ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = TourExportHelper.exportTour(this@ToursActivity, tour, uri)
            binding.progressBar.visibility = View.GONE
            val msg = if (ok) "تم تصدير الجولة بصيغة KML" else "فشل التصدير"
            Toast.makeText(this@ToursActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportAllTours(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val (count, points) = TourExportHelper.exportAllTours(this@ToursActivity, uri)
            binding.progressBar.visibility = View.GONE
            val msg = if (count > 0) "تم تصدير $count جولة ($points نقطة)" else "لا توجد جولات"
            Toast.makeText(this@ToursActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
