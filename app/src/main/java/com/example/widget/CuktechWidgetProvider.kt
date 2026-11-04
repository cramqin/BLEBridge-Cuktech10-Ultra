package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.example.BleBridgeService
import com.example.MainActivity
import com.example.R
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.protocol.CuktechProtocol

open class CuktechWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action.startsWith("com.cramqin.blecuktech10.WIDGET_")) {
            val mgr = BleBridgeService.cuktechManager
                ?: BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)

            when (action) {
                ACTION_TOGGLE_PORT_1 -> togglePort(context, mgr, 1)
                ACTION_TOGGLE_PORT_2 -> togglePort(context, mgr, 2)
                ACTION_TOGGLE_PORT_3 -> togglePort(context, mgr, 3)
                ACTION_TOGGLE_PORT_4 -> togglePort(context, mgr, 4)
                ACTION_CYCLE_SCENE -> {
                    val curScene = mgr.settings.value.sceneMode
                    val nextScene = if (curScene in 1..3) curScene + 1 else 1
                    mgr.setSceneMode(nextScene)
                    updateAllWidgets(context)
                }
                ACTION_CONNECT_OR_OPEN -> {
                    if (mgr.authState.value != CuktechAuthState.AUTHENTICATED) {
                        val svcIntent = Intent(context, BleBridgeService::class.java).apply {
                            this.action = BleBridgeService.ACTION_START_SERVER
                        }
                        runCatching { context.startForegroundService(svcIntent) }
                        mgr.connectToCharger(null)
                        updateAllWidgets(context)
                    } else {
                        val openIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(openIntent)
                    }
                }
            }
        }
    }

    private fun togglePort(
        context: Context,
        mgr: com.example.cuktech.ble.CuktechBleManager,
        portId: Int
    ) {
        if (mgr.authState.value != CuktechAuthState.AUTHENTICATED) {
            mgr.connectToCharger(null)
        } else {
            val curEnabled = mgr.ports.value[portId]?.enabled ?: true
            mgr.setPortEnabled(portId, !curEnabled)
        }
        updateAllWidgets(context)
    }

    companion object {
        const val ACTION_TOGGLE_PORT_1 = "com.cramqin.blecuktech10.WIDGET_TOGGLE_PORT_1"
        const val ACTION_TOGGLE_PORT_2 = "com.cramqin.blecuktech10.WIDGET_TOGGLE_PORT_2"
        const val ACTION_TOGGLE_PORT_3 = "com.cramqin.blecuktech10.WIDGET_TOGGLE_PORT_3"
        const val ACTION_TOGGLE_PORT_4 = "com.cramqin.blecuktech10.WIDGET_TOGGLE_PORT_4"
        const val ACTION_CYCLE_SCENE = "com.cramqin.blecuktech10.WIDGET_CYCLE_SCENE"
        const val ACTION_CONNECT_OR_OPEN = "com.cramqin.blecuktech10.WIDGET_CONNECT_OR_OPEN"

        fun updateAllWidgets(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                val awm = AppWidgetManager.getInstance(appCtx)

                val ids4x2 = awm.getAppWidgetIds(ComponentName(appCtx, CuktechWidget4x2Provider::class.java))
                if (ids4x2.isNotEmpty()) {
                    val views4x2 = build4x2Views(appCtx)
                    for (id in ids4x2) {
                        awm.updateAppWidget(id, views4x2)
                    }
                }

                val ids2x2 = awm.getAppWidgetIds(ComponentName(appCtx, CuktechWidget2x2Provider::class.java))
                if (ids2x2.isNotEmpty()) {
                    val views2x2 = build2x2Views(appCtx)
                    for (id in ids2x2) {
                        awm.updateAppWidget(id, views2x2)
                    }
                }
            }
        }

        private fun pendingBroadcast(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, CuktechWidget4x2Provider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun pendingOpenApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                900,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun build4x2Views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_cuktech_4x2)
            val mgr = BleBridgeService.cuktechManager
            val authState = mgr?.authState?.value ?: CuktechAuthState.DISCONNECTED
            val isConnected = authState == CuktechAuthState.AUTHENTICATED
            val isConnecting = authState == CuktechAuthState.CONNECTING_GATT ||
                authState == CuktechAuthState.DISCOVERING_SERVICES ||
                authState == CuktechAuthState.PHASE_A_INIT_A4 ||
                authState == CuktechAuthState.PHASE_B_HANDSHAKE

            val totalWatts = if (isConnected) (mgr?.totalPowerWatts?.value ?: 0.0) else 0.0
            val sceneCode = mgr?.settings?.value?.sceneMode ?: 1
            val sceneLabel = CuktechProtocol.SCENE_MODES[sceneCode] ?: "AI Mode"
            val ports = mgr?.ports?.value ?: emptyMap()

            // Header dot & status
            views.setImageViewResource(
                R.id.widget_status_dot,
                if (isConnected) R.drawable.bg_widget_dot_online else R.drawable.bg_widget_dot_offline
            )
            views.setTextViewText(
                R.id.widget_status_text,
                when {
                    isConnected -> "Connected · Tap to open"
                    isConnecting -> "Connecting BLE..."
                    else -> "Disconnected · Tap to connect"
                }
            )
            views.setTextViewText(R.id.widget_scene_pill, sceneLabel)
            views.setTextViewText(R.id.widget_total_watts, String.format(java.util.Locale.US, "%.1fW", totalWatts))

            // Click handlers
            views.setOnClickPendingIntent(R.id.widget_header_click, pendingBroadcast(context, ACTION_CONNECT_OR_OPEN, 100))
            views.setOnClickPendingIntent(R.id.widget_total_watts, pendingOpenApp(context))
            views.setOnClickPendingIntent(R.id.widget_scene_pill, pendingBroadcast(context, ACTION_CYCLE_SCENE, 105))

            val portConfigs = listOf(
                Triple(1, R.id.widget_port_1, Pair(R.id.widget_port_1_watts, R.id.widget_port_1_sub)),
                Triple(2, R.id.widget_port_2, Pair(R.id.widget_port_2_watts, R.id.widget_port_2_sub)),
                Triple(3, R.id.widget_port_3, Pair(R.id.widget_port_3_watts, R.id.widget_port_3_sub)),
                Triple(4, R.id.widget_port_4, Pair(R.id.widget_port_4_watts, R.id.widget_port_4_sub))
            )
            val portActions = mapOf(
                1 to ACTION_TOGGLE_PORT_1,
                2 to ACTION_TOGGLE_PORT_2,
                3 to ACTION_TOGGLE_PORT_3,
                4 to ACTION_TOGGLE_PORT_4
            )

            for ((pid, containerId, textIds) in portConfigs) {
                val (wattsId, subId) = textIds
                val p = ports[pid]
                val en = p?.enabled ?: true
                val w = if (isConnected) (p?.powerW ?: 0.0) else 0.0
                val isCharging = isConnected && en && w > 0.2

                val bgRes = when {
                    !isConnected -> R.drawable.bg_widget_port_idle
                    !en -> R.drawable.bg_widget_port_off
                    isCharging -> R.drawable.bg_widget_port_active
                    else -> R.drawable.bg_widget_port_idle
                }
                views.setInt(containerId, "setBackgroundResource", bgRes)

                views.setTextViewText(wattsId, String.format(java.util.Locale.US, "%.1fW", w))
                views.setTextColor(
                    wattsId,
                    when {
                        !isConnected -> Color.parseColor("#94A3B8")
                        !en -> Color.parseColor("#EF4444")
                        isCharging -> Color.parseColor("#00E5FF")
                        else -> Color.parseColor("#F8FAFC")
                    }
                )

                val subText = when {
                    !isConnected -> "Offline"
                    !en -> "OFF"
                    isCharging -> String.format(java.util.Locale.US, "%.1fV · %s", p?.voltageV ?: 0.0, p?.protocolName?.takeIf { it.isNotBlank() && it != "Normal" } ?: "ON")
                    else -> "Standby"
                }
                views.setTextViewText(subId, subText)
                views.setTextColor(
                    subId,
                    when {
                        !isConnected -> Color.parseColor("#64748B")
                        !en -> Color.parseColor("#EF4444")
                        isCharging -> Color.parseColor("#A5F3FC")
                        else -> Color.parseColor("#94A3B8")
                    }
                )

                val act = portActions[pid] ?: ACTION_TOGGLE_PORT_1
                views.setOnClickPendingIntent(containerId, pendingBroadcast(context, act, 200 + pid))
            }

            return views
        }

        private fun build2x2Views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_cuktech_2x2)
            val mgr = BleBridgeService.cuktechManager
            val authState = mgr?.authState?.value ?: CuktechAuthState.DISCONNECTED
            val isConnected = authState == CuktechAuthState.AUTHENTICATED
            val isConnecting = authState == CuktechAuthState.CONNECTING_GATT ||
                authState == CuktechAuthState.DISCOVERING_SERVICES ||
                authState == CuktechAuthState.PHASE_A_INIT_A4 ||
                authState == CuktechAuthState.PHASE_B_HANDSHAKE

            val totalWatts = if (isConnected) (mgr?.totalPowerWatts?.value ?: 0.0) else 0.0
            val sceneCode = mgr?.settings?.value?.sceneMode ?: 1
            val sceneLabel = CuktechProtocol.SCENE_MODES[sceneCode] ?: "AI Mode"
            val ports = mgr?.ports?.value ?: emptyMap()

            views.setImageViewResource(
                R.id.widget_compact_dot,
                if (isConnected) R.drawable.bg_widget_dot_online else R.drawable.bg_widget_dot_offline
            )
            views.setTextViewText(R.id.widget_compact_scene, sceneLabel)
            views.setTextViewText(R.id.widget_compact_watts, String.format(java.util.Locale.US, "%.1f W", totalWatts))
            views.setTextViewText(
                R.id.widget_compact_sub,
                when {
                    isConnected -> "Connected · 4 Ports"
                    isConnecting -> "Connecting..."
                    else -> "Tap to connect"
                }
            )

            views.setOnClickPendingIntent(R.id.widget_compact_hero, pendingBroadcast(context, ACTION_CONNECT_OR_OPEN, 300))
            views.setOnClickPendingIntent(R.id.widget_compact_scene, pendingBroadcast(context, ACTION_CYCLE_SCENE, 305))

            val miniPorts = listOf(
                Triple(1, "C1", R.id.widget_compact_p1),
                Triple(2, "C2", R.id.widget_compact_p2),
                Triple(3, "C3", R.id.widget_compact_p3),
                Triple(4, "A", R.id.widget_compact_p4)
            )
            val portActions = mapOf(
                1 to ACTION_TOGGLE_PORT_1,
                2 to ACTION_TOGGLE_PORT_2,
                3 to ACTION_TOGGLE_PORT_3,
                4 to ACTION_TOGGLE_PORT_4
            )

            for ((pid, shortLabel, viewId) in miniPorts) {
                val p = ports[pid]
                val en = p?.enabled ?: true
                val w = if (isConnected) (p?.powerW ?: 0.0) else 0.0
                val isCharging = isConnected && en && w > 0.2

                val bgRes = when {
                    !isConnected -> R.drawable.bg_widget_port_idle
                    !en -> R.drawable.bg_widget_port_off
                    isCharging -> R.drawable.bg_widget_port_active
                    else -> R.drawable.bg_widget_port_idle
                }
                views.setInt(viewId, "setBackgroundResource", bgRes)

                val txt = if (!en && isConnected) "$shortLabel: OFF" else "$shortLabel: ${w.toInt()}W"
                views.setTextViewText(viewId, txt)
                views.setTextColor(
                    viewId,
                    when {
                        !isConnected -> Color.parseColor("#94A3B8")
                        !en -> Color.parseColor("#EF4444")
                        isCharging -> Color.parseColor("#00E5FF")
                        else -> Color.parseColor("#E2E8F0")
                    }
                )

                val act = portActions[pid] ?: ACTION_TOGGLE_PORT_1
                views.setOnClickPendingIntent(viewId, pendingBroadcast(context, act, 400 + pid))
            }

            return views
        }
    }
}

class CuktechWidget4x2Provider : CuktechWidgetProvider()

class CuktechWidget2x2Provider : CuktechWidgetProvider()
