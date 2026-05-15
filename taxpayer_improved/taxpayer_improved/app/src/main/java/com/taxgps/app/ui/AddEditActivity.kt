package com.taxgps.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.taxgps.app.R
import com.taxgps.app.data.DatabaseHelper
import com.taxgps.app.data.Taxpayer
import com.taxgps.app.databinding.ActivityAddEditBinding
import com.taxgps.app.utils.LocationHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * شاشة إضافة / تعديل المكلف المحسّنة
 *
 * الإصلاحات v2:
 * ─────────────────────────────────────────────────────────────────────
 * 1. إصلاح التقاط الصورة: طلب صلاحية CAMERA قبل فتح الكاميرا
 * 2. التحقق من وجود تطبيق كاميرا قبل محاولة launch()
 * 3. رسائل خطأ واضحة عند فشل الكاميرا
 * 4. Timeout 60 ثانية مع تحذير للمستخدم
 * 5. تحذير عند الحفظ إذا كانت الدقة ضعيفة (> 25م)
 * 6. زر "حفظ أفضل قراءة متاحة" كخيار احتياطي
 * 7. عداد تنازلي واضح: "3 من 10 قراءات جيدة"
 */
class AddEditActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AddEditActivity"
        const val EXTRA_EDIT_ID = "extra_edit_id"
    }

    private lateinit var binding: ActivityAddEditBinding
    private lateinit var db: DatabaseHelper
    private lateinit var locationHelper: LocationHelper

    private var editId: Long = -1
    private var capturedLat: Double?  = null
    private var capturedLon: Double?  = null
    private var capturedAcc: Float?   = null
    private var capturedAt:  Long?    = null
    private var isCapturing = false
    private var photosList = mutableListOf<String>()  // مسارات الصور

    // ── صلاحية الموقع ────────────────────────────────────────────────────────

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startCapturingLocation()
        } else {
            Toast.makeText(this, getString(R.string.location_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    // ── صلاحية الكاميرا ──────────────────────────────────────────────────────

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(this, "يجب منح صلاحية الكاميرا لالتقاط الصور", Toast.LENGTH_LONG).show()
        }
    }

    // ── التقاط صورة من الكاميرا ──────────────────────────────────────────────
    private var currentPhotoPath: String = ""
    private var currentPhotoUri: Uri? = null

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoPath.isNotBlank()) {
            // ضغط الصورة في خلفية ثم إضافتها للقائمة
            lifecycleScope.launch {
                val compressedFile = com.taxgps.app.utils.PhotoCompressor.compressFile(
                    File(currentPhotoPath)
                )
                photosList.add(compressedFile.absolutePath)
                updatePhotoCount()
                val sizeKb = compressedFile.length() / 1024
                Toast.makeText(this@AddEditActivity,
                    "تم حفظ الصورة (${sizeKb}KB)", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Photo captured and compressed: $currentPhotoPath (${sizeKb}KB)")
            }
        } else {
            // التقاط الصورة فشل أو ألغاه المستخدم
            Log.w(TAG, "Photo capture failed or cancelled. success=$success, path=$currentPhotoPath")
            // حذف الملف الفارغ الذي أنشأناه
            if (currentPhotoPath.isNotBlank()) {
                val file = File(currentPhotoPath)
                if (file.exists() && file.length() == 0L) {
                    file.delete()
                }
            }
        }
    }

    // ── اختيار صورة من المعرض ────────────────────────────────────────────────
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { savePickedPhoto(it) }
    }

    // ── دورة الحياة ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = DatabaseHelper.getInstance(this)
        locationHelper = LocationHelper(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupStatusSpinner()

        editId = intent.getLongExtra(EXTRA_EDIT_ID, -1)
        if (editId != -1L) {
            binding.toolbar.title = getString(R.string.edit_taxpayer)
            loadExistingData()
        }

        binding.btnCaptureLocation.setOnClickListener {
            if (isCapturing) stopCapturingLocation(saveCurrentReading = true)
            else requestLocationCapture()
        }

        binding.btnSave.setOnClickListener { attemptSave() }

        // أزرار الصور — مع التحقق من الصلاحيات
        binding.btnTakePhoto.setOnClickListener { takePhoto() }
        binding.btnPickPhoto.setOnClickListener { pickPhotoLauncher.launch("image/*") }
    }

    override fun onDestroy() {
        locationHelper.stopLocationUpdates()
        super.onDestroy()
    }

    // ── إعداد العناصر ────────────────────────────────────────────────────────

    private fun setupStatusSpinner() {
        binding.spinnerStatus.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Taxpayer.STATUS_LIST
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    // ── تحميل البيانات عند التعديل ───────────────────────────────────────────

    private fun loadExistingData() {
        lifecycleScope.launch {
            val t = db.getTaxpayerByIdAsync(editId) ?: return@launch
            with(binding) {
                etName.setText(t.name)
                etTaxNumber.setText(t.taxNumber)
                etIdNumber.setText(t.idNumber)
                etPhone.setText(t.phone)
                etAddress.setText(t.address)
                etActivityType.setText(t.activityType)
                etNotes.setText(t.notes)
                etPropertyNumber.setText(t.propertyNumber)
                etNeighborRight.setText(t.neighborRight)
                etNeighborLeft.setText(t.neighborLeft)
                etShopDesc.setText(t.shopDescription)

                if (t.isOld()) rbOld.isChecked = true else rbNew.isChecked = true

                val statusIdx = Taxpayer.STATUS_LIST.indexOf(t.status)
                if (statusIdx >= 0) spinnerStatus.setSelection(statusIdx)

                // تحميل الصور
                if (t.photos.isNotBlank()) {
                    photosList = t.photos.split("|").filter { it.isNotBlank() }.toMutableList()
                    updatePhotoCount()
                }

                if (t.hasLocation()) {
                    capturedLat = t.latitude
                    capturedLon = t.longitude
                    capturedAcc = t.accuracy
                    capturedAt  = t.capturedAt
                    updateLocationDisplay()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── التقاط الصورة (مُصلَح) ──────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * التقاط صورة — التسلسل الصحيح:
     * 1. التحقق من صلاحية الكاميرا (طلبها إن لم تكن ممنوحة)
     * 2. التحقق من وجود تطبيق كاميرا على الجهاز
     * 3. إنشاء ملف الصورة وتوليد URI عبر FileProvider
     * 4. إطلاق intent الكاميرا
     */
    private fun takePhoto() {
        // الخطوة 1: التحقق من صلاحية الكاميرا
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED -> {
                // الصلاحية ممنوحة — تابع
                launchCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // عرض تفسير قبل الطلب
                AlertDialog.Builder(this)
                    .setTitle("صلاحية الكاميرا مطلوبة")
                    .setMessage("يحتاج التطبيق صلاحية الكاميرا لالتقاط صور المحلات التجارية.")
                    .setPositiveButton("منح الصلاحية") { _, _ ->
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
            else -> {
                // طلب الصلاحية مباشرة
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * فتح الكاميرا فعلياً بعد التحقق من الصلاحيات
     */
    private fun launchCamera() {
        try {
            // الخطوة 2: التحقق من وجود تطبيق كاميرا
            val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            if (cameraIntent.resolveActivity(packageManager) == null) {
                // لا يوجد تطبيق كاميرا — محاولة launch بدون resolveActivity
                // (على Android 11+ قد لا يعمل resolveActivity بسبب package visibility)
                Log.w(TAG, "resolveActivity returned null, attempting launch anyway (Android 11+ behavior)")
            }

            // الخطوة 3: إنشاء ملف الصورة
            val photosDir = File(filesDir, "taxpayer_photos")
            if (!photosDir.exists()) {
                val created = photosDir.mkdirs()
                Log.d(TAG, "Photos dir created: $created at ${photosDir.absolutePath}")
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(photosDir, "IMG_${timeStamp}.jpg")
            currentPhotoPath = photoFile.absolutePath

            // الخطوة 4: توليد URI عبر FileProvider
            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            currentPhotoUri = photoUri

            Log.i(TAG, "Launching camera with URI: $photoUri, path: $currentPhotoPath")

            // الخطوة 5: إطلاق الكاميرا
            takePhotoLauncher.launch(photoUri)

        } catch (e: IllegalArgumentException) {
            // FileProvider لم يتمكن من توليد URI — مشكلة في file_paths.xml
            Log.e(TAG, "FileProvider error", e)
            Toast.makeText(this,
                "خطأ في إعداد مسار الصور. تأكد من صحة إعدادات التطبيق.",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Camera launch error", e)
            Toast.makeText(this,
                "خطأ في فتح الكاميرا: ${e.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun savePickedPhoto(uri: Uri) {
        try {
            val photosDir = File(filesDir, "taxpayer_photos")
            if (!photosDir.exists()) photosDir.mkdirs()

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val destFile = File(photosDir, "PICK_${timeStamp}.jpg")

            // ضغط الصورة أثناء النسخ
            lifecycleScope.launch {
                val compressed = com.taxgps.app.utils.PhotoCompressor.compressFromUri(
                    this@AddEditActivity, uri, destFile
                )
                photosList.add(compressed.absolutePath)
                updatePhotoCount()
                val sizeKb = compressed.length() / 1024
                Toast.makeText(this@AddEditActivity,
                    "تم حفظ الصورة (${sizeKb}KB)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save picked photo", e)
            Toast.makeText(this, getString(R.string.photo_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePhotoCount() {
        val count = photosList.size
        binding.tvPhotoCount.text = if (count > 0) {
            getString(R.string.photos_count, count)
        } else {
            getString(R.string.no_photos)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── التقاط الموقع GPS ─────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private fun requestLocationCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startCapturingLocation()
        } else {
            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun startCapturingLocation() {
        isCapturing = true
        binding.btnCaptureLocation.text = "إيقاف وحفظ القراءة الحالية"
        binding.gpsProgressLayout.visibility = View.VISIBLE
        binding.averagingProgress.max      = LocationHelper.MAX_SAMPLES
        binding.averagingProgress.progress = 0

        locationHelper.startLocationUpdates(
            onLocationUpdate = { location, samples, max ->
                handleLocationUpdate(location, samples, max)
            },
            onTimeout = {
                showTimeoutDialog()
            },
            onError = { error ->
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                stopCapturingLocation(saveCurrentReading = false)
            }
        )
    }

    private fun handleLocationUpdate(location: Location, samples: Int, max: Int) {
        capturedLat = location.latitude
        capturedLon = location.longitude
        capturedAcc = location.accuracy
        capturedAt  = System.currentTimeMillis()

        binding.tvGpsProgress.text     = "جمع القراءة $samples من $max..."
        binding.averagingProgress.progress = samples

        updateLocationDisplay()

        // Auto-stop عند اكتمال العينات بدقة جيدة
        if (samples >= max && location.accuracy <= LocationHelper.GOOD_ACCURACY_METERS) {
            stopCapturingLocation(saveCurrentReading = false)
            Toast.makeText(this, "✓ تم تحقيق الدقة المطلوبة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCapturingLocation(saveCurrentReading: Boolean) {
        if (saveCurrentReading && !locationHelper.hasGoodReadings()) {
            locationHelper.getBestAvailableLocation()?.let { loc ->
                capturedLat = loc.latitude
                capturedLon = loc.longitude
                capturedAcc = loc.accuracy
                capturedAt  = System.currentTimeMillis()
                updateLocationDisplay()
            }
        }
        isCapturing = false
        locationHelper.stopLocationUpdates()
        binding.btnCaptureLocation.text    = getString(R.string.recapture_location)
        binding.gpsProgressLayout.visibility = View.GONE
    }

    /** حوار Timeout: خيار حفظ أفضل قراءة متاحة */
    private fun showTimeoutDialog() {
        val bestLocation = locationHelper.getBestAvailableLocation()
        AlertDialog.Builder(this)
            .setTitle("انتهت مهلة GPS")
            .setMessage(
                if (bestLocation != null)
                    "لم تُحقَّق الدقة المطلوبة خلال 60 ثانية.\n" +
                    "أفضل دقة متاحة: ${bestLocation.accuracy.toInt()} متر\n\n" +
                    "هل تريد حفظ هذه القراءة الاحتياطية؟"
                else
                    "لم يتمكن GPS من تحديد موقعك.\nتأكد من أنك في مكان مفتوح وحاول مجدداً."
            )
            .apply {
                if (bestLocation != null) {
                    setPositiveButton("حفظ القراءة الاحتياطية") { _, _ ->
                        capturedLat = bestLocation.latitude
                        capturedLon = bestLocation.longitude
                        capturedAcc = bestLocation.accuracy
                        capturedAt  = System.currentTimeMillis()
                        updateLocationDisplay()
                        stopCapturingLocation(saveCurrentReading = false)
                    }
                }
                setNegativeButton("إلغاء") { _, _ ->
                    stopCapturingLocation(saveCurrentReading = false)
                }
            }
            .show()
    }

    // ── عرض معلومات الموقع ────────────────────────────────────────────────────

    private fun updateLocationDisplay() {
        val lat = capturedLat ?: return
        val lon = capturedLon ?: return

        with(binding) {
            tvLocationStatus.text    = "✓ تم تحديد الموقع"
            tvCoordinates.text       = "${LocationHelper.formatCoordinate(lat)}, ${LocationHelper.formatCoordinate(lon)}"
            tvCoordinates.visibility = View.VISIBLE

            capturedAcc?.let { acc ->
                tvAccuracyInfo.text      = "${LocationHelper.getAccuracyLabel(acc)} (${acc.toInt()} متر)"
                tvAccuracyInfo.setTextColor(LocationHelper.getAccuracyColor(acc))
                tvAccuracyInfo.visibility = View.VISIBLE
            }

            capturedAt?.let { ts ->
                tvCapturedAt.text       = "آخر تحديث: ${LocationHelper.formatTimestamp(ts)}"
                tvCapturedAt.visibility = View.VISIBLE
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── الحفظ ─────────────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private fun attemptSave() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etName.error = getString(R.string.required_field)
            binding.etName.requestFocus()
            return
        }

        // تحذير إذا كانت الدقة ضعيفة عند الحفظ
        val acc = capturedAcc
        if (acc != null && acc > LocationHelper.GOOD_ACCURACY_METERS) {
            AlertDialog.Builder(this)
                .setTitle("تحذير: دقة ضعيفة")
                .setMessage(
                    "دقة الموقع الحالية ${acc.toInt()} متر.\n" +
                    "يُنصح بدقة أفضل من ${LocationHelper.GOOD_ACCURACY_METERS.toInt()} متر.\n\n" +
                    "هل تريد الحفظ بالدقة الحالية؟"
                )
                .setPositiveButton("حفظ على أي حال") { _, _ -> performSave(name) }
                .setNegativeButton("إعادة الالتقاط", null)
                .show()
        } else {
            performSave(name)
        }
    }

    private fun performSave(name: String) {
        val taxpayer = Taxpayer(
            id              = if (editId != -1L) editId else 0,
            name            = name,
            taxNumber       = binding.etTaxNumber.text.toString().trim(),
            idNumber        = binding.etIdNumber.text.toString().trim(),
            phone           = binding.etPhone.text.toString().trim(),
            address         = binding.etAddress.text.toString().trim(),
            activityType    = binding.etActivityType.text.toString().trim(),
            notes           = binding.etNotes.text.toString().trim(),
            type            = if (binding.rbOld.isChecked) Taxpayer.TYPE_OLD else Taxpayer.TYPE_NEW,
            status          = Taxpayer.STATUS_LIST[binding.spinnerStatus.selectedItemPosition],
            propertyNumber  = binding.etPropertyNumber.text.toString().trim(),
            neighborRight   = binding.etNeighborRight.text.toString().trim(),
            neighborLeft    = binding.etNeighborLeft.text.toString().trim(),
            shopDescription = binding.etShopDesc.text.toString().trim(),
            photos          = photosList.joinToString("|"),
            latitude        = capturedLat,
            longitude       = capturedLon,
            accuracy        = capturedAcc,
            capturedAt      = capturedAt
        )

        lifecycleScope.launch {
            val newId = if (editId != -1L) {
                db.updateTaxpayerAsync(taxpayer)
                editId
            } else {
                db.insertTaxpayerAsync(taxpayer)
            }

            // إذا كانت هناك جولة نشطة + للمكلف موقع، سجّل زيارة محل
            val tourState = com.taxgps.app.tracking.TourTrackingManager.state.value
            if (tourState.isActive && capturedLat != null && capturedLon != null && newId > 0) {
                val visitLocation = android.location.Location("").apply {
                    latitude = capturedLat!!
                    longitude = capturedLon!!
                    accuracy = capturedAcc ?: 0f
                }
                com.taxgps.app.tracking.TourTrackingManager.recordShopVisit(
                    this@AddEditActivity, newId, visitLocation
                )
                Toast.makeText(this@AddEditActivity,
                    "تم حفظ المكلف وربطه بالجولة الحالية",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AddEditActivity, "تم حفظ البيانات بنجاح", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
