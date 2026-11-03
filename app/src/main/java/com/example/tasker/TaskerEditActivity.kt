package com.example.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
        val initialTimeout = existingBundle?.getInt("TIMEOUT", 1) ?: 1

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
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onSave = { action, port, state, mode, timeout, blurb ->
                            val resultBundle = Bundle().apply {
                                putString("ACTION", action)
                                putInt("PORT", port)
                                putBoolean("STATE", state)
                                putInt("MODE", mode)
                                putInt("TIMEOUT", timeout)
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
    onCancel: () -> Unit,
    onSave: (action: String, port: Int, state: Boolean, mode: Int, timeout: Int, blurb: String) -> Unit
) {
    var selectedAction by remember { mutableStateOf(initialAction) }
    var selectedPort by remember { mutableIntStateOf(initialPort) }
    var selectedPortState by remember { mutableStateOf(initialState) }
    var selectedMode by remember { mutableIntStateOf(initialMode) }
    var selectedTimeout by remember { mutableIntStateOf(initialTimeout) }

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
                        val blurb = when (selectedAction) {
                            "CONNECT" -> "CUKTECH 10: Connect"
                            "DISCONNECT" -> "CUKTECH 10: Disconnect"
                            "SET_PORT" -> "CUKTECH 10: Port $selectedPort ${if (selectedPortState) "ON" else "OFF"}"
                            "SET_MODE" -> "CUKTECH 10: Mode $selectedMode"
                            "SET_TIMEOUT" -> "CUKTECH 10: Timeout ${if (selectedTimeout == 0) "Always On" else "${selectedTimeout}s"}"
                            else -> "CUKTECH 10: Action"
                        }
                        onSave(selectedAction, selectedPort, selectedPortState, selectedMode, selectedTimeout, blurb)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Select Action", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            val actions = listOf(
                "CONNECT" to "Connect Charger BLE",
                "DISCONNECT" to "Disconnect Charger BLE",
                "SET_PORT" to "Switch Port Power (On/Off)",
                "SET_MODE" to "Set Power Allocation Mode",
                "SET_TIMEOUT" to "Set Screen Timeout"
            )

            actions.forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedAction = key }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
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

            Divider()

            if (selectedAction == "SET_PORT") {
                Text("Select Port", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val portNames = listOf(1 to "USB-C1", 2 to "USB-C2", 3 to "USB-C3", 4 to "USB-A")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
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
                Text("Power Allocation Mode", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val modes = listOf(
                    1 to "Dynamic Allocation (120W Max)",
                    2 to "Balanced Distribution",
                    3 to "Single Port Priority Max"
                )
                modes.forEach { (mId, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mId }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedMode == mId), onClick = { selectedMode = mId })
                        Spacer(Modifier.width(8.dp))
                        Text(desc, fontSize = 14.sp)
                    }
                }
            }

            if (selectedAction == "SET_TIMEOUT") {
                Text("Screen Timeout Duration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val timeouts = listOf(0 to "Always On", 1 to "60s", 2 to "5m", 3 to "60m")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timeouts.forEach { (sec, label) ->
                        FilterChip(
                            selected = (selectedTimeout == sec),
                            onClick = { selectedTimeout = sec },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}
