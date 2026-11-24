package com.wifitracker.app.networkusage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri

/** Read-only API. All SQL structure is fixed/allowlisted; filter values are bound arguments. */
class WifiNetworkUsageProvider : ContentProvider() {
    private lateinit var database: WifiNetworkUsageDatabase

    override fun onCreate(): Boolean {
        val context = context ?: return false
        database = WifiNetworkUsageDatabase(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        requireNotNull(context) { "Provider is not attached" }
        require(selection == null && selectionArgs == null) {
            "SQL selection is not supported; use documented URI query parameters"
        }
        require(sortOrder == null) { "Custom sorting is not supported" }

        val isNetworks = matcher.match(uri) == MATCH_NETWORKS
        val isDaily = matcher.match(uri) == MATCH_DAILY
        require(isNetworks || isDaily) { "Unsupported content URI: $uri" }

        val allowed = if (isNetworks) NETWORK_COLUMNS else DAILY_COLUMNS
        val requested = projection?.toList() ?: allowed
        require(requested.isNotEmpty() && requested.all { it in allowed }) {
            "Unsupported projection"
        }
        if (isNetworks) {
            require(uri.queryParameterNames.isEmpty()) { "Networks URI does not accept query parameters" }
        } else {
            require(uri.queryParameterNames.all { it in DAILY_QUERY_PARAMETERS }) {
                "Unsupported daily query parameter"
            }
        }

        val sqlProjection = requested.joinToString(", ") {
            if (isNetworks) networkColumnSql.getValue(it) else dailyColumnSql.getValue(it)
        }
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (isDaily) {
            uri.getQueryParameter(WifiNetworkUsageStore.COLUMN_SSID)?.let {
                require(it.isNotBlank()) { "SSID filter must not be blank" }
                conditions += "ssid = ?"
                args += it
            }
            uri.getQueryParameter("fromDay")?.let {
                require(isIsoDate(it)) { "fromDay must use yyyy-MM-dd" }
                conditions += "day >= ?"
                args += it
            }
            uri.getQueryParameter("throughDay")?.let {
                require(isIsoDate(it)) { "throughDay must use yyyy-MM-dd" }
                conditions += "day <= ?"
                args += it
            }
        }
        val table = WifiNetworkUsageDatabase.TABLE_DAILY
        val sql = buildString {
            if (isNetworks) {
                append("SELECT $sqlProjection FROM $table GROUP BY ssid ")
                append("ORDER BY SUM(rx_bytes + tx_bytes) DESC, ssid ASC")
            } else {
                append("SELECT $sqlProjection FROM $table")
                if (conditions.isNotEmpty()) append(" WHERE ${conditions.joinToString(" AND ")}")
                append(" ORDER BY day DESC, ssid ASC")
            }
        }
        val cursor = database.readableDatabase.rawQuery(sql, args.toTypedArray())
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        MATCH_NETWORKS -> "vnd.android.cursor.dir/vnd.com.wifitracker.network-usage"
        MATCH_DAILY -> "vnd.android.cursor.dir/vnd.com.wifitracker.daily-network-usage"
        else -> throw IllegalArgumentException("Unsupported content URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Wi-Fi usage provider is read-only")

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int =
        throw UnsupportedOperationException("Wi-Fi usage provider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Wi-Fi usage provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Wi-Fi usage provider is read-only")

    private fun isIsoDate(value: String): Boolean =
        value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))

    private companion object {
        const val MATCH_NETWORKS = 1
        const val MATCH_DAILY = 2
        val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(WifiNetworkUsageStore.AUTHORITY, WifiNetworkUsageStore.PATH_NETWORKS, MATCH_NETWORKS)
            addURI(WifiNetworkUsageStore.AUTHORITY, WifiNetworkUsageStore.PATH_DAILY, MATCH_DAILY)
        }
        val DAILY_QUERY_PARAMETERS = setOf(WifiNetworkUsageStore.COLUMN_SSID, "fromDay", "throughDay")
        val NETWORK_COLUMNS = setOf(
            WifiNetworkUsageStore.COLUMN_SSID,
            WifiNetworkUsageStore.COLUMN_RX_BYTES,
            WifiNetworkUsageStore.COLUMN_TX_BYTES,
            WifiNetworkUsageStore.COLUMN_TOTAL_BYTES
        )
        val DAILY_COLUMNS = NETWORK_COLUMNS + WifiNetworkUsageStore.COLUMN_DAY
        val networkColumnSql = mapOf(
            WifiNetworkUsageStore.COLUMN_SSID to "ssid AS ssid",
            WifiNetworkUsageStore.COLUMN_RX_BYTES to "SUM(rx_bytes) AS rx_bytes",
            WifiNetworkUsageStore.COLUMN_TX_BYTES to "SUM(tx_bytes) AS tx_bytes",
            WifiNetworkUsageStore.COLUMN_TOTAL_BYTES to "SUM(rx_bytes + tx_bytes) AS total_bytes"
        )
        val dailyColumnSql = mapOf(
            WifiNetworkUsageStore.COLUMN_SSID to "ssid AS ssid",
            WifiNetworkUsageStore.COLUMN_DAY to "day AS day",
            WifiNetworkUsageStore.COLUMN_RX_BYTES to "rx_bytes AS rx_bytes",
            WifiNetworkUsageStore.COLUMN_TX_BYTES to "tx_bytes AS tx_bytes",
            WifiNetworkUsageStore.COLUMN_TOTAL_BYTES to "(rx_bytes + tx_bytes) AS total_bytes"
        )
    }
}
