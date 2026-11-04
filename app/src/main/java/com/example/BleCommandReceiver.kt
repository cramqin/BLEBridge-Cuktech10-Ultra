package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BroadcastReceiver enabling Tasker and ADB automation without Termux.
 *
 * Supported Actions:
 * 1. `com.cramqin.blecuktech10.CONNECT_CHARGER` (optional `--es mac "AA:BB:CC:DD:EE:FF"`)
 * 2. `com.cramqin.blecuktech10.DISCONNECT_CHARGER`
 * 3. `com.cramqin.blecuktech10.SCAN_CHARGER`
 * 4. `com.cramqin.blecuktech10.SET_PORT_STATE` (`--ei port 1 --ez enabled true`)
 * 5. `com.cramqin.blecuktech10.SET_SCENE_MODE` (`--ei mode 1`)
 * 6. `com.cramqin.blecuktech10.SET_SCREEN_TIMEOUT` (`--ei timeout 1`)
 * 7. `com.cramqin.blecuktech10.SET_CHARGE_LIMIT` (`--ei port 1 --ef wh_target 30.0 --es mode "once" --ez enabled true`)
 * 8. `com.cramqin.blecuktech10.QUERY_STATUS` (broadcasts `com.cramqin.blecuktech10.TELEMETRY_UPDATE`)
 * 9. `com.cramqin.blecuktech10.START_SERVER` (`--ei port 8080`)
 * 10. `com.cramqin.blecuktech10.STOP_SERVER`
 */
class BleCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(context)

        when (action) {
            ACTION_START, ACTION_LEGACY_START -> {
                val port = intent.getIntExtra("port", intent.getIntExtra(BleBridgeService.EXTRA_PORT, 8080))
                startServiceWithAction(context, BleBridgeService.ACTION_START_SERVER) {
                    putExtra(BleBridgeService.EXTRA_PORT, port)
                }
            }
            ACTION_STOP, ACTION_LEGACY_STOP -> {
                val serviceIntent = Intent(context, BleBridgeService::class.java).apply {
                    this.action = BleBridgeService.ACTION_STOP_SERVER
                }
                context.stopService(serviceIntent)
            }
            ACTION_CONNECT_CHARGER -> {
                val mac = intent.getStringExtra("mac")
                startServiceWithAction(context, BleBridgeService.ACTION_CONNECT_CUKTECH) {
                    putExtra(BleBridgeService.EXTRA_MAC, mac)
                }
            }
            ACTION_DISCONNECT_CHARGER -> {
                mgr.disconnectCharger()
            }
            ACTION_SCAN_CHARGER -> {
                startServiceWithAction(context, BleBridgeService.ACTION_SCAN_CUKTECH)
            }
            ACTION_SET_PORT_STATE -> {
                val portId = intent.getIntExtra("port", 1)
                val enabled = intent.getBooleanExtra("enabled", true)
                mgr.setPortEnabled(portId, enabled)
            }
            ACTION_SET_SCENE_MODE -> {
                val mode = intent.getIntExtra("mode", 1)
                mgr.setSceneMode(mode)
            }
            ACTION_SET_SCREEN_TIMEOUT -> {
                val timeoutCode = intent.getIntExtra("timeout", 1)
                mgr.setScreenTimeout(timeoutCode)
            }
            ACTION_SET_CHARGE_LIMIT -> {
                val portId = intent.getIntExtra("port", 1)
                val whTarget = intent.getFloatExtra("wh_target", 0f).toDouble()
                val mode = intent.getStringExtra("mode") ?: "once"
                val enabled = intent.getBooleanExtra("enabled", true)
                mgr.setPortChargeLimit(portId, whTarget, mode, enabled)
            }
            ACTION_SET_AUTO_CONNECT -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                mgr.setAutoReconnectConfigured(enabled)
            }
            ACTION_QUERY_STATUS -> {
                mgr.refreshAllTelemetryNow()
                BleBridgeService.broadcastTaskerTelemetry(
                    context = context,
                    portsMap = mgr.ports.value,
                    totalWatts = mgr.totalPowerWatts.value,
                    macAddress = mgr.deviceInfo.value.macAddress
                )
            }
        }
    }

    private fun startServiceWithAction(
        context: Context,
        actionName: String,
        block: Intent.() -> Unit = {}
    ) {
        val serviceIntent = Intent(context, BleBridgeService::class.java).apply {
            action = actionName
            block()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_START = "com.cramqin.blecuktech10.START_SERVER"
        const val ACTION_STOP = "com.cramqin.blecuktech10.STOP_SERVER"
        const val ACTION_LEGACY_START = "com.example.action.START_SERVER"
        const val ACTION_LEGACY_STOP = "com.example.action.STOP_SERVER"

        const val ACTION_CONNECT_CHARGER = "com.cramqin.blecuktech10.CONNECT_CHARGER"
        const val ACTION_DISCONNECT_CHARGER = "com.cramqin.blecuktech10.DISCONNECT_CHARGER"
        const val ACTION_SCAN_CHARGER = "com.cramqin.blecuktech10.SCAN_CHARGER"
        const val ACTION_SET_PORT_STATE = "com.cramqin.blecuktech10.SET_PORT_STATE"
        const val ACTION_SET_SCENE_MODE = "com.cramqin.blecuktech10.SET_SCENE_MODE"
        const val ACTION_SET_SCREEN_TIMEOUT = "com.cramqin.blecuktech10.SET_SCREEN_TIMEOUT"
        const val ACTION_SET_CHARGE_LIMIT = "com.cramqin.blecuktech10.SET_CHARGE_LIMIT"
        const val ACTION_SET_AUTO_CONNECT = "com.cramqin.blecuktech10.SET_AUTO_CONNECT"
        const val ACTION_QUERY_STATUS = "com.cramqin.blecuktech10.QUERY_STATUS"
    }
}
