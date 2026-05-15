package com.taxgps.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.taxgps.app.security.EncryptionKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File

/**
 * مساعد قاعدة البيانات SQLite — مع تشفير SQLCipher (AES-256)
 *
 * v8 - SQLCipher Encryption:
 * ─────────────────────────────────────────────────────────────────────
 * - قاعدة البيانات مشفّرة بالكامل بـ AES-256
 * - المفتاح يُولَّد تلقائياً ويُحفظ في Android Keystore
 * - ترقية تلقائية للقاعدة القديمة غير المشفّرة (encrypt-in-place)
 * - شفّاف للكود: نفس API الـ SQLite العادي
 *
 * ملاحظة مهمة:
 * ─────────────────────────────────────────────────────────────────────
 * نستخدم net.zetetic.database.sqlcipher (مكتبة SQLCipher الجديدة)
 * بدلاً من android.database.sqlite. الـ API متطابق تقريباً.
 */
class DatabaseHelper private constructor(
    context: Context,
    private val passphrase: ByteArray
) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    passphrase,
    null,
    DB_VERSION,
    0,
    null,
    null,
    false
) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "taxpayers_v4.db"
        private const val DB_VERSION = 9   // v9: جداول الجولات والمسارات

        // اسم الملف القديم غير المشفّر (للترقية)
        private const val LEGACY_DB_NAME = "taxpayers_v4.db"

        const val TABLE = "taxpayers"
        const val TABLE_LANDMARKS = "landmarks"
        const val TABLE_TOURS = "tours"
        const val TABLE_TRACK_POINTS = "track_points"
        const val TABLE_STREET_SEGMENTS = "street_segments"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null
        @Volatile
        private var sqlcipherLoaded = false

        /**
         * تحميل مكتبة SQLCipher الأصلية (يُستدعى مرة واحدة فقط)
         * يجب استدعاؤه قبل أول استخدام لـ DatabaseHelper
         */
        fun initSqlCipher() {
            if (!sqlcipherLoaded) {
                synchronized(this) {
                    if (!sqlcipherLoaded) {
                        System.loadLibrary("sqlcipher")
                        sqlcipherLoaded = true
                        Log.i(TAG, "SQLCipher native library loaded")
                    }
                }
            }
        }

        fun getInstance(context: Context): DatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createInstance(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun createInstance(context: Context): DatabaseHelper {
            initSqlCipher()

            // الحصول على مفتاح التشفير (يُولَّد عند أول مرة)
            val key = EncryptionKeyManager.getOrCreateDbKey(context)

            // ترقية القاعدة القديمة غير المشفّرة (إن وُجدت)
            DatabaseMigrationHelper.encryptLegacyDatabaseIfNeeded(context, DB_NAME, key)

            return DatabaseHelper(context, key)
        }

        // أعمدة جدول المكلفين
        const val COL_ID              = "_id"
        const val COL_RECORD_NUMBER   = "record_number"
        const val COL_NAME            = "name"
        const val COL_MOTHER_NAME     = "mother_name"
        const val COL_TAX_NUMBER      = "tax_number"
        const val COL_ID_NUMBER       = "id_number"
        const val COL_PHONE           = "phone"
        const val COL_ADDRESS         = "address"
        const val COL_ACTIVITY_TYPE   = "activity_type"
        const val COL_NOTES           = "notes"
        const val COL_TYPE            = "type"
        const val COL_STATUS          = "status"
        const val COL_ACCESS_NO       = "access_decision_no"
        const val COL_DECISION_DATE   = "decision_date"
        const val COL_TAX_AMOUNT      = "tax_amount"
        const val COL_WORK_NUMBER     = "work_number"
        const val COL_NET_PROFIT      = "net_profit"
        const val COL_NEIGHBOR_RIGHT  = "neighbor_right"
        const val COL_NEIGHBOR_LEFT   = "neighbor_left"
        const val COL_SHOP_DESC       = "shop_description"
        const val COL_PROPERTY_NUMBER = "property_number"
        const val COL_PHOTOS          = "photos"
        const val COL_LATITUDE        = "latitude"
        const val COL_LONGITUDE       = "longitude"
        const val COL_ACCURACY        = "accuracy"
        const val COL_CAPTURED_AT     = "captured_at"
        const val COL_CREATED_AT      = "created_at"
        const val COL_SYNC_STATUS     = "sync_status"
        const val COL_DRIVE_ID        = "google_drive_id"

        // أعمدة جدول المعالم المرجعية
        const val COL_LM_ID           = "_id"
        const val COL_LM_NAME         = "name"
        const val COL_LM_TYPE         = "type"
        const val COL_LM_DESCRIPTION  = "description"
        const val COL_LM_AREA         = "area"
        const val COL_LM_LATITUDE     = "latitude"
        const val COL_LM_LONGITUDE    = "longitude"
        const val COL_LM_ACCURACY     = "accuracy"
        const val COL_LM_IS_MAIN      = "is_main_reference"
        const val COL_LM_CREATED_AT   = "created_at"

        // أعمدة جدول الجولات
        const val COL_TOUR_ID          = "_id"
        const val COL_TOUR_NAME        = "name"
        const val COL_TOUR_STARTED     = "started_at"
        const val COL_TOUR_ENDED       = "ended_at"
        const val COL_TOUR_POINTS      = "point_count"
        const val COL_TOUR_TAXPAYERS   = "taxpayer_count"
        const val COL_TOUR_DISTANCE    = "distance_meters"
        const val COL_TOUR_NOTES       = "notes"
        const val COL_TOUR_CREATED     = "created_at"

        // أعمدة نقاط المسار
        const val COL_TP_ID            = "_id"
        const val COL_TP_TOUR          = "tour_id"
        const val COL_TP_LAT           = "latitude"
        const val COL_TP_LON           = "longitude"
        const val COL_TP_ACC           = "accuracy"
        const val COL_TP_TIME          = "timestamp"
        const val COL_TP_TYPE          = "type"
        const val COL_TP_TAXPAYER      = "taxpayer_id"
        const val COL_TP_SEGMENT       = "street_segment_id"
        const val COL_TP_ACCURATE      = "is_accurate"

        // أعمدة الـ street segments (للـ heatmap)
        const val COL_SEG_ID           = "_id"
        const val COL_SEG_LAT          = "center_lat"
        const val COL_SEG_LON          = "center_lon"
        const val COL_SEG_VISITS       = "visit_count"
        const val COL_SEG_FIRST        = "first_visit_at"
        const val COL_SEG_LAST         = "last_visit_at"
        const val COL_SEG_AVG_ACC      = "average_accuracy"

        private val ALL_COLUMNS = arrayOf(
            COL_ID, COL_RECORD_NUMBER, COL_NAME, COL_MOTHER_NAME,
            COL_TAX_NUMBER, COL_ID_NUMBER, COL_PHONE,
            COL_ADDRESS, COL_ACTIVITY_TYPE, COL_NOTES, COL_TYPE, COL_STATUS,
            COL_ACCESS_NO, COL_DECISION_DATE, COL_TAX_AMOUNT, COL_WORK_NUMBER, COL_NET_PROFIT,
            COL_NEIGHBOR_RIGHT, COL_NEIGHBOR_LEFT, COL_SHOP_DESC, COL_PROPERTY_NUMBER, COL_PHOTOS,
            COL_LATITUDE, COL_LONGITUDE, COL_ACCURACY,
            COL_CAPTURED_AT, COL_CREATED_AT,
            COL_SYNC_STATUS, COL_DRIVE_ID
        )

        private val ALL_LANDMARK_COLUMNS = arrayOf(
            COL_LM_ID, COL_LM_NAME, COL_LM_TYPE, COL_LM_DESCRIPTION,
            COL_LM_AREA, COL_LM_LATITUDE, COL_LM_LONGITUDE,
            COL_LM_ACCURACY, COL_LM_IS_MAIN, COL_LM_CREATED_AT
        )

        private val ALL_TOUR_COLUMNS = arrayOf(
            COL_TOUR_ID, COL_TOUR_NAME, COL_TOUR_STARTED, COL_TOUR_ENDED,
            COL_TOUR_POINTS, COL_TOUR_TAXPAYERS, COL_TOUR_DISTANCE,
            COL_TOUR_NOTES, COL_TOUR_CREATED
        )

        private val ALL_TRACK_POINT_COLUMNS = arrayOf(
            COL_TP_ID, COL_TP_TOUR, COL_TP_LAT, COL_TP_LON, COL_TP_ACC,
            COL_TP_TIME, COL_TP_TYPE, COL_TP_TAXPAYER, COL_TP_SEGMENT,
            COL_TP_ACCURATE
        )

        private val ALL_SEGMENT_COLUMNS = arrayOf(
            COL_SEG_ID, COL_SEG_LAT, COL_SEG_LON, COL_SEG_VISITS,
            COL_SEG_FIRST, COL_SEG_LAST, COL_SEG_AVG_ACC
        )
    }

    // ─── إنشاء الجداول ───────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        // جدول المكلفين
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID             INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_RECORD_NUMBER  INTEGER DEFAULT 0,
                $COL_NAME           TEXT NOT NULL,
                $COL_MOTHER_NAME    TEXT,
                $COL_TAX_NUMBER     TEXT,
                $COL_ID_NUMBER      TEXT,
                $COL_PHONE          TEXT,
                $COL_ADDRESS        TEXT,
                $COL_ACTIVITY_TYPE  TEXT,
                $COL_NOTES          TEXT,
                $COL_TYPE           TEXT NOT NULL DEFAULT '${Taxpayer.TYPE_OLD}',
                $COL_STATUS         TEXT DEFAULT '${Taxpayer.STATUS_ACTIVE}',
                $COL_ACCESS_NO      TEXT,
                $COL_DECISION_DATE  TEXT,
                $COL_TAX_AMOUNT     INTEGER DEFAULT 0,
                $COL_WORK_NUMBER    TEXT,
                $COL_NET_PROFIT     INTEGER DEFAULT 0,
                $COL_NEIGHBOR_RIGHT TEXT,
                $COL_NEIGHBOR_LEFT  TEXT,
                $COL_SHOP_DESC      TEXT,
                $COL_PROPERTY_NUMBER TEXT,
                $COL_PHOTOS         TEXT,
                $COL_LATITUDE       REAL,
                $COL_LONGITUDE      REAL,
                $COL_ACCURACY       REAL,
                $COL_CAPTURED_AT    INTEGER,
                $COL_CREATED_AT     INTEGER,
                $COL_SYNC_STATUS    INTEGER DEFAULT 0,
                $COL_DRIVE_ID       TEXT
            )
        """.trimIndent())

        // فهارس المكلفين
        db.execSQL("CREATE INDEX idx_name        ON $TABLE($COL_NAME)")
        db.execSQL("CREATE INDEX idx_access      ON $TABLE($COL_ACCESS_NO)")
        db.execSQL("CREATE INDEX idx_type        ON $TABLE($COL_TYPE)")
        db.execSQL("CREATE INDEX idx_record_num  ON $TABLE($COL_RECORD_NUMBER)")
        db.execSQL("CREATE INDEX idx_address     ON $TABLE($COL_ADDRESS)")
        db.execSQL("CREATE INDEX idx_activity    ON $TABLE($COL_ACTIVITY_TYPE)")

        // جدول المعالم المرجعية
        createLandmarksTable(db)

        // جداول الجولات
        createTourTables(db)

        Log.i(TAG, "Database v$DB_VERSION created")
    }

    private fun createLandmarksTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_LANDMARKS (
                $COL_LM_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LM_NAME        TEXT NOT NULL,
                $COL_LM_TYPE        TEXT NOT NULL DEFAULT '${Landmark.TYPE_OTHER}',
                $COL_LM_DESCRIPTION TEXT,
                $COL_LM_AREA        TEXT,
                $COL_LM_LATITUDE    REAL NOT NULL,
                $COL_LM_LONGITUDE   REAL NOT NULL,
                $COL_LM_ACCURACY    REAL,
                $COL_LM_IS_MAIN     INTEGER DEFAULT 0,
                $COL_LM_CREATED_AT  INTEGER
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_name ON $TABLE_LANDMARKS($COL_LM_NAME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_type ON $TABLE_LANDMARKS($COL_LM_TYPE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_area ON $TABLE_LANDMARKS($COL_LM_AREA)")
    }

    /**
     * إنشاء جداول الجولات + نقاط المسار + Street Segments (للـ heatmap)
     */
    private fun createTourTables(db: SQLiteDatabase) {
        // جدول الجولات
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_TOURS (
                $COL_TOUR_ID         INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TOUR_NAME       TEXT,
                $COL_TOUR_STARTED    INTEGER NOT NULL,
                $COL_TOUR_ENDED      INTEGER,
                $COL_TOUR_POINTS     INTEGER DEFAULT 0,
                $COL_TOUR_TAXPAYERS  INTEGER DEFAULT 0,
                $COL_TOUR_DISTANCE   REAL DEFAULT 0,
                $COL_TOUR_NOTES      TEXT,
                $COL_TOUR_CREATED    INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tour_started ON $TABLE_TOURS($COL_TOUR_STARTED)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tour_ended ON $TABLE_TOURS($COL_TOUR_ENDED)")

        // جدول نقاط المسار
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_TRACK_POINTS (
                $COL_TP_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TP_TOUR      INTEGER NOT NULL,
                $COL_TP_LAT       REAL NOT NULL,
                $COL_TP_LON       REAL NOT NULL,
                $COL_TP_ACC       REAL,
                $COL_TP_TIME      INTEGER NOT NULL,
                $COL_TP_TYPE      TEXT NOT NULL DEFAULT 'walking',
                $COL_TP_TAXPAYER  INTEGER,
                $COL_TP_SEGMENT   INTEGER,
                $COL_TP_ACCURATE  INTEGER DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tp_tour ON $TABLE_TRACK_POINTS($COL_TP_TOUR)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tp_time ON $TABLE_TRACK_POINTS($COL_TP_TIME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tp_segment ON $TABLE_TRACK_POINTS($COL_TP_SEGMENT)")

        // جدول الـ street segments (لتجميع المسارات المتكررة)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STREET_SEGMENTS (
                $COL_SEG_ID       INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SEG_LAT      REAL NOT NULL,
                $COL_SEG_LON      REAL NOT NULL,
                $COL_SEG_VISITS   INTEGER DEFAULT 1,
                $COL_SEG_FIRST    INTEGER NOT NULL,
                $COL_SEG_LAST     INTEGER NOT NULL,
                $COL_SEG_AVG_ACC  REAL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_seg_lat ON $TABLE_STREET_SEGMENTS($COL_SEG_LAT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_seg_lon ON $TABLE_STREET_SEGMENTS($COL_SEG_LON)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_seg_visits ON $TABLE_STREET_SEGMENTS($COL_SEG_VISITS)")
    }

    // ─── Migrations آمنة ومتسلسلة ────────────────────────────────────────────

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading DB from v$oldVersion to v$newVersion")

        if (oldVersion < 2) migrateTo2(db)
        if (oldVersion < 3) migrateTo3(db)
        if (oldVersion < 4) migrateTo4(db)
        if (oldVersion < 5) migrateTo5(db)
        if (oldVersion < 6) migrateTo6(db)
        if (oldVersion < 7) migrateTo7(db)
        if (oldVersion < 9) migrateTo9(db)

        Log.i(TAG, "Upgrade complete")
    }

    private fun migrateTo2(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NEIGHBOR_RIGHT TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NEIGHBOR_LEFT  TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_SHOP_DESC      TEXT")
    }

    private fun migrateTo3(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_ACCESS_NO   TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_SYNC_STATUS INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_DRIVE_ID    TEXT")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_access ON $TABLE($COL_ACCESS_NO)")
    }

    private fun migrateTo4(db: SQLiteDatabase) {
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_type ON $TABLE($COL_TYPE)")
    }

    private fun migrateTo5(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_RECORD_NUMBER  INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_MOTHER_NAME    TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_DECISION_DATE  TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_TAX_AMOUNT     INTEGER DEFAULT 0")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_WORK_NUMBER    TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_NET_PROFIT     INTEGER DEFAULT 0")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_record_num ON $TABLE($COL_RECORD_NUMBER)")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_address    ON $TABLE($COL_ADDRESS)")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_activity   ON $TABLE($COL_ACTIVITY_TYPE)")
    }

    /** v5 → v6: إضافة جدول المعالم المرجعية */
    private fun migrateTo6(db: SQLiteDatabase) {
        createLandmarksTable(db)
    }

    /** v6 → v7: إضافة رقم العقار والصور */
    private fun migrateTo7(db: SQLiteDatabase) {
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_PROPERTY_NUMBER TEXT")
        safeAlter(db, "ALTER TABLE $TABLE ADD COLUMN $COL_PHOTOS TEXT")
        safeAlter(db, "CREATE INDEX IF NOT EXISTS idx_property ON $TABLE($COL_PROPERTY_NUMBER)")
    }

    /**
     * v7/v8 → v9: إضافة جداول الجولات
     *
     * نتجاوز v8 لأنه كان مخصصاً للتشفير فقط (لا تغييرات في schema).
     * هنا نضيف الجداول الجديدة لميزة التتبّع.
     */
    private fun migrateTo9(db: SQLiteDatabase) {
        createTourTables(db)
    }

    private fun safeAlter(db: SQLiteDatabase, sql: String) {
        try {
            db.execSQL(sql)
        } catch (e: Exception) {
            Log.w(TAG, "Migration step skipped: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── عمليات CRUD للمكلفين ────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun insertTaxpayerAsync(t: Taxpayer): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE, null, t.toContentValues())
    }

    suspend fun updateTaxpayerAsync(t: Taxpayer): Int = withContext(Dispatchers.IO) {
        writableDatabase.update(TABLE, t.toContentValues(), "$COL_ID=?", arrayOf(t.id.toString()))
    }

    suspend fun deleteTaxpayerAsync(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
    }

    suspend fun insertBatchAsync(taxpayers: List<Taxpayer>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in taxpayers) {
                db.insert(TABLE, null, t.toContentValues())
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        count
    }

    // ─── استعلامات القراءة ───────────────────────────────────────────────────

    suspend fun getAllTaxpayersAsync(
        filter: String = "",
        typeFilter: String = "",
        limit: Int = 500,
        offset: Int = 0
    ): List<Taxpayer> = withContext(Dispatchers.IO) {

        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (filter.isNotBlank()) {
            // بحث محسّن: يدعم البحث الجزئي بكفاءة
            // استخدام GLOB بدلاً من LIKE للحروف العربية أسرع في بعض الحالات
            conditions.add(
                "($COL_NAME LIKE ? OR $COL_TAX_NUMBER LIKE ? " +
                "OR $COL_PHONE LIKE ? OR $COL_ACCESS_NO LIKE ? " +
                "OR $COL_ADDRESS LIKE ? OR $COL_ACTIVITY_TYPE LIKE ? " +
                "OR $COL_RECORD_NUMBER LIKE ? OR $COL_PROPERTY_NUMBER LIKE ?)"
            )
            val q = "%$filter%"
            repeat(8) { args.add(q) }
        }
        if (typeFilter.isNotBlank()) {
            conditions.add("$COL_TYPE=?")
            args.add(typeFilter)
        }

        val selection = if (conditions.isEmpty()) null else conditions.joinToString(" AND ")
        val selArgs  = if (args.isEmpty()) null else args.toTypedArray()

        // LIMIT مهم جداً لتحسين الأداء مع آلاف السجلات
        readableDatabase.query(
            TABLE, ALL_COLUMNS, selection, selArgs,
            null, null, "$COL_NAME ASC", "$limit OFFSET $offset"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTaxpayer()) }
        }
    }

    suspend fun getTaxpayersWithLocationAsync(): List<Taxpayer> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE, ALL_COLUMNS,
            "$COL_LATITUDE IS NOT NULL AND $COL_LONGITUDE IS NOT NULL",
            null, null, null, "$COL_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTaxpayer()) }
        }
    }

    /** جلب المكلفين في منطقة معينة مع إحداثيات */
    suspend fun getTaxpayersInAreaAsync(area: String): List<Taxpayer> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE, ALL_COLUMNS,
            "$COL_ADDRESS LIKE ? AND $COL_LATITUDE IS NOT NULL AND $COL_LONGITUDE IS NOT NULL",
            arrayOf("%$area%"),
            null, null, "$COL_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTaxpayer()) }
        }
    }

    suspend fun getTaxpayerByIdAsync(id: Long): Taxpayer? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE, ALL_COLUMNS, "$COL_ID=?", arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTaxpayer() else null
        }
    }

    suspend fun findTaxpayerForUpdateAsync(name: String, decisionNo: String): Taxpayer? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                TABLE, ALL_COLUMNS,
                "$COL_NAME=? AND $COL_ACCESS_NO=?",
                arrayOf(name, decisionNo),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toTaxpayer() else null
            }
        }

    suspend fun findByNameAndRecordAsync(name: String, recordNumber: Int): Taxpayer? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                TABLE, ALL_COLUMNS,
                "$COL_NAME=? AND $COL_RECORD_NUMBER=?",
                arrayOf(name, recordNumber.toString()),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toTaxpayer() else null
            }
        }

    suspend fun getStatsAsync(): TaxpayerStats = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN $COL_TYPE='${Taxpayer.TYPE_OLD}' THEN 1 ELSE 0 END) AS old_count,
                SUM(CASE WHEN $COL_TYPE='${Taxpayer.TYPE_NEW}' THEN 1 ELSE 0 END) AS new_count,
                SUM(CASE WHEN $COL_LATITUDE IS NOT NULL THEN 1 ELSE 0 END) AS with_location,
                SUM($COL_TAX_AMOUNT) AS total_tax,
                SUM($COL_NET_PROFIT) AS total_profit
            FROM $TABLE
            """.trimIndent(), null
        )
        cursor.use {
            if (it.moveToFirst()) {
                TaxpayerStats(
                    total        = it.getInt(it.getColumnIndexOrThrow("total")),
                    oldCount     = it.getInt(it.getColumnIndexOrThrow("old_count")),
                    newCount     = it.getInt(it.getColumnIndexOrThrow("new_count")),
                    withLocation = it.getInt(it.getColumnIndexOrThrow("with_location")),
                    totalTax     = it.getLong(it.getColumnIndexOrThrow("total_tax")),
                    totalProfit  = it.getLong(it.getColumnIndexOrThrow("total_profit"))
                )
            } else TaxpayerStats()
        }
    }

    suspend fun getCountAsync(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    suspend fun deleteAllAsync(): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, null, null)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── عمليات CRUD للمعالم المرجعية ────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun insertLandmarkAsync(landmark: Landmark): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE_LANDMARKS, null, landmark.toContentValues())
    }

    suspend fun updateLandmarkAsync(landmark: Landmark): Int = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_LANDMARKS, landmark.toContentValues(),
            "$COL_LM_ID=?", arrayOf(landmark.id.toString())
        )
    }

    suspend fun deleteLandmarkAsync(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_LANDMARKS, "$COL_LM_ID=?", arrayOf(id.toString()))
    }

    suspend fun getAllLandmarksAsync(): List<Landmark> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            null, null, null, null, "$COL_LM_IS_MAIN DESC, $COL_LM_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLandmark()) }
        }
    }

    suspend fun getLandmarksByTypeAsync(type: String): List<Landmark> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            "$COL_LM_TYPE=?", arrayOf(type),
            null, null, "$COL_LM_IS_MAIN DESC, $COL_LM_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLandmark()) }
        }
    }

    suspend fun getMainLandmarksAsync(): List<Landmark> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            "$COL_LM_IS_MAIN=1", null,
            null, null, "$COL_LM_NAME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toLandmark()) }
        }
    }

    suspend fun getLandmarkByIdAsync(id: Long): Landmark? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_LANDMARKS, ALL_LANDMARK_COLUMNS,
            "$COL_LM_ID=?", arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toLandmark() else null
        }
    }

    suspend fun getLandmarkCountAsync(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_LANDMARKS", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── عمليات CRUD للجولات والمسارات ───────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun insertTourAsync(tour: Tour): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE_TOURS, null, tour.toContentValues())
    }

    suspend fun updateTourAsync(tour: Tour): Int = withContext(Dispatchers.IO) {
        writableDatabase.update(
            TABLE_TOURS, tour.toContentValues(),
            "$COL_TOUR_ID=?", arrayOf(tour.id.toString())
        )
    }

    suspend fun endTourAsync(tourId: Long, endTime: Long, distance: Float, points: Int, taxpayers: Int): Int =
        withContext(Dispatchers.IO) {
            val cv = android.content.ContentValues().apply {
                put(COL_TOUR_ENDED, endTime)
                put(COL_TOUR_DISTANCE, distance)
                put(COL_TOUR_POINTS, points)
                put(COL_TOUR_TAXPAYERS, taxpayers)
            }
            writableDatabase.update(
                TABLE_TOURS, cv, "$COL_TOUR_ID=?", arrayOf(tourId.toString())
            )
        }

    suspend fun deleteTourAsync(tourId: Long): Int = withContext(Dispatchers.IO) {
        // حذف نقاط المسار المرتبطة أولاً
        writableDatabase.delete(TABLE_TRACK_POINTS, "$COL_TP_TOUR=?", arrayOf(tourId.toString()))
        writableDatabase.delete(TABLE_TOURS, "$COL_TOUR_ID=?", arrayOf(tourId.toString()))
    }

    suspend fun getActiveTourAsync(): Tour? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_TOURS, ALL_TOUR_COLUMNS,
            "$COL_TOUR_ENDED IS NULL", null,
            null, null, "$COL_TOUR_STARTED DESC", "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTour() else null
        }
    }

    suspend fun getAllToursAsync(): List<Tour> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_TOURS, ALL_TOUR_COLUMNS,
            null, null, null, null,
            "$COL_TOUR_STARTED DESC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTour()) }
        }
    }

    suspend fun getTourByIdAsync(tourId: Long): Tour? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_TOURS, ALL_TOUR_COLUMNS,
            "$COL_TOUR_ID=?", arrayOf(tourId.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTour() else null
        }
    }

    // ── نقاط المسار ──────────────────────────────────────────────────────────

    suspend fun insertTrackPointAsync(point: TrackPoint): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE_TRACK_POINTS, null, point.toContentValues())
    }

    suspend fun insertTrackPointsBatchAsync(points: List<TrackPoint>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in points) {
                db.insert(TABLE_TRACK_POINTS, null, p.toContentValues())
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        count
    }

    suspend fun getTrackPointsForTourAsync(tourId: Long): List<TrackPoint> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_TRACK_POINTS, ALL_TRACK_POINT_COLUMNS,
            "$COL_TP_TOUR=?", arrayOf(tourId.toString()),
            null, null, "$COL_TP_TIME ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTrackPoint()) }
        }
    }

    suspend fun getAllTrackPointsAsync(limit: Int = 50000): List<TrackPoint> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_TRACK_POINTS, ALL_TRACK_POINT_COLUMNS,
            null, null, null, null, "$COL_TP_TIME ASC", limit.toString()
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTrackPoint()) }
        }
    }

    // ── Street Segments (للـ heatmap) ────────────────────────────────────────

    suspend fun findNearbySegmentAsync(
        lat: Double, lon: Double, radiusDegrees: Double = 0.0001  // ~10 متر
    ): StreetSegment? = withContext(Dispatchers.IO) {
        // البحث في صندوق محيط (bounding box) بدلاً من حساب المسافة الفعلية - أسرع
        val minLat = lat - radiusDegrees
        val maxLat = lat + radiusDegrees
        val minLon = lon - radiusDegrees
        val maxLon = lon + radiusDegrees

        readableDatabase.query(
            TABLE_STREET_SEGMENTS, ALL_SEGMENT_COLUMNS,
            "$COL_SEG_LAT BETWEEN ? AND ? AND $COL_SEG_LON BETWEEN ? AND ?",
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toSegment() else null
        }
    }

    suspend fun insertSegmentAsync(segment: StreetSegment): Long = withContext(Dispatchers.IO) {
        writableDatabase.insert(TABLE_STREET_SEGMENTS, null, segment.toContentValues())
    }

    suspend fun incrementSegmentVisitAsync(segmentId: Long, currentTime: Long): Int =
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL(
                "UPDATE $TABLE_STREET_SEGMENTS SET " +
                "$COL_SEG_VISITS = $COL_SEG_VISITS + 1, " +
                "$COL_SEG_LAST = ? " +
                "WHERE $COL_SEG_ID = ?",
                arrayOf(currentTime, segmentId)
            )
            1
        }

    suspend fun getAllSegmentsAsync(): List<StreetSegment> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_STREET_SEGMENTS, ALL_SEGMENT_COLUMNS,
            null, null, null, null, "$COL_SEG_VISITS DESC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toSegment()) }
        }
    }

    suspend fun getSegmentCountAsync(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_STREET_SEGMENTS", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // ─── تحويل ContentValues / Cursor (مكلفين) ───────────────────────────────

    private fun Taxpayer.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_RECORD_NUMBER,  recordNumber)
        put(COL_NAME,           name)
        put(COL_MOTHER_NAME,    motherName)
        put(COL_TAX_NUMBER,     taxNumber)
        put(COL_ID_NUMBER,      idNumber)
        put(COL_PHONE,          phone)
        put(COL_ADDRESS,        address)
        put(COL_ACTIVITY_TYPE,  activityType)
        put(COL_NOTES,          notes)
        put(COL_TYPE,           type)
        put(COL_STATUS,         status)
        put(COL_ACCESS_NO,      accessDecisionNo)
        put(COL_DECISION_DATE,  decisionDate)
        put(COL_TAX_AMOUNT,     taxAmount)
        put(COL_WORK_NUMBER,    workNumber)
        put(COL_NET_PROFIT,     netProfit)
        put(COL_NEIGHBOR_RIGHT, neighborRight)
        put(COL_NEIGHBOR_LEFT,  neighborLeft)
        put(COL_SHOP_DESC,      shopDescription)
        put(COL_PROPERTY_NUMBER, propertyNumber)
        put(COL_PHOTOS,         photos)
        put(COL_LATITUDE,       latitude)
        put(COL_LONGITUDE,      longitude)
        put(COL_ACCURACY,       accuracy)
        put(COL_CAPTURED_AT,    capturedAt)
        put(COL_CREATED_AT,     createdAt)
        put(COL_SYNC_STATUS,    syncStatus)
        put(COL_DRIVE_ID,       googleDriveId)
    }

    private fun Cursor.toTaxpayer(): Taxpayer {
        fun str(col: String): String {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getString(idx) ?: "" else ""
        }
        fun lng(col: String): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else 0L
        }
        fun int(col: String): Int {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
        }
        fun dbl(col: String): Double? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getDouble(idx) else null
        }
        fun flt(col: String): Float? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else null
        }
        fun lngNull(col: String): Long? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
        }

        return Taxpayer(
            id              = lng(COL_ID),
            recordNumber    = int(COL_RECORD_NUMBER),
            name            = str(COL_NAME),
            motherName      = str(COL_MOTHER_NAME),
            taxNumber       = str(COL_TAX_NUMBER),
            idNumber        = str(COL_ID_NUMBER),
            phone           = str(COL_PHONE),
            address         = str(COL_ADDRESS),
            activityType    = str(COL_ACTIVITY_TYPE),
            notes           = str(COL_NOTES),
            type            = str(COL_TYPE).ifBlank { Taxpayer.TYPE_OLD },
            status          = str(COL_STATUS).ifBlank { Taxpayer.STATUS_ACTIVE },
            accessDecisionNo = str(COL_ACCESS_NO),
            decisionDate    = str(COL_DECISION_DATE),
            taxAmount       = lng(COL_TAX_AMOUNT),
            workNumber      = str(COL_WORK_NUMBER),
            netProfit       = lng(COL_NET_PROFIT),
            neighborRight   = str(COL_NEIGHBOR_RIGHT),
            neighborLeft    = str(COL_NEIGHBOR_LEFT),
            shopDescription = str(COL_SHOP_DESC),
            propertyNumber  = str(COL_PROPERTY_NUMBER),
            photos          = str(COL_PHOTOS),
            latitude        = dbl(COL_LATITUDE),
            longitude       = dbl(COL_LONGITUDE),
            accuracy        = flt(COL_ACCURACY),
            capturedAt      = lngNull(COL_CAPTURED_AT),
            createdAt       = lng(COL_CREATED_AT),
            syncStatus      = int(COL_SYNC_STATUS),
            googleDriveId   = str(COL_DRIVE_ID)
        )
    }

    // ─── تحويل ContentValues / Cursor (معالم) ────────────────────────────────

    private fun Landmark.toContentValues(): ContentValues = ContentValues().apply {
        put(COL_LM_NAME,        name)
        put(COL_LM_TYPE,        type)
        put(COL_LM_DESCRIPTION, description)
        put(COL_LM_AREA,        area)
        put(COL_LM_LATITUDE,    latitude)
        put(COL_LM_LONGITUDE,   longitude)
        put(COL_LM_ACCURACY,    accuracy)
        put(COL_LM_IS_MAIN,     if (isMainReference) 1 else 0)
        put(COL_LM_CREATED_AT,  createdAt)
    }

    private fun Cursor.toLandmark(): Landmark {
        fun str(col: String): String {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getString(idx) ?: "" else ""
        }
        fun lng(col: String): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else 0L
        }
        fun int(col: String): Int {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
        }
        fun dbl(col: String): Double {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getDouble(idx) else 0.0
        }
        fun flt(col: String): Float? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else null
        }

        return Landmark(
            id              = lng(COL_LM_ID),
            name            = str(COL_LM_NAME),
            type            = str(COL_LM_TYPE).ifBlank { Landmark.TYPE_OTHER },
            description     = str(COL_LM_DESCRIPTION),
            area            = str(COL_LM_AREA),
            latitude        = dbl(COL_LM_LATITUDE),
            longitude       = dbl(COL_LM_LONGITUDE),
            accuracy        = flt(COL_LM_ACCURACY),
            isMainReference = int(COL_LM_IS_MAIN) == 1,
            createdAt       = lng(COL_LM_CREATED_AT)
        )
    }

    // ─── تحويل ContentValues / Cursor (الجولات والمسارات) ────────────────────

    private fun Tour.toContentValues(): ContentValues = ContentValues().apply {
        if (id > 0) put(COL_TOUR_ID, id)
        put(COL_TOUR_NAME, name)
        put(COL_TOUR_STARTED, startedAt)
        endedAt?.let { put(COL_TOUR_ENDED, it) } ?: putNull(COL_TOUR_ENDED)
        put(COL_TOUR_POINTS, pointCount)
        put(COL_TOUR_TAXPAYERS, taxpayerCount)
        put(COL_TOUR_DISTANCE, distanceMeters)
        put(COL_TOUR_NOTES, notes)
        put(COL_TOUR_CREATED, createdAt)
    }

    private fun Cursor.toTour(): Tour {
        fun str(col: String): String {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getString(idx) ?: "" else ""
        }
        fun lng(col: String): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else 0L
        }
        fun int(col: String): Int {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
        }
        fun flt(col: String): Float {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else 0f
        }
        fun lngNull(col: String): Long? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
        }

        return Tour(
            id             = lng(COL_TOUR_ID),
            name           = str(COL_TOUR_NAME),
            startedAt      = lng(COL_TOUR_STARTED),
            endedAt        = lngNull(COL_TOUR_ENDED),
            pointCount     = int(COL_TOUR_POINTS),
            taxpayerCount  = int(COL_TOUR_TAXPAYERS),
            distanceMeters = flt(COL_TOUR_DISTANCE),
            notes          = str(COL_TOUR_NOTES),
            createdAt      = lng(COL_TOUR_CREATED)
        )
    }

    private fun TrackPoint.toContentValues(): ContentValues = ContentValues().apply {
        if (id > 0) put(COL_TP_ID, id)
        put(COL_TP_TOUR, tourId)
        put(COL_TP_LAT, latitude)
        put(COL_TP_LON, longitude)
        put(COL_TP_ACC, accuracy)
        put(COL_TP_TIME, timestamp)
        put(COL_TP_TYPE, type)
        taxpayerId?.let { put(COL_TP_TAXPAYER, it) } ?: putNull(COL_TP_TAXPAYER)
        streetSegmentId?.let { put(COL_TP_SEGMENT, it) } ?: putNull(COL_TP_SEGMENT)
        put(COL_TP_ACCURATE, if (isAccurate) 1 else 0)
    }

    private fun Cursor.toTrackPoint(): TrackPoint {
        fun str(col: String): String {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getString(idx) ?: "" else ""
        }
        fun lng(col: String): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else 0L
        }
        fun int(col: String): Int {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
        }
        fun dbl(col: String): Double {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getDouble(idx) else 0.0
        }
        fun flt(col: String): Float {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else 0f
        }
        fun lngNull(col: String): Long? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
        }

        return TrackPoint(
            id              = lng(COL_TP_ID),
            tourId          = lng(COL_TP_TOUR),
            latitude        = dbl(COL_TP_LAT),
            longitude       = dbl(COL_TP_LON),
            accuracy        = flt(COL_TP_ACC),
            timestamp       = lng(COL_TP_TIME),
            type            = str(COL_TP_TYPE).ifBlank { TrackPoint.TYPE_WALKING },
            taxpayerId      = lngNull(COL_TP_TAXPAYER),
            streetSegmentId = lngNull(COL_TP_SEGMENT),
            isAccurate      = int(COL_TP_ACCURATE) == 1
        )
    }

    private fun StreetSegment.toContentValues(): ContentValues = ContentValues().apply {
        if (id > 0) put(COL_SEG_ID, id)
        put(COL_SEG_LAT, centerLat)
        put(COL_SEG_LON, centerLon)
        put(COL_SEG_VISITS, visitCount)
        put(COL_SEG_FIRST, firstVisitAt)
        put(COL_SEG_LAST, lastVisitAt)
        put(COL_SEG_AVG_ACC, averageAccuracy)
    }

    private fun Cursor.toSegment(): StreetSegment {
        fun lng(col: String): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getLong(idx) else 0L
        }
        fun int(col: String): Int {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
        }
        fun dbl(col: String): Double {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getDouble(idx) else 0.0
        }
        fun flt(col: String): Float {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getFloat(idx) else 0f
        }

        return StreetSegment(
            id              = lng(COL_SEG_ID),
            centerLat       = dbl(COL_SEG_LAT),
            centerLon       = dbl(COL_SEG_LON),
            visitCount      = int(COL_SEG_VISITS),
            firstVisitAt    = lng(COL_SEG_FIRST),
            lastVisitAt     = lng(COL_SEG_LAST),
            averageAccuracy = flt(COL_SEG_AVG_ACC)
        )
    }
}

/** نموذج إحصائيات */
data class TaxpayerStats(
    val total: Int        = 0,
    val oldCount: Int     = 0,
    val newCount: Int     = 0,
    val withLocation: Int = 0,
    val totalTax: Long    = 0,
    val totalProfit: Long = 0
)
