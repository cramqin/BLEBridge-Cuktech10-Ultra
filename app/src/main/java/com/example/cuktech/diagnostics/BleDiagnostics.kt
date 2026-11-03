package com.example.cuktech.diagnostics

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.BleBridgeService
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
 * Uses native Android Bluetooth and PowerManager APIs without third-party privileged dependencies.
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

        sb.appendLine("=== ANDROID BLUETOOTH STACK ===")
        if (adapter == null) {
            sb.appendLine("Bluetooth Adapter: Unavailable / Hardware missing")
        } else {
            sb.appendLine("State: ${if (adapter.isEnabled) "ENABLED (Active)" else "DISABLED"}")
            sb.appendLine("Adapter Name: ${adapter.name ?: "Unknown"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sb.appendLine("LE 2M PHY: ${if (adapter.isLe2MPhySupported) "Supported" else "Not Supported"}")
                sb.appendLine("LE Coded PHY: ${if (adapter.isLeCodedPhySupported) "Supported" else "Not Supported"}")
                sb.appendLine("Extended Advertising: ${if (adapter.isLeExtendedAdvertisingSupported) "Supported" else "Not Supported"}")
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

        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isWhitelisted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerMgr?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
        sb.appendLine("\n=== POWER & DOZE STATUS ===")
        sb.appendLine("Battery Whitelist Exemption: ${if (isWhitelisted) "ACTIVE (Unrestricted Keepalive)" else "OPTIMIZED (May be throttled by OS)"}")

        val out = sb.toString().trim()
        _status.value = _status.value.copy(lastDiagnosticOutput = out)
        out
    }
}
