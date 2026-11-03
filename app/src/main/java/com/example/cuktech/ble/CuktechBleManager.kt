package com.example.cuktech.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import com.example.BleBridgeService
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.PowerChartPoint
import com.example.cuktech.energy.AdaptiveEnergyIntegrator
import com.example.cuktech.energy.ChargeSessionDetector
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.DecodedMiotFrame
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.protocol.SessionKeys
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.SecureCredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CuktechAuthState(val label: String) {
    DISCONNECTED("Disconnected"),
    SCANNING("Searching for chargers..."),
    CONNECTING_GATT("Establishing BLE connection..."),
    DISCOVERING_SERVICES("Discovering GATT services..."),
    PHASE_A_INIT_A4("Phase A: 0xA4 Handshake init..."),
    PHASE_B_HANDSHAKE("Phase B: MiOT Key exchange..."),
    AUTHENTICATED("Authenticated · Live Telemetry"),
    AUTH_FAILED("Authentication Failed")
}

data class DiscoveredCuktechDevice(
    val mac: String,
    val name: String,
    val rssi: Int,
    val productId: Int? = null,
    val embeddedMac: String? = null,
    val isCuktech10Ultra: Boolean = false,
    val matchedCredentials: CuktechDeviceCredentials? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class ChargerDeviceInfo(
    val name: String = "CUKTECH 10 Ultra",
    val manufacturer: String = "CUKTECH",
    val model: String = "njcuk.fitting.ad1204",
    val serialNumber: String = "--",
    val firmwareVersion: String = "--",
    val macAddress: String = "--",
    val rssi: Int = -65,
    val connectionQualityScore: Int = 100
) {
    val mac: String get() = macAddress
}

/**
 * Native Kotlin BLE Manager for CUKTECH 10 Ultra operating directly over
 * `BleBridgeService`'s single `BluetoothGatt` and sequential hardware command queue.
 * Ports `controller.py` and `ble_manager.py` from `cuktech-ble-server`.
 */
class CuktechBleManager(
    private val context: Context,
    val credentialStore: SecureCredentialStore,
    val persistence: CuktechPersistence
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val random = SecureRandom()
    private val cmdMutex = Mutex()

    // Incoming notification channels during handshake
    private val authCtrlQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val authDataQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val cmdSendQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val cmdRecvSubframeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile
    private var isAssemblingMultiFrame = false
    private val multiFrameMutex = Mutex()

    // Pending MiOT command responses keyed by seq
    private val pendingMiotResponses = ConcurrentHashMap<Int, CompletableDeferred<DecodedMiotFrame>>()

    // Session state
    private var sessionKeys: SessionKeys? = null
    private var appItCounter: Long = 1L
    private var devItHi: Long = 0L
    private var lastDevItLo: Int = 0
    private var miotSeq: Int = 1
    private var reqCounter: Int = 0x0300

    private var authJob: Job? = null
    private var pollingJob: Job? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null

    private var autoReconnectEnabled: Boolean = true
    var lastConnectedMac: String? = null
    private var consecutiveFailures: Int = 0
    private var totalTxCommands: Int = 0
    private var successfulTxCommands: Int = 0
    private var lastHistoryWriteMs: Long = 0L

    // Energy integrators & session detectors per port (1..4)
    private val energyIntegrators = mapOf(
        1 to AdaptiveEnergyIntegrator(),
        2 to AdaptiveEnergyIntegrator(),
        3 to AdaptiveEnergyIntegrator(),
        4 to AdaptiveEnergyIntegrator()
    )
    private val sessionDetectors = mapOf(
        1 to ChargeSessionDetector(1, "USB-C1"),
        2 to ChargeSessionDetector(2, "USB-C2"),
        3 to ChargeSessionDetector(3, "USB-C3"),
        4 to ChargeSessionDetector(4, "USB-A")
    )

    // Reactive UI StateFlows
    private val _authState = MutableStateFlow(CuktechAuthState.DISCONNECTED)
    val authState: StateFlow<CuktechAuthState> = _authState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredCuktechDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredCuktechDevice>> = _discoveredDevices.asStateFlow()

    private val _deviceInfo = MutableStateFlow(ChargerDeviceInfo())
    val deviceInfo: StateFlow<ChargerDeviceInfo> = _deviceInfo.asStateFlow()

    private val _ports = MutableStateFlow<Map<Int, PortTelemetry>>(
        mapOf(
            1 to PortTelemetry(1, "USB-C1"),
            2 to PortTelemetry(2, "USB-C2"),
            3 to PortTelemetry(3, "USB-C3"),
            4 to PortTelemetry(4, "USB-A")
        )
    )
    val ports: StateFlow<Map<Int, PortTelemetry>> = _ports.asStateFlow()

    private val _totalPowerWatts = MutableStateFlow(0.0)
    val totalPowerWatts: StateFlow<Double> = _totalPowerWatts.asStateFlow()

    private val _settings = MutableStateFlow(ChargerSettingsState())
    val settings: StateFlow<ChargerSettingsState> = _settings.asStateFlow()

    private val _powerChartHistory = MutableStateFlow<List<PowerChartPoint>>(emptyList())
    val powerChartHistory: StateFlow<List<PowerChartPoint>> = _powerChartHistory.asStateFlow()

    private val _activeSessions = MutableStateFlow<Map<Int, ChargeSessionRecord>>(emptyMap())
    val activeSessions: StateFlow<Map<Int, ChargeSessionRecord>> = _activeSessions.asStateFlow()

    private val _completedSessions = MutableStateFlow<List<ChargeSessionRecord>>(emptyList())
    val completedSessions: StateFlow<List<ChargeSessionRecord>> = _completedSessions.asStateFlow()

    private val _chargeLimits = MutableStateFlow<Map<Int, PortChargeLimit>>(emptyMap())
    val chargeLimits: StateFlow<Map<Int, PortChargeLimit>> = _chargeLimits.asStateFlow()

    // Map of portId -> remaining countdown seconds for active realtime display (e.g. 4:59, 4:58)
    private val _countdownRemainingSeconds = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val countdownRemainingSeconds: StateFlow<Map<Int, Long>> = _countdownRemainingSeconds.asStateFlow()
    private val targetCountdownEpochSec = ConcurrentHashMap<Int, Long>()

    // Callback invoked on telemetry update so Tasker / MQTT / Bemfa can broadcast
    var onTelemetryUpdated: ((Map<Int, PortTelemetry>, Double, ChargerDeviceInfo) -> Unit)? = null

    init {
        credentialStore.tryAutoImportLocalCredentialsFile()
        _completedSessions.value = persistence.getRecentSessions(200)
        _chargeLimits.value = persistence.loadPortChargeLimits()
        credentialStore.activeDevice.value?.let { cred ->
            _deviceInfo.value = _deviceInfo.value.copy(
                name = cred.name,
                model = cred.model.ifBlank { "njcuk.fitting.ad1204" },
                macAddress = cred.mac
            )
        }

        // Realtime 1-second ticker for second-by-second countdown and rich notification telemetry
        scope.launch {
            while (isActive) {
                delay(1000L)
                updateCountdownTickAndNotification()
            }
        }
    }

    // =========================================================================
    // 1. DISCOVERY & SCANNING ("SEARCH & PAIR CUKTECH 10 ULTRA")
    // =========================================================================

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    fun startSearchForCuktechChargers(durationMs: Long = 10000L) {
        val svc = BleBridgeService.getInstance()
        if (svc == null) {
            _statusMessage.value = "Bluetooth service is starting, please retry in a moment"
            return
        }
        clearDiscoveredDevices()
        _isScanning.value = true
        if (_authState.value != CuktechAuthState.AUTHENTICATED && _authState.value != CuktechAuthState.CONNECTING_GATT) {
            _authState.value = CuktechAuthState.SCANNING
        }
        _statusMessage.value = "Scanning for nearby CUKTECH 10 Ultra chargers..."
        svc.startNativeCuktechScan(durationMs)
    }

    fun onScanStopped() {
        _isScanning.value = false
        if (_authState.value == CuktechAuthState.SCANNING) {
            _authState.value = CuktechAuthState.DISCONNECTED
        }
        val count = _discoveredDevices.value.size
        if (_authState.value != CuktechAuthState.AUTHENTICATED) {
            _statusMessage.value = if (count > 0) {
                "Scan complete, found $count device(s)"
            } else {
                "No broadcast devices found. You can tap a saved credential to connect."
            }
        }
    }

    fun onScanResult(result: ScanResult) {
        val device = result.device ?: return
        val mac = device.address?.uppercase() ?: return
        val rawName = try {
            device.name ?: result.scanRecord?.deviceName ?: ""
        } catch (_: SecurityException) {
            result.scanRecord?.deviceName ?: ""
        }
        val rssi = result.rssi
        val fe95Bytes = result.scanRecord?.getServiceData(ParcelUuid(CuktechProtocol.MI_SERVICE_UUID))
        val parsedFe95 = CuktechProtocol.parseFe95Advertisement(fe95Bytes)

        val gattMac = mac.uppercase().trim()
        val storedList = credentialStore.devices.value
        val matchedCred = storedList.firstOrNull {
            it.mac.replace(":", "").equals(gattMac.replace(":", ""), ignoreCase = true) ||
                (parsedFe95?.embeddedMac != null && it.mac.replace(":", "").equals(parsedFe95.embeddedMac.replace(":", ""), ignoreCase = true))
        } ?: if (storedList.size == 1 && storedList[0].hasValidToken && (parsedFe95?.isCuktech10Ultra == true || rawName.contains("CUKTECH", ignoreCase = true) || rawName.contains("AD1204", ignoreCase = true))) {
            storedList[0]
        } else null

        val isCuktech = (parsedFe95?.isCuktech10Ultra == true) ||
            (matchedCred != null) ||
            rawName.contains("CUKTECH", ignoreCase = true) ||
            rawName.contains("AD1204", ignoreCase = true) ||
            rawName.contains("酷态科", ignoreCase = true) ||
            (fe95Bytes != null)

        if (!isCuktech && rawName.isBlank()) return

        val displayName = when {
            matchedCred != null && matchedCred.name.isNotBlank() -> matchedCred.name
            parsedFe95?.isCuktech10Ultra == true -> if (rawName.isNotBlank()) rawName else "CUKTECH 10 Ultra"
            rawName.isNotBlank() -> rawName
            else -> "MiOT BLE (${mac.takeLast(5)})"
        }.replace("酷态科10号超级电能充", "CUKTECH 10 Ultra")
         .replace("酷态科", "CUKTECH ")
         .trim()

        val entry = DiscoveredCuktechDevice(
            mac = mac,
            name = displayName,
            rssi = rssi,
            productId = parsedFe95?.productId,
            embeddedMac = parsedFe95?.embeddedMac,
            isCuktech10Ultra = parsedFe95?.isCuktech10Ultra == true || matchedCred?.isCuktech10Ultra == true,
            matchedCredentials = matchedCred,
            lastSeenMs = System.currentTimeMillis()
        )

        val current = _discoveredDevices.value.toMutableList()
        val idx = current.indexOfFirst { it.mac.equals(mac, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = entry
        } else {
            current.add(entry)
        }
        current.sortWith(
            compareByDescending<DiscoveredCuktechDevice> { it.matchedCredentials != null }
                .thenByDescending { it.isCuktech10Ultra }
                .thenByDescending { it.rssi }
        )
        _discoveredDevices.value = current
    }

    // =========================================================================
    // 2. GATT CONNECTION & AUTHENTICATION LIFECYCLE
    // =========================================================================

    fun connectToCharger(macAddress: String? = null) {
        val targetCred = if (!macAddress.isNullOrBlank()) {
            credentialStore.devices.value.firstOrNull { it.mac.equals(macAddress, ignoreCase = true) }
                ?: credentialStore.activeDevice.value
        } else {
            credentialStore.activeDevice.value
        }

        val targetMac = (macAddress ?: targetCred?.mac)?.uppercase()?.trim()
        if (targetMac.isNullOrBlank()) {
            _statusMessage.value = "Please log in to Xiaomi account or scan to select a charger"
            return
        }

        if (targetCred != null && !targetCred.isCuktech10Ultra) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Cannot connect to '$targetMac' (${targetCred.name}, model: ${targetCred.model}): Not a CUKTECH 10 Ultra charger. Only CUKTECH 10 Ultra (Model: AD1204) is supported."
            )
            _statusMessage.value = "Unsupported device (${targetCred.name}). Only CUKTECH 10 Ultra is supported."
            return
        }

        if (targetCred != null && targetCred.mac.equals(targetMac, ignoreCase = true)) {
            credentialStore.selectActiveDevice(targetMac)
        }

        val svc = BleBridgeService.getInstance()
        if (svc == null) {
            _statusMessage.value = "BLE Service is not ready"
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            _statusMessage.value = "Bluetooth is turned OFF. Please enable Bluetooth."
            _authState.value = CuktechAuthState.DISCONNECTED
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Cannot connect: Bluetooth is turned OFF."
            )
            return
        }

        autoReconnectEnabled = true
        lastConnectedMac = targetMac
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        stopBackgroundLoops()

        _deviceInfo.value = _deviceInfo.value.copy(
            macAddress = targetMac,
            name = targetCred?.name ?: "CUKTECH 10 Ultra"
        )
        _authState.value = CuktechAuthState.CONNECTING_GATT
        _statusMessage.value = "Connecting to $targetMac..."

        connectionTimeoutJob = scope.launch {
            delay(15000L)
            if (_authState.value in listOf(
                CuktechAuthState.CONNECTING_GATT,
                CuktechAuthState.DISCOVERING_SERVICES,
                CuktechAuthState.PHASE_A_INIT_A4,
                CuktechAuthState.PHASE_B_HANDSHAKE
            )) {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Connection to $targetMac timed out after 15s. Charger display may be asleep or out of range."
                )
                _statusMessage.value = "Connection timed out. Wake charger display or verify Bluetooth."
                disconnectCharger()
            }
        }

        svc.connectBleNative(targetMac)
    }

    fun onConnectionFailed(reason: String) {
        connectionTimeoutJob?.cancel()
        authJob?.cancel()
        stopBackgroundLoops()
        _authState.value = CuktechAuthState.DISCONNECTED
        _statusMessage.value = reason
    }

    fun disconnectCharger() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        authJob?.cancel()
        stopBackgroundLoops()
        sessionKeys = null
        _authState.value = CuktechAuthState.DISCONNECTED
        _statusMessage.value = "Disconnected"
        BleBridgeService.getInstance()?.disconnectBleNative()
    }

    fun onConnectionStateChange(address: String, status: Int, newState: Int) {
        lastConnectedMac = address
        if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            _authState.value = CuktechAuthState.DISCOVERING_SERVICES
            _statusMessage.value = "GATT connected, discovering services..."
        } else {
            connectionTimeoutJob?.cancel()
            authJob?.cancel()
            stopBackgroundLoops()
            sessionKeys = null
            for ((_, deferred) in pendingMiotResponses) {
                deferred.cancel()
            }
            pendingMiotResponses.clear()

            _authState.value = CuktechAuthState.DISCONNECTED
            val isError = (status != BluetoothGatt.GATT_SUCCESS && status != 0)
            _statusMessage.value = if (isError) "Disconnected (GATT status=$status). Retrying..." else "Disconnected (status=$status)"

            if (autoReconnectEnabled && !lastConnectedMac.isNullOrBlank()) {
                val delayMs = if (isError) 4500L else 3000L
                scheduleAutoReconnect(lastConnectedMac!!, delayMs)
            }
        }
    }

    private fun scheduleAutoReconnect(mac: String, delayMs: Long = 3000L) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (autoReconnectEnabled && _authState.value != CuktechAuthState.AUTHENTICATED) {
                _statusMessage.value = "Attempting auto-reconnect to $mac..."
                BleBridgeService.getInstance()?.connectBleNative(mac)
            }
        }
    }

    fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            _authState.value = CuktechAuthState.AUTH_FAILED
            _statusMessage.value = "GATT service discovery failed (status=$status)"
            return
        }
        val miService = gatt.getService(CuktechProtocol.MI_SERVICE_UUID)
        if (miService == null) {
            val gattMac = gatt.device.address?.uppercase()?.trim() ?: "Unknown"
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Device $gattMac does not support MiOT Service (0xFE95). BLEBridge only supports CUKTECH 10 Ultra (Model: AD1204, Product ID: 0x660E)."
            )
            _authState.value = CuktechAuthState.AUTH_FAILED
            _statusMessage.value = "Unsupported device ($gattMac). Only CUKTECH 10 Ultra is supported."
            disconnectCharger()
            return
        }

        authJob?.cancel()
        authJob = scope.launch {
            val gattMac = gatt.device.address.uppercase().trim()
            var activeCred = credentialStore.devices.value.firstOrNull {
                it.mac.replace(":", "").equals(gattMac.replace(":", ""), ignoreCase = true)
            } ?: credentialStore.activeDevice.value
              ?: credentialStore.devices.value.firstOrNull { it.isCuktech10Ultra && it.hasValidToken }
              ?: credentialStore.devices.value.firstOrNull { it.hasValidToken }

            // If token is missing, attempt automatic sync with saved Xiaomi Cloud session
            if (activeCred == null || !activeCred.hasValidToken) {
                val session = credentialStore.session.value
                if (session != null) {
                    _statusMessage.value = "Fetching MiOT token from Xiaomi Cloud for $gattMac..."
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL2,
                        "[Cuktech] Token missing for $gattMac. Syncing from Xiaomi Cloud (UID: ${session.userId})..."
                    )
                    val res = BleBridgeService.xiaomiCloudClient.fetchDevicesAndBleKeys(session, searchAllRegionsIfEmpty = true)
                    res.onSuccess { list ->
                        if (list.isNotEmpty()) {
                            credentialStore.saveDevices(list)
                            activeCred = list.firstOrNull {
                                it.mac.replace(":", "").equals(gattMac.replace(":", ""), ignoreCase = true)
                            } ?: list.firstOrNull { it.isCuktech10Ultra && it.hasValidToken }
                              ?: list.firstOrNull { it.hasValidToken }
                        }
                    }
                }
            }

            val currentCred = activeCred
            if (currentCred != null && !currentCred.isCuktech10Ultra) {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Connected peripheral '$gattMac' (${currentCred.name}, model: ${currentCred.model}) is not a CUKTECH 10 Ultra charger. Only CUKTECH 10 Ultra (Model: AD1204, Product ID: 0x660E) is supported."
                )
                _authState.value = CuktechAuthState.AUTH_FAILED
                _statusMessage.value = "Unsupported device (${currentCred.name}). Only CUKTECH 10 Ultra is supported."
                disconnectCharger()
                return@launch
            }

            if (currentCred == null || !currentCred.hasValidToken) {
                _authState.value = CuktechAuthState.AUTH_FAILED
                _statusMessage.value = "Missing valid 12-byte MiOT Token for $gattMac. Only paired CUKTECH 10 Ultra chargers are supported."
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Device $gattMac has no valid CUKTECH 10 Ultra credentials in Xiaomi account. Only CUKTECH 10 Ultra (Model: AD1204) is supported."
                )
                BleBridgeService.refreshClientsList()
                return@launch
            }

            val ok = runAuthenticationHandshake(currentCred.tokenBytes())
            if (ok) {
                connectionTimeoutJob?.cancel()
                _authState.value = CuktechAuthState.AUTHENTICATED
                _statusMessage.value = "CUKTECH 10 Ultra authenticated successfully! Live telemetry active."
                consecutiveFailures = 0
                startBackgroundLoops()
                queryInitialChargerState()
                BleBridgeService.refreshClientsList()
            } else {
                _authState.value = CuktechAuthState.AUTH_FAILED
                BleBridgeService.refreshClientsList()
            }
        }
    }

    /**
     * Manually triggers a Xiaomi Cloud credential sync using the saved session and retries
     * BLE connection/authentication to the charger.
     */
    fun syncCredentialsAndRetryAuth() {
        val session = credentialStore.session.value
        if (session == null) {
            _statusMessage.value = "No Xiaomi account session found. Please log in first."
            return
        }
        scope.launch {
            _statusMessage.value = "Syncing credentials from Xiaomi Cloud..."
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Syncing credentials from Xiaomi Cloud (UID ${session.userId})..."
            )
            val res = BleBridgeService.xiaomiCloudClient.fetchDevicesAndBleKeys(session, searchAllRegionsIfEmpty = true)
            res.onSuccess { list ->
                if (list.isNotEmpty()) {
                    credentialStore.saveDevices(list)
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL2,
                        "[Cuktech] Saved ${list.size} device credential(s) from Xiaomi Cloud."
                    )
                    val targetMac = lastConnectedMac
                        ?: credentialStore.activeDevice.value?.mac
                        ?: list.firstOrNull { it.hasValidToken }?.mac
                    if (!targetMac.isNullOrBlank()) {
                        connectToCharger(targetMac)
                    }
                } else {
                    _statusMessage.value = "No CUKTECH devices found in Xiaomi Cloud"
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL1,
                        "[Cuktech] No CUKTECH devices found in Xiaomi Cloud account."
                    )
                }
            }.onFailure { err ->
                val clean = XiaomiCloudClient.translateXiaomiError(err.message ?: "Sync failed")
                _statusMessage.value = "Sync failed: $clean"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Sync failed: $clean"
                )
            }
        }
    }

    fun onRemoteRssi(rssi: Int) {
        val current = _deviceInfo.value
        val quality = computeConnectionQuality(rssi)
        _deviceInfo.value = current.copy(
            rssi = rssi,
            connectionQualityScore = quality
        )
    }

    private fun computeConnectionQuality(rssi: Int): Int {
        // Map RSSI (-95..-45 dBm) to 0..70 + command success rate 0..30
        val rssiScore = ((rssi + 95).coerceIn(0, 50) * 70) / 50
        val txRate = if (totalTxCommands > 0) {
            ((successfulTxCommands.toDouble() / totalTxCommands.toDouble()) * 30.0).roundToInt()
        } else {
            30
        }
        return (rssiScore + txRate).coerceIn(0, 100)
    }

    // =========================================================================
    // 3. EXACT PORT OF `controller.py::authenticate` (PHASE A + B + PHASE 6)
    // =========================================================================

    private suspend fun recvAuthResponse(label: String): ByteArray? {
        val svc = BleBridgeService.getInstance() ?: return null
        val data = withTimeoutOrNull(4000L) { authDataQueue.receive() }
        if (data == null || data.size < 4) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Timeout or invalid data for $label (size=${data?.size ?: 0})"
            )
            return null
        }

        // Inline format: 00 00 02 XX [data]
        if (data[2] == 0x02.toByte()) {
            val payload = data.copyOfRange(4, data.size)
            // Send ACK: 00 00 03 00
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                byteArrayOf(0x00, 0x00, 0x03, 0x00),
                withResponse = false
            )
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Received inline $label (${payload.size}B)"
            )
            return payload
        } else if (data[2] == 0x00.toByte() && data.size >= 6) {
            // Multi-frame header: 00 00 00 XX count_lo count_hi
            val frameCount = minOf((data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8), 100)
            val dataId = data[3].toInt() and 0xFF
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Multi-frame $label: $frameCount frames, ID=0x%02X".format(dataId)
            )

            // Send RCV_RDY: 00 00 01 01
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                CuktechProtocol.RCV_RDY,
                withResponse = false
            )

            var received = ByteArray(0)
            for (i in 0 until frameCount) {
                val frame = withTimeoutOrNull(3000L) { authDataQueue.receive() }
                if (frame == null) {
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL1,
                        "[Cuktech] Frame ${i + 1}/$frameCount timeout for $label"
                    )
                    break
                }
                if (frame.size > 2) {
                    received += frame.copyOfRange(2, frame.size)
                }
            }

            // Send RCV_OK: 00 00 01 00
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                CuktechProtocol.RCV_OK,
                withResponse = false
            )
            return received
        } else {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Unknown auth response format for $label: byte[2]=0x%02X, size=${data.size}".format(data[2])
            )
            return null
        }
    }

    private suspend fun runAuthenticationHandshake(tokenBytes: ByteArray): Boolean {
        val svc = BleBridgeService.getInstance() ?: return false
        try {
            drainChannel(authCtrlQueue)
            drainChannel(authDataQueue)

            // Step 1: Subscribe to CMD_RECV (0x001b), CMD_SEND (0x001a), DEVICE_INFO (0x001c), AUTH_DATA (0x0019)
            _authState.value = CuktechAuthState.PHASE_A_INIT_A4
            _statusMessage.value = "Subscribing to MiOT characteristics..."
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Subscribing to MiOT characteristics..."
            )

            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_CMD_RECV, true)
            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_CMD_SEND, true)
            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_DEVICE_INFO, true)
            val subAuthDataOk = svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_AUTH_DATA, true)
            if (!subAuthDataOk) {
                _statusMessage.value = "Failed to subscribe to CHAR_AUTH_DATA (0x0019)"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Failed to subscribe to CHAR_AUTH_DATA (0x0019)"
                )
                return false
            }

            // Step 2: Phase A (0xA4 Pre-Handshake Initialization)
            _statusMessage.value = "Phase A: Sending 0xA4 initialization command..."
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase A: Sending 0xA4 initialization command..."
            )
            drainChannel(authDataQueue)
            val a4WriteOk = svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_CTRL,
                CuktechProtocol.CMD_INIT_A4,
                withResponse = false
            )
            if (!a4WriteOk) {
                _statusMessage.value = "Failed to write 0xA4 to CHAR_AUTH_CTRL"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Failed to write 0xA4 to CHAR_AUTH_CTRL"
                )
                return false
            }

            val initResp = withTimeoutOrNull(3000L) { authDataQueue.receive() }
            if (initResp == null) {
                _statusMessage.value = "Phase A: No 0xA4 initialization response received"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase A: No 0xA4 initialization response received from charger"
                )
                return false
            }
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase A: Init response: ${CuktechProtocol.bytesToHex(initResp)}"
            )

            // Protocol negotiation response: byte[2] += 1 (04 -> 05)
            val ack = initResp.copyOf()
            if (ack.size >= 3) {
                ack[2] = ((ack[2].toInt() + 1) and 0xFF).toByte()
            }
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                ack,
                withResponse = false
            )

            // Receive key exchange data (device sends placeholder frame)
            var keyData = withTimeoutOrNull(5000L) { authDataQueue.receive() }
            if (keyData != null) {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL2,
                    "[Cuktech] Phase A: Received key exchange data: ${keyData.size} bytes"
                )
            }

            // Desync check: byte[2] == 0x04 && size < 20 (duplicate init response instead of key exchange)
            val isDesync = (keyData != null && keyData.size in 3..19 && keyData[2] == 0x04.toByte())
            if (isDesync) {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL2,
                    "[Cuktech] Phase A: Device state desync detected (${keyData!!.size}B), waiting for recovery..."
                )
                val deadline = System.currentTimeMillis() + 12000L
                for (retry in 0 until 3) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val extra = withTimeoutOrNull(minOf(remaining, 4000L)) { authDataQueue.receive() }
                    if (extra != null && extra.size >= 20 && extra[2] == 0x04.toByte()) {
                        keyData = extra
                        BleBridgeService.addLogMessage(
                            BleBridgeService.Companion.LogLevel.LEVEL2,
                            "[Cuktech] Phase A: Received recovered key exchange data: ${extra.size} bytes"
                        )
                        break
                    }
                }
            }

            if (keyData == null) {
                _statusMessage.value = "Phase A: Did not receive key exchange data"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase A: Failed to receive key exchange data"
                )
                return false
            }

            // Send placeholder frame with matching length (0x00 0x00 0x05 0x01 + 0xF2 padding)
            val padLen = maxOf(0, keyData.size - 4)
            val placeholder = ByteArray(4 + padLen)
            placeholder[0] = 0x00
            placeholder[1] = 0x00
            placeholder[2] = 0x05
            placeholder[3] = 0x01
            for (i in 0 until padLen) {
                placeholder[4 + i] = 0xF2.toByte()
            }
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                placeholder,
                withResponse = false
            )

            // Drain leftover notifications
            delay(300L)
            drainChannel(authDataQueue)
            delay(300L)
            drainChannel(authDataQueue)

            // Step 3: Phase B (CMD_LOGIN = 0x24)
            _authState.value = CuktechAuthState.PHASE_B_HANDSHAKE
            _statusMessage.value = "Phase B: Sending CMD_LOGIN (0x24) for key negotiation..."
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: Subscribing to CHAR_AUTH_CTRL..."
            )

            // Subscribe to CHAR_AUTH_CTRL (aligned with Mi Home timing)
            try {
                svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_AUTH_CTRL, true)
            } catch (e: Exception) {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL2,
                    "[Cuktech] Phase B: auth_ctrl subscribe: ${e.message}"
                )
            }
            drainChannel(authCtrlQueue)
            drainChannel(authDataQueue)
            delay(50L)

            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: Sending CMD_LOGIN (0x24)..."
            )
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_CTRL,
                CuktechProtocol.CMD_LOGIN,
                withResponse = false
            )

            // Step 4: Send App Random Key
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: Sending CMD_SEND_KEY..."
            )
            val randKey = ByteArray(16).also { random.nextBytes(it) }
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                CuktechProtocol.CMD_SEND_KEY,
                withResponse = false
            )

            // Wait for RCV_RDY (skipping any stale key exchange frames, up to 5 retries)
            var gotRdy1 = false
            for (retry in 0 until 5) {
                val data = withTimeoutOrNull(3000L) { authDataQueue.receive() }
                if (data != null && data.contentEquals(CuktechProtocol.RCV_RDY)) {
                    gotRdy1 = true
                    break
                }
            }
            if (!gotRdy1) {
                _statusMessage.value = "Timeout waiting for RCV_RDY after CMD_SEND_KEY"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase B: Timeout waiting for RCV_RDY after CMD_SEND_KEY"
                )
                return false
            }

            // Send random key with header [0x01, 0x00]
            val keyFrame = byteArrayOf(0x01, 0x00) + randKey
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                keyFrame,
                withResponse = false
            )

            // Wait for RCV_OK
            val ok1 = withTimeoutOrNull(3000L) { authDataQueue.receive() }
            if (ok1 == null || !ok1.contentEquals(CuktechProtocol.RCV_OK)) {
                _statusMessage.value = "Did not receive RCV_OK after sending random key"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase B: Did not receive RCV_OK after sending random key"
                )
                return false
            }

            // Step 5: Receive Device Random Key (16B) and Device HMAC (32B)
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: Receiving device random key..."
            )
            val devRandomRaw = recvAuthResponse("device random")
            if (devRandomRaw == null || devRandomRaw.size < 16) {
                _statusMessage.value = "Invalid device random key received"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase B: Invalid device random key (size=${devRandomRaw?.size ?: 0})"
                )
                return false
            }
            val devRandom = devRandomRaw.copyOfRange(0, 16)

            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: Receiving device HMAC..."
            )
            val devHmacRaw = recvAuthResponse("device HMAC")
            if (devHmacRaw == null || devHmacRaw.size < 32) {
                _statusMessage.value = "Invalid device HMAC received"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase B: Invalid device HMAC (size=${devHmacRaw?.size ?: 0})"
                )
                return false
            }
            val devHmac = devHmacRaw.copyOfRange(0, 32)

            // Step 6: Derive Session Keys & Verify Device HMAC
            val derived = CuktechProtocol.deriveSessionKeys(tokenBytes, randKey, devRandom)
            val expectedDevHmac = CuktechProtocol.computeDeviceExpectedHmac(derived.devKey, devRandom, randKey)
            if (!devHmac.contentEquals(expectedDevHmac)) {
                _statusMessage.value = "Device HMAC verification failed! Invalid token or BLE key."
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Device HMAC verification failed! Verify MiOT token in Xiaomi account."
                )
                return false
            }
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: Device HMAC verified successfully!"
            )

            // Step 7: Send App HMAC Confirmation
            val ourHmac = CuktechProtocol.computeAppHmac(derived.appKey, randKey, devRandom)
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                CuktechProtocol.CMD_SEND_INFO,
                withResponse = false
            )

            val rdy2 = withTimeoutOrNull(3000L) { authDataQueue.receive() }
            if (rdy2 == null || !rdy2.contentEquals(CuktechProtocol.RCV_RDY)) {
                _statusMessage.value = "Did not receive RCV_RDY before sending HMAC"
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL1,
                    "[Cuktech] Phase B: Did not receive RCV_RDY before sending HMAC"
                )
                return false
            }

            // Send HMAC (single 34-byte frame: 0x01 0x00 + 32-byte HMAC)
            val hmacFrame = byteArrayOf(0x01, 0x00) + ourHmac
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                hmacFrame,
                withResponse = false
            )

            val ok2 = withTimeoutOrNull(3000L) { authDataQueue.receive() }
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Phase B: HMAC ACK: ${ok2?.let { CuktechProtocol.bytesToHex(it) } ?: "null"}"
            )

            // Step 8: Phase 6 Challenge-Response loop (up to 8 seconds)
            try {
                val deadline = System.currentTimeMillis() + 8000L
                var responseData: ByteArray? = null

                while (System.currentTimeMillis() < deadline) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val data = withTimeoutOrNull(minOf(remaining, 3000L)) { authDataQueue.receive() } ?: continue

                    // Challenge: 00 00 02 0d [16B]
                    if (data.size >= 3 && data[2] == 0x0d.toByte()) {
                        BleBridgeService.addLogMessage(
                            BleBridgeService.Companion.LogLevel.LEVEL2,
                            "[Cuktech] Phase 6: Challenge received (${data.size}B)"
                        )
                        svc.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_AUTH_DATA,
                            byteArrayOf(0x00, 0x00, 0x03, 0x00),
                            withResponse = false
                        )
                    } else if (data.size >= 3 && data[2] == 0x0c.toByte()) {
                        // Response: 00 00 02 0c [32B]
                        responseData = data
                        BleBridgeService.addLogMessage(
                            BleBridgeService.Companion.LogLevel.LEVEL2,
                            "[Cuktech] Phase 6: Response received (${data.size}B)"
                        )
                        svc.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_AUTH_DATA,
                            byteArrayOf(0x00, 0x00, 0x03, 0x00),
                            withResponse = false
                        )
                        svc.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_AUTH_DATA,
                            CuktechProtocol.CMD_SEND_INFO,
                            withResponse = false
                        )
                    } else if (data.contentEquals(CuktechProtocol.RCV_RDY)) {
                        break
                    } else if (data.contentEquals(CuktechProtocol.RCV_OK)) {
                        break
                    }
                }

                if (responseData != null) {
                    val authRespPayload = byteArrayOf(0x01, 0x00, 0x0c) + responseData.copyOfRange(3, responseData.size)
                    svc.enqueueBleWrite(
                        CuktechProtocol.MI_SERVICE_UUID,
                        CuktechProtocol.CHAR_AUTH_DATA,
                        authRespPayload,
                        withResponse = false
                    )
                    withTimeoutOrNull(3000L) { authDataQueue.receive() }
                }
            } catch (e: Exception) {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL2,
                    "[Cuktech] Phase 6 completed: ${e.message}"
                )
            }

            // Step 9: Final Auth Status on CHAR_AUTH_CTRL
            val ctrlResult = withTimeoutOrNull(5000L) { authCtrlQueue.receive() }
            if (ctrlResult != null && ctrlResult.isNotEmpty()) {
                val statusByte = ctrlResult[0].toInt() and 0xFF
                if (statusByte == 0x21 || statusByte == 0x11) {
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL2,
                        "[Cuktech] Authentication successful (Status 0x%02X)".format(statusByte)
                    )
                } else {
                    _statusMessage.value = "Authentication status rejected: 0x%02X".format(statusByte)
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL1,
                        "[Cuktech] Authentication status rejected: 0x%02X".format(statusByte)
                    )
                    return false
                }
            } else {
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL2,
                    "[Cuktech] No final auth status packet, assuming authenticated by session key agreement"
                )
            }

            // Save session keys and reset counters
            sessionKeys = derived
            appItCounter = 1L
            devItHi = 0L
            lastDevItLo = 0
            reqCounter = 0x0300
            return true
        } catch (e: Exception) {
            _statusMessage.value = "Handshake error: ${e.message}"
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Handshake error: ${e.message}"
            )
            return false
        }
    }

    private fun drainChannel(ch: Channel<ByteArray>) {
        while (ch.tryReceive().isSuccess) {
            // Drain queued notifications
        }
    }

    // =========================================================================
    // 4. NOTIFICATION ROUTING & AES-CCM DECRYPTION
    // =========================================================================

    fun onCharacteristicChanged(charUuid: UUID, value: ByteArray) {
        when (charUuid) {
            CuktechProtocol.CHAR_AUTH_CTRL -> {
                authCtrlQueue.trySend(value.copyOf())
            }
            CuktechProtocol.CHAR_AUTH_DATA -> {
                authDataQueue.trySend(value.copyOf())
            }
            CuktechProtocol.CHAR_CMD_SEND -> {
                cmdSendQueue.trySend(value.copyOf())
            }
            CuktechProtocol.CHAR_DEVICE_INFO -> {
                val info = PortTelemetryDecoder.decodeAsciiInfo(value)
                if (info.isNotBlank()) {
                    _deviceInfo.value = _deviceInfo.value.copy(serialNumber = info)
                }
            }
            CuktechProtocol.CHAR_CMD_RECV -> {
                if (isAssemblingMultiFrame) {
                    cmdRecvSubframeQueue.trySend(value.copyOf())
                } else {
                    handleCmdRecvNotification(value.copyOf())
                }
            }
        }
    }

    private fun handleCmdRecvNotification(raw: ByteArray) {
        if (raw.size < 3) return
        val frameType = raw[2].toInt() and 0xFF

        if (frameType == 0x02) {
            // Inline format: [00 00 02 XX it_lo it_hi ciphertext tag]
            scope.launch {
                BleBridgeService.getInstance()?.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_CMD_RECV,
                    byteArrayOf(0x00, 0x00, 0x03, 0x00),
                    withResponse = false
                )
            }
            if (raw.size < 6) return
            val payload = raw.copyOfRange(4, raw.size)
            decryptAndDispatch(payload, raw)
        } else if (frameType == 0x00 && raw.size >= 6) {
            // Multi-frame header: [00 00 00 XX count_lo count_hi]
            scope.launch {
                multiFrameMutex.withLock {
                    drainChannel(cmdRecvSubframeQueue)
                    isAssemblingMultiFrame = true
                    try {
                        val rawCount = (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
                        val frameCount = minOf(rawCount, 100)

                        // Write RCV_RDY to start receiving subframes
                        BleBridgeService.getInstance()?.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_CMD_RECV,
                            byteArrayOf(0x00, 0x00, 0x01, 0x01),
                            withResponse = false
                        )

                        var assembled = ByteArray(0)
                        var receivedCount = 0
                        val deadline = System.currentTimeMillis() + 10000L
                        for (i in 0 until frameCount) {
                            val remaining = deadline - System.currentTimeMillis()
                            if (remaining <= 0) break
                            val subframe = withTimeoutOrNull(minOf(remaining, 3000L)) {
                                cmdRecvSubframeQueue.receive()
                            }
                            if (subframe == null) break
                            receivedCount++
                            // Strip 2-byte frame index (subframe[0..1])
                            if (subframe.size > 2) {
                                assembled += subframe.copyOfRange(2, subframe.size)
                            }
                        }

                        // Write RCV_OK to finish multi-frame transfer
                        BleBridgeService.getInstance()?.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_CMD_RECV,
                            byteArrayOf(0x00, 0x00, 0x01, 0x00),
                            withResponse = false
                        )

                        if (receivedCount == rawCount && assembled.size >= 6) {
                            decryptAndDispatch(assembled, raw)
                        } else {
                            BleBridgeService.addLogMessage(
                                BleBridgeService.Companion.LogLevel.LEVEL2,
                                "[Cuktech] Multiframe incomplete: received $receivedCount/$rawCount frames (${assembled.size}B)"
                            )
                        }
                    } finally {
                        isAssemblingMultiFrame = false
                        drainChannel(cmdRecvSubframeQueue)
                    }
                }
            }
        }
    }

    private fun decryptAndDispatch(payloadToDecrypt: ByteArray, rawContext: ByteArray) {
        val keys = sessionKeys ?: return
        if (payloadToDecrypt.size < 6) return

        val itLo = (payloadToDecrypt[0].toInt() and 0xFF) or ((payloadToDecrypt[1].toInt() and 0xFF) shl 8)
        val ciphertextWithTag = payloadToDecrypt.copyOfRange(2, payloadToDecrypt.size)

        // Detect 16-bit counter rollover
        if (itLo < lastDevItLo && (lastDevItLo - itLo) > 32768) {
            devItHi += 0x10000L
        }
        lastDevItLo = itLo

        val candidateCounters = listOf(
            devItHi or itLo.toLong(),
            (devItHi + 0x10000L) or itLo.toLong(),
            max(0L, devItHi - 0x10000L) or itLo.toLong()
        )

        var plaintext: ByteArray? = null
        for (counter in candidateCounters) {
            val nonce = CuktechProtocol.buildNonce(keys.devIv, counter)
            val attempt = runCatching {
                CuktechProtocol.aesCcmDecrypt(keys.devKey, nonce, ciphertextWithTag, aad = ByteArray(0))
            }.getOrNull()
            if (attempt != null) {
                plaintext = attempt
                devItHi = counter and 0xFFFF0000L
                break
            }
        }

        if (plaintext == null) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL2,
                "[Cuktech] Failed to decrypt payload (${payloadToDecrypt.size}B, itLo=$itLo)"
            )
            return
        }

        processDecryptedFrame(plaintext)
    }

    private fun processDecryptedFrame(decrypted: ByteArray) {
        if (decrypted.size < 8) return
        val b4 = decrypted[4].toInt() and 0xFF
        val siid = decrypted[6].toInt() and 0xFF
        val piid = if (decrypted.size > 8) {
            (decrypted[7].toInt() and 0xFF) or ((decrypted[8].toInt() and 0xFF) shl 8)
        } else {
            decrypted[7].toInt() and 0xFF
        }

        // Hardware protocol push (b4 == 0x04, siid == 2, piid in 17..18)
        if (b4 == 0x04 && siid == CuktechProtocol.SIID_CHARGER && (piid == 17 || piid == 18)) {
            if (decrypted.size >= 16) {
                val val32 = (decrypted[12].toLong() and 0xFF) or
                    ((decrypted[13].toLong() and 0xFF) shl 8) or
                    ((decrypted[14].toLong() and 0xFF) shl 16) or
                    ((decrypted[15].toLong() and 0xFF) shl 24)
                handleHwProtocolPush(piid, val32)
            }
            return
        }

        // Live Port Push notification (b4 == 0x04) OR GET response (b4 == 0x03) for ports 1..4
        if ((b4 == 0x04 || b4 == 0x03) && siid == CuktechProtocol.SIID_CHARGER && piid in 1..4) {
            val curPorts = _ports.value
            val existing = curPorts[piid]
            val decoded = PortTelemetryDecoder.decodePortV2(piid, decrypted, existing)
            if (decoded != null) {
                updatePortTelemetry(piid, decoded)
                BleBridgeService.addLogMessage(
                    BleBridgeService.Companion.LogLevel.LEVEL2,
                    "[Cuktech] Port $piid Telemetry: ${decoded.voltageV}V ${decoded.currentA}A ${decoded.powerW}W (${decoded.protocolName})"
                )
            }
        }

        val frame = PortTelemetryDecoder.parseMiotFrame(decrypted)
        if (frame != null) {
            // Complete any awaiting command deferred
            val match = pendingMiotResponses.remove(frame.reqId)
            if (match != null) {
                match.complete(frame)
            } else if (pendingMiotResponses.isNotEmpty() && frame.b4 in listOf(0x01, 0x03, 0x04)) {
                // Complete any actively awaiting command if seq didn't match directly
                val firstKey = pendingMiotResponses.keys.firstOrNull()
                if (firstKey != null) {
                    pendingMiotResponses.remove(firstKey)?.complete(frame)
                }
            }
            applyDecodedMiotFrame(frame)
        }
    }

    private fun handleHwProtocolPush(piid: Int, val32: Long) {
        val lowHalf = (val32 and 0xFFFF).toInt()
        val highHalf = ((val32 shr 16) and 0xFFFF).toInt()
        val hiProto = (highHalf shr 8) and 0xFF
        val loProto = (lowHalf shr 8) and 0xFF

        val curPorts = _ports.value.toMutableMap()
        if (piid == 17) {
            // C1 (high) and C2 (low)
            val p1 = curPorts[1]
            if (p1 != null && hiProto > 0) {
                curPorts[1] = p1.copy(
                    mijiaProtocolCode = hiProto,
                    protocolName = PortTelemetryDecoder.MIJIA_PROTOCOL_MAP[hiProto] ?: p1.protocolName
                )
            }
            val p2 = curPorts[2]
            if (p2 != null && loProto > 0) {
                curPorts[2] = p2.copy(
                    mijiaProtocolCode = loProto,
                    protocolName = PortTelemetryDecoder.MIJIA_PROTOCOL_MAP[loProto] ?: p2.protocolName
                )
            }
        } else if (piid == 18) {
            // C3 (high) and A (low)
            val p3 = curPorts[3]
            if (p3 != null && hiProto > 0) {
                curPorts[3] = p3.copy(
                    mijiaProtocolCode = hiProto,
                    protocolName = PortTelemetryDecoder.MIJIA_PROTOCOL_MAP[hiProto] ?: p3.protocolName
                )
            }
            val p4 = curPorts[4]
            if (p4 != null && loProto > 0) {
                curPorts[4] = p4.copy(
                    mijiaProtocolCode = loProto,
                    protocolName = PortTelemetryDecoder.MIJIA_PROTOCOL_MAP[loProto] ?: p4.protocolName
                )
            }
        }
        _ports.value = curPorts
    }

    private fun updatePortTelemetry(portId: Int, decoded: PortTelemetry) {
        val curPorts = _ports.value.toMutableMap()
        val nowSec = System.currentTimeMillis() / 1000.0
        val integrator = energyIntegrators[portId]!!
        val deltaWh = integrator.update(decoded.powerW, nowSec)
        val cumulativeWh = integrator.totalWh

        val detector = sessionDetectors[portId]!!
        val finishedSession = detector.onSample(decoded.powerW, deltaWh, decoded.protocolName, nowSec)
        if (finishedSession != null) {
            persistence.insertCompletedSession(finishedSession)
            _completedSessions.value = persistence.getRecentSessions(200)
        }

        val updatedActive = _activeSessions.value.toMutableMap()
        val curActive = detector.activeSession
        if (curActive != null) {
            updatedActive[portId] = curActive
        } else {
            updatedActive.remove(portId)
        }
        _activeSessions.value = updatedActive

        val updatedPort = decoded.copy(cumulativeWh = cumulativeWh)
        curPorts[portId] = updatedPort
        _ports.value = curPorts

        val totalW = curPorts.values.sumOf { it.powerW }
        val roundedTotalW = (totalW * 100.0).roundToInt() / 100.0
        val oldTotalW = _totalPowerWatts.value
        _totalPowerWatts.value = roundedTotalW

        if (abs(oldTotalW - roundedTotalW) >= 0.5 || System.currentTimeMillis() - lastHistoryWriteMs >= 5000L) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL3,
                "[Cuktech] Telemetry: Total=%.1fW (C1=%.1fW, C2=%.1fW, C3=%.1fW, A=%.1fW)".format(
                    roundedTotalW,
                    curPorts[1]?.powerW ?: 0.0,
                    curPorts[2]?.powerW ?: 0.0,
                    curPorts[3]?.powerW ?: 0.0,
                    curPorts[4]?.powerW ?: 0.0
                )
            )
        }

        // Check per-port charge limit auto-cutoff
        checkAndEnforcePortChargeLimit(portId, cumulativeWh)

        // Record chart point & periodic SQLite history
        recordChartAndHistory(curPorts, roundedTotalW)

        onTelemetryUpdated?.invoke(curPorts, roundedTotalW, _deviceInfo.value)
    }

    private fun applyDecodedMiotFrame(frame: DecodedMiotFrame) {
        // Opcode 0x01 is SET ACK — it confirms a write operation and carries no property payload
        if (frame.b4 == 0x01) return

        if (frame.siid == CuktechProtocol.SIID_DEVICE_INFO) {
            val text = PortTelemetryDecoder.decodeAsciiInfo(frame.payload)
            if (text.isNotBlank()) {
                val cur = _deviceInfo.value
                _deviceInfo.value = when (frame.piid) {
                    1 -> cur.copy(manufacturer = text)
                    2 -> cur.copy(model = text)
                    3 -> cur.copy(serialNumber = text)
                    4 -> cur.copy(firmwareVersion = text)
                    else -> cur
                }
            }
            return
        }

        if (frame.siid != CuktechProtocol.SIID_CHARGER) return

        when (frame.piid) {
            CuktechProtocol.PIID_PORT_C1,
            CuktechProtocol.PIID_PORT_C2,
            CuktechProtocol.PIID_PORT_C3,
            CuktechProtocol.PIID_PORT_A -> {
                val portId = frame.piid
                val curPorts = _ports.value
                val existing = curPorts[portId]
                val decoded = PortTelemetryDecoder.decodePortV2(portId, frame.payload, existing) ?: return
                updatePortTelemetry(portId, decoded)
            }

            CuktechProtocol.PIID_PORT_C1_PROTOCOL,
            CuktechProtocol.PIID_PORT_C2_PROTOCOL -> {
                val portId = if (frame.piid == CuktechProtocol.PIID_PORT_C1_PROTOCOL) 1 else 2
                if (frame.payload.isNotEmpty()) {
                    val code = frame.payload[0].toInt() and 0xFF
                    val curPorts = _ports.value.toMutableMap()
                    val existing = curPorts[portId] ?: return
                    val newName = PortTelemetryDecoder.resolveProtocolName(
                        portId = portId,
                        status = existing.status,
                        voltageV = existing.voltageV,
                        currentA = existing.currentA,
                        mijiaProtocolCode = code
                    )
                    curPorts[portId] = existing.copy(
                        mijiaProtocolCode = code,
                        protocolName = newName
                    )
                    _ports.value = curPorts
                }
            }

            CuktechProtocol.PIID_PORT_C1_PDO,
            CuktechProtocol.PIID_PORT_C2_PDO -> {
                val portId = if (frame.piid == CuktechProtocol.PIID_PORT_C1_PDO) 1 else 2
                val caps = PortTelemetryDecoder.decodePdoCaps(frame.payload)
                if (caps != null) {
                    val curPorts = _ports.value.toMutableMap()
                    val existing = curPorts[portId] ?: return
                    curPorts[portId] = existing.copy(pdoCaps = caps)
                    _ports.value = curPorts
                }
            }

            CuktechProtocol.PIID_SCENE_MODE -> {
                if (frame.payload.isNotEmpty()) {
                    _settings.value = _settings.value.copy(sceneMode = frame.payload[0].toInt() and 0xFF)
                }
            }

            CuktechProtocol.PIID_SCREEN_TIMEOUT -> {
                if (frame.payload.isNotEmpty()) {
                    _settings.value = _settings.value.copy(screenTimeout = frame.payload[0].toInt() and 0xFF)
                }
            }

            CuktechProtocol.PIID_COUNTDOWN_C1,
            CuktechProtocol.PIID_COUNTDOWN_C2,
            CuktechProtocol.PIID_COUNTDOWN_C3,
            CuktechProtocol.PIID_COUNTDOWN_A -> {
                if (frame.payload.isNotEmpty()) {
                    val portId = frame.piid - CuktechProtocol.PIID_COUNTDOWN_C1 + 1
                    val minutes = if (frame.payload.size >= 2) {
                        (frame.payload[0].toInt() and 0xFF) or ((frame.payload[1].toInt() and 0xFF) shl 8)
                    } else {
                        frame.payload[0].toInt() and 0xFF
                    }
                    val curPorts = _ports.value.toMutableMap()
                    val existing = curPorts[portId] ?: return
                    curPorts[portId] = existing.copy(countdownCode = minutes)
                    _ports.value = curPorts

                    if (minutes > 0) {
                        if (!targetCountdownEpochSec.containsKey(portId)) {
                            targetCountdownEpochSec[portId] = (System.currentTimeMillis() / 1000L) + (minutes * 60L)
                        }
                    } else {
                        targetCountdownEpochSec.remove(portId)
                        _countdownRemainingSeconds.value = _countdownRemainingSeconds.value - portId
                    }
                }
            }

            CuktechProtocol.PIID_LANGUAGE -> {
                if (frame.payload.isNotEmpty()) {
                    val raw = frame.payload[0].toInt() and 0xFF
                    if (raw in 0..1) {
                        _settings.value = _settings.value.copy(language = raw)
                    }
                }
            }

            CuktechProtocol.PIID_USB_A_LOW_CURRENT -> {
                if (frame.payload.isNotEmpty()) {
                    _settings.value = _settings.value.copy(usbALowCurrent = (frame.payload[0].toInt() and 0xFF) != 0)
                }
            }

            CuktechProtocol.PIID_PORT_SW_BITMAP -> {
                if (frame.payload.isNotEmpty()) {
                    val bitmap = frame.payload[0].toInt() and 0x0F
                    _settings.value = _settings.value.copy(portSwitchBitmap = bitmap)
                    val curPorts = _ports.value.toMutableMap()
                    for (pid in 1..4) {
                        val p = curPorts[pid] ?: continue
                        val en = (bitmap and (1 shl (pid - 1))) != 0
                        curPorts[pid] = p.copy(enabled = en)
                    }
                    _ports.value = curPorts
                }
            }

            CuktechProtocol.PIID_IDLE_SCREEN_OFF -> {
                if (frame.payload.isNotEmpty()) {
                    _settings.value = _settings.value.copy(idleScreenOff = (frame.payload[0].toInt() and 0xFF) != 0)
                }
            }

            CuktechProtocol.PIID_ORIENTATION_LOCK -> {
                if (frame.payload.isNotEmpty()) {
                    _settings.value = _settings.value.copy(orientationLock = (frame.payload[0].toInt() and 0xFF) != 0)
                }
            }

            CuktechProtocol.PIID_PROTOCOL_EXTEND -> {
                if (frame.payload.size >= 4) {
                    val val32 = (frame.payload[0].toLong() and 0xFF) or
                        ((frame.payload[1].toLong() and 0xFF) shl 8) or
                        ((frame.payload[2].toLong() and 0xFF) shl 16) or
                        ((frame.payload[3].toLong() and 0xFF) shl 24)

                    val c1 = (val32 and 0xFF).toInt()
                    val c2 = ((val32 shr 8) and 0xFF).toInt()
                    val c3 = ((val32 shr 16) and 0xFF).toInt()
                    val a = ((val32 shr 24) and 0xFF).toInt()

                    _settings.value = _settings.value.copy(
                        rawProtocolExtendVal = val32,
                        c1Pd = (c1 and 0x01) != 0,
                        c1Pps = (c1 and 0x02) != 0,
                        c1Ufcs = (c1 and 0x04) != 0,
                        c2Pd = (c2 and 0x01) != 0,
                        c2Pps = (c2 and 0x02) != 0,
                        c2Ufcs = (c2 and 0x04) != 0,
                        c3Ufcs = (c3 and 0x01) != 0,
                        c3Scp = (c3 and 0x02) != 0,
                        aUfcs = (a and 0x01) != 0,
                        aScp = (a and 0x02) != 0
                    )
                }
            }
        }
    }

    private fun recordChartAndHistory(portsMap: Map<Int, PortTelemetry>, totalW: Double) {
        val now = System.currentTimeMillis()
        val point = PowerChartPoint(
            timestampMs = now,
            totalWatts = totalW,
            portWatts = portsMap.mapValues { it.value.powerW }
        )
        val chart = _powerChartHistory.value.toMutableList()
        chart.add(point)
        while (chart.size > 120) {
            chart.removeAt(0)
        }
        _powerChartHistory.value = chart

        if (now - lastHistoryWriteMs >= 5000L) {
            lastHistoryWriteMs = now
            persistence.recordPortSnapshot(portsMap.values, now)
        }
    }

    // =========================================================================
    // 5. MIOT ENCRYPTED COMMAND SENDER & CHARGER CONTROLS
    // =========================================================================

    suspend fun sendMiotCommand(
        siid: Int,
        piid: Int,
        isSet: Boolean = false,
        valueBytes: ByteArray = ByteArray(0),
        fmtOverride: Int? = null,
        timeoutMs: Long = 2500L
    ): DecodedMiotFrame? = cmdMutex.withLock {
        val keys = sessionKeys ?: return@withLock null
        val svc = BleBridgeService.getInstance() ?: return@withLock null

        drainChannel(cmdSendQueue)

        val seq = miotSeq and 0xFF
        miotSeq = (miotSeq + 1) and 0xFF

        val currentCounter = appItCounter
        appItCounter++

        val tlvPlain = if (isSet || valueBytes.isNotEmpty()) {
            if (valueBytes.size > 4) {
                CuktechProtocol.buildMiotTlv(seq = seq, siid = siid, piid = piid, rawBytes = valueBytes)
            } else if (valueBytes.size == 1) {
                CuktechProtocol.buildMiotTlv(seq = seq, siid = siid, piid = piid, value = valueBytes[0].toLong() and 0xFFL)
            } else if (valueBytes.size == 2) {
                val v = (valueBytes[0].toLong() and 0xFFL) or ((valueBytes[1].toLong() and 0xFFL) shl 8)
                CuktechProtocol.buildMiotTlv(seq = seq, siid = siid, piid = piid, value = v)
            } else if (valueBytes.size == 4) {
                val v = (valueBytes[0].toLong() and 0xFFL) or
                    ((valueBytes[1].toLong() and 0xFFL) shl 8) or
                    ((valueBytes[2].toLong() and 0xFFL) shl 16) or
                    ((valueBytes[3].toLong() and 0xFFL) shl 24)
                CuktechProtocol.buildMiotTlv(seq = seq, siid = siid, piid = piid, value = v)
            } else {
                CuktechProtocol.buildMiotTlv(seq = seq, siid = siid, piid = piid, rawBytes = valueBytes)
            }
        } else {
            CuktechProtocol.buildMiotTlv(seq = seq, siid = siid, piid = piid)
        }

        val nonce = CuktechProtocol.buildNonce(keys.appIv, currentCounter)
        val ciphertextWithTag = CuktechProtocol.aesCcmEncrypt(keys.appKey, nonce, tlvPlain, aad = ByteArray(0))
        val itBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((currentCounter and 0xFFFFFFFFL).toInt()).array()
        val encryptedData = itBytes.copyOfRange(0, 2) + ciphertextWithTag

        val deferred = CompletableDeferred<DecodedMiotFrame>()
        pendingMiotResponses[seq] = deferred
        totalTxCommands++

        // Step 1: Write header [00 00 00 00 01 00] (1 frame) to CHAR_CMD_SEND
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x00)
        val hOk = svc.enqueueBleWrite(
            CuktechProtocol.MI_SERVICE_UUID,
            CuktechProtocol.CHAR_CMD_SEND,
            header,
            withResponse = false
        )
        if (!hOk) {
            pendingMiotResponses.remove(seq)
            consecutiveFailures++
            return@withLock null
        }

        // Step 2: Wait for RCV_RDY [00 00 01 01] on CHAR_CMD_SEND
        val rdy = withTimeoutOrNull(2500L) { cmdSendQueue.receive() }
        if (rdy == null || !rdy.contentEquals(CuktechProtocol.RCV_RDY)) {
            pendingMiotResponses.remove(seq)
            consecutiveFailures++
            return@withLock null
        }

        // Step 3: Write frame [01 00] + encryptedData to CHAR_CMD_SEND
        val frame = byteArrayOf(0x01, 0x00) + encryptedData
        val fOk = svc.enqueueBleWrite(
            CuktechProtocol.MI_SERVICE_UUID,
            CuktechProtocol.CHAR_CMD_SEND,
            frame,
            withResponse = false
        )
        if (!fOk) {
            pendingMiotResponses.remove(seq)
            consecutiveFailures++
            return@withLock null
        }

        // Step 4: Wait for RCV_OK [00 00 01 00] on CHAR_CMD_SEND
        val ok = withTimeoutOrNull(2500L) { cmdSendQueue.receive() }
        if (ok == null || !ok.contentEquals(CuktechProtocol.RCV_OK)) {
            pendingMiotResponses.remove(seq)
            consecutiveFailures++
            return@withLock null
        }

        // If it's a SET / write-only command, receiving RCV_OK on CHAR_CMD_SEND means
        // the hardware charger has accepted and executed the command!
        if (isSet) {
            pendingMiotResponses.remove(seq)
            successfulTxCommands++
            consecutiveFailures = 0
            return@withLock DecodedMiotFrame(
                reqId = seq,
                b2 = 0x20,
                b4 = 0x01,
                siid = siid,
                piid = piid,
                fmt = 1,
                payload = valueBytes
            )
        }

        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingMiotResponses.remove(seq)
        if (resp != null) {
            successfulTxCommands++
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
        }
        resp
    }

    fun refreshAllTelemetryNow() {
        scope.launch {
            queryInitialChargerState()
        }
    }

    private suspend fun queryInitialChargerState() {
        // Device info (SIID 1)
        for (piid in listOf(2, 3, 4)) {
            sendMiotCommand(CuktechProtocol.SIID_DEVICE_INFO, piid, isSet = false, timeoutMs = 1500L)
        }
        // All 4 ports + protocols + settings (SIID 2)
        val initPiids = listOf(
            CuktechProtocol.PIID_PORT_SW_BITMAP,
            CuktechProtocol.PIID_PORT_C1,
            CuktechProtocol.PIID_PORT_C2,
            CuktechProtocol.PIID_PORT_C3,
            CuktechProtocol.PIID_PORT_A,
            CuktechProtocol.PIID_PORT_C1_PROTOCOL,
            CuktechProtocol.PIID_PORT_C2_PROTOCOL,
            CuktechProtocol.PIID_SCENE_MODE,
            CuktechProtocol.PIID_SCREEN_TIMEOUT,
            CuktechProtocol.PIID_LANGUAGE,
            CuktechProtocol.PIID_USB_A_LOW_CURRENT,
            CuktechProtocol.PIID_IDLE_SCREEN_OFF,
            CuktechProtocol.PIID_ORIENTATION_LOCK,
            CuktechProtocol.PIID_PROTOCOL_EXTEND,
            CuktechProtocol.PIID_PORT_C1_PDO,
            CuktechProtocol.PIID_PORT_C2_PDO
        )
        for (piid in initPiids) {
            if (_authState.value != CuktechAuthState.AUTHENTICATED) break
            sendMiotCommand(CuktechProtocol.SIID_CHARGER, piid, isSet = false, timeoutMs = 1500L)
        }
        BleBridgeService.getInstance()?.requestGattRssi()
    }

    private fun startBackgroundLoops() {
        stopBackgroundLoops()

        // Loop 1: C3 & USB-A Polling (since C1/C2 push via notifications while C3/A require GET)
        pollingJob = scope.launch {
            var cycle = 0
            while (isActive && _authState.value == CuktechAuthState.AUTHENTICATED) {
                delay(3000L)
                if (_authState.value != CuktechAuthState.AUTHENTICATED) break

                sendMiotCommand(CuktechProtocol.SIID_CHARGER, CuktechProtocol.PIID_PORT_C3, isSet = false, timeoutMs = 1800L)
                sendMiotCommand(CuktechProtocol.SIID_CHARGER, CuktechProtocol.PIID_PORT_A, isSet = false, timeoutMs = 1800L)

                if (cycle % 2 == 0) {
                    sendMiotCommand(CuktechProtocol.SIID_CHARGER, CuktechProtocol.PIID_PORT_C1, isSet = false, timeoutMs = 1800L)
                    sendMiotCommand(CuktechProtocol.SIID_CHARGER, CuktechProtocol.PIID_PORT_C2, isSet = false, timeoutMs = 1800L)
                    BleBridgeService.getInstance()?.requestGattRssi()
                }
                if (cycle % 5 == 0) {
                    sendMiotCommand(CuktechProtocol.SIID_CHARGER, CuktechProtocol.PIID_PORT_SW_BITMAP, isSet = false, timeoutMs = 1800L)
                }
                cycle++
            }
        }

        // Loop 2: 10-second Keepalive (`[00 00 00 00]` on `CHAR_CMD_RECV`)
        keepaliveJob = scope.launch {
            while (isActive && _authState.value == CuktechAuthState.AUTHENTICATED) {
                delay(10000L)
                if (_authState.value != CuktechAuthState.AUTHENTICATED) break
                BleBridgeService.getInstance()?.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_CMD_RECV,
                    CuktechProtocol.KEEPALIVE_PAYLOAD,
                    withResponse = false
                )
            }
        }
    }

    private fun stopBackgroundLoops() {
        pollingJob?.cancel()
        pollingJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    // =========================================================================
    // 6. SUPPORTED CHARGER & PORT CONTROLS (PIID 5, 6, 9..12, 13, 15, 16, 19, 20, 21)
    // =========================================================================

    fun setPortEnabled(portId: Int, enabled: Boolean) {
        if (portId !in 1..4) return
        scope.launch {
            val currentBitmap = _settings.value.portSwitchBitmap
            val bit = 1 shl (portId - 1)
            val newBitmap = if (enabled) (currentBitmap or bit) else (currentBitmap and bit.inv())
            val cleanBitmap = newBitmap and 0x0F
            // Optimistically update UI
            _settings.value = _settings.value.copy(portSwitchBitmap = cleanBitmap)
            val curPorts = _ports.value.toMutableMap()
            curPorts[portId]?.let { curPorts[portId] = it.copy(enabled = enabled) }
            _ports.value = curPorts

            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_PORT_SW_BITMAP,
                isSet = true,
                valueBytes = byteArrayOf(cleanBitmap.toByte())
            )
        }
    }

    /**
     * Executes a non-destructive soft port reset by toggling all port switches
     * (0x00 -> 0x0F). This discharges the DC-DC stages and triggers fresh USB-PD / PPS / UFCS
     * negotiation for all connected devices without unplugging from AC mains.
     */
    fun forceSoftRestart(onComplete: (() -> Unit)? = null) {
        scope.launch {
            _statusMessage.value = "Executing soft port reset..."
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_PORT_SW_BITMAP,
                isSet = true,
                valueBytes = byteArrayOf(0x00.toByte())
            )
            delay(1500L)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_PORT_SW_BITMAP,
                isSet = true,
                valueBytes = byteArrayOf(0x0F.toByte())
            )
            _settings.value = _settings.value.copy(portSwitchBitmap = 0x0F)
            val curPorts = _ports.value.toMutableMap()
            (1..4).forEach { pId ->
                curPorts[pId]?.let { curPorts[pId] = it.copy(enabled = true) }
            }
            _ports.value = curPorts
            _statusMessage.value = "Soft port reset complete. USB-PD re-negotiated."
            onComplete?.invoke()
        }
    }

    fun setSceneMode(mode: Int) {
        if (mode !in CuktechProtocol.SCENE_MODES.keys) return
        scope.launch {
            _settings.value = _settings.value.copy(sceneMode = mode)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_SCENE_MODE,
                isSet = true,
                valueBytes = byteArrayOf(mode.toByte())
            )
        }
    }

    fun setScreenTimeout(timeoutCode: Int) {
        if (timeoutCode !in CuktechProtocol.SCREEN_TIMEOUTS.keys) return
        scope.launch {
            _settings.value = _settings.value.copy(screenTimeout = timeoutCode)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_SCREEN_TIMEOUT,
                isSet = true,
                valueBytes = byteArrayOf(timeoutCode.toByte())
            )
        }
    }

    fun forceScreenPower(turnOn: Boolean, timeoutCode: Int = 4) {
        scope.launch {
            if (turnOn) {
                setIdleScreenOff(false)
                setScreenTimeout(timeoutCode)
                gotoScreen(1)
            } else {
                setIdleScreenOff(true)
                setScreenTimeout(5) // 1 min timeout
            }
        }
    }

    fun setPortCountdown(portId: Int, countdownMinutes: Int) {
        if (portId !in 1..4 || countdownMinutes !in 0..1440) return
        val piid = CuktechProtocol.PIID_COUNTDOWN_C1 + (portId - 1)
        scope.launch {
            val curPorts = _ports.value.toMutableMap()
            curPorts[portId]?.let { curPorts[portId] = it.copy(countdownCode = countdownMinutes) }
            _ports.value = curPorts

            if (countdownMinutes > 0) {
                targetCountdownEpochSec[portId] = (System.currentTimeMillis() / 1000L) + (countdownMinutes * 60L)
                val remMap = _countdownRemainingSeconds.value.toMutableMap()
                remMap[portId] = countdownMinutes * 60L
                _countdownRemainingSeconds.value = remMap
            } else {
                targetCountdownEpochSec.remove(portId)
                val remMap = _countdownRemainingSeconds.value.toMutableMap()
                remMap.remove(portId)
                _countdownRemainingSeconds.value = remMap
            }

            val valBytes = if (countdownMinutes <= 0xFF) {
                byteArrayOf((countdownMinutes and 0xFF).toByte())
            } else {
                byteArrayOf((countdownMinutes and 0xFF).toByte(), ((countdownMinutes shr 8) and 0xFF).toByte())
            }
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = piid,
                isSet = true,
                valueBytes = valBytes
            )
        }
    }

    private fun updateCountdownTickAndNotification() {
        val nowSec = System.currentTimeMillis() / 1000L
        val remMap = mutableMapOf<Int, Long>()
        for ((pId, targetEpoch) in targetCountdownEpochSec) {
            val rem = targetEpoch - nowSec
            if (rem > 0) {
                remMap[pId] = rem
            } else {
                targetCountdownEpochSec.remove(pId)
                val curPorts = _ports.value.toMutableMap()
                curPorts[pId]?.let { curPorts[pId] = it.copy(countdownCode = 0) }
                _ports.value = curPorts
            }
        }
        _countdownRemainingSeconds.value = remMap

        if (_authState.value == CuktechAuthState.AUTHENTICATED) {
            val totalW = _totalPowerWatts.value
            val ports = _ports.value
            val activePorts = ports.values.filter { it.powerW >= 0.1 }

            val title = if (totalW >= 0.1) {
                String.format(java.util.Locale.US, "⚡ %.1fW Total · CUKTECH 10 Ultra", totalW)
            } else {
                "⚡ CUKTECH 10 Ultra (Idle)"
            }

            val timers = remMap.entries.sortedBy { it.key }.joinToString(", ") { (pId, secs) ->
                val m = secs / 60
                val s = secs % 60
                val pLabel = ports[pId]?.portLabel ?: "P$pId"
                String.format(java.util.Locale.US, "%s: %d:%02d", pLabel, m, s)
            }

            val portWatts = if (activePorts.isNotEmpty()) {
                activePorts.joinToString(" · ") {
                    String.format(java.util.Locale.US, "%s: %.1fW", it.portLabel, it.powerW)
                }
            } else {
                "All ports idle"
            }

            val content = if (timers.isNotEmpty()) {
                "⏱️ $timers | $portWatts"
            } else {
                portWatts
            }

            BleBridgeService.getInstance()?.updateNotificationTelemetry(title, content)
        } else {
            val title = "BLE Cuktech10 Gateway"
            val content = when (_authState.value) {
                CuktechAuthState.CONNECTING_GATT, CuktechAuthState.DISCOVERING_SERVICES,
                CuktechAuthState.PHASE_A_INIT_A4, CuktechAuthState.PHASE_B_HANDSHAKE -> "Connecting to CUKTECH 10 Ultra..."
                CuktechAuthState.AUTH_FAILED -> "Auth failed · Check credentials"
                else -> "Ready · Service active"
            }
            BleBridgeService.getInstance()?.updateNotificationTelemetry(title, content)
        }
    }

    fun setLanguage(langCode: Int) {
        if (langCode !in 0..1) return
        scope.launch {
            _settings.value = _settings.value.copy(language = langCode)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_LANGUAGE,
                isSet = true,
                valueBytes = byteArrayOf(langCode.toByte())
            )
        }
    }

    fun gotoScreen(screenIndex: Int) {
        if (screenIndex !in 1..5) return
        scope.launch {
            _settings.value = _settings.value.copy(activeLcdPage = screenIndex)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_GOTO_SCREEN,
                isSet = true,
                valueBytes = byteArrayOf(screenIndex.toByte())
            )
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Sent LCD Goto Page $screenIndex (PIID 14)"
            )
        }
    }

    fun rotateScreen180() {
        scope.launch {
            val curDeg = _settings.value.orientationDegrees
            val targetDeg = if (curDeg == 180) 0 else 180
            _settings.value = _settings.value.copy(
                orientationLock = true,
                orientationDegrees = targetDeg
            )
            // 1. Lock orientation (PIID 20)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_ORIENTATION_LOCK,
                isSet = true,
                valueBytes = byteArrayOf(1.toByte())
            )
            // 2. Direct inverted screen direction command (PIID 14: 2 for 180° inverted, 1 for normal)
            val dirCode = if (targetDeg == 180) 2 else 1
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_GOTO_SCREEN,
                isSet = true,
                valueBytes = byteArrayOf(dirCode.toByte())
            )
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Rotated screen to ${targetDeg}° (PIID 20=1, PIID 14=$dirCode)"
            )
        }
    }

    fun invokeAdcAssistant(onComplete: (() -> Unit)? = null) {
        scope.launch {
            setSceneMode(1)
            gotoScreen(2)
            forceSoftRestart {
                onComplete?.invoke()
            }
        }
    }

    fun setUsbALowCurrent(enabled: Boolean) {
        scope.launch {
            _settings.value = _settings.value.copy(usbALowCurrent = enabled)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_USB_A_LOW_CURRENT,
                isSet = true,
                valueBytes = byteArrayOf(if (enabled) 1 else 0)
            )
        }
    }

    fun setIdleScreenOff(enabled: Boolean) {
        scope.launch {
            _settings.value = _settings.value.copy(idleScreenOff = enabled)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_IDLE_SCREEN_OFF,
                isSet = true,
                valueBytes = byteArrayOf(if (enabled) 1 else 0)
            )
        }
    }

    fun setOrientationLock(locked: Boolean) {
        scope.launch {
            _settings.value = _settings.value.copy(
                orientationLock = locked,
                orientationDegrees = if (locked) 0 else -1
            )
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_ORIENTATION_LOCK,
                isSet = true,
                valueBytes = byteArrayOf(if (locked) 1.toByte() else 0.toByte())
            )
        }
    }

    fun setOrientation(degrees: Int) {
        setOrientationLock(degrees != -1)
    }

    fun setPortProtocol(portId: Int, protocol: String, enabled: Boolean) {
        if (portId !in 1..4) return
        scope.launch {
            var c1Pd = _settings.value.c1Pd
            var c1Pps = _settings.value.c1Pps
            var c1Ufcs = _settings.value.c1Ufcs

            var c2Pd = _settings.value.c2Pd
            var c2Pps = _settings.value.c2Pps
            var c2Ufcs = _settings.value.c2Ufcs

            var c3Ufcs = _settings.value.c3Ufcs
            var c3Scp = _settings.value.c3Scp

            var aUfcs = _settings.value.aUfcs
            var aScp = _settings.value.aScp

            when (portId) {
                1 -> {
                    when (protocol.lowercase()) {
                        "pd" -> {
                            c1Pd = enabled
                            if (!enabled) c1Pps = false // Disabling PD also disables PPS
                        }
                        "pps" -> {
                            c1Pps = enabled
                            if (enabled) c1Pd = true // Enabling PPS requires PD
                        }
                        "ufcs" -> c1Ufcs = enabled
                    }
                }
                2 -> {
                    when (protocol.lowercase()) {
                        "pd" -> {
                            c2Pd = enabled
                            if (!enabled) c2Pps = false
                        }
                        "pps" -> {
                            c2Pps = enabled
                            if (enabled) c2Pd = true
                        }
                        "ufcs" -> c2Ufcs = enabled
                    }
                }
                3 -> {
                    when (protocol.lowercase()) {
                        "ufcs" -> c3Ufcs = enabled
                        "scp" -> c3Scp = enabled
                    }
                }
                4 -> {
                    when (protocol.lowercase()) {
                        "ufcs" -> aUfcs = enabled
                        "scp" -> aScp = enabled
                    }
                }
            }

            val c1Byte = (if (c1Pd) 1 else 0) or ((if (c1Pps) 1 else 0) shl 1) or ((if (c1Ufcs) 1 else 0) shl 2) or (1 shl 3)
            val c2Byte = (if (c2Pd) 1 else 0) or ((if (c2Pps) 1 else 0) shl 1) or ((if (c2Ufcs) 1 else 0) shl 2) or (1 shl 3)
            val c3Byte = (if (c3Ufcs) 1 else 0) or ((if (c3Scp) 1 else 0) shl 1)
            val aByte = (if (aUfcs) 1 else 0) or ((if (aScp) 1 else 0) shl 1)

            val val32 = (c1Byte.toLong() and 0xFF) or
                ((c2Byte.toLong() and 0xFF) shl 8) or
                ((c3Byte.toLong() and 0xFF) shl 16) or
                ((aByte.toLong() and 0xFF) shl 24)

            _settings.value = _settings.value.copy(
                rawProtocolExtendVal = val32,
                c1Pd = c1Pd, c1Pps = c1Pps, c1Ufcs = c1Ufcs,
                c2Pd = c2Pd, c2Pps = c2Pps, c2Ufcs = c2Ufcs,
                c3Ufcs = c3Ufcs, c3Scp = c3Scp,
                aUfcs = aUfcs, aScp = aScp
            )

            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_PROTOCOL_EXTEND,
                isSet = true,
                valueBytes = byteArrayOf(
                    (val32 and 0xFF).toByte(),
                    ((val32 shr 8) and 0xFF).toByte(),
                    ((val32 shr 16) and 0xFF).toByte(),
                    ((val32 shr 24) and 0xFF).toByte()
                )
            )
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Cuktech] Set Port $portId Protocol $protocol = $enabled (val32=0x${val32.toString(16)})"
            )
        }
    }

    fun setFastChargeProtocols(
        pd: Boolean = _settings.value.pdEnabled,
        pps: Boolean = _settings.value.ppsEnabled,
        ufcs: Boolean = _settings.value.ufcsEnabled,
        scp: Boolean = _settings.value.scpEnabled
    ) {
        scope.launch {
            setPortProtocol(1, "pd", pd)
            setPortProtocol(1, "pps", pps)
            setPortProtocol(1, "ufcs", ufcs)
            setPortProtocol(2, "pd", pd)
            setPortProtocol(2, "pps", pps)
            setPortProtocol(2, "ufcs", ufcs)
            setPortProtocol(3, "ufcs", ufcs)
            setPortProtocol(3, "scp", scp)
            setPortProtocol(4, "ufcs", ufcs)
            setPortProtocol(4, "scp", scp)
        }
    }

    // =========================================================================
    // 7. PER-PORT WH CHARGE LIMITS (`once` / `always` AUTO-CUTOFF)
    // =========================================================================

    fun setPortChargeLimit(portId: Int, whTarget: Double, mode: String = "once", enabled: Boolean = true) {
        if (portId !in 1..4) return
        if (!enabled || whTarget <= 0.0) {
            persistence.removePortChargeLimit(portId)
            val map = _chargeLimits.value.toMutableMap()
            map.remove(portId)
            _chargeLimits.value = map
            return
        }
        val baseline = _ports.value[portId]?.cumulativeWh ?: 0.0
        val limit = PortChargeLimit(
            portId = portId,
            whTarget = whTarget,
            mode = if (mode == "always") "always" else "once",
            baselineWh = baseline,
            enabled = true,
            triggered = false
        )
        persistence.savePortChargeLimit(limit)
        val map = _chargeLimits.value.toMutableMap()
        map[portId] = limit
        _chargeLimits.value = map
    }

    private fun checkAndEnforcePortChargeLimit(portId: Int, cumulativeWh: Double) {
        val limit = _chargeLimits.value[portId] ?: return
        if (!limit.enabled || limit.triggered) return
        val usedWh = limit.currentUsedWh(cumulativeWh)
        if (usedWh >= limit.whTarget) {
            // Cut off port power automatically via PIID 16
            setPortEnabled(portId, false)
            _statusMessage.value = "Port ${PortTelemetryDecoder.PORT_NAMES[portId]} reached limit (${"%.1f".format(limit.whTarget)} Wh) - auto cut off"

            val updatedLimit = if (limit.mode == "always") {
                limit.copy(baselineWh = cumulativeWh, triggered = false)
            } else {
                limit.copy(enabled = false, triggered = true)
            }
            persistence.savePortChargeLimit(updatedLimit)
            val map = _chargeLimits.value.toMutableMap()
            map[portId] = updatedLimit
            _chargeLimits.value = map
        }
    }

    fun clearHistory(option: com.example.cuktech.db.CuktechPersistence.ClearHistoryOption) {
        persistence.clearHistory(option)
        _completedSessions.value = persistence.getRecentSessions(200)
        when (option) {
            com.example.cuktech.db.CuktechPersistence.ClearHistoryOption.CLEAR_ALL -> {
                sessionDetectors.values.forEach { it.reset() }
                energyIntegrators.values.forEach { it.reset() }
                _activeSessions.value = emptyMap()
                _powerChartHistory.value = emptyList()
                val updated = _ports.value.toMutableMap()
                updated.keys.forEach { pId ->
                    updated[pId]?.let { updated[pId] = it.copy(cumulativeWh = 0.0) }
                }
                _ports.value = updated
            }
            com.example.cuktech.db.CuktechPersistence.ClearHistoryOption.KEEP_ACTIVE -> {
                // Completed sessions reloaded from DB
            }
            else -> {
                // Older 24h / 7d reloaded from DB
            }
        }
    }
}
