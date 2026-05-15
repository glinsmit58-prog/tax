package com.taxgps.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityMainBinding
import com.taxgps.app.utils.AccessDbImportHelper
import com.taxgps.app.utils.BackupHelper
import com.taxgps.app.viewmodel.TaxpayerViewModel
import kotlinx.coroutines.launch

/**
 * الشاشة الرئيسية المحسّنة - مع دعم Paging 3
 *
 * v5 - Paging 3:
 * - استخدام collectLatest على Flow<PagingData> بدلاً من observe على LiveData
 * - تحميل تلقائي عند التمرير (لا يوجد حد أقصى للسجلات)
 * - حالة التحميل تظهر تلقائياً عبر LoadState
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TaxpayerViewModel by viewModels()
    private lateinit var adapter: TaxpayerAdapter

    // ── ملتقطات الملفات ──────────────────────────────────────────────────────

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { startImport(it) }
    }

    private val backupPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBackup(it) }
    }

    private val backupCreateLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { performExportBackup(it) }
    }

    // ── ملتقطات التصدير ──────────────────────────────────────────────────────

    private var pendingExportFilter: ExportFilter = ExportFilter.ALL

    private val exportPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { performExport(it, ExportFormat.PDF) }
    }

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let { performExport(it, ExportFormat.CSV) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupSearchView()
        setupFilterButtons()
        observeViewModel()

        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java))
        }
    }

    // ── قائمة الخيارات ───────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_IMPORT, 0, "استيراد ملف Access")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_IMPORT_CSV, 1, "استيراد CSV")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MAP_ALL, 2, getString(R.string.show_all_on_map))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_LANDMARKS, 3, getString(R.string.manage_landmarks))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_BACKUP_EXPORT, 4, getString(R.string.export_backup))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_BACKUP_IMPORT, 5, getString(R.string.import_backup))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_EXPORT_REPORT, 6, getString(R.string.export_title))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_IMPORT -> { showImportDialog(); true }
            MENU_IMPORT_CSV -> {
                filePickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/*"))
                true
            }
            MENU_MAP_ALL -> {
                Intent(this, MapViewActivity::class.java).also {
                    it.putExtra(MapViewActivity.EXTRA_SHOW_ALL, true)
                    startActivity(it)
                }
                true
            }
            MENU_LANDMARKS -> {
                startActivity(Intent(this, LandmarkListActivity::class.java))
                true
            }
            MENU_BACKUP_EXPORT -> { exportBackup(); true }
            MENU_BACKUP_IMPORT -> {
                backupPickerLauncher.launch(arrayOf("*/*"))
                true
            }
            MENU_EXPORT_REPORT -> { showExportDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── إعداد القائمة (مع Paging) ────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = TaxpayerAdapter { taxpayer ->
            Intent(this, DetailActivity::class.java).also {
                it.putExtra(EXTRA_ID, taxpayer.id)
                startActivity(it)
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        // مراقبة حالة التحميل (LoadState)
        // Paging يُبلِّغ عن: Loading, Error, NotLoading
        adapter.addLoadStateListener { loadStates ->
            val isLoading = loadStates.refresh is LoadState.Loading
            val isEmpty = loadStates.refresh is LoadState.NotLoading
                    && adapter.itemCount == 0

            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

            // إظهار خطأ إن وجد
            val errorState = loadStates.refresh as? LoadState.Error
            errorState?.let {
                Toast.makeText(this,
                    "خطأ في تحميل البيانات: ${it.error.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── البحث ────────────────────────────────────────────────────────────────

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true.also {
                viewModel.onSearchQueryChanged(query ?: "")
            }
            override fun onQueryTextChange(newText: String?) = true.also {
                viewModel.onSearchQueryChanged(newText ?: "")
            }
        })
    }

    // ── أزرار الفلترة ────────────────────────────────────────────────────────

    private fun setupFilterButtons() {
        binding.btnFilterAll.setOnClickListener { viewModel.onTypeFilterChanged("") }
        binding.btnFilterOld.setOnClickListener { viewModel.onTypeFilterChanged(Taxpayer.TYPE_OLD) }
        binding.btnFilterNew.setOnClickListener { viewModel.onTypeFilterChanged(Taxpayer.TYPE_NEW) }
    }

    // ── مراقبة ViewModel (Paging Flow + Stats LiveData) ──────────────────────

    private fun observeViewModel() {
        // قائمة المكلفين عبر PagingData Flow
        lifecycleScope.launch {
            viewModel.taxpayersPaging.collect { pagingData ->
                adapter.submitData(pagingData)
            }
        }

        // الإحصائيات
        viewModel.stats.observe(this) { stats ->
            binding.tvTotal.text = "الإجمالي: ${stats.total}"
            binding.tvOldCount.text = "قدامى: ${stats.oldCount}"
            binding.tvNewCount.text = "جدد: ${stats.newCount}"
            binding.tvWithGps.text = "موقع: ${stats.withLocation}"
        }

        // رسائل الخطأ
        viewModel.errorMessage.observe(this) { msg ->
            msg?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    // ── تحديث عند العودة للشاشة ──────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        adapter.refresh()  // إعادة تحميل صفحة Paging
        viewModel.refresh() // تحديث الإحصائيات
    }

    // ── الاستيراد ────────────────────────────────────────────────────────────

    private fun showImportDialog() {
        AlertDialog.Builder(this)
            .setTitle("استيراد ملف Access (.accdb)")
            .setMessage(
                "اختر ملف قاعدة بيانات Access\n\n" +
                "سيتم استيراد جميع السجلات:\n" +
                "- السجل، اسم المكلف، اسم الأم\n" +
                "- رقم القرار، تاريخ القرار\n" +
                "- المهنة، العنوان\n" +
                "- مقدار الضريبة، الربح الصافي\n\n" +
                "هل تريد استبدال البيانات الحالية أم إضافة الجديدة فقط؟"
            )
            .setPositiveButton("استبدال الكل") { _, _ ->
                launchFilePicker(clearExisting = true)
            }
            .setNeutralButton("إضافة فقط") { _, _ ->
                launchFilePicker(clearExisting = false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launchFilePicker(clearExisting: Boolean) {
        importClearExisting = clearExisting
        filePickerLauncher.launch(arrayOf(
            "application/msaccess",
            "application/x-msaccess",
            "application/vnd.ms-access",
            "application/octet-stream",
            "*/*"
        ))
    }

    private var importClearExisting = false

    private fun startImport(uri: Uri) {
        val fileName = uri.lastPathSegment ?: "file"
        val isCsv = fileName.endsWith(".csv", true)

        val db = DatabaseHelper.getInstance(this)
        val importHelper = AccessDbImportHelper(this, db)

        // حوار التقدم
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("جاري الاستيراد")
            .setMessage("يرجى الانتظار...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        val listener = object : AccessDbImportHelper.ImportListener {
            override fun onProgress(current: Int, total: Int, message: String) {
                progressDialog.setMessage(message)
            }

            override fun onFinished(result: AccessDbImportHelper.ImportResult) {
                progressDialog.dismiss()
                adapter.refresh()  // إعادة تحميل القائمة
                viewModel.refresh()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تم الاستيراد بنجاح")
                    .setMessage(buildString {
                        if (result.tableName.isNotBlank()) {
                            append("الجدول: ${result.tableName}\n\n")
                        }
                        append("مُضاف: ${result.added}\n")
                        append("مُحدَّث: ${result.updated}\n")
                        append("مُتخطَّى: ${result.skipped}\n")
                        append("أخطاء: ${result.errors}\n\n")
                        append("الإجمالي: ${result.total}")
                    })
                    .setPositiveButton("حسناً", null)
                    .show()
            }

            override fun onError(error: String) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("خطأ في الاستيراد")
                    .setMessage(error)
                    .setPositiveButton("حسناً", null)
                    .show()
            }
        }

        lifecycleScope.launch {
            if (isCsv) {
                importHelper.importFromCsv(uri, listener, importClearExisting)
            } else {
                importHelper.importFromUri(uri, listener, importClearExisting)
            }
        }
    }

    // ── النسخ الاحتياطي ──────────────────────────────────────────────────────

    private fun exportBackup() {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date())
        backupCreateLauncher.launch("tax_backup_${timestamp}.taxbackup")
    }

    private fun performExportBackup(uri: Uri) {
        val backupHelper = BackupHelper(this, DatabaseHelper.getInstance(this))
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            backupHelper.exportBackup(uri, object : BackupHelper.BackupListener {
                override fun onProgress(message: String, percent: Int) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                override fun onSuccess(message: String) {
                    binding.progressBar.visibility = View.GONE
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.backup_success))
                        .setMessage(message)
                        .setPositiveButton("حسناً", null)
                        .show()
                }
                override fun onError(error: String) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun importBackup(uri: Uri) {
        val backupHelper = BackupHelper(this, DatabaseHelper.getInstance(this))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_title))
            .setMessage(getString(R.string.backup_replace_warning))
            .setPositiveButton("استبدال الكل") { _, _ ->
                performImportBackup(backupHelper, uri, true)
            }
            .setNeutralButton("إضافة فقط") { _, _ ->
                performImportBackup(backupHelper, uri, false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performImportBackup(helper: BackupHelper, uri: Uri, replace: Boolean) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            helper.importBackup(uri, replace, object : BackupHelper.BackupListener {
                override fun onProgress(message: String, percent: Int) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                override fun onSuccess(message: String) {
                    binding.progressBar.visibility = View.GONE
                    adapter.refresh()
                    viewModel.refresh()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.restore_success))
                        .setMessage(message)
                        .setPositiveButton("حسناً", null)
                        .show()
                }
                override fun onError(error: String) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    companion object {
        const val EXTRA_ID = "extra_taxpayer_id"
        private const val MENU_IMPORT = 100
        private const val MENU_IMPORT_CSV = 101
        private const val MENU_MAP_ALL = 102
        private const val MENU_LANDMARKS = 103
        private const val MENU_BACKUP_EXPORT = 104
        private const val MENU_BACKUP_IMPORT = 105
        private const val MENU_EXPORT_REPORT = 106
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ─── تصدير التقارير (PDF / CSV) ──────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private enum class ExportFilter { ALL, OLD_ONLY, NEW_ONLY, WITH_LOCATION }
    private enum class ExportFormat { PDF, CSV }

    private fun showExportDialog() {
        val scopes = arrayOf(
            getString(R.string.export_all),
            getString(R.string.export_old_only),
            getString(R.string.export_new_only),
            getString(R.string.export_with_location)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.export_choose_scope)
            .setItems(scopes) { _, which ->
                pendingExportFilter = when (which) {
                    1 -> ExportFilter.OLD_ONLY
                    2 -> ExportFilter.NEW_ONLY
                    3 -> ExportFilter.WITH_LOCATION
                    else -> ExportFilter.ALL
                }
                showFormatDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFormatDialog() {
        val formats = arrayOf(
            getString(R.string.export_pdf),
            getString(R.string.export_csv)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.export_choose_format)
            .setItems(formats) { _, which ->
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                    java.util.Locale.getDefault()).format(java.util.Date())
                when (which) {
                    0 -> exportPdfLauncher.launch("taxpayers_${timestamp}.pdf")
                    1 -> exportCsvLauncher.launch("taxpayers_${timestamp}.csv")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performExport(uri: Uri, format: ExportFormat) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.export_in_progress)
            .setMessage("0 / 0")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                // جلب البيانات حسب الفلتر
                val db = DatabaseHelper.getInstance(this@MainActivity)
                val all = db.getAllTaxpayersAsync(limit = 100000)
                val taxpayers = when (pendingExportFilter) {
                    ExportFilter.OLD_ONLY -> all.filter { it.isOld() }
                    ExportFilter.NEW_ONLY -> all.filter { !it.isOld() }
                    ExportFilter.WITH_LOCATION -> all.filter { it.hasLocation() }
                    ExportFilter.ALL -> all
                }

                if (taxpayers.isEmpty()) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity,
                        "لا توجد بيانات للتصدير", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val subtitle = when (pendingExportFilter) {
                    ExportFilter.OLD_ONLY -> "المكلفون القدامى فقط"
                    ExportFilter.NEW_ONLY -> "المكلفون الجدد فقط"
                    ExportFilter.WITH_LOCATION -> "المكلفون مع تحديد موقع GPS"
                    ExportFilter.ALL -> "كل المكلفين"
                }

                val config = com.taxgps.app.utils.ExportHelper.ReportConfig(
                    title = "تقرير المكلفين",
                    subtitle = subtitle,
                    taxpayers = taxpayers,
                    includeStats = true,
                    includeLocation = true
                )

                val exportHelper = com.taxgps.app.utils.ExportHelper(this@MainActivity)

                val listener = object : com.taxgps.app.utils.ExportHelper.ExportListener {
                    override fun onProgress(current: Int, total: Int, message: String) {
                        progressDialog.setMessage(message)
                    }
                    override fun onSuccess(message: String, recordCount: Int) {
                        progressDialog.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.export_success)
                            .setMessage("$message\n\nعدد السجلات: $recordCount")
                            .setPositiveButton("فتح") { _, _ ->
                                openExportedFile(uri, format)
                            }
                            .setNegativeButton("حسناً", null)
                            .show()
                    }
                    override fun onError(error: String) {
                        progressDialog.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.export_failed)
                            .setMessage(error)
                            .setPositiveButton("حسناً", null)
                            .show()
                    }
                }

                when (format) {
                    ExportFormat.PDF -> exportHelper.exportToPdf(config, uri, listener)
                    ExportFormat.CSV -> exportHelper.exportToCsv(config, uri, listener)
                }

            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@MainActivity,
                    "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * فتح الملف المُصدَّر بالتطبيق المناسب (PDF reader / Excel)
     */
    private fun openExportedFile(uri: Uri, format: ExportFormat) {
        try {
            val mimeType = when (format) {
                ExportFormat.PDF -> "application/pdf"
                ExportFormat.CSV -> "text/csv"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "فتح الملف"))
        } catch (e: Exception) {
            Toast.makeText(this,
                "لا يوجد تطبيق لفتح هذا الملف. الملف محفوظ في الموقع المحدد.",
                Toast.LENGTH_LONG).show()
        }
    }
}
