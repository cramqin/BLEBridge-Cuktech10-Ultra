package com.example.ui.cuktech

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import com.example.BleBridgeService
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.foundation.BorderStroke
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
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.PowerChartPoint
import com.example.cuktech.diagnostics.BleDiagnostics
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.integrations.CloudIntegrations
import com.example.cuktech.integrations.IntegrationConfig
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetry
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun CuktechDashboardScreen(
    bleManager: CuktechBleManager,
    cloudClient: XiaomiCloudClient,
    bleDiagnostics: BleDiagnostics,
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

    val diagStatus by bleDiagnostics.status.collectAsState()
    val integrationCfg by cloudIntegrations.config.collectAsState()
    val bemfaConnected by cloudIntegrations.bemfaConnected.collectAsState()

    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var chargeLimitDialogPort by remember { mutableStateOf<Int?>(null) }
    var showTaskerDocs by remember { mutableStateOf(false) }
    var showCloudConfig by remember { mutableStateOf(false) }
    var showMiHomeWarningDialog by remember { mutableStateOf(false) }
    var showSoftRestartDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var selectedExportTimeframe by remember { mutableStateOf(CuktechPersistence.ExportTimeframe.ALL_TIME) }
    var selectedExportScope by remember { mutableStateOf(CuktechPersistence.ExportScope.ALL) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var selectedClearHistoryOption by remember { mutableStateOf(CuktechPersistence.ClearHistoryOption.KEEP_ACTIVE) }
    var showScreenOnTimeoutDialog by remember { mutableStateOf(false) }
    var pendingScreenTimeoutCode by remember { mutableStateOf(4) }

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
                            onClick = {
                                bleManager.disconnectCharger()
                                onNavigateBack?.invoke()
                            },
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
                        val activeModeLabel = when (settings.sceneMode) {
                            1 -> "AI Mode"
                            2 -> "Hybrid Mode"
                            3 -> "Single Port Max"
                            4 -> "Dual Port Mode"
                            else -> "AI Mode"
                        }

                        // 1. Hardware Visual Panel (Mi Home Style Charger Graphic)
                        CuktechChargerVisual(
                            portsMap = portsMap,
                            modeLabel = activeModeLabel,
                            onPortClick = { selectedTab = 1 },
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        )

                        // 2. Scenarios Mode Selector (Mi Home Style Circular Chips)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
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
                                    Text("Scenarios", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(activeModeLabel, fontSize = 12.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    val modes = listOf(
                                        Triple(1, R.drawable.main_card_scene_icon_ai, "AI Mode"),
                                        Triple(2, R.drawable.main_card_scene_icon_apple, "Hybrid"),
                                        Triple(3, R.drawable.main_card_scene_icon_single, "Single"),
                                        Triple(4, R.drawable.main_card_scene_icon_balance, "Dual")
                                    )
                                    modes.forEach { (code, iconRes, label) ->
                                        val isSelected = settings.sceneMode == code
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.clickable { bleManager.setSceneMode(code) }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 0.dp,
                                                        color = if (isSelected) Color(0xFF00E5FF) else Color.Transparent,
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Image(
                                                    painter = painterResource(id = iconRes),
                                                    contentDescription = label,
                                                    modifier = Modifier.size(34.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

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
                                            text = "CURRENT POWER",
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
                                        if (devInfo.firmwareVersion.isNotBlank() && devInfo.firmwareVersion != "--" && !devInfo.firmwareVersion.contains('\uFFFD')) {
                                            Text(
                                                text = "FW: ${devInfo.firmwareVersion}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                // Multi-color Port Breakdown Legend (Mi Home Palette)
                                val p1 = portsMap[1]?.powerW ?: 0.0
                                val p2 = portsMap[2]?.powerW ?: 0.0
                                val p3 = portsMap[3]?.powerW ?: 0.0
                                val p4 = portsMap[4]?.powerW ?: 0.0
                                val sumW = p1 + p2 + p3 + p4

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF7A00)))
                                        Spacer(Modifier.width(4.dp))
                                        Text("C1: ${if (p1 > 0.1) "%.1fW".format(p1) else "--"}", fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3B82F6)))
                                        Spacer(Modifier.width(4.dp))
                                        Text("C2: ${if (p2 > 0.1) "%.1fW".format(p2) else "--"}", fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00E5FF)))
                                        Spacer(Modifier.width(4.dp))
                                        Text("C3: ${if (p3 > 0.1) "%.1fW".format(p3) else "--"}", fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFBBF24)))
                                        Spacer(Modifier.width(4.dp))
                                        Text("A: ${if (p4 > 0.1) "%.1fW".format(p4) else "--"}", fontSize = 11.sp)
                                    }
                                }

                                // Stacked Power Proportion Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Power Proportion",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (sumW > 0.1) {
                                        Text(
                                            text = "C1: ${(p1/sumW*100).toInt()}% · C2: ${(p2/sumW*100).toInt()}% · C3: ${(p3/sumW*100).toInt()}% · A: ${(p4/sumW*100).toInt()}%",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    if (sumW > 0.1) {
                                        val f1 = (p1 / sumW).toFloat()
                                        val f2 = (p2 / sumW).toFloat()
                                        val f3 = (p3 / sumW).toFloat()
                                        val f4 = (p4 / sumW).toFloat()
                                        if (f1 > 0f) Box(Modifier.fillMaxHeight().weight(f1).background(Color(0xFFFF7A00)))
                                        if (f2 > 0f) Box(Modifier.fillMaxHeight().weight(f2).background(Color(0xFF3B82F6)))
                                        if (f3 > 0f) Box(Modifier.fillMaxHeight().weight(f3).background(Color(0xFF00E5FF)))
                                        if (f4 > 0f) Box(Modifier.fillMaxHeight().weight(f4).background(Color(0xFFFBBF24)))
                                    }
                                }

                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            bleManager.gotoScreen(3)
                                            Toast.makeText(context, "Switching charger LCD to AI Temperature Control screen (Page 3)", Toast.LENGTH_SHORT).show()
                                        }
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Thermostat,
                                            contentDescription = null,
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "AI Temperature Control",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (totalWatts > 100) "OPTIMAL" else "EXCELLENT",
                                                color = Color(0xFF10B981),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Show on LCD ➔",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
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

                    Text(
                        text = "Individual Port Controls & Protocols",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
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

                            // 2. Screen Power & Sleep Timeout (PIID 6)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Charger Screen Display",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = if (settings.screenTimeout == 0) "Status: Force Off (Standby)" else "Status: Active (${if (settings.screenTimeout == 4) "Always On" else "Auto-Sleep"})",
                                            fontSize = 11.sp,
                                            color = if (settings.screenTimeout == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            if (settings.screenTimeout == 0) {
                                                showScreenOnTimeoutDialog = true
                                            } else {
                                                bleManager.forceScreenPower(false)
                                            }
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (settings.screenTimeout == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (settings.screenTimeout == 0) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (settings.screenTimeout == 0) "Turn Screen ON" else "Force Screen OFF",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val timeoutList = listOf(
                                        0 to "Off (0)",
                                        4 to "Always On",
                                        5 to "1 min",
                                        1 to "5 min",
                                        2 to "10 min",
                                        3 to "30 min"
                                    )
                                    timeoutList.forEach { (code, label) ->
                                        FilterChip(
                                            selected = settings.screenTimeout == code,
                                            onClick = {
                                                if (code == 0) {
                                                    bleManager.forceScreenPower(false)
                                                } else {
                                                    bleManager.forceScreenPower(true, code)
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (settings.screenTimeout == code) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
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
                                title = "USB-A Low Current (Keep On 2-4 Hours)",
                                subtitle = "Prevents auto-cutoff for low-draw devices (earbuds, fitness bands, smartwatches)",
                                checked = settings.usbALowCurrent,
                                onCheckedChange = { bleManager.setUsbALowCurrent(it) }
                            )
                            SettingSwitchRow(
                                title = "Idle Screen Off",
                                subtitle = "Turns off charger's color LCD when all ports are idle",
                                checked = settings.idleScreenOff,
                                onCheckedChange = { bleManager.setIdleScreenOff(it) }
                            )
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Screen Orientation (Degrees & G-Sensor)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Auto-rotates with G-sensor or lock to specific angle (0°, 90°, 180°, 270°)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val currentDeg = settings.orientationDegrees
                                    val isAuto = !settings.orientationLock && currentDeg == -1
                                    FilterChip(
                                        selected = isAuto,
                                        onClick = { bleManager.setOrientation(-1) },
                                        label = {
                                            Text(
                                                text = "🔄 Auto",
                                                fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 11.sp
                                            )
                                        }
                                    )
                                    listOf(
                                        0 to "0° (Normal)",
                                        90 to "90° (Vertical)",
                                        180 to "180° (Invert)",
                                        270 to "270° (Vertical)"
                                    ).forEach { (deg, label) ->
                                        val isSelected = (settings.orientationLock && currentDeg == deg)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { bleManager.setOrientation(deg) },
                                            label = {
                                                Text(
                                                    text = label,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 11.sp
                                                )
                                            }
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Charging Session History",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "Energy (Wh) & power records",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { showExportDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Export CSV", fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = { showClearHistoryDialog = true },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
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

                    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
                    var isBatteryIgnored by remember {
                        mutableStateOf(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                            } else true
                        )
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                                } else true
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    // 1. Background Keepalive & Battery Optimization Card
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
                                text = "Keeps BLE gateway connected when screen turns off or when you switch apps.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Surface(
                                color = if (isBatteryIgnored) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isBatteryIgnored) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isBatteryIgnored) Color(0xFF10B981) else Color(0xFFF59E0B),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isBatteryIgnored) {
                                            "Battery optimizations ignored (Keepalive active)"
                                        } else {
                                            "Battery optimization active (May be throttled by OS)"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isBatteryIgnored) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (isBatteryIgnored) {
                                        Toast.makeText(context, "Battery optimizations are already disabled for this app.", Toast.LENGTH_SHORT).show()
                                    }
                                    bleDiagnostics.requestIgnoreBatteryOptimizations()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBatteryIgnored) "Battery Settings (Already Set)" else "Ignore Battery Optimization",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // 2. Bluetooth Low Energy Diagnostics Card
                    val hasDiagOutput = diagStatus.lastDiagnosticOutput.isNotBlank()
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
                                text = "Bluetooth Low Energy Diagnostics",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Inspect hardware adapter state, BLE connection parameters, and MiOT protocol status.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = {
                                    if (hasDiagOutput) {
                                        bleDiagnostics.clearDiagnosticOutput()
                                    } else {
                                        scope.launch {
                                            bleDiagnostics.runBluetoothStackDiagnostics()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (hasDiagOutput) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (hasDiagOutput) "Hide BLE Diagnostics" else "Run BLE Diagnostics",
                                    fontSize = 12.sp
                                )
                            }

                            if (hasDiagOutput) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = diagStatus.lastDiagnosticOutput,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. Tasker & Automation Integration Card
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
                                text = "Tasker & Automation Integration",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Tasker Action Plugin Ready", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(
                                            "In Tasker: Add Action ➔ Plugin ➔ CUKTECH 10 Ultra",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { showTaskerDocs = !showTaskerDocs }) {
                                    Text(if (showTaskerDocs) "Hide Broadcast Intent API" else "View Broadcast Intent API (Send Intent)", fontSize = 11.sp)
                                }
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

                    // 4. Cloud Bridge Card
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Cloud Voice & Automation Bridges",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Bemfa (XiaoAI/Siri) & Home Assistant webhooks",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { showCloudConfig = !showCloudConfig }) {
                                    Text(if (showCloudConfig) "Hide" else "Configure", fontSize = 12.sp)
                                }
                            }

                            if (showCloudConfig) {
                                CloudIntegrationConfigPanel(
                                    config = integrationCfg,
                                    bemfaConnected = bemfaConnected,
                                    onSave = { cloudIntegrations.updateConfig(it) }
                                )
                            }
                        }
                    }

                    // 5. Charger Physical LCD Language & Screen Orientation Card
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Charger Display Language",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Physical LCD screen language on the CUKTECH 10 Ultra",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CuktechProtocol.LANGUAGE_OPTIONS.forEach { (code, name) ->
                                    val isSelected = (settings.language == code)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { bleManager.setLanguage(code) },
                                        label = {
                                            Text(
                                                text = name,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 12.sp
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Screen Orientation (Gravity Sensor)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Auto-rotates 180° when upside-down in wall outlets",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val currentDeg = settings.orientationDegrees
                                val isAuto = !settings.orientationLock && currentDeg == -1
                                FilterChip(
                                    selected = isAuto,
                                    onClick = { bleManager.setOrientation(-1) },
                                    label = {
                                        Text(
                                            text = "🔄 Auto (G-Sensor)",
                                            fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 11.sp
                                        )
                                    }
                                )
                                listOf(
                                    0 to "0° (Normal)",
                                    90 to "90° (Vertical)",
                                    180 to "180° (Invert)",
                                    270 to "270° (Vertical)"
                                ).forEach { (deg, label) ->
                                    val isSelected = (settings.orientationLock && currentDeg == deg)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { bleManager.setOrientation(deg) },
                                        label = {
                                            Text(
                                                text = label,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 11.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 6. Force Soft Port Reset & ADC Assistant Card
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Port Reset & ADC Assistant",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Re-negotiates USB-PD contracts or switches physical LCD to ADC load distribution screen",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "PHYSICAL LCD SCREEN QUICK-SWITCH",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        bleManager.gotoScreen(1)
                                        Toast.makeText(context, "Switched charger LCD to Total Power (Page 1)", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text("⚡ Total Power (P1)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        bleManager.gotoScreen(2)
                                        Toast.makeText(context, "Switched charger LCD to ADC Assistant (Page 2)", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text("🤖 ADC Assistant (P2)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        bleManager.gotoScreen(3)
                                        Toast.makeText(context, "Switched charger LCD to AI Temp Control (Page 3)", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text("🌡️ AI Temp (P3)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        bleManager.gotoScreen(4)
                                        Toast.makeText(context, "Switched charger LCD to Ports Breakdown (Page 4)", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text("📊 Ports Detail (P4)", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        bleManager.gotoScreen(5)
                                        Toast.makeText(context, "Switched charger LCD to System Info (Page 5)", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text("ℹ️ System Info (P5)", fontSize = 11.sp) }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        bleManager.invokeAdcAssistant {
                                            scope.launch {
                                                Toast.makeText(context, "ADC Assistant activated on charger LCD & ports re-negotiated.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors()
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Run ADC Setup", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { showSoftRestartDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Soft Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 6. Charger Firmware & Xiaomi Mi Home Updates Card
                    val miHomePkg = "com.xiaomi.smarthome"
                    val miHomeLaunchIntent = remember { context.packageManager.getLaunchIntentForPackage(miHomePkg) }
                    val isMiHomeInstalled = (miHomeLaunchIntent != null)
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
                                text = "Charger Firmware & Official Updates",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Official firmware upgrades for the CUKTECH 10 Ultra GaN charger require Xiaomi's proprietary Mi Home cloud OTA infrastructure.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = {
                                    if (isMiHomeInstalled) {
                                        showMiHomeWarningDialog = true
                                    } else {
                                        try {
                                            val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$miHomePkg")).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(playIntent)
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Xiaomi Mi Home app is not installed.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6900))
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isMiHomeInstalled) "Open in Xiaomi Mi Home" else "Install Mi Home (Play Store)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
    if (showMiHomeWarningDialog) {
        val miHomePkg = "com.xiaomi.smarthome"
        val miHomeLaunchIntent = context.packageManager.getLaunchIntentForPackage(miHomePkg)
        AlertDialog(
            onDismissRequest = { showMiHomeWarningDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B)) },
            title = { Text("Switch to Xiaomi Mi Home?") },
            text = {
                Text("Xiaomi Mi Home requires exclusive Bluetooth access to check for and install official charger firmware updates.\n\nOpening Mi Home will safely disconnect the BLE Bridge gateway and stop background server tasks.\n\nWhen you return to this app, you can start the server and reconnect with 1 tap.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMiHomeWarningDialog = false
                        bleManager.disconnectCharger()
                        BleBridgeService.getInstance()?.closeGattClean()
                        val stopIntent = Intent(context, BleBridgeService::class.java).apply {
                            action = BleBridgeService.ACTION_STOP_SERVER
                        }
                        context.startService(stopIntent)
                        onNavigateBack?.invoke()
                        val intent = miHomeLaunchIntent ?: context.packageManager.getLaunchIntentForPackage(miHomePkg)
                        if (intent != null) {
                            context.startActivity(intent)
                        } else {
                            try {
                                val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$miHomePkg")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(storeIntent)
                            } catch (e: Exception) {
                                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$miHomePkg")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(webIntent)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6900))
                ) {
                    Text("Disconnect & Open Mi Home")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMiHomeWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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

    if (showSoftRestartDialog) {
        AlertDialog(
            onDismissRequest = { showSoftRestartDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Force Soft Port Reset?") },
            text = {
                Text("This will momentarily cycle all 4 port power switches off and on (0x00 -> 0x0F) to trigger fresh USB-PD, PPS, and UFCS protocol negotiation.\n\n• Discharges DC-DC converters cleanly\n• Safe for laptops, phones, and power banks\n• No physical wear and no need to unplug from AC outlet")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSoftRestartDialog = false
                        bleManager.forceSoftRestart {
                            scope.launch {
                                Toast.makeText(context, "All ports reset and re-negotiated successfully.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Execute Soft Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSoftRestartDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Export Telemetry History") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "TIMEFRAME FILTER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CuktechPersistence.ExportTimeframe.values().forEach { tf ->
                            FilterChip(
                                selected = (selectedExportTimeframe == tf),
                                onClick = { selectedExportTimeframe = tf },
                                label = { Text(tf.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Text(
                        text = "DATA SCOPE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CuktechPersistence.ExportScope.values().forEach { sc ->
                            FilterChip(
                                selected = (selectedExportScope == sc),
                                onClick = { selectedExportScope = sc },
                                label = { Text(sc.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Text(
                        text = "CSV file will be saved to:\nDownloads/cuktech10/history/",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        val activeMin = activeSessions.values.minOfOrNull { (it.startTimeSec * 1000).toLong() } ?: 0L
                        val file = bleManager.persistence.exportSessionsAndHistoryCsv(
                            timeframe = selectedExportTimeframe,
                            scope = selectedExportScope,
                            currentSessionStartMs = activeMin
                        )
                        Toast.makeText(context, "Saved: ${file.name}\nIn: Downloads/cuktech10/history", Toast.LENGTH_LONG).show()
                    }
                ) {
                    Text("Export to CSV")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear Charging Records") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose which charging records and telemetry data to delete:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    CuktechPersistence.ClearHistoryOption.values().forEach { option ->
                        val isSelected = (selectedClearHistoryOption == option)
                        Surface(
                            onClick = { selectedClearHistoryOption = option },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedClearHistoryOption = option },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = option.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryDialog = false
                        bleManager.persistence.clearHistory(selectedClearHistoryOption)
                        Toast.makeText(context, "History cleared: ${selectedClearHistoryOption.label}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Records")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (showScreenOnTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showScreenOnTimeoutDialog = false },
            icon = { Icon(Icons.Default.LightMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Turn Screen On") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Select how long the charger display should stay illuminated before sleeping:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val options = listOf(
                        4 to ("Always On (Default)" to "Display stays illuminated continuously"),
                        5 to ("1 Minute" to "Turns off display after 1 min of inactivity"),
                        1 to ("5 Minutes" to "Turns off display after 5 min of inactivity"),
                        2 to ("10 Minutes" to "Turns off display after 10 min of inactivity"),
                        3 to ("30 Minutes" to "Turns off display after 30 min of inactivity")
                    )

                    options.forEach { (code, pair) ->
                        val (label, desc) = pair
                        val isSelected = (pendingScreenTimeoutCode == code)
                        Surface(
                            onClick = { pendingScreenTimeoutCode = code },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { pendingScreenTimeoutCode = code }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(text = desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showScreenOnTimeoutDialog = false
                        bleManager.forceScreenPower(true, pendingScreenTimeoutCode)
                        Toast.makeText(context, "Screen turned ON (Timeout: ${CuktechProtocol.SCREEN_TIMEOUTS[pendingScreenTimeoutCode] ?: "Always On"})", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Turn On Screen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenOnTimeoutDialog = false }) {
                    Text("Cancel")
                }
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Bemfa Section
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bemfa Cloud Voice Bridge", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("XiaoAI & Siri Control", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(
                                color = if (bemfaConnected) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (bemfaConnected) "Connected" else "Disconnected",
                                    fontSize = 10.sp,
                                    color = if (bemfaConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Switch(checked = bemfaEn, onCheckedChange = { bemfaEn = it })
                }
                if (bemfaEn) {
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
                }
            }
        }

        // Home Assistant Section
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Home Assistant Webhook", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Pushes live wattage & energy telemetry", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = haEn, onCheckedChange = { haEn = it })
                }
                if (haEn) {
                    OutlinedTextField(
                        value = haUrl,
                        onValueChange = { haUrl = it },
                        label = { Text("HA Webhook URL (http://.../api/webhook/...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Save Cloud Settings")
        }
    }
}
