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
                val timeout = bundle.getInt("TIMEOUT", 1)
                mgr.setScreenTimeout(timeout)
            }
        }
    }
}
