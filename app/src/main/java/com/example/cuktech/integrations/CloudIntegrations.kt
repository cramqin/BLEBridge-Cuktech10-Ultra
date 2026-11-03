package com.example.cuktech.integrations

import android.content.Context
import com.example.cuktech.protocol.PortTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class IntegrationConfig(
    val haWebhookEnabled: Boolean = false,
    val haWebhookUrl: String = ""
)

/**
 * Home Assistant HTTP Webhook telemetry publisher.
 */
class CloudIntegrations(
    context: Context,
    @Suppress("UNUSED_PARAMETER") private val onPortSwitchCommand: (portId: Int, enabled: Boolean) -> Unit
) {
    private val prefs = context.applicationContext.getSharedPreferences("cuktech_integrations", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<IntegrationConfig> = _config.asStateFlow()

    private var lastPushMs: Long = 0L

    private fun loadConfig(): IntegrationConfig {
        return IntegrationConfig(
            haWebhookEnabled = prefs.getBoolean("ha_enabled", false),
            haWebhookUrl = prefs.getString("ha_url", "").orEmpty()
        )
    }

    fun updateConfig(newConfig: IntegrationConfig) {
        prefs.edit()
            .putBoolean("ha_enabled", newConfig.haWebhookEnabled)
            .putString("ha_url", newConfig.haWebhookUrl.trim())
            .apply()
        _config.value = newConfig
    }

    fun onTelemetryUpdate(ports: Map<Int, PortTelemetry>, totalWatts: Double) {
        val now = System.currentTimeMillis()
        if (now - lastPushMs < 10000L) return
        lastPushMs = now

        val cfg = _config.value
        if (cfg.haWebhookEnabled && cfg.haWebhookUrl.startsWith("http")) {
            scope.launch {
                runCatching {
                    val json = JSONObject().apply {
                        put("total_power_w", totalWatts)
                        for ((pid, p) in ports) {
                            put("port_${pid}_v", p.voltageV)
                            put("port_${pid}_a", p.currentA)
                            put("port_${pid}_w", p.powerW)
                            put("port_${pid}_protocol", p.protocolName)
                            put("port_${pid}_enabled", p.enabled)
                        }
                    }
                    val conn = (URL(cfg.haWebhookUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(json.toString())
                    }
                    conn.responseCode
                    conn.disconnect()
                }
            }
        }
    }
}
