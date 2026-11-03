package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cuktech.ble.CuktechAuthState
import com.example.ui.cuktech.CuktechDiscoveryPairingDialog
import com.example.ui.cuktech.XiaomiAccountLoginDialog
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.cuktech.CuktechDashboardScreen
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BleBridgeService.ensureCuktechManagersInitialized(applicationContext)

        val prefs = getSharedPreferences("ble_cuktech_prefs", Context.MODE_PRIVATE)
        val initialTheme = when (prefs.getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

        setContent {
            var themeMode by remember { mutableStateOf(initialTheme) }

            fun updateTheme(newMode: ThemeMode) {
                themeMode = newMode
                prefs.edit().putString("theme_mode", newMode.name).apply()
            }

            MyApplicationTheme(themeMode = themeMode) {
                val appColors = LocalAppColors.current
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = appColors.background
                ) {
                    BleBridgeDashboard(
                        currentTheme = themeMode,
                        onThemeChanged = { updateTheme(it) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleBridgeDashboard(
    currentTheme: ThemeMode = ThemeMode.SYSTEM,
    onThemeChanged: (ThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val cuktechManager = remember { BleBridgeService.ensureCuktechManagersInitialized(context) }
    val bleDiagnostics = remember { BleBridgeService.bleDiagnostics!! }
    val cloudIntegrations = remember { BleBridgeService.cloudIntegrations!! }

    var showTelemetryScreen by remember { mutableStateOf(false) }
    var dashboardCurrentTab by remember { mutableStateOf(0) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var showMiHomeDialogInSettings by remember { mutableStateOf(false) }
    var prerequisiteWarning by remember { mutableStateOf<String?>(null) }

    val accountSession by cuktechManager.credentialStore.session.collectAsState()
    val savedDevices by cuktechManager.credentialStore.devices.collectAsState()
    val activeCred by cuktechManager.credentialStore.activeDevice.collectAsState()
    val cuktechAuthState by cuktechManager.authState.collectAsState()
    val cuktechDevInfo by cuktechManager.deviceInfo.collectAsState()
    val diagStatus by bleDiagnostics.status.collectAsState()

    BackHandler(enabled = showTelemetryScreen) {
        showTelemetryScreen = false
    }

    // Collect states dynamically from BleBridgeService's companion object
    val isRunning by BleBridgeService.isServerRunning.collectAsStateWithLifecycle()
    val serverPort by BleBridgeService.serverPort.collectAsStateWithLifecycle()
    val wifiIp by BleBridgeService.wifiIp.collectAsStateWithLifecycle()
    val connectedClients by BleBridgeService.connectedClients.collectAsStateWithLifecycle()
    val bleState by BleBridgeService.bleConnectionState.collectAsStateWithLifecycle()
    val bleAddress by BleBridgeService.bleDeviceAddress.collectAsStateWithLifecycle()
    val currentLogLevel by BleBridgeService.logLevel.collectAsStateWithLifecycle()
    val logs by BleBridgeService.logs.collectAsStateWithLifecycle()
    val updateAvailable by BleBridgeService.updateAvailable.collectAsStateWithLifecycle()

    var portInput by remember { mutableStateOf(serverPort.toString()) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(serverPort) {
        if (portInput != serverPort.toString()) {
            portInput = serverPort.toString()
        }
    }

    val permissionsToRequest = remember {
        mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var permissionsGranted by remember { mutableStateOf(false) }
    var pendingPermissionCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ok = results.all { it.value }
        permissionsGranted = ok
        if (ok) {
            val svcIntent = Intent(context, BleBridgeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
            pendingPermissionCallback?.invoke()
            pendingPermissionCallback = null
        } else {
            Toast.makeText(context, "Bluetooth and Notification permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    fun ensureBleServiceStartedAndPermissions(onReady: () -> Unit) {
        val allOk = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allOk) {
            permissionsGranted = true
            val svcIntent = Intent(context, BleBridgeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
            onReady()
        } else {
            pendingPermissionCallback = onReady
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        val allOk = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allOk) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            permissionsGranted = true
            val svcIntent = Intent(context, BleBridgeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
        }
        BleBridgeService.checkForUpdates()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = (!showTelemetryScreen || dashboardCurrentTab == 0),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = appColors.surface,
                drawerContentColor = appColors.textPrimary,
                modifier = Modifier.width(300.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(appColors.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = appColors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = appColors.textPrimary
                    )
                }

                HorizontalDivider(color = appColors.border, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                Text(
                    text = "APPEARANCE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = appColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )

                // Theme Mode options: System, Light, Dark
                listOf(
                    Triple(ThemeMode.SYSTEM, "System Default", Icons.Default.Settings),
                    Triple(ThemeMode.LIGHT, "Light Mode", Icons.Default.Brightness7),
                    Triple(ThemeMode.DARK, "Dark Mode", Icons.Default.Brightness4)
                ).forEach { (mode, label, icon) ->
                    val selected = currentTheme == mode
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (selected) appColors.primary else appColors.textSecondary
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) appColors.textPrimary else appColors.textSecondary
                            )
                        },
                        selected = selected,
                        onClick = {
                            onThemeChanged(mode)
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = appColors.primaryContainer.copy(alpha = 0.5f),
                            unselectedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                HorizontalDivider(color = appColors.border, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                Text(
                    text = "XIAOMI MI HOME",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = appColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )

                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Launch,
                            contentDescription = "Open Xiaomi Mi Home",
                            tint = appColors.primary
                        )
                    },
                    label = {
                        Column {
                            Text(
                                text = "Open Xiaomi Mi Home",
                                fontWeight = FontWeight.Medium,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Firmware updates & official settings",
                                fontSize = 11.sp,
                                color = appColors.textSecondary
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showMiHomeDialogInSettings = true
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                HorizontalDivider(color = appColors.border, modifier = Modifier.padding(horizontal = 20.dp))
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "BLE Cuktech10 Ultra",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = appColors.textPrimary
                    )
                    Text(
                        text = "Hardware Bridge & Dashboard",
                        fontSize = 11.sp,
                        color = appColors.textSecondary
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = appColors.background,
            topBar = {
                TopAppBar(
                    title = {
                        if (showTelemetryScreen) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showTelemetryScreen = false },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(appColors.surfaceVariant)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back to Gateway",
                                        tint = appColors.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "CUKTECH 10 Ultra",
                                        fontWeight = FontWeight.Bold,
                                        color = appColors.textPrimary,
                                        fontSize = 18.sp,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                    Text(
                                        text = "TELEMETRY & 4-PORT CONTROLS",
                                        fontWeight = FontWeight.Bold,
                                        color = StatusSuccess,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(appColors.primaryContainer)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings Menu",
                                        tint = appColors.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "BLE Cuktech10 Gateway",
                                            fontWeight = FontWeight.Bold,
                                            color = appColors.textPrimary,
                                            fontSize = 18.sp,
                                            fontFamily = FontFamily.SansSerif
                                        )
                                        if (updateAvailable) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(StatusError)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isRunning) "SERVER ACTIVE" else "SERVER INACTIVE",
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRunning) StatusSuccess else appColors.textSecondary,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (!showTelemetryScreen && updateAvailable) {
                            IconButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/cramqin/BLEBridge-Cuktech10-Ultra/releases/latest"))
                                context.startActivity(intent)
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Update Available", tint = StatusError)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = appColors.background,
                        titleContentColor = appColors.textPrimary
                    )
                )
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(appColors.surface)
                        .border(width = 1.dp, color = appColors.border, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val bleIndicatorColor = when (bleState) {
                                BleBridgeService.Companion.BleConnectionState.DISCONNECTED -> StatusError
                                BleBridgeService.Companion.BleConnectionState.CONNECTING -> StatusWarning
                                BleBridgeService.Companion.BleConnectionState.CONNECTED -> StatusSuccess
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(bleIndicatorColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BLE: ${bleState.name}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                        }

                        bleAddress?.let { addr ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(appColors.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = addr,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = appColors.primary
                                )
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (showTelemetryScreen) {
                    CuktechDashboardScreen(
                        bleManager = cuktechManager,
                        cloudClient = BleBridgeService.xiaomiCloudClient,
                        bleDiagnostics = bleDiagnostics,
                        cloudIntegrations = cloudIntegrations,
                        onRequestBlePermissions = { onReady ->
                            ensureBleServiceStartedAndPermissions(onReady)
                        },
                        onNavigateBack = {
                            showTelemetryScreen = false
                        },
                        onTabChanged = { tab ->
                            dashboardCurrentTab = tab
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // --- Server Configuration Section ---
                        Card(
                            colors = CardDefaults.cardColors(containerColor = appColors.surface),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, appColors.border, RoundedCornerShape(24.dp))
                        ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SERVER CONFIGURATION",
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textSecondary,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "IP: ${wifiIp ?: "Disconnected / No Wifi"}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = appColors.textPrimary
                                )
                            }

                            Switch(
                                checked = isRunning,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = appColors.primary,
                                    uncheckedThumbColor = if (appColors.isDark) Color(0xFF94A3B8) else MaterialTheme.colorScheme.outline,
                                    uncheckedTrackColor = if (appColors.isDark) Color(0xFF1E2430) else MaterialTheme.colorScheme.surfaceVariant,
                                    uncheckedBorderColor = if (appColors.isDark) Color(0xFF475569) else MaterialTheme.colorScheme.outline
                                ),
                                onCheckedChange = { start ->
                                    val port = portInput.toIntOrNull() ?: 8080
                                    val intent = Intent(context, BleBridgeService::class.java).apply {
                                        action = if (start) BleBridgeService.ACTION_START_SERVER else BleBridgeService.ACTION_STOP_SERVER
                                        putExtra(BleBridgeService.EXTRA_PORT, port)
                                    }
                                    if (start) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(intent)
                                        } else {
                                            context.startService(intent)
                                        }
                                    } else {
                                        context.stopService(intent)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = appColors.primary,
                                    uncheckedThumbColor = appColors.textSecondary,
                                    uncheckedTrackColor = appColors.surfaceVariant
                                )
                            )
                        }

                        if (!isRunning) {
                            Surface(
                                color = StatusWarning.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StatusWarning.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StatusWarning,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Server Configuration is OFF. Turn ON the switch above first to enable Bluetooth pairing and client connections.",
                                        color = appColors.textPrimary,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = appColors.border)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Port Config
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "TCP PORT",
                                    color = appColors.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = portInput,
                                    onValueChange = { portInput = it },
                                    enabled = !isRunning,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = appColors.textPrimary,
                                        unfocusedTextColor = appColors.textPrimary,
                                        disabledTextColor = appColors.textSecondary,
                                        focusedContainerColor = appColors.surfaceVariant,
                                        unfocusedContainerColor = appColors.surfaceVariant,
                                        disabledContainerColor = appColors.surfaceVariant.copy(alpha = 0.5f),
                                        focusedBorderColor = appColors.primary,
                                        unfocusedBorderColor = appColors.border,
                                        disabledBorderColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Log Level selection
                            Column(modifier = Modifier.weight(1.3f)) {
                                Text(
                                    text = "VERBOSITY",
                                    color = appColors.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box {
                                    OutlinedButton(
                                        onClick = { dropdownExpanded = true },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = appColors.surfaceVariant
                                        ),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(appColors.border)),
                                        modifier = Modifier.fillMaxWidth().height(56.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = when (currentLogLevel) {
                                                    BleBridgeService.Companion.LogLevel.LEVEL1 -> "LEVEL 1"
                                                    BleBridgeService.Companion.LogLevel.LEVEL2 -> "LEVEL 2"
                                                    BleBridgeService.Companion.LogLevel.LEVEL3 -> "LEVEL 3"
                                                },
                                                color = appColors.textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Icon(
                                                Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                tint = appColors.textSecondary
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = dropdownExpanded,
                                        onDismissRequest = { dropdownExpanded = false },
                                        modifier = Modifier.background(appColors.surface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("LEVEL 1: Errors Only", color = appColors.textPrimary, fontSize = 12.sp) },
                                            onClick = {
                                                BleBridgeService.setLogLevel(BleBridgeService.Companion.LogLevel.LEVEL1)
                                                dropdownExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("LEVEL 2: Errors & Connect Status", color = appColors.textPrimary, fontSize = 12.sp) },
                                            onClick = {
                                                BleBridgeService.setLogLevel(BleBridgeService.Companion.LogLevel.LEVEL2)
                                                dropdownExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("LEVEL 3: Full Telemetry", color = appColors.textPrimary, fontSize = 12.sp) },
                                            onClick = {
                                                BleBridgeService.setLogLevel(BleBridgeService.Companion.LogLevel.LEVEL3)
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                    }
                }

                // --- CUKTECH 10 Ultra & Xiaomi Cloud Section ---
                Card(
                    colors = CardDefaults.cardColors(containerColor = appColors.surface),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, appColors.border, RoundedCornerShape(24.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "CUKTECH 10 ULTRA & XIAOMI CLOUD",
                            fontWeight = FontWeight.Bold,
                            color = appColors.textSecondary,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        // Xiaomi Cloud Account Status
                        Surface(
                            color = appColors.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (accountSession != null) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = if (accountSession != null) StatusSuccess else StatusWarning,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (accountSession != null) "Xiaomi Cloud: Logged In" else "Xiaomi Cloud: Not Logged In",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = appColors.textPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (accountSession != null) {
                                            "UID: ${accountSession?.userId} · Region: ${accountSession?.region?.uppercase()} · Synced: ${savedDevices.size} dev"
                                        } else {
                                            "Required for 12-byte MiOT Token exchange & Phase B authentication"
                                        },
                                        fontSize = 11.sp,
                                        color = appColors.textSecondary
                                    )
                                    if (accountSession != null && !accountSession!!.region.equals("cn", ignoreCase = true)) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "⚠️ Account region is [${accountSession!!.region.uppercase()}]. CUKTECH 10 Ultra requires Mainland China (CN) in Mi Home.",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = StatusWarning
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                if (accountSession != null) {
                                    OutlinedButton(
                                        onClick = { showXiaomiLoginDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Switch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = { showXiaomiLoginDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = appColors.primary,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Log In", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // If authentication failed, show a helpful alert banner with Sync / Login actions
                        if (cuktechAuthState == CuktechAuthState.AUTH_FAILED) {
                            Surface(
                                color = StatusErrorContainer.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StatusError),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Authentication Failed",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = StatusError
                                        )
                                        Text(
                                            text = "MiOT token required for handshake. Sync or log into Xiaomi account.",
                                            fontSize = 11.sp,
                                            color = appColors.textSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (accountSession != null) {
                                        Button(
                                            onClick = { cuktechManager.syncCredentialsAndRetryAuth() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = appColors.primary,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Sync Token", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Search & Pair Button (ALWAYS visible & accessible)
                        Button(
                            onClick = {
                                if (!isRunning) {
                                    prerequisiteWarning = "Prerequisite Required:\n\nServer Configuration must be turned ON first before searching and pairing."
                                } else {
                                    ensureBleServiceStartedAndPermissions {
                                        showPairingDialog = true
                                    }
                                }
                            },
                            enabled = isRunning,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = appColors.primary,
                                contentColor = Color.White,
                                disabledContainerColor = appColors.surfaceVariant,
                                disabledContentColor = appColors.textSecondary
                            )
                        ) {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "Search & Pair CUKTECH 10 Ultra" else "Search & Pair (Turn ON Server First)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // Live Telemetry Card (Shown directly below Search & Pair when paired or active)
                        if (activeCred != null || savedDevices.isNotEmpty() || cuktechAuthState == CuktechAuthState.AUTHENTICATED) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = appColors.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, if (cuktechAuthState == CuktechAuthState.AUTHENTICATED) Color(0xFF00E5FF) else appColors.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (activeCred != null && cuktechAuthState != CuktechAuthState.AUTHENTICATED) {
                                            cuktechManager.connectToCharger(activeCred?.mac)
                                        }
                                        showTelemetryScreen = true
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (cuktechAuthState == CuktechAuthState.AUTHENTICATED) Color(0xFF00E5FF) else appColors.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Bolt,
                                                contentDescription = null,
                                                tint = if (cuktechAuthState == CuktechAuthState.AUTHENTICATED) Color(0xFF0A0E1A) else Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = cuktechDevInfo.name.ifBlank { activeCred?.deviceName ?: "CUKTECH 10 Ultra" },
                                                fontWeight = FontWeight.Bold,
                                                color = appColors.textPrimary,
                                                fontSize = 14.sp
                                            )
                                            val statusStr = when (cuktechAuthState) {
                                                CuktechAuthState.AUTHENTICATED -> "Authenticated · ${"%.1f".format(cuktechTotalWatts)}W"
                                                CuktechAuthState.CONNECTING, CuktechAuthState.CONNECTED -> "Connecting / Handshake..."
                                                CuktechAuthState.AUTH_FAILED -> "Auth Failed"
                                                else -> "Saved · Ready to connect"
                                            }
                                            Text(
                                                text = statusStr,
                                                color = if (cuktechAuthState == CuktechAuthState.AUTHENTICATED) StatusSuccess else appColors.textSecondary,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (activeCred != null && cuktechAuthState != CuktechAuthState.AUTHENTICATED) {
                                                cuktechManager.connectToCharger(activeCred?.mac)
                                            }
                                            showTelemetryScreen = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (cuktechAuthState == CuktechAuthState.AUTHENTICATED) Color(0xFF00E5FF) else appColors.primary,
                                            contentColor = if (cuktechAuthState == CuktechAuthState.AUTHENTICATED) Color(0xFF0A0E1A) else Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("Telemetry →", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Unified System Console & BLE Diagnostics Card with Tabs ---
                var terminalTab by remember { mutableStateOf(0) } // 0 = Live Console, 1 = BLE Diagnostics
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (appColors.isDark) Color(0xFF030712) else appColors.surface),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .border(1.dp, appColors.border, RoundedCornerShape(24.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Tabs (Live Console / BLE Diagnostics)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.clickable { terminalTab = 0 },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (terminalTab == 0) appColors.primary.copy(alpha = 0.22f) else Color.Transparent,
                                    border = if (terminalTab == 0) BorderStroke(1.dp, appColors.primary.copy(alpha = 0.5f)) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "CONSOLE LOG",
                                            fontWeight = FontWeight.Bold,
                                            color = if (terminalTab == 0) appColors.primary else Color(0xFF94A3B8),
                                            fontSize = 11.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(StatusSuccess)
                                        )
                                    }
                                }

                                Surface(
                                    modifier = Modifier.clickable { terminalTab = 1 },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (terminalTab == 1) appColors.primary.copy(alpha = 0.22f) else Color.Transparent,
                                    border = if (terminalTab == 1) BorderStroke(1.dp, appColors.primary.copy(alpha = 0.5f)) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "BLE DIAGNOSTICS",
                                            fontWeight = FontWeight.Bold,
                                            color = if (terminalTab == 1) appColors.primary else Color(0xFF94A3B8),
                                            fontSize = 11.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }

                            // Right: Action Icon Buttons
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (terminalTab == 0) {
                                    IconButton(
                                        onClick = {
                                            val fullLogText = logs.joinToString("\n") { logMsg ->
                                                "[${formatTimeHMS(logMsg.timestamp)}] ${logMsg.message}"
                                            }
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Console Output", fullLogText)
                                            clipboard?.setPrimaryClip(clip)
                                            Toast.makeText(context, "Console output copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy logs",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { BleBridgeService.clearLogs() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Clear logs",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    // BLE Diagnostics: Recheck, Copy, Close
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                bleDiagnostics.runBluetoothStackDiagnostics()
                                            }
                                            Toast.makeText(context, "Running BLE diagnostics...", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Recheck Diagnostics",
                                            tint = appColors.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (diagStatus.lastDiagnosticOutput.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("BLE Diagnostics", diagStatus.lastDiagnosticOutput)
                                                clipboard?.setPrimaryClip(clip)
                                                Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy diagnostics",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = {
                                                bleDiagnostics.clearDiagnosticOutput()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close diagnostics",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Content Body
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 4.dp)
                        ) {
                            if (terminalTab == 0) {
                                // Tab 0: Console Logs
                                val listState = rememberLazyListState()
                                LaunchedEffect(logs.size) {
                                    if (logs.isNotEmpty()) {
                                        listState.animateScrollToItem(logs.size - 1)
                                    }
                                }

                                if (logs.isEmpty()) {
                                    Text(
                                        text = "System logs are empty.",
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(logs) { msg ->
                                            val logColor = when (msg.level) {
                                                BleBridgeService.Companion.LogLevel.LEVEL1 -> if (appColors.isDark) Color(0xFFFCA5A5) else Color(0xFFDC2626) // Red
                                                BleBridgeService.Companion.LogLevel.LEVEL2 -> if (appColors.isDark) Color(0xFFBAE6FD) else Color(0xFF00838F) // Cyan
                                                BleBridgeService.Companion.LogLevel.LEVEL3 -> if (appColors.isDark) Color(0xFFFDE68A) else Color(0xFFD97706) // Amber
                                            }
                                            Text(
                                                text = "[${formatTimeHMS(msg.timestamp)}] ${msg.message}",
                                                color = logColor,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 15.sp,
                                                modifier = Modifier.padding(vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Tab 1: BLE Diagnostics Output
                                if (diagStatus.lastDiagnosticOutput.isBlank()) {
                                    Column(
                                        modifier = Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Bluetooth adapter & MiOT diagnostics not run yet.",
                                            color = Color(0xFF64748B),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    bleDiagnostics.runBluetoothStackDiagnostics()
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = appColors.primary,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Run Stack Diagnostics", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = diagStatus.lastDiagnosticOutput,
                                            color = if (appColors.isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 16.sp
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
        }

        if (showXiaomiLoginDialog) {
            XiaomiAccountLoginDialog(
                cloudClient = BleBridgeService.xiaomiCloudClient,
                credentialStore = cuktechManager.credentialStore,
                onDismiss = { showXiaomiLoginDialog = false },
                onDevicesSynced = { list ->
                    showXiaomiLoginDialog = false
                    if (list.isNotEmpty()) {
                        Toast.makeText(context, "Successfully synced ${list.size} device credentials from Xiaomi Cloud", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Xiaomi account session saved. Ready to connect to charger.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        if (showPairingDialog) {
            CuktechDiscoveryPairingDialog(
                bleManager = cuktechManager,
                onDismiss = { showPairingDialog = false },
                onSelectAndConnect = { dev ->
                    showPairingDialog = false
                    cuktechManager.connectToCharger(dev.mac)
                }
            )
        }

        if (prerequisiteWarning != null) {
            AlertDialog(
                onDismissRequest = { prerequisiteWarning = null },
                containerColor = appColors.surface,
                titleContentColor = appColors.textPrimary,
                textContentColor = appColors.textSecondary,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarning)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Prerequisites Required", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Text(
                        text = prerequisiteWarning ?: "",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { prerequisiteWarning = null }) {
                        Text("UNDERSTOOD", color = appColors.primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        if (showMiHomeDialogInSettings) {
            val miHomePkg = "com.xiaomi.smarthome"
            val miHomeLaunchIntent = remember { context.packageManager.getLaunchIntentForPackage(miHomePkg) }
            val isConnected = (cuktechAuthState == CuktechAuthState.AUTHENTICATED)
            AlertDialog(
                onDismissRequest = { showMiHomeDialogInSettings = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B)) },
                title = { Text("Switch to Xiaomi Mi Home?") },
                text = {
                    Text(
                        if (isConnected) {
                            "Xiaomi Mi Home requires exclusive Bluetooth access to check for and install official charger firmware updates.\n\nOpening Mi Home will safely disconnect the BLE Bridge gateway and release the charger handle.\n\nWhen you return to this app, you will be on the Gateway screen ready to reconnect."
                        } else {
                            "Launch Xiaomi Mi Home to check for charger firmware updates or configure official MiOT settings.\n\nMake sure the charger is within Bluetooth range."
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showMiHomeDialogInSettings = false
                            if (isConnected) {
                                cuktechManager.disconnectCharger()
                                BleBridgeService.getInstance()?.closeGattClean()
                            }
                            val stopIntent = Intent(context, BleBridgeService::class.java).apply {
                                action = BleBridgeService.ACTION_STOP_SERVER
                            }
                            context.startService(stopIntent)
                            showTelemetryScreen = false
                            val launchIntent = miHomeLaunchIntent ?: context.packageManager.getLaunchIntentForPackage(miHomePkg)
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text(
                            if (isConnected) "Disconnect & Open Mi Home" else "Open Mi Home",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMiHomeDialogInSettings = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatTimeHMS(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
