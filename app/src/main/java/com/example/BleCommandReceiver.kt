package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BroadcastReceiver enabling shell / Shizuku / Shevery / ADB automation
 * Example Shizuku / ADB commands:
 *   am broadcast -a com.cramqin.blecuktech10.START_SERVER --ei port 8080
 *   am broadcast -a com.cramqin.blecuktech10.STOP_SERVER
 */
class BleCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_START, ACTION_LEGACY_START -> {
                val port = intent.getIntExtra("port", intent.getIntExtra(BleBridgeService.EXTRA_PORT, 8080))
                val serviceIntent = Intent(context, BleBridgeService::class.java).apply {
                    this.action = BleBridgeService.ACTION_START_SERVER
                    putExtra(BleBridgeService.EXTRA_PORT, port)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            ACTION_STOP, ACTION_LEGACY_STOP -> {
                val serviceIntent = Intent(context, BleBridgeService::class.java).apply {
                    this.action = BleBridgeService.ACTION_STOP_SERVER
                }
                context.stopService(serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.cramqin.blecuktech10.START_SERVER"
        const val ACTION_STOP = "com.cramqin.blecuktech10.STOP_SERVER"
        const val ACTION_LEGACY_START = "com.example.action.START_SERVER"
        const val ACTION_LEGACY_STOP = "com.example.action.STOP_SERVER"
    }
}
