package com.example.cuktech.integrations

import android.content.Context
import com.example.cuktech.protocol.PortTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

data class IntegrationConfig(
    val bemfaEnabled: Boolean = false,
    val bemfaUid: String = "",
    val bemfaTopic: String = "cuktech10002",
    val haWebhookEnabled: Boolean = false,
    val haWebhookUrl: String = ""
)

/**
 * Optional Bemfa Cloud (`bemfa.com:8344` for XiaoAi / Mi Home / Siri voice control)
 * and Home Assistant HTTP/MQTT Webhook telemetry publisher (`bemfa.py` & `mqtt.py` parity).
 */
class CloudIntegrations(
    context: Context,
    private val onPortSwitchCommand: (portId: Int, enabled: Boolean) -> Unit
) {
    private val prefs = context.applicationContext.getSharedPreferences("cuktech_integrations", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<IntegrationConfig> = _config.asStateFlow()

    private val _bemfaConnected = MutableStateFlow(false)
    val bemfaConnected: StateFlow<Boolean> = _bemfaConnected.asStateFlow()

    private var bemfaJob: Job? = null
    private var bemfaWriter: OutputStreamWriter? = null
    private var lastPushMs: Long = 0L

    init {
        if (_config.value.bemfaEnabled && _config.value.bemfaUid.isNotBlank()) {
            startBemfaLoop()
        }
    }

    private fun loadConfig(): IntegrationConfig {
        return IntegrationConfig(
            bemfaEnabled = prefs.getBoolean("bemfa_enabled", false),
            bemfaUid = prefs.getString("bemfa_uid", "").orEmpty(),
            bemfaTopic = prefs.getString("bemfa_topic", "cuktech10002").orEmpty(),
            haWebhookEnabled = prefs.getBoolean("ha_enabled", false),
            haWebhookUrl = prefs.getString("ha_url", "").orEmpty()
        )
    }

    fun updateConfig(newConfig: IntegrationConfig) {
        prefs.edit()
            .putBoolean("bemfa_enabled", newConfig.bemfaEnabled)
            .putString("bemfa_uid", newConfig.bemfaUid.trim())
            .putString("bemfa_topic", newConfig.bemfaTopic.trim().ifBlank { "cuktech10002" })
            .putBoolean("ha_enabled", newConfig.haWebhookEnabled)
            .putString("ha_url", newConfig.haWebhookUrl.trim())
            .apply()
        _config.value = newConfig
        if (newConfig.bemfaEnabled && newConfig.bemfaUid.isNotBlank()) {
            startBemfaLoop()
        } else {
            stopBemfaLoop()
        }
    }

    private fun startBemfaLoop() {
        stopBemfaLoop()
        bemfaJob = scope.launch {
            while (isActive && _config.value.bemfaEnabled) {
                val uid = _config.value.bemfaUid.trim()
                val topic = _config.value.bemfaTopic.trim()
                if (uid.isBlank() || topic.isBlank()) break
                try {
                    Socket("bemfa.com", 8344).use { socket ->
                        socket.soTimeout = 65000
                        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                        bemfaWriter = writer
                        // Subscribe to topic
                        writer.write("cmd=1&uid=$uid&topic=$topic\r\n")
                        writer.flush()
                        _bemfaConnected.value = true

                        val pingJob = launch {
                            while (isActive) {
                                delay(45000L)
                                synchronized(writer) {
                                    writer.write("ping\r\n")
                                    writer.flush()
                                }
                            }
                        }

                        while (isActive) {
                            val line = reader.readLine() ?: break
                            handleBemfaLine(line)
                        }
                        pingJob.cancel()
                    }
                } catch (_: Exception) {
                    _bemfaConnected.value = false
                } finally {
                    bemfaWriter = null
                    _bemfaConnected.value = false
                }
                delay(8000L)
            }
        }
    }

    private fun stopBemfaLoop() {
        bemfaJob?.cancel()
        bemfaJob = null
        bemfaWriter = null
        _bemfaConnected.value = false
    }

    private fun handleBemfaLine(line: String) {
        if (!line.contains("msg=")) return
        val msg = line.substringAfter("msg=").substringBefore("&").trim().lowercase()
        when {
            msg == "on" -> onPortSwitchCommand(1, true)
            msg == "off" -> onPortSwitchCommand(1, false)
            msg.startsWith("port") && msg.contains(":") -> {
                val pIdx = msg.removePrefix("port").substringBefore(":").toIntOrNull() ?: return
                val en = msg.substringAfter(":") == "on"
                onPortSwitchCommand(pIdx, en)
            }
        }
    }

    fun onTelemetryUpdate(ports: Map<Int, PortTelemetry>, totalWatts: Double) {
        val now = System.currentTimeMillis()
        if (now - lastPushMs < 10000L) return
        lastPushMs = now

        val cfg = _config.value
        if (cfg.bemfaEnabled && _bemfaConnected.value && cfg.bemfaUid.isNotBlank()) {
            scope.launch {
                runCatching {
                    val writer = bemfaWriter ?: return@launch
                    val stateStr = if (totalWatts > 0.5) "on#${totalWatts.toInt()}" else "off#0"
                    synchronized(writer) {
                        writer.write("cmd=2&uid=${cfg.bemfaUid}&topic=${cfg.bemfaTopic}/up&msg=$stateStr\r\n")
                        writer.flush()
                    }
                }
            }
        }

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
