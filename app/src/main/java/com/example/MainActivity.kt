package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
    currentTheme: ThemeMode,
    onThemeChanged: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
    var showInstallDialog by remember { mutableStateOf(false) }

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ok = results.all { it.value }
        permissionsGranted = ok
        if (!ok) {
            Toast.makeText(context, "Bluetooth and Notification permissions are required.", Toast.LENGTH_LONG).show()
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
        }
        BleBridgeService.checkForUpdates()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
                                        text = "BLE Cuktech10",
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
                                    text = if (isRunning) "SERVICE ACTIVE" else "SERVICE INACTIVE",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRunning) StatusSuccess else appColors.textSecondary,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    },
                    actions = {
                        if (updateAvailable) {
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

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = appColors.border)
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { showInstallDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = appColors.primaryContainer,
                                contentColor = appColors.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Setup Python Tools", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                if (showInstallDialog) {
                    AlertDialog(
                        onDismissRequest = { showInstallDialog = false },
                        containerColor = appColors.surface,
                        titleContentColor = appColors.textPrimary,
                        textContentColor = appColors.textSecondary,
                        title = { Text("Termux Setup") },
                        text = {
                            Column {
                                Text("Run this command in Termux to fetch the bridge client scripts:", fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    color = Color(0xFF0F172A),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "curl -L https://raw.githubusercontent.com/cramqin/BLEBridge-Cuktech10-Ultra/main/ble_tui.py -o ble_tui.py && curl -L https://raw.githubusercontent.com/cramqin/BLEBridge-Cuktech10-Ultra/main/uuids.json -o uuids.json",
                                        color = Color(0xFF38BDF8),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showInstallDialog = false }) {
                                Text("DONE", color = appColors.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                // --- Connected Clients (Terminal) Section ---
                Card(
                    colors = CardDefaults.cardColors(containerColor = appColors.surface),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.42f)
                        .border(1.dp, appColors.border, RoundedCornerShape(24.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Active Clients",
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(appColors.primary)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = connectedClients.size.toString(),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (connectedClients.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        connectedClients.forEach { client ->
                                            BleBridgeService.disconnectClientExternal(client.id)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "CLEAR ALL",
                                        color = StatusError,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (connectedClients.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(appColors.surfaceVariant, RoundedCornerShape(12.dp))
                                    .border(1.dp, appColors.border, RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isRunning) "Ready. Connect via TCP socket:\nnc ${wifiIp ?: "127.0.0.1"} ${serverPort}"
                                    else "No TCP sockets listening.\nTurn on the server to accept connections.",
                                    color = appColors.textSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(connectedClients) { client ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(appColors.surfaceVariant, RoundedCornerShape(12.dp))
                                            .border(1.dp, appColors.border, RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = client.id,
                                                color = appColors.textPrimary,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Connected: ${formatTime(client.connectedAt)}",
                                                color = appColors.textSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        Button(
                                            onClick = { BleBridgeService.disconnectClientExternal(client.id) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = StatusErrorContainer,
                                                contentColor = StatusOnErrorContainer
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                text = "BOOT",
                                                color = StatusOnErrorContainer,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Console Log Section ---
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (appColors.isDark) Color(0xFF030712) else Color(0xFF0F172A)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.58f)
                        .border(1.dp, if (appColors.isDark) Color(0xFF1F2937) else Color(0xFF334155), RoundedCornerShape(24.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "CONSOLE OUTPUT",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "LIVE",
                                    color = StatusSuccess,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            IconButton(
                                onClick = { BleBridgeService.clearLogs() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear logs",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        val listState = rememberLazyListState()
                        LaunchedEffect(logs.size) {
                            if (logs.isNotEmpty()) {
                                listState.animateScrollToItem(logs.size - 1)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 4.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    text = "System logs are empty.",
                                    color = Color(0xFF64748B),
                                    fontSize = 10.sp,
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
                                            BleBridgeService.Companion.LogLevel.LEVEL1 -> Color(0xFFFCA5A5) // Soft red
                                            BleBridgeService.Companion.LogLevel.LEVEL2 -> Color(0xFFBAE6FD) // Light sky cyan
                                            BleBridgeService.Companion.LogLevel.LEVEL3 -> Color(0xFFFDE68A) // Soft amber
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
                        }
                    }
                }
            }
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
