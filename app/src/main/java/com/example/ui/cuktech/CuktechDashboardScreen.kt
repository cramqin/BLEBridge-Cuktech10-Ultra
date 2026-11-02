package com.example.ui.cuktech

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.db.PowerChartPoint
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.integrations.CloudIntegrations
import com.example.cuktech.integrations.IntegrationConfig
import com.example.cuktech.integrations.ShizukuCompanion
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetry
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun CuktechDashboardScreen(
    bleManager: CuktechBleManager,
    cloudClient: XiaomiCloudClient,
    shizukuCompanion: ShizukuCompanion,
    cloudIntegrations: CloudIntegrations,
    onRequestBlePermissions: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val authState by bleManager.authState.collectAsState()
    val statusMsg by bleManager.statusMessage.collectAsState()
    val devInfo by bleManager.deviceInfo.collectAsState()
    val portsMap by bleManager.ports.collectAsState()
    val totalWatts by bleManager.totalPowerWatts.collectAsState()
    val settings by bleManager.settings.collectAsState()
    val chartHistory by bleManager.powerChartHistory.collectAsState()
    val activeSessions by bleManager.activeSessions.collectAsState()
    val completedSessions by bleManager.completedSessions.collectAsState()
    val chargeLimits by bleManager.chargeLimits.collectAsState()

    val accountSession by bleManager.credentialStore.session.collectAsState()
    val savedDevices by bleManager.credentialStore.devices.collectAsState()
    val activeCred by bleManager.credentialStore.activeDevice.collectAsState()

    val shizukuStatus by shizukuCompanion.status.collectAsState()
    val integrationCfg by cloudIntegrations.config.collectAsState()
    val bemfaConnected by cloudIntegrations.bemfaConnected.collectAsState()

    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var chargeLimitDialogPort by remember { mutableStateOf<Int?>(null) }
    var showTaskerDocs by remember { mutableStateOf(false) }
    var showCloudConfig by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =====================================================================
        // 1. PROMINENT SEARCH & PAIR BUTTON + CONNECTION HEADER CARD
        // =====================================================================
        Button(
            onClick = {
                onRequestBlePermissions {
                    showPairingDialog = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SEARCH & PAIR CUKTECH 10 ULTRA",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp
            )
        }

        // Xiaomi Account & Stored Credentials Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = if (activeCred?.hasValidToken == true) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (accountSession != null) {
                                    "小米云已登录 (UID: ${accountSession!!.userId})"
                                } else if (activeCred != null) {
                                    "已加载加密凭证 (${activeCred!!.name})"
                                } else {
                                    "小米云账号未登录"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (activeCred != null) {
                                    "当前设备: ${activeCred!!.mac} · Token: ${if (activeCred!!.hasValidToken) "已加密就绪" else "未配置"}"
                                } else {
                                    "登录小米账号后可自动获取充电器 MAC、Token 与 BLE Key"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = { showXiaomiLoginDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (accountSession != null || activeCred != null) "账号/凭证" else "登录小米云", fontSize = 12.sp)
                    }
                }

                if (savedDevices.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        savedDevices.forEach { dev ->
                            FilterChip(
                                selected = activeCred?.mac.equals(dev.mac, ignoreCase = true),
                                onClick = { bleManager.credentialStore.selectActiveDevice(dev.mac) },
                                label = { Text("${dev.name} (${dev.mac.takeLast(5)})", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (accountSession != null || activeCred != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeCred != null) {
                            val isConnected = authState == CuktechAuthState.AUTHENTICATED ||
                                authState == CuktechAuthState.CONNECTING_GATT ||
                                authState == CuktechAuthState.PHASE_A_INIT_A4 ||
                                authState == CuktechAuthState.PHASE_B_HANDSHAKE

                            Button(
                                onClick = {
                                    if (isConnected) {
                                        bleManager.disconnectCharger()
                                    } else {
                                        onRequestBlePermissions {
                                            bleManager.connectToCharger(activeCred!!.mac)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else Color(0xFF10B981)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.LinkOff else Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isConnected) "断开充电器连接" else "一键直连充电器", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { bleManager.refreshAllTelemetryNow() },
                            enabled = authState == CuktechAuthState.AUTHENTICATED
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("刷新", fontSize = 12.sp)
                        }

                        IconButton(
                            onClick = {
                                bleManager.disconnectCharger()
                                bleManager.credentialStore.logoutAndWipeAll()
                                Toast.makeText(context, "已安全擦除小米云会话与本地加密密钥", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "清除凭证",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 2. TOTAL OUTPUT POWER & CHARGER DIAGNOSTICS HERO CARD
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = devInfo.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = statusMsg,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val badgeColor = when (authState) {
                        CuktechAuthState.AUTHENTICATED -> Color(0xFF10B981)
                        CuktechAuthState.AUTH_FAILED -> Color(0xFFEF4444)
                        CuktechAuthState.DISCONNECTED -> Color.Gray
                        else -> Color(0xFFF59E0B)
                    }
                    Surface(
                        color = badgeColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = authState.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }
                }

                // Total Output Power Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "总输出功率 (TOTAL OUTPUT)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "%.1f".format(totalWatts),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "W / 120W",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "信号: ${devInfo.rssi} dBm · 质量 ${devInfo.connectionQualityScore}%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "固件: ${devInfo.firmwareVersion} · 型号: ${devInfo.model}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { (totalWatts.toFloat() / 120f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }

        // =====================================================================
        // 3. FOUR INDIVIDUAL PORTS TELEMETRY & CONTROLS (USB-C1, C2, C3, USB-A)
        // =====================================================================
        Text(
            text = "四口独立实时遥测与控制 (USB-C1 / C2 / C3 / USB-A)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (portId in 1..4) {
                val port = portsMap[portId] ?: PortTelemetry(portId, "Port $portId")
                val limit = chargeLimits[portId]
                PortTelemetryCard(
                    port = port,
                    chargeLimit = limit,
                    onToggleEnabled = { enabled -> bleManager.setPortEnabled(portId, enabled) },
                    onSelectCountdown = { code -> bleManager.setPortCountdown(portId, code) },
                    onOpenChargeLimit = { chargeLimitDialogPort = portId }
                )
            }
        }

        // =====================================================================
        // 4. REAL-TIME POWER WAVEFORM CHART
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "实时功率曲线 (W)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "样本数: ${chartHistory.size}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LivePowerChart(
                    points = chartHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }
        }

        // =====================================================================
        // 5. CHARGER SETTINGS & FAST-CHARGE PROTOCOL SWITCHES
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "充电器硬件参数与协议开关 (MIoT SIID=2)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                // Scene Mode (PIID 5)
                Text("功率分配场景模式 (PIID 5):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CuktechProtocol.SCENE_MODES.forEach { (code, label) ->
                        FilterChip(
                            selected = settings.sceneMode == code,
                            onClick = { bleManager.setSceneMode(code) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                // Screen Timeout (PIID 6)
                Text("屏幕常亮/息屏时间 (PIID 6):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CuktechProtocol.SCREEN_TIMEOUTS.forEach { (code, label) ->
                        FilterChip(
                            selected = settings.screenTimeout == code,
                            onClick = { bleManager.setScreenTimeout(code) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                // Fast-Charge Protocol Switches (PIID 21: PD, PPS, UFCS, SCP)
                Text("快充协议扩展开关 (PIID 21):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.pdEnabled,
                        onClick = { bleManager.setFastChargeProtocols(pd = !settings.pdEnabled) },
                        label = { Text("PD") }
                    )
                    FilterChip(
                        selected = settings.ppsEnabled,
                        onClick = { bleManager.setFastChargeProtocols(pps = !settings.ppsEnabled) },
                        label = { Text("PPS / Mi Turbo") }
                    )
                    FilterChip(
                        selected = settings.ufcsEnabled,
                        onClick = { bleManager.setFastChargeProtocols(ufcs = !settings.ufcsEnabled) },
                        label = { Text("UFCS 融合快充") }
                    )
                    FilterChip(
                        selected = settings.scpEnabled,
                        onClick = { bleManager.setFastChargeProtocols(scp = !settings.scpEnabled) },
                        label = { Text("SCP") }
                    )
                }

                HorizontalDivider()

                // Boolean Switches (PIID 15, 19, 20, 13)
                SettingSwitchRow(
                    title = "USB-A 小电流模式 (PIID 15)",
                    subtitle = "适合蓝牙耳机、手环等微电流设备充电",
                    checked = settings.usbALowCurrent,
                    onCheckedChange = { bleManager.setUsbALowCurrent(it) }
                )
                SettingSwitchRow(
                    title = "空载自动息屏 (PIID 19)",
                    subtitle = "所有接口无负载输出时自动关闭充电器屏幕",
                    checked = settings.idleScreenOff,
                    onCheckedChange = { bleManager.setIdleScreenOff(it) }
                )
                SettingSwitchRow(
                    title = "屏幕重力方向锁定 (PIID 20)",
                    subtitle = "锁定充电器机身显示屏旋转方向",
                    checked = settings.orientationLock,
                    onCheckedChange = { bleManager.setOrientationLock(it) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("机身界面语言 (PIID 13)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CuktechProtocol.LANGUAGE_OPTIONS.forEach { (code, label) ->
                            FilterChip(
                                selected = settings.language == code,
                                onClick = { bleManager.setLanguage(code) },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 6. CHARGING SESSIONS & CSV EXPORT CARD
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "充电会话统计与能量记录 (Wh)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                val file = bleManager.persistence.exportSessionsAndHistoryCsv()
                                Toast.makeText(context, "已导出 CSV: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("导出 CSV", fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = {
                                bleManager.persistence.clearSessions()
                                Toast.makeText(context, "已清空历史会话", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("清空", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (activeSessions.isEmpty() && completedSessions.isEmpty()) {
                    Text(
                        text = "暂无充电会话记录。当任一端口输出功率 ≥ 0.5W 时将自动开始梯形积分累计 Wh。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    activeSessions.values.forEach { s ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${s.portLabel} · 正在充电 (${s.protocolUsed})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "时长: ${s.durationSeconds / 60}分${s.durationSeconds % 60}秒 · 峰值: ${"%.1f".format(s.peakWatts)}W · 均值: ${"%.1f".format(s.avgWatts)}W",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${"%.2f".format(s.whAccumulated)} Wh",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    completedSessions.take(6).forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("${s.portLabel} (${s.protocolUsed})", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                                Text(
                                    text = "耗时 ${s.durationSeconds / 60}m · 峰值 ${"%.1f".format(s.peakWatts)}W · 均功 ${"%.1f".format(s.avgWatts)}W",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${"%.2f".format(s.whAccumulated)} Wh",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 7. SHIZUKU COMPANION, TASKER API & BEMFA/HA CLOUD INTEGRATIONS CARD
        // =====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Shizuku 增强、Tasker 自动化与智能家居云桥接",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Text(
                    text = "Shizuku 状态: ${shizukuStatus.providerPackage} · 电池无限制白名单: ${if (shizukuStatus.batteryWhitelisted) "已开启" else "未开启"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                shizukuCompanion.applyHyperOsKeepaliveOptimization()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("HyperOS 后台保活加固", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                shizukuCompanion.runBluetoothStackDiagnostics()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("蓝牙 GATT 协议栈诊断", fontSize = 11.sp)
                    }
                }

                if (shizukuStatus.lastDiagnosticOutput.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = shizukuStatus.lastDiagnosticOutput,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { showCloudConfig = !showCloudConfig }) {
                        Text(if (showCloudConfig) "收起巴法云 / HA 配置" else "展开巴法云 (小爱同学) / HA 配置")
                    }
                    TextButton(onClick = { showTaskerDocs = !showTaskerDocs }) {
                        Text(if (showTaskerDocs) "收起 Tasker 接口文档" else "查看 Tasker 广播接口")
                    }
                }

                if (showCloudConfig) {
                    CloudIntegrationConfigPanel(
                        config = integrationCfg,
                        bemfaConnected = bemfaConnected,
                        onSave = { cloudIntegrations.updateConfig(it) }
                    )
                }

                if (showTaskerDocs) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = """
                                Tasker / ADB 广播指令接口 (Package: com.cramqin.blecuktech10):
                                • 连接充电器:
                                  Action: com.cramqin.blecuktech10.CONNECT_CHARGER
                                  Extra (可选): mac:AA:BB:CC:DD:EE:FF
                                • 断开连接:
                                  Action: com.cramqin.blecuktech10.DISCONNECT_CHARGER
                                • 控制端口通断 (port=1..4, enabled=true/false):
                                  Action: com.cramqin.blecuktech10.SET_PORT_STATE
                                • 设置功率分配模式 (mode=1..4):
                                  Action: com.cramqin.blecuktech10.SET_SCENE_MODE
                                • 设置单口电量上限 (port=1..4, wh_target=30.0, mode=once/always):
                                  Action: com.cramqin.blecuktech10.SET_CHARGE_LIMIT
                                • 实时遥测广播接收 (Tasker Intent Received):
                                  Action: com.cramqin.blecuktech10.TELEMETRY_UPDATE
                                  Extras: %total_watts, %port_1_v, %port_1_a, %port_1_w, %port_1_protocol (1..4)
                            """.trimIndent(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // DIALOGS
    // =========================================================================
    if (showXiaomiLoginDialog) {
        XiaomiAccountLoginDialog(
            cloudClient = cloudClient,
            credentialStore = bleManager.credentialStore,
            onDismiss = { showXiaomiLoginDialog = false },
            onDevicesSynced = { list ->
                Toast.makeText(context, "成功同步 ${list.size} 台设备凭证", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showPairingDialog) {
        CuktechDiscoveryPairingDialog(
            bleManager = bleManager,
            onDismiss = { showPairingDialog = false },
            onSelectAndConnect = { dev ->
                showPairingDialog = false
                bleManager.connectToCharger(dev.mac)
            }
        )
    }

    chargeLimitDialogPort?.let { portId ->
        val existing = chargeLimits[portId]
        PortChargeLimitDialog(
            portId = portId,
            existingLimit = existing,
            onDismiss = { chargeLimitDialogPort = null },
            onConfirm = { wh, mode, en ->
                bleManager.setPortChargeLimit(portId, wh, mode, en)
                chargeLimitDialogPort = null
            }
        )
    }
}

@Composable
private fun PortTelemetryCard(
    port: PortTelemetry,
    chargeLimit: PortChargeLimit?,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectCountdown: (Int) -> Unit,
    onOpenChargeLimit: () -> Unit
) {
    val accentColor = when (port.portId) {
        1 -> Color(0xFF38BDF8)
        2 -> Color(0xFF818CF8)
        3 -> Color(0xFF34D399)
        else -> Color(0xFFFBBF24)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (port.isCharging) 1.5.dp else 0.5.dp,
                color = if (port.isCharging) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (port.isCharging) accentColor else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = port.portLabel,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = accentColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = port.protocolName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (port.enabled) "已开启" else "已断电",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = port.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                }
            }

            // V / A / W / Wh Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn("电压 (V)", "%.2f V".format(port.voltageV), accentColor)
                MetricColumn("电流 (A)", "%.2f A".format(port.currentA), accentColor)
                MetricColumn("功率 (W)", "%.2f W".format(port.powerW), accentColor)
                MetricColumn("累计能量", "%.2f Wh".format(port.cumulativeWh), MaterialTheme.colorScheme.onSurface)
            }

            port.pdoCaps?.let { pdo ->
                Text(
                    text = "协商 PDO 档位: ${pdo.formatSummary()}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Charge Limit Progress Bar if enabled
            if (chargeLimit != null && chargeLimit.enabled) {
                val used = chargeLimit.currentUsedWh(port.cumulativeWh)
                val progress = chargeLimit.progressFraction(port.cumulativeWh)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "定量断电保护 (${if (chargeLimit.mode == "always") "每次生效" else "单次"}): ${"%.2f".format(used)} / ${"%.1f".format(chargeLimit.whTarget)} Wh",
                            fontSize = 11.sp,
                            color = accentColor
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            // Port Controls Row: Countdown Timer + Wh Charge Limit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("定时:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CuktechProtocol.COUNTDOWN_OPTIONS.forEach { (code, label) ->
                        FilterChip(
                            selected = port.countdownCode == code,
                            onClick = { onSelectCountdown(code) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(
                    onClick = onOpenChargeLimit,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (chargeLimit?.enabled == true) "上限 ${"%.0f".format(chargeLimit.whTarget)}Wh" else "电量上限",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LivePowerChart(
    points: List<PowerChartPoint>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Draw horizontal reference lines
        for (i in 0..3) {
            val y = h * (i / 3f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        if (points.size < 2) return@Canvas
        val maxWatts = max(20.0, points.maxOf { it.totalWatts } * 1.15)
        val path = Path()
        points.forEachIndexed { idx, pt ->
            val x = (idx.toFloat() / (points.size - 1).toFloat()) * w
            val y = (h - (pt.totalWatts / maxWatts).toFloat() * h).coerceIn(2f, h - 2f)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = primaryColor,
            style = Stroke(width = 3.5f)
        )
    }
}

@Composable
private fun PortChargeLimitDialog(
    portId: Int,
    existingLimit: PortChargeLimit?,
    onDismiss: () -> Unit,
    onConfirm: (Double, String, Boolean) -> Unit
) {
    var whInput by remember { mutableStateOf(existingLimit?.whTarget?.toString() ?: "25.0") }
    var mode by remember { mutableStateOf(existingLimit?.mode ?: "once") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("端口 $portId 定量断电保护 (Wh)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "当该端口累计输出能量达到设定 Wh 阈值时，将自动通过 PIID 16 切断该端口输出。",
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = whInput,
                    onValueChange = { whInput = it },
                    label = { Text("目标能量上限 (Wh)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "once",
                        onClick = { mode = "once" },
                        label = { Text("单次触发 (once)") }
                    )
                    FilterChip(
                        selected = mode == "always",
                        onClick = { mode = "always" },
                        label = { Text("每次生效 (always)") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = whInput.toDoubleOrNull() ?: 0.0
                    onConfirm(target, mode, target > 0.0)
                }
            ) {
                Text("启用上限")
            }
        },
        dismissButton = {
            Row {
                if (existingLimit != null) {
                    TextButton(onClick = { onConfirm(0.0, mode, false) }) {
                        Text("关闭限制", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
private fun CloudIntegrationConfigPanel(
    config: IntegrationConfig,
    bemfaConnected: Boolean,
    onSave: (IntegrationConfig) -> Unit
) {
    var bemfaEn by remember(config) { mutableStateOf(config.bemfaEnabled) }
    var bemfaUid by remember(config) { mutableStateOf(config.bemfaUid) }
    var bemfaTopic by remember(config) { mutableStateOf(config.bemfaTopic) }
    var haEn by remember(config) { mutableStateOf(config.haWebhookEnabled) }
    var haUrl by remember(config) { mutableStateOf(config.haWebhookUrl) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("巴法云语音控制 (小爱同学/Siri · ${if (bemfaConnected) "已连接" else "未连接"})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Switch(checked = bemfaEn, onCheckedChange = { bemfaEn = it })
        }
        OutlinedTextField(
            value = bemfaUid,
            onValueChange = { bemfaUid = it },
            label = { Text("巴法云私钥 UID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = bemfaTopic,
            onValueChange = { bemfaTopic = it },
            label = { Text("巴法云主题 Topic (如 cuktech10002)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Home Assistant Webhook 遥测推送", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Switch(checked = haEn, onCheckedChange = { haEn = it })
        }
        OutlinedTextField(
            value = haUrl,
            onValueChange = { haUrl = it },
            label = { Text("HA Webhook URL (http://.../api/webhook/...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                onSave(
                    IntegrationConfig(
                        bemfaEnabled = bemfaEn,
                        bemfaUid = bemfaUid,
                        bemfaTopic = bemfaTopic,
                        haWebhookEnabled = haEn,
                        haWebhookUrl = haUrl
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存云端桥接设置")
        }
    }
}
