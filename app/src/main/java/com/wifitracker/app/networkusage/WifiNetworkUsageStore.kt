package com.wifitracker.app.networkusage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Aggregate totals for one SSID across all locally recorded dates. */
data class WifiNetworkUsageTotal(
    val ssid: String,
    val rxBytes: Long,
    val txBytes: Long,
    val totalBytes: Long
)

/**
 * Public read API for the app UI. Call getTotals() from a background thread: it performs
 * synchronous SQLite I/O. Totals only include approximate deltas sampled while tracking
 * was explicitly active; there are no historical or retroactive usage records.
 */
class WifiNetworkUsageStore(context: Context) {
    private val appContext = context.applicationContext

    fun getTotals(): List<WifiNetworkUsageTotal> {
        val helper = WifiNetworkUsageDatabase(appContext)
        return try {
            val cursor = helper.readableDatabase.rawQuery(
                "SELECT ssid, SUM(rx_bytes) AS rx_bytes, SUM(tx_bytes) AS tx_bytes " +
                    "FROM ${WifiNetworkUsageDatabase.TABLE_DAILY} GROUP BY ssid " +
                    "ORDER BY SUM(rx_bytes + tx_bytes) DESC, ssid ASC",
                emptyArray()
            )
            try {
                buildList {
                    val ssidIndex = cursor.getColumnIndexOrThrow("ssid")
                    val rxIndex = cursor.getColumnIndexOrThrow("rx_bytes")
                    val txIndex = cursor.getColumnIndexOrThrow("tx_bytes")
                    while (cursor.moveToNext()) {
                        val rx = cursor.getLong(rxIndex)
                        val tx = cursor.getLong(txIndex)
                        add(WifiNetworkUsageTotal(cursor.getString(ssidIndex), rx, tx, rx + tx))
                    }
                }
            } finally {
                cursor.close()
            }
        } finally {
            helper.close()
        }
    }

    /** True means the user-started tracker was asked to remain active (including system restart). */
    fun isTracking(): Boolean = appContext
        .getSharedPreferences(WifiNetworkUsageService.PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(WifiNetworkUsageService.PREF_TRACKING, false)

    companion object {
        /** Stable external API contract; this provider is read-only and signature-protected. */
        const val AUTHORITY = "com.wifitracker.app.networkusage"
        const val CONTENT_URI = "content://$AUTHORITY/networks"
        const val DAILY_CONTENT_URI = "content://$AUTHORITY/daily"

        const val PATH_NETWORKS = "networks"
        const val PATH_DAILY = "daily"

        const val COLUMN_SSID = "ssid"
        const val COLUMN_DAY = "day"
        const val COLUMN_RX_BYTES = "rx_bytes"
        const val COLUMN_TX_BYTES = "tx_bytes"
        const val COLUMN_TOTAL_BYTES = "total_bytes"

        const val READ_PERMISSION = "com.wifitracker.app.permission.READ_WIFI_USAGE"
    }
}

/** Private DB implementation shared by the store, service and provider. */
internal class WifiNetworkUsageDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_DAILY (" +
                "ssid TEXT NOT NULL, " +
                "day TEXT NOT NULL, " +
                "rx_bytes INTEGER NOT NULL DEFAULT 0, " +
                "tx_bytes INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (ssid, day))"
        )
        db.execSQL("CREATE INDEX idx_wifi_usage_day ON $TABLE_DAILY(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve local aggregates. Add explicit migration steps here when the schema changes.
    }

    /** Add only sampled nonnegative deltas, atomically preserving the SSID/day aggregate. */
    fun addDelta(ssid: String, day: String, rxBytes: Long, txBytes: Long) {
        if (rxBytes < 0L || txBytes < 0L || (rxBytes == 0L && txBytes == 0L)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT rx_bytes, tx_bytes FROM $TABLE_DAILY WHERE ssid = ? AND day = ?",
                arrayOf(ssid, day)
            )
            val oldRx: Long
            val oldTx: Long
            try {
                if (existing.moveToFirst()) {
                    oldRx = existing.getLong(0)
                    oldTx = existing.getLong(1)
                } else {
                    oldRx = 0L
                    oldTx = 0L
                }
            } finally {
                existing.close()
            }

            val values = ContentValues().apply {
                put(COL_RX_BYTES, oldRx + rxBytes)
                put(COL_TX_BYTES, oldTx + txBytes)
            }
            val updated = db.update(
                TABLE_DAILY,
                values,
                "$COL_SSID = ? AND $COL_DAY = ?",
                arrayOf(ssid, day)
            )
            if (updated == 0) {
                values.put(COL_SSID, ssid)
                values.put(COL_DAY, day)
                db.insertOrThrow(TABLE_DAILY, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val TABLE_DAILY = "wifi_usage_daily"
        const val COL_SSID = "ssid"
        const val COL_DAY = "day"
        const val COL_RX_BYTES = "rx_bytes"
        const val COL_TX_BYTES = "tx_bytes"
        private const val DB_NAME = "wifi_network_usage.db"
        private const val DB_VERSION = 1
    }
}
