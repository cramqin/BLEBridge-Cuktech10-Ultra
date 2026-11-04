package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
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

                // 1. 4x2 Soft Glass
                val ids4x2Glass = awm.getAppWidgetIds(ComponentName(appCtx, CuktechWidget4x2Provider::class.java))
                if (ids4x2Glass.isNotEmpty()) {
                    awm.updateAppWidget(ids4x2Glass, build4x2Views(appCtx, isSolid = false))
                }

                // 2. 2x2 Soft Glass
                val ids2x2Glass = awm.getAppWidgetIds(ComponentName(appCtx, CuktechWidget2x2Provider::class.java))
                if (ids2x2Glass.isNotEmpty()) {
                    awm.updateAppWidget(ids2x2Glass, build2x2Views(appCtx, isSolid = false))
                }

                // 3. 4x2 Solid Dark
                val ids4x2Solid = awm.getAppWidgetIds(ComponentName(appCtx, CuktechWidget4x2SolidProvider::class.java))
                if (ids4x2Solid.isNotEmpty()) {
                    awm.updateAppWidget(ids4x2Solid, build4x2Views(appCtx, isSolid = true))
                }

                // 4. 2x2 Solid Dark
                val ids2x2Solid = awm.getAppWidgetIds(ComponentName(appCtx, CuktechWidget2x2SolidProvider::class.java))
                if (ids2x2Solid.isNotEmpty()) {
                    awm.updateAppWidget(ids2x2Solid, build2x2Views(appCtx, isSolid = true))
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

        /**
         * Renders a glowing cyan sparkline chart bitmap for the widget hero area when charging.
         */
        private fun renderSparklineBitmap(
            points: List<Double>,
            currentWatts: Double
        ): Bitmap {
            val width = 320
            val height = 96
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val samples = if (points.size >= 4) {
                points.takeLast(24)
            } else {
                listOf(currentWatts * 0.85, currentWatts * 0.92, currentWatts * 0.98, currentWatts)
            }

            val maxVal = (samples.maxOrNull() ?: 0.0).coerceAtLeast(15.0) * 1.18
            val padTop = 10f
            val padBot = 10f
            val usableH = height - padTop - padBot

            val stepX = if (samples.size > 1) width.toFloat() / (samples.size - 1) else width.toFloat()
            val linePath = Path()
            val fillPath = Path()

            samples.forEachIndexed { idx, v ->
                val x = idx * stepX
                val norm = (v / maxVal).coerceIn(0.0, 1.0).toFloat()
                val y = (height - padBot) - (norm * usableH)
                if (idx == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, height - padBot)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = (idx - 1) * stepX
                    val prevNorm = (samples[idx - 1] / maxVal).coerceIn(0.0, 1.0).toFloat()
                    val prevY = (height - padBot) - (prevNorm * usableH)
                    val midX = (prevX + x) * 0.5f
                    linePath.cubicTo(midX, prevY, midX, y, x, y)
                    fillPath.cubicTo(midX, prevY, midX, y, x, y)
                }
            }

            fillPath.lineTo(width.toFloat(), height - padBot)
            fillPath.close()

            val activeLineColor = Color.parseColor("#00E5FF")

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, padTop, 0f, height - padBot,
                    Color.parseColor("#5500E5FF"),
                    Color.parseColor("#0000E5FF"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(fillPath, fillPaint)

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = activeLineColor
                strokeWidth = 3.2f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(linePath, strokePaint)

            if (samples.isNotEmpty()) {
                val lastNorm = (samples.last() / maxVal).coerceIn(0.0, 1.0).toFloat()
                val lastY = (height - padBot) - (lastNorm * usableH)
                val dotX = width - 4f
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#4400E5FF")
                    style = Paint.Style.FILL
                }
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = activeLineColor
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(dotX, lastY, 8f, glowPaint)
                canvas.drawCircle(dotX, lastY, 3.8f, dotPaint)
            }

            return bmp
        }

        private fun build4x2Views(context: Context, isSolid: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_cuktech_4x2)
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (isSolid) R.drawable.bg_widget_dark_solid else R.drawable.bg_widget_hyperos_glass
            )

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

        private fun build2x2Views(context: Context, isSolid: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_cuktech_2x2)
            views.setInt(
                R.id.widget_compact_root,
                "setBackgroundResource",
                if (isSolid) R.drawable.bg_widget_dark_solid else R.drawable.bg_widget_hyperos_glass
            )

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
            val chartHistory = mgr?.powerChartHistory?.value?.map { it.totalWatts } ?: emptyList()

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

            // Only show live sparkline graph when actively charging (> 0.2W) so idle state has no flat horizontal line!
            if (isConnected && totalWatts > 0.2) {
                val sparkBmp = renderSparklineBitmap(chartHistory, totalWatts)
                views.setImageViewBitmap(R.id.widget_compact_graph, sparkBmp)
                views.setViewVisibility(R.id.widget_compact_graph, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_compact_graph, View.GONE)
            }

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

                val txt = when {
                    !isConnected -> "$shortLabel: 0W"
                    !en -> "$shortLabel: OFF"
                    w >= 10.0 -> String.format(java.util.Locale.US, "%s: %.0fW", shortLabel, w)
                    w > 0.1 -> String.format(java.util.Locale.US, "%s: %.1fW", shortLabel, w)
                    else -> "$shortLabel: 0W"
                }
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

class CuktechWidget4x2SolidProvider : CuktechWidgetProvider()

class CuktechWidget2x2SolidProvider : CuktechWidgetProvider()
