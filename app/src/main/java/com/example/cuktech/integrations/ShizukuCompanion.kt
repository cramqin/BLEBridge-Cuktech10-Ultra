package com.example.cuktech.integrations

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class ShizukuStatus(
    val isInstalled: Boolean = false,
    val providerPackage: String = "Not detected",
    val batteryWhitelisted: Boolean = false,
    val lastDiagnosticOutput: String = ""
)

/**
 * Optional Shizuku / Shevery companion integration for Xiaomi HyperOS / Android 17.
 * Never required for BLE scanning, GATT connection, authentication, or telemetry.
 * Provides optional ADB-level background keepalive optimization and Bluetooth diagnostics.
 */
class ShizukuCompanion(private val context: Context) {

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        val pm = context.packageManager
        val candidates = listOf(
            "moe.shizuku.privileged.api" to "Shizuku",
            "com.shizuku.privileged.api" to "Shizuku / Shevery",
            "rikka.shizuku" to "Shizuku"
        )
        var foundPkg: String? = null
        for ((pkg, label) in candidates) {
            val installed = runCatching {
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (installed) {
                foundPkg = "$label ($pkg)"
                break
            }
        }
        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val ignoringBattery = powerMgr?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        _status.value = _status.value.copy(
            isInstalled = foundPkg != null,
            providerPackage = foundPkg ?: "Not detected (optional)",
            batteryWhitelisted = ignoringBattery
        )
    }

    fun clearDiagnosticOutput() {
        _status.value = _status.value.copy(lastDiagnosticOutput = "")
    }

    fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    /**
     * Applies background keepalive & Doze whitelist rules via Shizuku/rish if available.
     */
    suspend fun applyHyperOsKeepaliveOptimization(): String = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val cmd = "dumpsys deviceidle whitelist +$pkg; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; cmd appops set $pkg WAKE_LOCK allow"
        val res = runElevatedOrLocalCommand(cmd)
        refreshStatus()
        val raw = res.getOrNull() ?: ""
        val msg = if (res.isSuccess && !raw.contains("Permission Denial") && !raw.contains("SecurityException")) {
            "Keepalive & Doze whitelist applied successfully."
        } else {
            "Shizuku ADB service is not active. Please use 'System Battery Settings' to whitelist the app from battery optimizations."
        }
        _status.value = _status.value.copy(lastDiagnosticOutput = msg)
        msg
    }

    /**
     * Collects Android Bluetooth GATT & HCI stack summary for troubleshooting.
     */
    suspend fun runBluetoothStackDiagnostics(): String = withContext(Dispatchers.IO) {
        val cmd = "dumpsys bluetooth_manager | grep -E 'enabled|ConnectionState|AdapterProperties|BondState|GattService' | head -n 25"
        val res = runElevatedOrLocalCommand(cmd)
        val raw = res.getOrNull() ?: ""
        val out = if (res.isSuccess && !raw.contains("Permission Denial") && !raw.contains("SecurityException")) {
            raw.ifBlank { "Bluetooth stack status normal (Native Android BLE API is ready)" }
        } else {
            "Shizuku ADB permission required to read full bluetooth dumpsys."
        }
        _status.value = _status.value.copy(lastDiagnosticOutput = out)
        out
    }

    private fun runElevatedOrLocalCommand(shellCmd: String): Result<String> {
        return runCatching {
            val rishBin = "/data/user/0/com.agy/files/usr/bin/rish"
            val pb = if (java.io.File(rishBin).exists()) {
                ProcessBuilder("sh", rishBin, "-c", shellCmd)
            } else {
                ProcessBuilder("sh", "-c", shellCmd)
            }
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }.trim()
            val exitCode = proc.waitFor()
            if (exitCode != 0 || output.contains("Permission Denial") || output.contains("SecurityException")) {
                throw SecurityException(output.ifBlank { "Exit code $exitCode" })
            }
            output
        }
    }
}
