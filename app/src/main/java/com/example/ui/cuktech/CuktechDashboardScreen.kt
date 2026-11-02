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
    onRequestBlePermissions: (() -> Unit) -> Unit,
    onNavigateBack: (() -> Unit)? = null
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
        // Navigation Back Button (when embedded from Gateway)
        // =====================================================================
        if (onNavigateBack != null) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("← Back to BLE Bridge Gateway", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

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
                                    "Xiaomi Cloud (UID: ${accountSession!!.userId})"
                                } else if (activeCred != null) {
                                    "Loaded Key: ${activeCred!!.name}"
                                } else {
                                    "Xiaomi Cloud Not Logged In"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (activeCred != null) {
                                    "Device: ${activeCred!!.mac} · Token: ${if (activeCred!!.hasValidToken) "Ready" else "Not configured"}"
                                } else {
                                    "Log in to auto-retrieve charger MAC, Token & BLE Key"
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
                        Text(if (accountSession != null || activeCred != null) "Credentials" else "Sign In", fontSize = 12.sp)
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
                                Text(if (isConnected) "Disconnect Charger" else "Connect Charger", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { bleManager.refreshAllTelemetryNow() },
                            enabled = authState == CuktechAuthState.AUTHENTICATED
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh", fontSize = 12.sp)
                        }

                        IconButton(
                            onClick = {
                                bleManager.disconnectCharger()
                                bleManager.credentialStore.logoutAndWipeAll()
                                Toast.makeText(context, "Xiaomi session & local keys wiped securely", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "Wipe credentials",
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
                            text = "TOTAL OUTPUT POWER",
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
                            text = "RSSI: ${devInfo.rssi} dBm · Quality ${devInfo.connectionQualityScore}%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "FW: ${devInfo.firmwareVersion} · Model: ${devInfo.model}",
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
            text = "4-Port Live Telemetry & Control (USB-C1 / C2 / C3 / USB-A)",
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
                        text = "Live Power Waveform (W)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Samples: ${chartHistory.size}",
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
                    text = "Charger Hardware Configuration (MIoT SIID=2)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                // Scene Mode (PIID 5)
                Text("Power Allocation Mode (PIID 5):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Screen Sleep Timeout (PIID 6):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Fast-Charge Protocol Extensions (PIID 21):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        label = { Text("UFCS") }
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
                    title = "USB-A Low Current Mode (PIID 15)",
                    subtitle = "For earbuds, fitness bands and low-current IoT accessories",
                    checked = settings.usbALowCurrent,
                    onCheckedChange = { bleManager.setUsbALowCurrent(it) }
                )
                SettingSwitchRow(
                    title = "Idle Screen Off (PIID 19)",
                    subtitle = "Automatically turns off charger screen when no load is detected",
                    checked = settings.idleScreenOff,
                    onCheckedChange = { bleManager.setIdleScreenOff(it) }
                )
                SettingSwitchRow(
                    title = "Screen Orientation Lock (PIID 20)",
                    subtitle = "Locks the charger's physical screen orientation",
                    checked = settings.orientationLock,
                    onCheckedChange = { bleManager.setOrientationLock(it) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Language (PIID 13)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                        text = "Charging Session Statistics (Wh)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                val file = bleManager.persistence.exportSessionsAndHistoryCsv()
                                Toast.makeText(context, "Exported CSV: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Export CSV", fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = {
                                bleManager.persistence.clearSessions()
                                Toast.makeText(context, "Cleared session history", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Clear", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (activeSessions.isEmpty() && completedSessions.isEmpty()) {
                    Text(
                        text = "No charging sessions recorded yet. Trapezoidal Wh integration begins automatically whenever port output ≥ 0.5W.",
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
                                    Text("${s.portLabel} · Charging (${s.protocolUsed})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "Duration: ${s.durationSeconds / 60}m ${s.durationSeconds % 60}s · Peak: ${"%.1f".format(s.peakWatts)}W · Avg: ${"%.1f".format(s.avgWatts)}W",
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
                                    text = "Duration: ${s.durationSeconds / 60}m · Peak: ${"%.1f".format(s.peakWatts)}W · Avg: ${"%.1f".format(s.avgWatts)}W",
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
                    text = "Shizuku, Tasker Automation & Cloud Bridge",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Text(
                    text = "Shizuku: ${shizukuStatus.providerPackage} · Battery Whitelist: ${if (shizukuStatus.batteryWhitelisted) "Active" else "Disabled"}",
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
                        Text("Apply Doze Whitelist", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                shizukuCompanion.runBluetoothStackDiagnostics()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("BLE Stack Diagnostics", fontSize = 11.sp)
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
                        Text(if (showCloudConfig) "Hide Bemfa / HA Config" else "Configure Bemfa / HA")
                    }
                    TextButton(onClick = { showTaskerDocs = !showTaskerDocs }) {
                        Text(if (showTaskerDocs) "Hide Tasker Docs" else "View Tasker Broadcast API")
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
                                Tasker / ADB Broadcast Intent API (Package: com.cramqin.blecuktech10):
                                • Connect Charger:
                                  Action: com.cramqin.blecuktech10.CONNECT_CHARGER
                                  Extra (optional): mac:AA:BB:CC:DD:EE:FF
                                • Disconnect:
                                  Action: com.cramqin.blecuktech10.DISCONNECT_CHARGER
                                • Toggle Port Power (port=1..4, enabled=true/false):
                                  Action: com.cramqin.blecuktech10.SET_PORT_STATE
                                • Set Scene Mode (mode=1..4):
                                  Action: com.cramqin.blecuktech10.SET_SCENE_MODE
                                • Set Port Charge Limit (port=1..4, wh_target=30.0, mode=once/always):
                                  Action: com.cramqin.blecuktech10.SET_CHARGE_LIMIT
                                • Receive Live Telemetry (Tasker Intent Received):
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
                Toast.makeText(context, "Successfully synced ${list.size} device credentials", Toast.LENGTH_SHORT).show()
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
                        text = if (port.enabled) "ENABLED" else "DISABLED",
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
                MetricColumn("Voltage (V)", "%.2f V".format(port.voltageV), accentColor)
                MetricColumn("Current (A)", "%.2f A".format(port.currentA), accentColor)
                MetricColumn("Power (W)", "%.2f W".format(port.powerW), accentColor)
                MetricColumn("Energy (Wh)", "%.2f Wh".format(port.cumulativeWh), MaterialTheme.colorScheme.onSurface)
            }

            port.pdoCaps?.let { pdo ->
                Text(
                    text = "Negotiated PDO: ${pdo.formatSummary()}",
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
                            text = "Energy Cutoff (${if (chargeLimit.mode == "always") "Always" else "Once"}): ${"%.2f".format(used)} / ${"%.1f".format(chargeLimit.whTarget)} Wh",
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
                    Text("Timer:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        text = if (chargeLimit?.enabled == true) "Limit ${"%.0f".format(chargeLimit.whTarget)}Wh" else "Energy Limit",
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
        title = { Text("Port $portId Energy Cutoff Protection (Wh)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "When cumulative Wh on this port reaches the target threshold, power will automatically cut off via PIID 16.",
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = whInput,
                    onValueChange = { whInput = it },
                    label = { Text("Target Energy Limit (Wh)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "once",
                        onClick = { mode = "once" },
                        label = { Text("One-Time (once)") }
                    )
                    FilterChip(
                        selected = mode == "always",
                        onClick = { mode = "always" },
                        label = { Text("Always Active") }
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
                Text("Enable Limit")
            }
        },
        dismissButton = {
            Row {
                if (existingLimit != null) {
                    TextButton(onClick = { onConfirm(0.0, mode, false) }) {
                        Text("Disable Limit", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
            Text("Bemfa Cloud Voice Bridge (XiaoAI / Siri · ${if (bemfaConnected) "Connected" else "Disconnected"})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Switch(checked = bemfaEn, onCheckedChange = { bemfaEn = it })
        }
        OutlinedTextField(
            value = bemfaUid,
            onValueChange = { bemfaUid = it },
            label = { Text("Bemfa Secret UID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = bemfaTopic,
            onValueChange = { bemfaTopic = it },
            label = { Text("Bemfa Topic (e.g. cuktech10002)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Home Assistant Webhook Telemetry Push", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
            Text("Save Cloud Bridge Settings")
        }
    }
}
