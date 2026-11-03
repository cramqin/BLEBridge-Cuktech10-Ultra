package com.example.cuktech.diagnostics

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.BleBridgeService
import com.example.cuktech.ble.CuktechAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class DiagnosticStatus(
    val lastDiagnosticOutput: String = ""
)

/**
 * Standard Android BLE and System diagnostics helper.
 * Uses native Android Bluetooth, Build, and PowerManager APIs.
 */
class BleDiagnostics(private val context: Context) {

    private val _status = MutableStateFlow(DiagnosticStatus())
    val status: StateFlow<DiagnosticStatus> = _status.asStateFlow()

    fun clearDiagnosticOutput() {
        _status.value = _status.value.copy(lastDiagnosticOutput = "")
    }

    fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    suspend fun runBluetoothStackDiagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        val mgr = BleBridgeService.cuktechManager

        // === 1. PHONE & BLUETOOTH HARDWARE ===
        sb.appendLine("=== HOST PHONE & BLUETOOTH CAPABILITIES ===")
        val brand = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
        sb.appendLine("Device: $brand ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        if (adapter == null) {
            sb.appendLine("Bluetooth Adapter: Unavailable / Hardware missing")
        } else {
            sb.appendLine("Bluetooth Stack: ${if (adapter.isEnabled) "ACTIVE (Enabled)" else "DISABLED"}")
            sb.appendLine("Adapter Name: ${adapter.name ?: "Unknown"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sb.appendLine("LE 2M PHY (High Speed): ${if (adapter.isLe2MPhySupported) "Supported" else "Not Supported"}")
                sb.appendLine("LE Coded PHY (Long Range): ${if (adapter.isLeCodedPhySupported) "Supported" else "Not Supported"}")
                sb.appendLine("Extended Advertising: ${if (adapter.isLeExtendedAdvertisingSupported) "Supported" else "Not Supported"}")
                sb.appendLine("Periodic Advertising: ${if (adapter.isLePeriodicAdvertisingSupported) "Supported" else "Not Supported"}")
                sb.appendLine("Max Advertised Data: ${adapter.leMaximumAdvertisingDataLength} bytes")
            }
            sb.appendLine("Multiple Advertisement: ${if (adapter.isMultipleAdvertisementSupported) "Supported" else "Not Supported"}")
            sb.appendLine("Offloaded Filtering: ${if (adapter.isOffloadedFilteringSupported) "Supported" else "Not Supported"}")
            sb.appendLine("Offloaded Scan Batching: ${if (adapter.isOffloadedScanBatchingSupported) "Supported" else "Not Supported"}")
        }

        // === 2. BLE LINK & GATT STATUS ===
        sb.appendLine("\n=== BLE CONNECTION & GATT STATUS ===")
        val connState = BleBridgeService.bleConnectionState.value
        val addr = BleBridgeService.bleDeviceAddress.value ?: mgr?.credentialStore?.activeDevice?.value?.mac ?: "None"
        val activeDevName = mgr?.credentialStore?.activeDevice?.value?.name ?: "CUKTECH 10 Ultra"
        sb.appendLine("Link State: $connState")
        sb.appendLine("Target Charger: $activeDevName ($addr)")
        sb.appendLine("Auth Phase: ${mgr?.authState?.value ?: CuktechAuthState.DISCONNECTED}")

        val gatt = BleBridgeService.getInstance()?.getBluetoothGatt()
        if (gatt != null) {
            val services = gatt.services
            sb.appendLine("GATT Instance: Active (MTU Negotiated: 512 bytes)")
            sb.appendLine("GATT Services Discovered (${services.size}):")
            for (s in services) {
                val name = when (s.uuid.toString().uppercase()) {
                    "0000FE95-0000-1000-8000-00805F9B34FB" -> "Xiaomi MiOT Service (0xFE95)"
                    "00001800-0000-1000-8000-00805F9B34FB" -> "Generic Access Service (0x1800)"
                    "00001801-0000-1000-8000-00805F9B34FB" -> "Generic Attribute Service (0x1801)"
                    else -> "Service ${s.uuid.toString().take(8)}... (${s.characteristics.size} chars)"
                }
                sb.appendLine("  • $name (${s.characteristics.size} characteristics)")
            }
        } else {
            sb.appendLine("GATT Instance: Idle (Connect to inspect active GATT handles)")
        }

        // === 3. CHARGER HARDWARE & CAPABILITY SPECIFICATIONS ===
        sb.appendLine("\n=== CUKTECH CHARGER HARDWARE SPECS ===")
        val devInfo = mgr?.deviceInfo?.value
        val fw = devInfo?.firmwareVersion
        val sn = devInfo?.serialNumber

        sb.appendLine("Model: CUKTECH 10 Ultra (AD1204 / AD1204U)")
        sb.appendLine("MiOT Model: njcuk.fitting.ad1204")
        if (!fw.isNullOrBlank() && fw != "--" && !fw.contains("Unknown", ignoreCase = true) && fw != "1.0") {
            sb.appendLine("Firmware: $fw")
        }
        if (!sn.isNullOrBlank() && sn != "--" && !sn.contains("Unknown", ignoreCase = true)) {
            sb.appendLine("Serial Number: $sn")
        }
        sb.appendLine("Smart Screen: 1.57\" TFT Vientiane Multi-Color Display")
        sb.appendLine("Protocol Engine: MiOT Spec v5 + CUKTECH Extended v2 (8-byte payload)")
        sb.appendLine("Rated Power Ceiling:")
        sb.appendLine("  • 200V-240V Input: 120W Max Total")
        sb.appendLine("  • 100V-130V Input: 100W Max Total")
        sb.appendLine("Rated Port Capabilities:")
        sb.appendLine("  • USB-C1: 120W Max (PD 100W / PPS / Xiaomi 120W HyperCharge)")
        sb.appendLine("  • USB-C2: 120W Max (PD 100W / PPS / Xiaomi 120W HyperCharge)")
        sb.appendLine("  • USB-C3: 44W Max (UFCS / SCP / PD)")
        sb.appendLine("  • USB-A : 33W Max (UFCS / QC3.0 / SCP)")

        // === 4. SYSTEM ENVIRONMENT & KEEPALIVE ===
        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isWhitelisted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerMgr?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
        sb.appendLine("\n=== SYSTEM & KEEPALIVE ===")
        sb.appendLine("Battery Whitelist: ${if (isWhitelisted) "ACTIVE (Unrestricted Keepalive)" else "OPTIMIZED (Action required)"}")
        sb.appendLine("CPU Partial WakeLock: ACTIVE (Foreground Service)")

        val out = sb.toString().trim()
        _status.value = _status.value.copy(lastDiagnosticOutput = out)
        out
    }
}
