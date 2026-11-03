package com.example.cuktech.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.PortTelemetry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PowerChartPoint(
    val timestampMs: Long,
    val totalWatts: Double,
    val portWatts: Map<Int, Double>
)

/**
 * Native SQLite persistence for CUKTECH 10 Ultra telemetry history, charging sessions,
 * per-port Wh charge limits, and CSV export (`cuktech-ble-server/src/cuktech_ble/history.py`).
 */
class CuktechPersistence(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS port_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms INTEGER NOT NULL,
                port_id INTEGER NOT NULL,
                voltage_v REAL NOT NULL,
                current_a REAL NOT NULL,
                power_w REAL NOT NULL,
                protocol_name TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_port_history_ts ON port_history(ts_ms)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS charge_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                port_id INTEGER NOT NULL,
                port_label TEXT NOT NULL,
                start_ts REAL NOT NULL,
                end_ts REAL,
                wh_accumulated REAL NOT NULL,
                peak_watts REAL NOT NULL,
                avg_watts REAL NOT NULL,
                protocol_used TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS port_charge_limits (
                port_id INTEGER PRIMARY KEY,
                wh_target REAL NOT NULL,
                mode TEXT NOT NULL,
                baseline_wh REAL NOT NULL,
                enabled INTEGER NOT NULL,
                triggered INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    fun recordPortSnapshot(ports: Collection<PortTelemetry>, timestampMs: Long = System.currentTimeMillis()) {
        runCatching {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (p in ports) {
                    val cv = ContentValues().apply {
                        put("ts_ms", timestampMs)
                        put("port_id", p.portId)
                        put("voltage_v", p.voltageV)
                        put("current_a", p.currentA)
                        put("power_w", p.powerW)
                        put("protocol_name", p.protocolName)
                    }
                    db.insert("port_history", null, cv)
                }
                // Prune records older than 7 days periodically
                val cutoff = timestampMs - 7L * 24L * 3600L * 1000L
                db.delete("port_history", "ts_ms < ?", arrayOf(cutoff.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun insertCompletedSession(session: ChargeSessionRecord): Long {
        return runCatching {
            val cv = ContentValues().apply {
                put("port_id", session.portId)
                put("port_label", session.portLabel)
                put("start_ts", session.startTimeSec)
                put("end_ts", session.endTimeSec ?: (System.currentTimeMillis() / 1000.0))
                put("wh_accumulated", session.whAccumulated)
                put("peak_watts", session.peakWatts)
                put("avg_watts", session.avgWatts)
                put("protocol_used", session.protocolUsed)
            }
            writableDatabase.insert("charge_sessions", null, cv)
        }.getOrDefault(-1L)
    }

    fun getRecentSessions(limit: Int = 50): List<ChargeSessionRecord> {
        val result = mutableListOf<ChargeSessionRecord>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT id, port_id, port_label, start_ts, end_ts, wh_accumulated, peak_watts, avg_watts, protocol_used FROM charge_sessions ORDER BY start_ts DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        ChargeSessionRecord(
                            id = cursor.getLong(0),
                            portId = cursor.getInt(1),
                            portLabel = cursor.getString(2),
                            startTimeSec = cursor.getDouble(3),
                            endTimeSec = if (cursor.isNull(4)) null else cursor.getDouble(4),
                            whAccumulated = cursor.getDouble(5),
                            peakWatts = cursor.getDouble(6),
                            avgWatts = cursor.getDouble(7),
                            protocolUsed = cursor.getString(8),
                            isActive = false
                        )
                    )
                }
            }
        }
        return result
    }

    fun clearSessions() {
        runCatching {
            writableDatabase.delete("charge_sessions", null, null)
            writableDatabase.delete("port_history", null, null)
        }
    }

    fun savePortChargeLimit(limit: PortChargeLimit) {
        runCatching {
            val cv = ContentValues().apply {
                put("port_id", limit.portId)
                put("wh_target", limit.whTarget)
                put("mode", limit.mode)
                put("baseline_wh", limit.baselineWh)
                put("enabled", if (limit.enabled) 1 else 0)
                put("triggered", if (limit.triggered) 1 else 0)
            }
            writableDatabase.insertWithOnConflict(
                "port_charge_limits",
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    fun removePortChargeLimit(portId: Int) {
        runCatching {
            writableDatabase.delete("port_charge_limits", "port_id = ?", arrayOf(portId.toString()))
        }
    }

    fun loadPortChargeLimits(): Map<Int, PortChargeLimit> {
        val map = mutableMapOf<Int, PortChargeLimit>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT port_id, wh_target, mode, baseline_wh, enabled, triggered FROM port_charge_limits",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val pid = cursor.getInt(0)
                    map[pid] = PortChargeLimit(
                        portId = pid,
                        whTarget = cursor.getDouble(1),
                        mode = cursor.getString(2),
                        baselineWh = cursor.getDouble(3),
                        enabled = cursor.getInt(4) == 1,
                        triggered = cursor.getInt(5) == 1
                    )
                }
            }
        }
        return map
    }

    /**
     * Exports charging sessions and recent port history to a CSV file in the public
     * Downloads/cuktech10/history folder and returns the exported `File`.
     */
    fun exportSessionsAndHistoryCsv(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "cuktech10_telemetry_$stamp.csv"

        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "cuktech10/history")
        var outFile = try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            File(targetDir, fileName)
        } catch (_: Exception) {
            val appDir = context.getExternalFilesDir("cuktech10/history") ?: context.cacheDir
            File(appDir, fileName)
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("# CUKTECH 10 Ultra Charging Sessions")
        sb.appendLine("session_id,port,start_time,end_time,duration_sec,energy_wh,peak_watts,avg_watts,protocol")
        val sessions = getRecentSessions(200)
        for (s in sessions) {
            val startStr = fmt.format(Date((s.startTimeSec * 1000).toLong()))
            val endStr = s.endTimeSec?.let { fmt.format(Date((it * 1000).toLong())) } ?: "active"
            sb.appendLine(
                "${s.id},${s.portLabel},$startStr,$endStr,${s.durationSeconds},${"%.3f".format(s.whAccumulated)},${"%.2f".format(s.peakWatts)},${"%.2f".format(s.avgWatts)},${s.protocolUsed}"
            )
        }

        sb.appendLine()
        sb.appendLine("# Recent Port Telemetry History (Last 500 Samples)")
        sb.appendLine("timestamp,port_id,voltage_v,current_a,power_w,protocol")
        runCatching {
            readableDatabase.rawQuery(
                "SELECT ts_ms, port_id, voltage_v, current_a, power_w, protocol_name FROM port_history ORDER BY ts_ms DESC LIMIT 500",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val tStr = fmt.format(Date(c.getLong(0)))
                    sb.appendLine(
                        "$tStr,${c.getInt(1)},${"%.2f".format(c.getDouble(2))},${"%.2f".format(c.getDouble(3))},${"%.2f".format(c.getDouble(4))},${c.getString(5)}"
                    )
                }
            }
        }

        val content = sb.toString()
        try {
            outFile.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            // Android 10+ MediaStore fallback
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/cuktech10/history")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                }
            } else {
                val fallbackDir = context.getExternalFilesDir(null) ?: context.cacheDir
                outFile = File(fallbackDir, fileName)
                outFile.writeText(content, Charsets.UTF_8)
            }
        }
        return outFile
    }

    companion object {
        private const val DB_NAME = "cuktech10_history.db"
        private const val DB_VERSION = 1
    }
}
