package com.example.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme

class TaskerEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read previously saved bundle if editing existing Tasker action
        val existingBundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")
        val initialAction = existingBundle?.getString("ACTION") ?: "CONNECT"
        val initialPort = existingBundle?.getInt("PORT", 1) ?: 1
        val initialState = existingBundle?.getBoolean("STATE", true) ?: true
        val initialMode = existingBundle?.getInt("MODE", 1) ?: 1
        val initialTimeout = existingBundle?.getInt("TIMEOUT", 4) ?: 4
        val initialTimerMins = existingBundle?.getInt("TIMER_MINS", 60) ?: 60

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TaskerEditScreen(
                        initialAction = initialAction,
                        initialPort = initialPort,
                        initialState = initialState,
                        initialMode = initialMode,
                        initialTimeout = initialTimeout,
                        initialTimerMins = initialTimerMins,
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onSave = { action, port, state, mode, timeout, timerMins, blurb ->
                            val resultBundle = Bundle().apply {
                                putString("ACTION", action)
                                putInt("PORT", port)
                                putBoolean("STATE", state)
                                putInt("MODE", mode)
                                putInt("TIMEOUT", timeout)
                                putInt("TIMER_MINS", timerMins)
                            }
                            val resultIntent = Intent().apply {
                                putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", resultBundle)
                                putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskerEditScreen(
    initialAction: String,
    initialPort: Int,
    initialState: Boolean,
    initialMode: Int,
    initialTimeout: Int,
    initialTimerMins: Int,
    onCancel: () -> Unit,
    onSave: (action: String, port: Int, state: Boolean, mode: Int, timeout: Int, timerMins: Int, blurb: String) -> Unit
) {
    var selectedAction by remember { mutableStateOf(initialAction) }
    var selectedPort by remember { mutableIntStateOf(initialPort) }
    var selectedPortState by remember { mutableStateOf(initialState) }
    var selectedMode by remember { mutableIntStateOf(initialMode) }
    var selectedTimeout by remember { mutableIntStateOf(initialTimeout) }
    var selectedTimerMins by remember { mutableIntStateOf(initialTimerMins) }

    val portNames = listOf(1 to "USB-C1", 2 to "USB-C2", 3 to "USB-C3", 4 to "USB-A")
    val modeNames = mapOf(
        1 to "AI Mode",
        2 to "Hybrid",
        3 to "Single",
        4 to "Dual"
    )
    val timeoutNames = mapOf(
        4 to "Always On",
        5 to "1 min",
        1 to "5 min",
        2 to "10 min",
        3 to "30 min"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasker: CUKTECH 10 Ultra") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val portLabel = portNames.firstOrNull { it.first == selectedPort }?.second ?: "Port $selectedPort"
                        val blurb = when (selectedAction) {
                            "CONNECT" -> "CUKTECH 10: Connect BLE"
                            "DISCONNECT" -> "CUKTECH 10: Disconnect BLE"
                            "SET_PORT" -> "CUKTECH 10: $portLabel ${if (selectedPortState) "ON" else "OFF"}"
                            "SET_MODE" -> "CUKTECH 10: Scenario ${modeNames[selectedMode] ?: "AI Mode"}"
                            "SET_TIMEOUT" -> "CUKTECH 10: Screen Timeout (${timeoutNames[selectedTimeout] ?: "Always On"})"
                            "SET_LOW_CURRENT" -> "CUKTECH 10: USB-A Low Current ${if (selectedPortState) "ON (4h)" else "OFF"}"
                            "SET_PORT_TIMER" -> {
                                val tLabel = if (selectedTimerMins == 0) "Always On" else "${selectedTimerMins}m"
                                "CUKTECH 10: $portLabel Timer ($tLabel)"
                            }
                            "SOFT_RESET" -> "CUKTECH 10: Force Soft Port Reset"
                            "SET_SCREEN_POWER" -> "CUKTECH 10: Display ${if (selectedPortState) "Wake ON" else "Sleep OFF"}"
                            else -> "CUKTECH 10: Action"
                        }
                        onSave(selectedAction, selectedPort, selectedPortState, selectedMode, selectedTimeout, selectedTimerMins, blurb)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Select Action", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            val actions = listOf(
                "CONNECT" to "Connect Charger BLE",
                "DISCONNECT" to "Disconnect Charger BLE",
                "SET_PORT" to "Switch Port Power (On/Off)",
                "SET_MODE" to "Set Power Allocation Scenario",
                "SET_TIMEOUT" to "Set Screen Timeout",
                "SET_SCREEN_POWER" to "Wake or Sleep Charger Display",
                "SET_LOW_CURRENT" to "USB-A Low Current Mode (4h)",
                "SET_PORT_TIMER" to "Set Port Delay Poweroff Timer",
                "SOFT_RESET" to "Force Soft Port Reset (Renegotiate PD)"
            )

            actions.forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedAction = key }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedAction == key),
                        onClick = { selectedAction = key }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontSize = 14.sp)
                }
            }

            HorizontalDivider()

            if (selectedAction == "SET_PORT") {
                Text("Select Port", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Port Power State", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text("Power ON") }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text("Power OFF") }
                    )
                }
            }

            if (selectedAction == "SET_MODE") {
                Text("Power Allocation Scenario", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val modes = listOf(
                    1 to ("AI Mode" to "Auto-balances power between USB-C and USB-A"),
                    2 to ("Hybrid" to "Guarantees up to 100W for USB-C1 first"),
                    3 to ("Single" to "Maximized burst wattage for one device"),
                    4 to ("Dual" to "Equal 60W/60W or dynamic split across two active ports")
                )
                modes.forEach { (mId, pair) ->
                    val (title, subtitle) = pair
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedMode = mId }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedMode == mId), onClick = { selectedMode = mId })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (selectedAction == "SET_TIMEOUT") {
                Text("Screen Timeout Duration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val timeouts = listOf(
                    4 to "Always On",
                    5 to "1 min",
                    1 to "5 min",
                    2 to "10 min",
                    3 to "30 min"
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    timeouts.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTimeout = code }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (selectedTimeout == code), onClick = { selectedTimeout = code })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (selectedAction == "SET_SCREEN_POWER") {
                Text("Charger Display State", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text("Wake Screen ON") }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text("Turn Screen OFF") }
                    )
                }
            }

            if (selectedAction == "SET_LOW_CURRENT") {
                Text("USB-A Low Current Mode (4 Hours)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "Prevents auto-cutoff for low-draw devices like smartwatches and earbuds.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text("Enable (4 Hours)") }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text("Disable") }
                    )
                }
            }

            if (selectedAction == "SET_PORT_TIMER") {
                Text("Select Port", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Shutoff Countdown Duration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val presets = listOf(
                    0 to "Always On (Cancel Timer)",
                    15 to "15 Minutes",
                    30 to "30 Minutes",
                    60 to "1 Hour",
                    120 to "2 Hours",
                    240 to "4 Hours"
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { (mins, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTimerMins = mins }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (selectedTimerMins == mins), onClick = { selectedTimerMins = mins })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (selectedAction == "SOFT_RESET") {
                Text("Force Soft Port Reset", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "Momentarily cycles all 4 ports off and on (0x00 -> 0x0F) to trigger fresh USB-PD, PPS, and UFCS protocol negotiation.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
