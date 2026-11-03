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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
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

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabItems = listOf(
        Triple("Live", Icons.Default.Bolt, 0),
        Triple("Ports", Icons.Default.Settings, 1),
        Triple("Stats", Icons.Default.Refresh, 2),
        Triple("Setup", Icons.Default.CloudDone, 3)
    )

    val scrollTab0 = rememberScrollState()
    val scrollTab1 = rememberScrollState()
    val scrollTab2 = rememberScrollState()
    val scrollTab3 = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // =====================================================================
        // PINNED CONNECTION STATUS BAR & CATEGORIZATION TABS (Never scrolls off)
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val statusDotColor = when (authState) {
                        CuktechAuthState.AUTHENTICATED -> Color(0xFF10B981)
                        CuktechAuthState.AUTH_FAILED -> Color(0xFFEF4444)
                        CuktechAuthState.DISCONNECTED -> Color.Gray
                        else -> Color(0xFFF59E0B)
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val displayMac = if (devInfo.macAddress.isNotBlank() && devInfo.macAddress != "--") {
                        devInfo.macAddress
                    } else if (!activeCred?.mac.isNullOrBlank()) {
                        activeCred?.mac ?: ""
                    } else {
                        "Disconnected"
                    }
                    Column {
                        Text(
                            text = devInfo.name.ifBlank { "CUKTECH 10 Ultra" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "$displayMac · ${authState.label}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { bleManager.refreshAllTelemetryNow() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Telemetry",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (authState == CuktechAuthState.AUTHENTICATED) {
                        Spacer(modifier = Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = { bleManager.disconnectCharger() },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Disconnect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                tabItems.forEach { (label, icon, idx) ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }

        // =====================================================================
        // INDEPENDENT SCROLLABLE TAB CONTENTS
        // =====================================================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> {
                    // =============================================================
                    // TAB 0: LIVE TELEMETRY & HERO POWER
                    // =============================================================
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollTab0)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Total Output Power Hero Card (Streamlined, no wasted space)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
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
                                                fontSize = 36.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "W / 120W max",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 6.dp)
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "RSSI: ${devInfo.rssi} dBm",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "FW: ${devInfo.firmwareVersion}",
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

                    // Four Individual Port Telemetry Tiles
                    Text(
                        text = "Individual Port Live Telemetry (USB-C1 / C2 / C3 / USB-A)",
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

                    // Real-Time Power Waveform Chart
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
                }
            }

            1 -> {
                // =============================================================
                // TAB 1: CONTROLS & HARDWARE SETTINGS
                // =============================================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollTab1)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                text = "Hardware Power & Display Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )

                            // 1. Scene Mode (PIID 5) - Clean Column with RadioButton and Descriptions
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Power Allocation Mode",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Select how 120W total power is distributed across ports:",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val modeDescriptions = mapOf(
                                    1 to Pair("Dynamic Allocation", "Auto-balances power between USB-C and USB-A"),
                                    2 to Pair("Laptop Priority", "Guarantees up to 100W for USB-C1 first"),
                                    3 to Pair("Single Port Max", "Maximized burst wattage for one device")
                                )
                                CuktechProtocol.SCENE_MODES.forEach { (code, label) ->
                                    val desc = modeDescriptions[code]?.second ?: ""
                                    Surface(
                                        color = if (settings.sceneMode == code) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { bleManager.setSceneMode(code) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = settings.sceneMode == code,
                                                onClick = { bleManager.setSceneMode(code) },
                                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                if (desc.isNotBlank()) {
                                                    Text(desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider()

                            // 2. Screen Sleep Timeout (PIID 6) - 4-segment equal Row
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Screen Sleep Timeout",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val timeoutLabels = mapOf(
                                        1 to "Always On",
                                        2 to "60s",
                                        3 to "5 min",
                                        4 to "60 min"
                                    )
                                    CuktechProtocol.SCREEN_TIMEOUTS.forEach { (code, _) ->
                                        val shortLabel = timeoutLabels[code] ?: code.toString()
                                        FilterChip(
                                            selected = settings.screenTimeout == code,
                                            onClick = { bleManager.setScreenTimeout(code) },
                                            label = {
                                                Text(
                                                    text = shortLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (settings.screenTimeout == code) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    maxLines = 1
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider()

                            // 3. Fast-Charge Protocols (PIID 21) - 2x2 Clean Grid
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Fast-Charge Protocol Extensions",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = settings.pdEnabled,
                                        onClick = { bleManager.setFastChargeProtocols(pd = !settings.pdEnabled) },
                                        label = { Text("PD 3.0 (100W)", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = settings.ppsEnabled,
                                        onClick = { bleManager.setFastChargeProtocols(pps = !settings.ppsEnabled) },
                                        label = { Text("PPS / Mi Turbo", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = settings.ufcsEnabled,
                                        onClick = { bleManager.setFastChargeProtocols(ufcs = !settings.ufcsEnabled) },
                                        label = { Text("UFCS Fusion", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = settings.scpEnabled,
                                        onClick = { bleManager.setFastChargeProtocols(scp = !settings.scpEnabled) },
                                        label = { Text("SCP Fast Charge", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            HorizontalDivider()

                            // 4. Boolean Switches
                            SettingSwitchRow(
                                title = "USB-A Low Current Mode (PIID 15)",
                                subtitle = "Prevents auto-cutoff for earbuds, fitness bands and smartwatches",
                                checked = settings.usbALowCurrent,
                                onCheckedChange = { bleManager.setUsbALowCurrent(it) }
                            )
                            SettingSwitchRow(
                                title = "Idle Screen Off (PIID 19)",
                                subtitle = "Turns off charger's color LCD when all ports are idle",
                                checked = settings.idleScreenOff,
                                onCheckedChange = { bleManager.setIdleScreenOff(it) }
                            )
                            SettingSwitchRow(
                                title = "Screen Orientation Lock (PIID 20)",
                                subtitle = "Locks the charger's physical screen orientation",
                                checked = settings.orientationLock,
                                onCheckedChange = { bleManager.setOrientationLock(it) }
                            )

                            HorizontalDivider()

                            // 5. Charger LCD Screen Language (PIID 13)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Charger LCD Screen Language",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Changes the language displayed on the charger's physical color LCD screen.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CuktechProtocol.LANGUAGE_OPTIONS.forEach { (code, label) ->
                                        FilterChip(
                                            selected = settings.language == code,
                                            onClick = { bleManager.setLanguage(code) },
                                            label = {
                                                Text(
                                                    text = label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (settings.language == code) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    maxLines = 1
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // =============================================================
                // TAB 2: CHARGING SESSIONS & HISTORICAL DATA
                // =============================================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollTab2)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                }
            }

            3 -> {
                // =============================================================
                // TAB 3: CLOUD, TASKER & AUTOMATION INTEGRATIONS
                // =============================================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollTab3)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Search & Pair Cuktech 10 Ultra button
                    Button(
                        onClick = {
                            onRequestBlePermissions {
                                showPairingDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SEARCH & PAIR CUKTECH 10 ULTRA",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    // Xiaomi Account & Stored Credentials Card
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
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = if (activeCred?.hasValidToken == true) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (accountSession != null) {
                                                "Xiaomi Cloud (${accountSession!!.userId})"
                                            } else if (activeCred != null) {
                                                "Key: ${activeCred!!.name}"
                                            } else {
                                                "Xiaomi Cloud Not Logged In"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (activeCred != null) {
                                                "${activeCred!!.mac} · ${if (activeCred!!.hasValidToken) "Ready" else "No Token"}"
                                            } else {
                                                "Sign in to auto-retrieve charger keys"
                                            },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                FilledTonalButton(
                                    onClick = { showXiaomiLoginDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (accountSession != null || activeCred != null) "Account" else "Sign In", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                        }
                    }

                    // Background Keepalive & Battery Whitelist Card
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
                                text = "Background Keepalive & System Integration",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Text(
                                text = "Prevents Android from killing the background BLE server or dropping Bluetooth connection when your phone screen turns off.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { shizukuCompanion.requestIgnoreBatteryOptimizations() },
                                    modifier = Modifier.weight(1.3f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Ignore Battery Optimization", fontSize = 11.sp, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            shizukuCompanion.runBluetoothStackDiagnostics()
                                        }
                                    },
                                    modifier = Modifier.weight(0.9f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("BLE Diagnostics", fontSize = 11.sp, maxLines = 1)
                                }
                            }

                            if (shizukuStatus.lastDiagnosticOutput.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Diagnostic Output", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            TextButton(
                                                onClick = { shizukuCompanion.clearDiagnosticOutput() },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Text("Dismiss", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        Text(
                                            text = shizukuStatus.lastDiagnosticOutput,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 6,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(onClick = { showCloudConfig = !showCloudConfig }) {
                                    Text(if (showCloudConfig) "Hide Cloud Bridge (Bemfa/HA)" else "Configure Cloud Bridge (Bemfa/HA)")
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
