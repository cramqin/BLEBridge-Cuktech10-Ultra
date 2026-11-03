@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.ui.cuktech

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetry
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CuktechDashboardScreen(
    bleManager: CuktechBleManager,
    cloudClient: XiaomiCloudClient,
    bleDiagnostics: BleDiagnostics,
    cloudIntegrations: CloudIntegrations,
    onRequestBlePermissions: (() -> Unit) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    onTabChanged: ((Int) -> Unit)? = null
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
    val countdownRemainingSeconds by bleManager.countdownRemainingSeconds.collectAsState()

    val accountSession by bleManager.credentialStore.session.collectAsState()
    val savedDevices by bleManager.credentialStore.devices.collectAsState()
    val activeCred by bleManager.credentialStore.activeDevice.collectAsState()

    val diagStatus by bleDiagnostics.status.collectAsState()
    val integrationCfg by cloudIntegrations.config.collectAsState()

    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var chargeLimitDialogPort by remember { mutableStateOf<Int?>(null) }
    var closureTimeDialogPort by remember { mutableStateOf<Int?>(null) }
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
    var selectedSessionDetail by remember { mutableStateOf<com.example.cuktech.energy.ChargeSessionRecord?>(null) }
    var showConsolidatedTable by remember { mutableStateOf(false) }
    var showScenariosDialog by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    LaunchedEffect(pagerState.currentPage) {
        onTabChanged?.invoke(pagerState.currentPage)
    }
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

    val portRequesters = remember { List(4) { BringIntoViewRequester() } }
    var highlightedPortId by remember { mutableStateOf<Int?>(null) }
    var historyPageIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // =====================================================================
        // PINNED CONNECTION STATUS BAR & CATEGORIZATION TABS (Never scrolls off)
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (isLandscape) 2.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 2.dp else 8.dp)
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
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = devInfo.name.ifBlank { "CUKTECH 10 Ultra" },
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isLandscape) 13.sp else 14.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (statusMsg.isNotBlank() && authState != CuktechAuthState.AUTHENTICATED) {
                                statusMsg
                            } else {
                                "$displayMac · ${authState.label}"
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (authState == CuktechAuthState.AUTHENTICATED) {
                                bleManager.refreshAllTelemetryNow()
                            } else {
                                val targetMac = bleManager.lastConnectedMac
                                    ?: activeCred?.mac
                                    ?: savedDevices.firstOrNull()?.mac
                                if (!targetMac.isNullOrBlank()) {
                                    bleManager.connectToCharger(targetMac)
                                } else {
                                    showPairingDialog = true
                                }
                            }
                        },
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
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = {
                                val targetMac = bleManager.lastConnectedMac
                                    ?: activeCred?.mac
                                    ?: savedDevices.firstOrNull()?.mac
                                if (!targetMac.isNullOrBlank()) {
                                    bleManager.connectToCharger(targetMac)
                                } else {
                                    showPairingDialog = true
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Connect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isLandscape) 38.dp else 50.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                tabItems.forEach { (label, icon, idx) ->
                    val isSelected = (pagerState.currentPage == idx)
                    Tab(
                        selected = isSelected,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(idx)
                            }
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (!isLandscape) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                            Text(
                                text = label,
                                fontSize = if (isLandscape) 11.sp else 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            if (authState != CuktechAuthState.AUTHENTICATED) {
                val hasReadyCredentials = savedDevices.any { it.hasValidToken } || activeCred?.hasValidToken == true
                val targetMac = bleManager.lastConnectedMac
                    ?: activeCred?.mac
                    ?: savedDevices.firstOrNull { it.hasValidToken }?.mac
                    ?: savedDevices.firstOrNull()?.mac

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasReadyCredentials) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                        },
                        contentColor = if (hasReadyCredentials) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (hasReadyCredentials) Icons.Default.BluetoothSearching else Icons.Default.Lock,
                                contentDescription = if (hasReadyCredentials) "Disconnected" else "Locked",
                                modifier = Modifier.size(16.dp),
                                tint = if (hasReadyCredentials) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.padding(end = 6.dp)) {
                                Text(
                                    text = if (hasReadyCredentials) {
                                        if (authState == CuktechAuthState.AUTH_FAILED) "Authentication Failed" else "Charger Disconnected"
                                    } else {
                                        "Charger Unauthenticated"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (hasReadyCredentials) {
                                        if (statusMsg.isNotBlank()) statusMsg else "Tap Connect to start live telemetry & controls."
                                    } else {
                                        "Screen controls & live telemetry require Xiaomi token."
                                    },
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (hasReadyCredentials && !targetMac.isNullOrBlank()) {
                            Button(
                                onClick = { bleManager.connectToCharger(targetMac) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Connect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { showXiaomiLoginDialog = true },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("Sign In", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // INDEPENDENT SCROLLABLE TAB CONTENTS (Horizontal Swiping Support)
        // =====================================================================
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
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
                            2 -> "Hybrid"
                            3 -> "Single"
                            4 -> "Dual"
                            else -> "AI Mode"
                        }

                        // 1. Hardware Visual Panel (Mi Home Style Charger Graphic)
                        CuktechChargerVisual(
                            portsMap = portsMap,
                            modeLabel = activeModeLabel,
                            onPortClick = { portId ->
                                scope.launch {
                                    highlightedPortId = portId
                                    pagerState.animateScrollToPage(1)
                                    kotlinx.coroutines.delay(120)
                                    portRequesters.getOrNull(portId - 1)?.bringIntoView()
                                    kotlinx.coroutines.delay(2000)
                                    if (highlightedPortId == portId) highlightedPortId = null
                                }
                            },
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { showScenariosDialog = true }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = activeModeLabel,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.MoreHoriz,
                                            contentDescription = "Expand Scenarios",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
                                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 0.dp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
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
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Total Output Power Hero Card (Mi Home Layout with Integrated Waveform & Port Grid)
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
                                val p1 = portsMap[1]?.powerW ?: 0.0
                                val p2 = portsMap[2]?.powerW ?: 0.0
                                val p3 = portsMap[3]?.powerW ?: 0.0
                                val p4 = portsMap[4]?.powerW ?: 0.0
                                val sumW = p1 + p2 + p3 + p4

                                // 1. Current Power Section
                                Text(
                                    text = "Current Power",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = "%.1f".format(totalWatts),
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.SansSerif,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "W",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                                Text(
                                    text = "Current Power",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Total Power Chart (Cyan)
                                LivePowerChart(
                                    points = chartHistory,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 2.dp))

                                // 2. Port Power Section (2x2 Grid)
                                Text(
                                    text = "Port Power",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val c1Port = portsMap[1]
                                val c2Port = portsMap[2]
                                val c3Port = portsMap[3]
                                val aPort = portsMap[4]

                                val c1Proto = c1Port?.protocolName?.takeIf { it.isNotBlank() && it != "Idle" && it != "--" }?.let { " · $it" } ?: ""
                                val c2Proto = c2Port?.protocolName?.takeIf { it.isNotBlank() && it != "Idle" && it != "--" }?.let { " · $it" } ?: ""
                                val c3Proto = c3Port?.protocolName?.takeIf { it.isNotBlank() && it != "Idle" && it != "--" }?.let { " · $it" } ?: ""
                                val aProto = aPort?.protocolName?.takeIf { it.isNotBlank() && it != "Idle" && it != "--" }?.let { " · $it" } ?: ""

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PortPowerMiniCell(
                                        color = Color(0xFFFF7A00),
                                        portName = "C1",
                                        powerW = c1Port?.powerW ?: 0.0,
                                        subtitle = "${if ((c1Port?.voltageV ?: 0.0) > 0.1) "%.1fV".format(c1Port?.voltageV) else "--V"}  ${if ((c1Port?.currentA ?: 0.0) > 0.01) "%.2fA".format(c1Port?.currentA) else "--A"}$c1Proto",
                                        modifier = Modifier.weight(1f)
                                    )
                                    PortPowerMiniCell(
                                        color = Color(0xFF3B82F6),
                                        portName = "C2",
                                        powerW = c2Port?.powerW ?: 0.0,
                                        subtitle = "${if ((c2Port?.voltageV ?: 0.0) > 0.1) "%.1fV".format(c2Port?.voltageV) else "--V"}  ${if ((c2Port?.currentA ?: 0.0) > 0.01) "%.2fA".format(c2Port?.currentA) else "--A"}$c2Proto",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PortPowerMiniCell(
                                        color = Color(0xFF00E5FF),
                                        portName = "C3",
                                        powerW = c3Port?.powerW ?: 0.0,
                                        subtitle = "${if ((c3Port?.voltageV ?: 0.0) > 0.1) "%.1fV".format(c3Port?.voltageV) else "--V"}  ${if ((c3Port?.currentA ?: 0.0) > 0.01) "%.2fA".format(c3Port?.currentA) else "--A"}$c3Proto",
                                        modifier = Modifier.weight(1f)
                                    )
                                    PortPowerMiniCell(
                                        color = Color(0xFFFBBF24),
                                        portName = "A",
                                        powerW = aPort?.powerW ?: 0.0,
                                        subtitle = "${if ((aPort?.voltageV ?: 0.0) > 0.1) "%.1fV".format(aPort?.voltageV) else "--V"}  ${if ((aPort?.currentA ?: 0.0) > 0.01) "%.2fA".format(aPort?.currentA) else "--A"}$aProto",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 2.dp))

                                // 3. Multi-Port Power Curve
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Power Curve",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${chartHistory.size} samples",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                MultiPortPowerChart(
                                    points = chartHistory,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 2.dp))

                                // 4. Power Proportion Section
                                Text(
                                    text = "Power Proportion",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                // Stacked Power Proportion Bar
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val c1Str = if (p1 > 0.1 && sumW > 0.1) "${(p1 / sumW * 100).toInt()}%" else "--"
                                    val c2Str = if (p2 > 0.1 && sumW > 0.1) "${(p2 / sumW * 100).toInt()}%" else "--"
                                    val c3Str = if (p3 > 0.1 && sumW > 0.1) "${(p3 / sumW * 100).toInt()}%" else "--"
                                    val aStr = if (p4 > 0.1 && sumW > 0.1) "${(p4 / sumW * 100).toInt()}%" else "--"
                                    Text("C1 $c1Str", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFF7A00), fontWeight = FontWeight.Bold)
                                    Text("C2 $c2Str", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold)
                                    Text("C3 $c3Str", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                    Text("A $aStr", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                                }
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
                                settings = settings,
                                chargeLimit = limit,
                                countdownRemainingSec = countdownRemainingSeconds[portId],
                                isHighlighted = (highlightedPortId == portId),
                                bringIntoViewRequester = portRequesters.getOrNull(portId - 1),
                                onToggleEnabled = { enabled -> bleManager.setPortEnabled(portId, enabled) },
                                onSelectCountdown = { code -> bleManager.setPortCountdown(portId, code) },
                                onSetProtocol = { proto, en -> bleManager.setPortProtocol(portId, proto, en) },
                                onOpenChargeLimit = { chargeLimitDialogPort = portId },
                                onOpenClosureTimeDialog = { closureTimeDialogPort = portId }
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
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "USB-A Port Specialty Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            // USB-A Low Current Mode
                            SettingSwitchRow(
                                title = "USB-A Low Current (On for 4 Hours)",
                                subtitle = "Prevents auto-cutoff for low-draw devices (smartwatches, earbuds). Automatically turns off after 4 hours.",
                                checked = settings.usbALowCurrent,
                                onCheckedChange = { bleManager.setUsbALowCurrent(it) }
                            )
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
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { showConsolidatedTable = true },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("View All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedSessionDetail = s }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = "${s.portLabel} · Charging (${s.protocolUsed})",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Duration: ${s.durationSeconds / 60}m ${s.durationSeconds % 60}s · Peak: ${"%.1f".format(s.peakWatts)}W · Avg: ${"%.1f".format(s.avgWatts)}W",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
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

                                val pageSize = 5
                                val totalPages = (completedSessions.size + pageSize - 1) / pageSize
                                val safePage = historyPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                val pagedSessions = completedSessions.drop(safePage * pageSize).take(pageSize)

                                pagedSessions.forEach { s ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { selectedSessionDetail = s }
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                text = "${s.portLabel} (${s.protocolUsed})",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Duration: ${s.durationSeconds / 60}m · Peak: ${"%.1f".format(s.peakWatts)}W · Avg: ${"%.1f".format(s.avgWatts)}W",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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

                                if (totalPages > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { if (historyPageIndex > 0) historyPageIndex-- },
                                            enabled = safePage > 0,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text("◀ Prev", fontSize = 11.sp)
                                        }

                                        Text(
                                            text = "Page ${safePage + 1} of $totalPages (${completedSessions.size} total)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        OutlinedButton(
                                            onClick = { if (safePage < totalPages - 1) historyPageIndex++ },
                                            enabled = safePage < totalPages - 1,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text("Next ▶", fontSize = 11.sp)
                                        }
                                    }
                                }

                                if (completedSessions.isNotEmpty()) {
                                    TextButton(
                                        onClick = { showConsolidatedTable = true },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text("View All Consolidated Sessions (${completedSessions.size}) ➔", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                if (completedSessions.size >= 300) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "300-session rolling history limit reached: oldest sessions are automatically purged as new ones complete.",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
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
                                        text = "Home Assistant Webhook Integration",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Pushes real-time telemetry to Home Assistant server",
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
                                    onSave = { cloudIntegrations.updateConfig(it) }
                                )
                            }
                        }
                    }

                    // 5. Charger Screen & Display Settings Card (Consolidated)
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
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Charger Screen & Display Settings",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Manage timeout, idle power saving, orientation, LCD pages, and language",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // A. Screen Sleep Timeout (PIID 6)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Screen-Off Sleep Timeout",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val timeoutList = listOf(
                                        4 to "Always On",
                                        5 to "1 min",
                                        1 to "5 min",
                                        2 to "10 min",
                                        3 to "30 min"
                                    )
                                    timeoutList.forEach { (code, label) ->
                                        val isSelected = (settings.screenTimeout == code)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { bleManager.setScreenTimeout(code) },
                                            label = {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                            // B. Idle Screen Off (PIID 19)
                            SettingSwitchRow(
                                title = "Idle Screen-Off",
                                subtitle = "Turns off charger's color LCD when all ports are idle",
                                checked = settings.idleScreenOff,
                                onCheckedChange = { bleManager.setIdleScreenOff(it) }
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                            // C. Physical Screen Language (PIID 13)
                            var localLanguage by remember(settings.language) {
                                mutableStateOf(if (settings.language in 0..1) settings.language else 0)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Physical Screen Language",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CuktechProtocol.LANGUAGE_OPTIONS.forEach { (code, name) ->
                                        val isSelected = (localLanguage == code)
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            border = BorderStroke(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    localLanguage = code
                                                    bleManager.setLanguage(code)
                                                    Toast.makeText(context, "Charger language set to $name", Toast.LENGTH_SHORT).show()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = name,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Force Soft Port Reset Card
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
                                        text = "Soft Port Reset",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Power-cycles all ports to force USB-PD contract re-negotiation",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Button(
                                onClick = { showSoftRestartDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Re-negotiate Ports (Soft Reset)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Xiaomi Mi Home requires exclusive Bluetooth access to check for and install official charger firmware updates.\n\nOpening Mi Home will safely disconnect the BLE Bridge gateway and stop background server tasks.\n\nWhen you return to this app, you can start the server and reconnect with 1 tap.")
                }
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
                showXiaomiLoginDialog = false
                Toast.makeText(context, "Successfully synced ${list.size} device credentials", Toast.LENGTH_SHORT).show()
                val target = list.firstOrNull { it.isCuktech10Ultra && it.hasValidToken }
                    ?: list.firstOrNull { it.hasValidToken }
                    ?: list.firstOrNull()
                if (target != null) {
                    bleManager.credentialStore.selectActiveDevice(target.mac)
                    bleManager.connectToCharger(target.mac)
                }
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

    closureTimeDialogPort?.let { portId ->
        val port = portsMap[portId]
        val currentMinutes = port?.countdownCode ?: 0
        var selectedHours by remember(portId, currentMinutes) { mutableIntStateOf(currentMinutes / 60) }
        var selectedMins by remember(portId, currentMinutes) { mutableIntStateOf(currentMinutes % 60) }

        AlertDialog(
            onDismissRequest = { closureTimeDialogPort = null },
            title = {
                Text(
                    text = "Closure Time (${port?.portLabel ?: "Port $portId"})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 2-Column Wheel-style Hours & Minutes Picker (Mi Home replica)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmoothWheelPicker(
                                value = selectedHours,
                                maxValue = 23,
                                unit = "h",
                                onValueChange = { selectedHours = it },
                                modifier = Modifier.weight(1f)
                            )

                            // Divider
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(100.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            )

                            SmoothWheelPicker(
                                value = selectedMins,
                                maxValue = 59,
                                unit = "min",
                                onValueChange = { selectedMins = it },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Quick Template Presets
                    Text(
                        text = "Quick Presets",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val presets = listOf(
                        0 to "Always On",
                        15 to "15m",
                        30 to "30m",
                        60 to "1h",
                        120 to "2h",
                        240 to "4h"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        presets.take(3).forEach { (m, label) ->
                            val isSel = (selectedHours * 60 + selectedMins) == m
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    selectedHours = m / 60
                                    selectedMins = m % 60
                                },
                                label = { Text(label, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        presets.drop(3).forEach { (m, label) ->
                            val isSel = (selectedHours * 60 + selectedMins) == m
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    selectedHours = m / 60
                                    selectedMins = m % 60
                                },
                                label = { Text(label, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    val totalM = selectedHours * 60 + selectedMins
                    Text(
                        text = if (totalM == 0) "Current Selection: Always On (Never shut off)" else "Current Selection: Shut off in ${formatCountdown(totalM)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val totalM = selectedHours * 60 + selectedMins
                        bleManager.setPortCountdown(portId, totalM)
                        closureTimeDialogPort = null
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            dismissButton = {
                Surface(
                    onClick = { closureTimeDialogPort = null },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(38.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Cancel", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        )
    }

    selectedSessionDetail?.let { session ->
        SessionDetailDialog(
            session = session,
            onDismiss = { selectedSessionDetail = null }
        )
    }

    if (showConsolidatedTable) {
        ConsolidatedSessionsTableDialog(
            allSessions = activeSessions.values.toList() + completedSessions,
            onDismiss = { showConsolidatedTable = false },
            onExportCsv = { showExportDialog = true }
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
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
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
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.error,
                                        unselectedColor = Color(0xFF64748B)
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
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
                        bleManager.clearHistory(selectedClearHistoryOption)
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
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
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
                                    onClick = { pendingScreenTimeoutCode = code },
                                    colors = cuktechRadioButtonColors(),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        fontSize = 11.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
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

    if (showScenariosDialog) {
        AlertDialog(
            onDismissRequest = { showScenariosDialog = false },
            icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Power Allocation Scenarios", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Select how 120W total power is distributed across ports:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val modeDescriptions = mapOf(
                        1 to "Auto-balances power between USB-C and USB-A",
                        2 to "Guarantees up to 100W for USB-C1 first",
                        3 to "Maximized burst wattage for one device",
                        4 to "Equal 60W/60W or dynamic split across two active ports"
                    )
                    CuktechProtocol.SCENE_MODES.forEach { (code, label) ->
                        val desc = modeDescriptions[code] ?: ""
                        val isSelected = (settings.sceneMode == code)
                        Surface(
                            onClick = {
                                bleManager.setSceneMode(code)
                                showScenariosDialog = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            ),
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
                                    onClick = {
                                        bleManager.setSceneMode(code)
                                        showScenariosDialog = false
                                    },
                                    colors = cuktechRadioButtonColors(),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (desc.isNotBlank()) {
                                        Text(
                                            text = desc,
                                            fontSize = 11.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScenariosDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun cuktechSwitchColors(): SwitchColors {
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = Color(0xFF94A3B8),
        uncheckedTrackColor = Color(0xFF1E2430),
        uncheckedBorderColor = Color(0xFF475569)
    )
}

@Composable
private fun cuktechRadioButtonColors(): RadioButtonColors {
    return RadioButtonDefaults.colors(
        selectedColor = MaterialTheme.colorScheme.primary,
        unselectedColor = Color(0xFF64748B)
    )
}

@Composable
private fun SmoothWheelPicker(
    value: Int,
    maxValue: Int,
    unit: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = value.coerceIn(0, maxValue),
        pageCount = { maxValue + 1 }
    )

    LaunchedEffect(value) {
        if (pagerState.currentPage != value && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(value.coerceIn(0, maxValue))
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != value) {
            onValueChange(pagerState.settledPage)
        }
    }

    Box(
        modifier = modifier.height(130.dp),
        contentAlignment = Alignment.Center
    ) {
        // Selection highlight band
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(42.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp)
                )
        )

        VerticalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(42.dp),
            contentPadding = PaddingValues(vertical = 44.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) { page ->
            val isSelected = pagerState.currentPage == page
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "%02d".format(page),
                    fontSize = if (isSelected) 28.sp else 16.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                if (isSelected) {
                    Text(
                        text = " $unit",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PortTelemetryCard(
    port: PortTelemetry,
    settings: ChargerSettingsState,
    chargeLimit: PortChargeLimit?,
    countdownRemainingSec: Long? = null,
    isHighlighted: Boolean = false,
    bringIntoViewRequester: BringIntoViewRequester? = null,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectCountdown: (Int) -> Unit,
    onSetProtocol: (String, Boolean) -> Unit,
    onOpenChargeLimit: () -> Unit,
    onOpenClosureTimeDialog: () -> Unit
) {
    val accentColor = when (port.portId) {
        1 -> Color(0xFF38BDF8)
        2 -> Color(0xFF818CF8)
        3 -> Color(0xFF34D399)
        else -> Color(0xFFFBBF24)
    }

    val cardModifier = if (bringIntoViewRequester != null) {
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester)
    } else {
        Modifier.fillMaxWidth()
    }

    Card(
        modifier = cardModifier
            .border(
                width = if (isHighlighted) 2.dp else if (port.isCharging) 1.5.dp else 0.5.dp,
                color = if (isHighlighted) MaterialTheme.colorScheme.primary else if (port.isCharging) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Port Header Row with Title + Circular Power Toggle Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (port.isCharging) accentColor else if (port.enabled) Color.Gray else Color.Red.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val maxW = port.maxPowerW
                        val headerText = if (port.isCharging) {
                            "${port.portLabel} (${maxW}W Max)  |  ${"%.1f".format(port.powerW)}W ${"%.1f".format(port.voltageV)}V ${"%.2f".format(port.currentA)}A"
                        } else {
                            "${port.portLabel} (${maxW}W Max)  |  No output"
                        }
                        Text(
                            text = headerText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    if (port.protocolName.isNotBlank() && port.protocolName != "Normal") {
                        Text(
                            text = "Protocol: ${port.protocolName}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor,
                            modifier = Modifier.padding(start = 18.dp, top = 2.dp)
                        )
                    }
                }

                // Circular Power Toggle Button (Mi Home style)
                IconButton(
                    onClick = { onToggleEnabled(!port.enabled) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (port.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = if (port.enabled) "Turn Port Off" else "Turn Port On",
                        tint = if (port.enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 2. Delay Poweroff & Closure Time Rows
            val liveRemainingText = if (countdownRemainingSec != null && countdownRemainingSec > 0) {
                val m = countdownRemainingSec / 60
                val s = countdownRemainingSec % 60
                String.format(java.util.Locale.US, "%d:%02d", m, s)
            } else null

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Delay Poweroff Switch Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp)
                    ) {
                        Text(
                            text = "Delay Poweroff",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (liveRemainingText != null) {
                                "Power cuts off in $liveRemainingText"
                            } else if (port.countdownCode > 0) {
                                "Power cuts off in ${formatCountdown(port.countdownCode)}"
                            } else {
                                "Cut off port power after set duration"
                            },
                            fontSize = 11.sp,
                            fontWeight = if (liveRemainingText != null) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (liveRemainingText != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = (countdownRemainingSec != null && countdownRemainingSec > 0) || port.countdownCode > 0,
                        colors = cuktechSwitchColors(),
                        onCheckedChange = { checked ->
                            if (checked) {
                                onOpenClosureTimeDialog()
                            } else {
                                onSelectCountdown(0)
                            }
                        }
                    )
                }

                // Closure Time Clickable Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenClosureTimeDialog() }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Closure Time",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val durationText = liveRemainingText ?: formatCountdown(port.countdownCode)
                        Text(
                            text = durationText,
                            fontSize = 13.sp,
                            fontWeight = if (liveRemainingText != null || port.countdownCode > 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (liveRemainingText != null || port.countdownCode > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 3. Discharging Protocols Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Discharging protocols",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (port.portId == 1 || port.portId == 2) {
                    val ufcsChecked = if (port.portId == 1) settings.c1Ufcs else settings.c2Ufcs
                    val pdChecked = if (port.portId == 1) settings.c1Pd else settings.c2Pd
                    val ppsChecked = if (port.portId == 1) settings.c1Pps else settings.c2Pps

                    ProtocolSwitchRow(
                        title = "UFCS",
                        subtitle = "Universal Fast Charging Specification (China unified fast-charge standard)",
                        checked = ufcsChecked,
                        onCheckedChange = { onSetProtocol("ufcs", it) }
                    )

                    ProtocolSwitchRow(
                        title = "PD",
                        subtitle = "USB Power Delivery (up to 100W). Disabling PD also disables PPS.",
                        checked = pdChecked,
                        onCheckedChange = { onSetProtocol("pd", it) }
                    )

                    ProtocolSwitchRow(
                        title = "PPS",
                        subtitle = "Programmable Power Supply (Samsung Super Fast & Xiaomi Mi Turbo)",
                        checked = ppsChecked,
                        enabled = pdChecked,
                        onCheckedChange = { onSetProtocol("pps", it) }
                    )
                } else {
                    // C3 or USB-A
                    val ufcsChecked = if (port.portId == 3) settings.c3Ufcs else settings.aUfcs
                    val scpChecked = if (port.portId == 3) settings.c3Scp else settings.aScp

                    ProtocolSwitchRow(
                        title = "UFCS",
                        subtitle = "Universal Fast Charging Specification. Take effect after re-plugging.",
                        checked = ufcsChecked,
                        onCheckedChange = { onSetProtocol("ufcs", it) }
                    )

                    ProtocolSwitchRow(
                        title = "SCP",
                        subtitle = "Huawei SuperCharge Protocol (high current). Take effect after re-plugging.",
                        checked = scpChecked,
                        onCheckedChange = { onSetProtocol("scp", it) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 4. V / A / W / Wh Metrics Row
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

            // Wh Charge Limit button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenChargeLimit,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (chargeLimit?.enabled == true) "Limit: ${"%.0f".format(chargeLimit.whTarget)}Wh Active" else "Set Wh Cutoff Limit",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            colors = cuktechSwitchColors(),
            onCheckedChange = onCheckedChange
        )
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            colors = cuktechSwitchColors(),
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PortPowerMiniCell(
    color: Color,
    portName: String,
    powerW: Double,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$portName ",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (powerW > 0.1) "%.1f W".format(powerW) else "-- W",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (powerW > 0.1) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LivePowerChart(
    points: List<PowerChartPoint>,
    modifier: Modifier = Modifier
) {
    val lineColor = Color(0xFF00E5FF)
    val gridColor = Color(0xFF64748B).copy(alpha = 0.25f)
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }

    val firstTimeStr = remember(points.firstOrNull()?.timestampMs) {
        points.firstOrNull()?.let { timeFormat.format(java.util.Date(it.timestampMs)) } ?: ""
    }
    val lastTimeStr = remember(points.lastOrNull()?.timestampMs) {
        points.lastOrNull()?.let { timeFormat.format(java.util.Date(it.timestampMs)) } ?: ""
    }

    val maxWatts = remember(points) {
        if (points.isEmpty()) 30.0 else max(30.0, points.maxOf { it.totalWatts } * 1.15)
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Dashed horizontal reference grid lines
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                val yMid = h * 0.5f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, yMid),
                    end = Offset(w, yMid),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, h - 1f),
                    end = Offset(w, h - 1f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )

                if (points.size < 2) return@Canvas

                val strokePath = Path()
                val fillPath = Path()

                val coords = points.mapIndexed { idx, pt ->
                    val x = (idx.toFloat() / (points.size - 1).toFloat()) * w
                    val y = (h - (pt.totalWatts / maxWatts).toFloat() * h).coerceIn(4f, h - 4f)
                    Offset(x, y)
                }

                strokePath.moveTo(coords[0].x, coords[0].y)
                fillPath.moveTo(coords[0].x, h)
                fillPath.lineTo(coords[0].x, coords[0].y)

                for (i in 1 until coords.size) {
                    val prev = coords[i - 1]
                    val curr = coords[i]
                    val cX = (prev.x + curr.x) / 2f
                    strokePath.cubicTo(cX, prev.y, cX, curr.y, curr.x, curr.y)
                    fillPath.cubicTo(cX, prev.y, cX, curr.y, curr.x, curr.y)
                }

                fillPath.lineTo(coords.last().x, h)
                fillPath.close()

                // Draw gradient fill below curve
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(
                            lineColor.copy(alpha = 0.35f),
                            lineColor.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = h
                    )
                )

                // Draw curve stroke
                drawPath(
                    path = strokePath,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Glowing point on latest sample
                val lastPt = coords.last()
                drawCircle(
                    color = lineColor.copy(alpha = 0.45f),
                    radius = 6.dp.toPx(),
                    center = lastPt
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = lastPt
                )
            }

            // Power labels on the right
            Text(
                text = "${(maxWatts * 0.5).toInt()}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            )
            Text(
                text = "0",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 2.dp)
            )
        }

        // X-axis timestamps
        if (firstTimeStr.isNotBlank() && lastTimeStr.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(firstTimeStr, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Text(lastTimeStr, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

private fun formatCountdown(minutes: Int): String {
    if (minutes <= 0) return "Always On"
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0 && m > 0) "${h}h ${m}m"
    else if (h > 0) "${h} hr"
    else "${m} min"
}

@Composable
private fun MultiPortPowerChart(
    points: List<PowerChartPoint>,
    modifier: Modifier = Modifier
) {
    val portColors = listOf(
        1 to Color(0xFFFF7A00), // C1: Orange
        2 to Color(0xFF3B82F6), // C2: Blue
        3 to Color(0xFF00E5FF), // C3: Cyan
        4 to Color(0xFFFBBF24)  // A: Amber
    )
    val gridColor = Color(0xFF64748B).copy(alpha = 0.25f)
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }

    val firstTimeStr = remember(points.firstOrNull()?.timestampMs) {
        points.firstOrNull()?.let { timeFormat.format(java.util.Date(it.timestampMs)) } ?: ""
    }
    val lastTimeStr = remember(points.lastOrNull()?.timestampMs) {
        points.lastOrNull()?.let { timeFormat.format(java.util.Date(it.timestampMs)) } ?: ""
    }

    val maxWatts = remember(points) {
        val peak = points.flatMap { it.portWatts.values }.maxOrNull() ?: 20.0
        max(20.0, peak * 1.15)
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Dashed horizontal reference grid lines
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                val yMid = h * 0.5f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, yMid),
                    end = Offset(w, yMid),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, h - 1f),
                    end = Offset(w, h - 1f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )

                if (points.size < 2) return@Canvas

                // Draw each port with power > 0.1W
                for ((portId, color) in portColors) {
                    val hasPower = points.any { (it.portWatts[portId] ?: 0.0) > 0.1 }
                    if (!hasPower) continue

                    val strokePath = Path()
                    val fillPath = Path()

                    val coords = points.mapIndexed { idx, pt ->
                        val pW = pt.portWatts[portId] ?: 0.0
                        val x = (idx.toFloat() / (points.size - 1).toFloat()) * w
                        val y = (h - (pW / maxWatts).toFloat() * h).coerceIn(4f, h - 4f)
                        Offset(x, y)
                    }

                    strokePath.moveTo(coords[0].x, coords[0].y)
                    fillPath.moveTo(coords[0].x, h)
                    fillPath.lineTo(coords[0].x, coords[0].y)

                    for (i in 1 until coords.size) {
                        val prev = coords[i - 1]
                        val curr = coords[i]
                        val cX = (prev.x + curr.x) / 2f
                        strokePath.cubicTo(cX, prev.y, cX, curr.y, curr.x, curr.y)
                        fillPath.cubicTo(cX, prev.y, cX, curr.y, curr.x, curr.y)
                    }

                    fillPath.lineTo(coords.last().x, h)
                    fillPath.close()

                    // Semi-transparent gradient under the curve
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.0f)),
                            startY = 0f,
                            endY = h
                        )
                    )

                    // Draw stroke
                    drawPath(
                        path = strokePath,
                        color = color,
                        style = Stroke(width = 2.2.dp.toPx())
                    )

                    // Endpoint glowing dot
                    val last = coords.last()
                    drawCircle(color = color, radius = 3.5.dp.toPx(), center = last)
                }
            }

            // Power labels on right
            Text(
                text = "${(maxWatts * 0.5).toInt()}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            )
            Text(
                text = "0",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 2.dp)
            )
        }

        // Bottom timeline timestamps
        if (firstTimeStr.isNotBlank() && lastTimeStr.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(firstTimeStr, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Text(lastTimeStr, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
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
    onSave: (IntegrationConfig) -> Unit
) {
    var haEn by remember(config) { mutableStateOf(config.haWebhookEnabled) }
    var haUrl by remember(config) { mutableStateOf(config.haWebhookUrl) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp)
                    ) {
                        Text("Home Assistant Webhook", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Pushes live wattage & energy telemetry every 10s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = haEn,
                        colors = cuktechSwitchColors(),
                        onCheckedChange = { haEn = it }
                    )
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
                        haWebhookEnabled = haEn,
                        haWebhookUrl = haUrl
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Save Integration Settings")
        }
    }
}

@Composable
private fun SessionDetailDialog(
    session: com.example.cuktech.energy.ChargeSessionRecord,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val timeFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }
    val startTimeStr = remember(session.startTimeSec) {
        timeFormat.format(java.util.Date((session.startTimeSec * 1000).toLong()))
    }
    val endTimeStr = remember(session.endTimeSec) {
        session.endTimeSec?.let { timeFormat.format(java.util.Date((it * 1000).toLong())) } ?: "In Progress"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("${session.portLabel} Session Details") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Port:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(session.portLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Protocol:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(session.protocolUsed, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Energy Delivered:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${"%.3f".format(session.whAccumulated)} Wh", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Duration:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${session.durationSeconds / 60}m ${session.durationSeconds % 60}s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Peak Power:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${"%.1f".format(session.peakWatts)} W", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Average Power:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${"%.1f".format(session.avgWatts)} W", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Start Time:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(startTimeStr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("End Time:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(endTimeStr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val csvLine = "${session.portLabel},${session.protocolUsed},${"%.3f".format(session.whAccumulated)},${session.durationSeconds},${"%.1f".format(session.peakWatts)},${"%.1f".format(session.avgWatts)},$startTimeStr,$endTimeStr"
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cuktech Session", csvLine))
                    Toast.makeText(context, "Session CSV copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copy CSV Row")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ConsolidatedSessionsTableDialog(
    allSessions: List<com.example.cuktech.energy.ChargeSessionRecord>,
    onDismiss: () -> Unit,
    onExportCsv: () -> Unit
) {
    val context = LocalContext.current
    val timeFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    var pageIndex by remember { mutableIntStateOf(0) }
    val pageSize = 10
    val totalPages = ((allSessions.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = pageIndex.coerceIn(0, totalPages - 1)
    val pageSessions = allSessions.drop(safePage * pageSize).take(pageSize)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Consolidated Charging History (${allSessions.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Rolling history: up to 300 sessions retained (FIFO auto-prune)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (allSessions.isEmpty()) {
                    Text("No charging sessions recorded yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Table Header Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Port / Proto", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                                Text("Energy", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                                Text("Dur", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
                                Text("Peak", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                                Text("Time", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.0f))
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            pageSessions.forEachIndexed { idx, s ->
                                val bg = if (idx % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                val cleanProto = s.protocolUsed
                                    .replace(Regex("\\s*\\([0-9.]+V\\)"), "")
                                    .trim()
                                val shortPort = s.portLabel.replace("USB-", "")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bg)
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1.5f)) {
                                        Text(shortPort, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = cleanProto,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text("${"%.2f".format(s.whAccumulated)}Wh", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.9f))
                                    Text("${s.durationSeconds / 60}m", fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f))
                                    Text("${"%.1f".format(s.peakWatts)}W", fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.8f))
                                    val startStr = timeFormat.format(java.util.Date((s.startTimeSec * 1000).toLong()))
                                    Text(startStr, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.0f))
                                }
                            }
                        }
                    }

                    if (totalPages > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { if (pageIndex > 0) pageIndex-- },
                                enabled = safePage > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("◀ Prev", fontSize = 11.sp)
                            }
                            Text(
                                "Page ${safePage + 1} of $totalPages",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { if (safePage < totalPages - 1) pageIndex++ },
                                enabled = safePage < totalPages - 1,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Next ▶", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sb = StringBuilder("Port,Protocol,EnergyWh,DurationSec,PeakWatts,AvgWatts,StartTime\n")
                    allSessions.forEach { s ->
                        val startStr = timeFormat.format(java.util.Date((s.startTimeSec * 1000).toLong()))
                        sb.append("${s.portLabel},\"${s.protocolUsed}\",${"%.3f".format(s.whAccumulated)},${s.durationSeconds},${"%.1f".format(s.peakWatts)},${"%.1f".format(s.avgWatts)},$startStr\n")
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cuktech All Sessions CSV", sb.toString()))
                    Toast.makeText(context, "All ${allSessions.size} sessions copied as CSV", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copy All CSV")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
