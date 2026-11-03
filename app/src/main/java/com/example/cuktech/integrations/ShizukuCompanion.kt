package com.example.cuktech.integrations

import android.content.Context
import android.content.pm.PackageManager
import com.example.BleBridgeService
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
     * Collects Android Bluetooth GATT, adapter capabilities, connection state, and stack summary.
     */
    suspend fun runBluetoothStackDiagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = btManager?.adapter

        sb.appendLine("=== ANDROID BLUETOOTH STACK ===")
        if (adapter == null) {
            sb.appendLine("Bluetooth Adapter: Unavailable / Hardware missing")
        } else {
            sb.appendLine("State: ${if (adapter.isEnabled) "ENABLED (Active)" else "DISABLED"}")
            sb.appendLine("Adapter Name: ${adapter.name ?: "Unknown"}")
            sb.appendLine("Hardware Address: ${adapter.address}")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                sb.appendLine("LE 2M PHY: ${if (adapter.isLe2MPhySupported) "Supported" else "Not Supported"}")
                sb.appendLine("LE Coded PHY: ${if (adapter.isLeCodedPhySupported) "Supported" else "Not Supported"}")
                sb.appendLine("Extended Advertising: ${if (adapter.isLeExtendedAdvertisingSupported) "Supported (Max: ${adapter.maxAdvertisingDataLength}B)" else "Not Supported"}")
            }
        }

        sb.appendLine("\n=== BLE GATT & PERIPHERAL ===")
        val connState = BleBridgeService.bleConnectionState.value
        val addr = BleBridgeService.bleDeviceAddress.value ?: "None"
        sb.appendLine("Connection State: $connState")
        sb.appendLine("Target MAC: $addr")

        val gatt = BleBridgeService.getInstance()?.getBluetoothGatt()
        if (gatt != null) {
            val services = gatt.services
            sb.appendLine("GATT Services Discovered: ${services.size}")
            for (s in services) {
                val name = when (s.uuid.toString().uppercase()) {
                    "0000FE95-0000-1000-8000-00805F9B34FB" -> "Xiaomi Mi Service (MiOT FE95)"
                    "00001800-0000-1000-8000-00805F9B34FB" -> "Generic Access"
                    "00001801-0000-1000-8000-00805F9B34FB" -> "Generic Attribute"
                    else -> "GATT Service (${s.characteristics.size} chars)"
                }
                sb.appendLine("  • ${s.uuid}: $name")
            }
        } else {
            sb.appendLine("GATT Instance: Not currently connected")
        }

        val mgr = BleBridgeService.cuktechManager
        if (mgr != null) {
            sb.appendLine("\n=== CUKTECH MIOT PROTOCOL ===")
            sb.appendLine("Auth State: ${mgr.authState.value}")
            sb.appendLine("Total Power Output: ${mgr.totalPowerWatts.value}W")
            sb.appendLine("Device Info: ${mgr.deviceInfo.value.model} (FW: ${mgr.deviceInfo.value.firmwareVersion})")
            val p1 = mgr.ports.value[1]
            val p2 = mgr.ports.value[2]
            val p3 = mgr.ports.value[3]
            val p4 = mgr.ports.value[4]
            sb.appendLine("Live Ports: C1=${p1?.powerW ?: 0.0}W, C2=${p2?.powerW ?: 0.0}W, C3=${p3?.powerW ?: 0.0}W, A=${p4?.powerW ?: 0.0}W")
        }

        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isWhitelisted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerMgr?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
        sb.appendLine("\n=== POWER & DOZE STATUS ===")
        sb.appendLine("Battery Whitelist Exemption: ${if (isWhitelisted) "ACTIVE (Unrestricted Keepalive)" else "OPTIMIZED (May be throttled by OS)"}")

        // Check Shizuku/rish dump if available
        val cmd = "dumpsys bluetooth_manager | grep -E 'enabled|ConnectionState|AdapterProperties|BondState' | head -n 10"
        val res = runElevatedOrLocalCommand(cmd)
        val raw = res.getOrNull() ?: ""
        if (res.isSuccess && !raw.contains("Permission Denial") && !raw.contains("SecurityException") && raw.isNotBlank()) {
            sb.appendLine("\n=== SYSTEM BLUETOOTH DUMP (Shizuku) ===")
            sb.appendLine(raw)
        }

        val out = sb.toString().trim()
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
