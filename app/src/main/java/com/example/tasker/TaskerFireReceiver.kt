package com.example.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BleBridgeService

/**
 * Tasker / Locale Action Plugin background receiver.
 * Fires when Tasker executes a configured CUKTECH 10 Ultra Action Plugin step.
 */
class TaskerFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != "com.twofortyfouram.locale.intent.action.FIRE_SETTING") return

        val bundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE") ?: return
        val taskAction = bundle.getString("ACTION") ?: return

        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)

        Log.d("TaskerFireReceiver", "Firing Tasker setting: action=$taskAction")

        when (taskAction) {
            "CONNECT" -> {
                val mac = bundle.getString("MAC")
                mgr.connectToCharger(mac)
            }
            "DISCONNECT" -> {
                mgr.disconnectCharger()
            }
            "SET_PORT" -> {
                val port = bundle.getInt("PORT", 1)
                val state = bundle.getBoolean("STATE", true)
                mgr.setPortEnabled(port, state)
            }
            "SET_MODE" -> {
                val mode = bundle.getInt("MODE", 1)
                mgr.setSceneMode(mode)
            }
            "SET_TIMEOUT" -> {
                val timeout = bundle.getInt("TIMEOUT", 4)
                mgr.setScreenTimeout(timeout)
            }
            "SET_IDLE_SCREEN_OFF" -> {
                val state = bundle.getBoolean("STATE", true)
                mgr.setIdleScreenOff(state)
            }
            "SET_LOW_CURRENT" -> {
                val state = bundle.getBoolean("STATE", true)
                mgr.setUsbALowCurrent(state)
            }
            "SET_PORT_TIMER" -> {
                val port = bundle.getInt("PORT", 1)
                val mins = bundle.getInt("TIMER_MINS", 0)
                mgr.setPortCountdown(port, mins)
            }
            "SOFT_RESET" -> {
                mgr.forceSoftRestart {}
            }
            "SET_AUTO_CONNECT" -> {
                val state = bundle.getBoolean("STATE", true)
                mgr.setAutoReconnectConfigured(state)
            }
            "SET_PORT_LOGGING" -> {
                val port = bundle.getInt("PORT", 1)
                val state = bundle.getBoolean("STATE", true)
                mgr.setPortLoggingEnabled(port, state)
            }
            "SET_TELEMETRY" -> {
                val state = bundle.getBoolean("STATE", true)
                mgr.setTelemetryMasterEnabled(state)
            }
            "SET_LOG_WHEN_CHARGING" -> {
                val state = bundle.getBoolean("STATE", false)
                mgr.setLogOnlyWhenPhoneCharging(state)
            }
            "SET_PAUSE_WHEN_LOCKED" -> {
                val state = bundle.getBoolean("STATE", false)
                mgr.setPausePollingWhenScreenLocked(state)
            }
        }
    }
}
