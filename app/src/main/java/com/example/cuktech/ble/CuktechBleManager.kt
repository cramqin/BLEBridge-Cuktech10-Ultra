package com.example.cuktech.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import com.example.BleBridgeService
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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
)

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

    // Pending MiOT command responses keyed by reqId
    private val pendingMiotResponses = ConcurrentHashMap<Int, CompletableDeferred<DecodedMiotFrame>>()

    // Session state
    private var sessionKeys: SessionKeys? = null
    private var appItCounter: Long = 1L
    private var devItHi: Long = 0L
    private var lastDevItLo: Int = 0
    private var reqCounter: Int = 0x0300

    private var authJob: Job? = null
    private var pollingJob: Job? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null

    private var autoReconnectEnabled: Boolean = true
    private var lastConnectedMac: String? = null
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

    // Callback invoked on telemetry update so Tasker / MQTT / Bemfa can broadcast
    var onTelemetryUpdated: ((Map<Int, PortTelemetry>, Double, ChargerDeviceInfo) -> Unit)? = null

    init {
        credentialStore.tryAutoImportLocalCredentialsFile()
        _completedSessions.value = persistence.getRecentSessions(50)
        _chargeLimits.value = persistence.loadPortChargeLimits()
        credentialStore.activeDevice.value?.let { cred ->
            _deviceInfo.value = _deviceInfo.value.copy(
                name = cred.name,
                model = cred.model.ifBlank { "njcuk.fitting.ad1204" },
                macAddress = cred.mac
            )
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
        _authState.value = CuktechAuthState.SCANNING
        _statusMessage.value = "Scanning for nearby CUKTECH 10 Ultra chargers..."
        svc.startNativeCuktechScan(durationMs)
    }

    fun onScanStopped() {
        if (_authState.value == CuktechAuthState.SCANNING) {
            _authState.value = CuktechAuthState.DISCONNECTED
            val count = _discoveredDevices.value.size
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

        if (targetCred != null && targetCred.mac.equals(targetMac, ignoreCase = true)) {
            credentialStore.selectActiveDevice(targetMac)
        }

        val svc = BleBridgeService.getInstance()
        if (svc == null) {
            _statusMessage.value = "BLE Service is not ready"
            return
        }

        autoReconnectEnabled = true
        lastConnectedMac = targetMac
        reconnectJob?.cancel()
        stopBackgroundLoops()

        _deviceInfo.value = _deviceInfo.value.copy(
            macAddress = targetMac,
            name = targetCred?.name ?: "CUKTECH 10 Ultra"
        )
        _authState.value = CuktechAuthState.CONNECTING_GATT
        _statusMessage.value = "Connecting to $targetMac..."
        svc.connectBleNative(targetMac)
    }

    fun disconnectCharger() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        authJob?.cancel()
        stopBackgroundLoops()
        sessionKeys = null
        _authState.value = CuktechAuthState.DISCONNECTED
        _statusMessage.value = "Disconnected"
        BleBridgeService.getInstance()?.disconnectBleNative()
    }

    fun onConnectionStateChange(address: String, status: Int, newState: Int) {
        if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            _authState.value = CuktechAuthState.DISCOVERING_SERVICES
            _statusMessage.value = "GATT connected, discovering services..."
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            authJob?.cancel()
            stopBackgroundLoops()
            sessionKeys = null
            for ((_, deferred) in pendingMiotResponses) {
                deferred.cancel()
            }
            pendingMiotResponses.clear()

            if (_authState.value != CuktechAuthState.AUTH_FAILED) {
                _authState.value = CuktechAuthState.DISCONNECTED
            }
            _statusMessage.value = "Disconnected (status=$status)"

            if (autoReconnectEnabled && !lastConnectedMac.isNullOrBlank()) {
                scheduleAutoReconnect(lastConnectedMac!!)
            }
        }
    }

    private fun scheduleAutoReconnect(mac: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(4500L)
            if (autoReconnectEnabled && _authState.value == CuktechAuthState.DISCONNECTED) {
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
            // Connected device is a generic BLE peripheral (not a MiOT 0xFE95 device)
            return
        }

        authJob?.cancel()
        authJob = scope.launch {
            val gattMac = gatt.device.address.uppercase().trim()
            var activeCred = credentialStore.devices.value.firstOrNull {
                it.mac.replace(":", "").equals(gattMac.replace(":", ""), ignoreCase = true)
            } ?: credentialStore.activeDevice.value
              ?: if (credentialStore.devices.value.size == 1 && credentialStore.devices.value[0].hasValidToken) {
                  credentialStore.devices.value[0]
              } else null

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

            if (activeCred == null || !activeCred.hasValidToken) {
                _authState.value = CuktechAuthState.AUTH_FAILED
                _statusMessage.value = "Missing valid 12-byte MiOT Token. Tap 'Sync Token' or log in to Xiaomi account."
                BleBridgeService.refreshClientsList()
                return@launch
            }

            val ok = runAuthenticationHandshake(activeCred!!.tokenBytes())
            if (ok) {
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

    private suspend fun runAuthenticationHandshake(tokenBytes: ByteArray): Boolean {
        val svc = BleBridgeService.getInstance() ?: return false
        try {
            drainChannel(authCtrlQueue)
            drainChannel(authDataQueue)

            // Step 1: Subscribe to CMD_RECV (0x001b), CMD_SEND (0x001a), DEVICE_INFO (0x001c), AUTH_DATA (0x0019)
            _authState.value = CuktechAuthState.PHASE_A_INIT_A4
            _statusMessage.value = "Subscribing to MiOT characteristics..."

            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_CMD_RECV, true)
            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_CMD_SEND, true)
            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_DEVICE_INFO, true)
            val subAuthDataOk = svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_AUTH_DATA, true)
            if (!subAuthDataOk) {
                _statusMessage.value = "Failed to subscribe to CHAR_AUTH_DATA (0x0019)"
                return false
            }

            // Optional read of CHAR_DEVICE_INFO (0x001c)
            svc.enqueueBleRead(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_DEVICE_INFO)?.let { infoBytes ->
                val str = PortTelemetryDecoder.decodeAsciiInfo(infoBytes)
                if (str.isNotBlank()) {
                    _deviceInfo.value = _deviceInfo.value.copy(serialNumber = str)
                }
            }

            // Step 2: Phase A (0xA4 Pre-Handshake Initialization)
            _statusMessage.value = "Phase A: Sending 0xA4 initialization command..."
            drainChannel(authDataQueue)
            val a4WriteOk = svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_CTRL,
                CuktechProtocol.CMD_INIT_A4,
                withResponse = true
            )
            if (!a4WriteOk) {
                _statusMessage.value = "Failed to write 0xA4 to CHAR_AUTH_CTRL"
                return false
            }

            val initResp = withTimeoutOrNull(3000L) { authDataQueue.receive() }
            if (initResp != null && initResp.size >= 4) {
                val ack = initResp.copyOfRange(0, 4)
                ack[2] = ((ack[2].toInt() + 1) and 0xFF).toByte()
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    ack,
                    withResponse = false
                )

                val keyData = withTimeoutOrNull(3000L) { authDataQueue.receive() }
                if (keyData != null) {
                    if (keyData.size < 20) {
                        // Stale frame desync recovery
                        delay(500L)
                        drainChannel(authDataQueue)
                    } else {
                        val f2Reply = ByteArray(keyData.size)
                        f2Reply[0] = 0x00
                        f2Reply[1] = 0x00
                        f2Reply[2] = 0x05
                        f2Reply[3] = 0x01
                        for (i in 4 until f2Reply.size) {
                            f2Reply[i] = 0xF2.toByte()
                        }
                        svc.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_AUTH_DATA,
                            f2Reply,
                            withResponse = false
                        )
                        delay(300L)
                        drainChannel(authDataQueue)
                        delay(300L)
                        drainChannel(authDataQueue)
                    }
                }
            } else {
                // Device already initialized from prior session; drain any leftover bytes
                delay(200L)
                drainChannel(authDataQueue)
            }

            // Step 3: Phase B (0x24 MiOT Token Handshake)
            _authState.value = CuktechAuthState.PHASE_B_HANDSHAKE
            _statusMessage.value = "Phase B: Sending CMD_LOGIN (0x24) for key negotiation..."

            svc.enqueueBleNotify(CuktechProtocol.MI_SERVICE_UUID, CuktechProtocol.CHAR_AUTH_CTRL, true)
            drainChannel(authCtrlQueue)
            drainChannel(authDataQueue)

            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_CTRL,
                CuktechProtocol.CMD_LOGIN,
                withResponse = true
            )

            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                CuktechProtocol.CMD_SEND_KEY,
                withResponse = true
            )

            val rdy1 = withTimeoutOrNull(5000L) { authDataQueue.receive() }
            if (rdy1 == null || !rdy1.contentEquals(CuktechProtocol.RCV_RDY)) {
                _statusMessage.value = "Timeout waiting for RCV_RDY response"
                return false
            }

            val randKey = ByteArray(16).also { random.nextBytes(it) }
            val keyFrame = byteArrayOf(0x01, 0x00) + randKey
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                keyFrame,
                withResponse = false
            )

            val ok1 = withTimeoutOrNull(5000L) { authDataQueue.receive() }
            if (ok1 == null || !ok1.contentEquals(CuktechProtocol.RCV_OK)) {
                _statusMessage.value = "Did not receive RCV_OK after sending random key"
                return false
            }

            // Step 4: Receive device random (16B) and device HMAC (32B)
            val devSendKeyCmd = withTimeoutOrNull(5000L) { authDataQueue.receive() }
                ?: run {
                    _statusMessage.value = "Timeout waiting for device public key"
                    return false
                }

            val devRandom: ByteArray
            val devHmac: ByteArray

            if (devSendKeyCmd.size >= 6 && (devSendKeyCmd[2].toInt() and 0xFF) == 0x02) {
                // Inline mode (device sends key + info inline without waiting for RCV_RDY)
                devRandom = devSendKeyCmd.copyOfRange(4, minOf(20, devSendKeyCmd.size))
                val devSendInfo1 = withTimeoutOrNull(5000L) { authDataQueue.receive() } ?: return false
                val devSendInfo2 = withTimeoutOrNull(5000L) { authDataQueue.receive() } ?: return false
                val part1 = if (devSendInfo1.size > 4) devSendInfo1.copyOfRange(4, devSendInfo1.size) else ByteArray(0)
                val part2 = if (devSendInfo2.size > 2) devSendInfo2.copyOfRange(2, devSendInfo2.size) else ByteArray(0)
                devHmac = part1 + part2
            } else {
                // Standard multi-frame mode
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    CuktechProtocol.RCV_RDY,
                    withResponse = false
                )
                val devKeyFrame = withTimeoutOrNull(5000L) { authDataQueue.receive() } ?: return false
                devRandom = if (devKeyFrame.size > 2) devKeyFrame.copyOfRange(2, devKeyFrame.size) else devKeyFrame
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    CuktechProtocol.RCV_OK,
                    withResponse = false
                )

                withTimeoutOrNull(5000L) { authDataQueue.receive() } ?: return false
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    CuktechProtocol.RCV_RDY,
                    withResponse = false
                )
                val hmacF1 = withTimeoutOrNull(5000L) { authDataQueue.receive() } ?: return false
                val hmacF2 = withTimeoutOrNull(5000L) { authDataQueue.receive() } ?: return false
                val part1 = if (hmacF1.size > 2) hmacF1.copyOfRange(2, hmacF1.size) else ByteArray(0)
                val part2 = if (hmacF2.size > 2) hmacF2.copyOfRange(2, hmacF2.size) else ByteArray(0)
                devHmac = part1 + part2
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    CuktechProtocol.RCV_OK,
                    withResponse = false
                )
            }

            if (devRandom.size != 16) {
                _statusMessage.value = "Unexpected device random length (${devRandom.size}B)"
                return false
            }

            // Step 5: HKDF-SHA256 Key Derivation & App HMAC Transmission
            val derived = CuktechProtocol.deriveSessionKeys(tokenBytes, randKey, devRandom)
            val ourHmac = CuktechProtocol.computeAppHmac(derived.appKey, randKey, devRandom)

            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                CuktechProtocol.CMD_SEND_INFO,
                withResponse = true
            )
            val rdy2 = withTimeoutOrNull(5000L) { authDataQueue.receive() }
            if (rdy2 == null || !rdy2.contentEquals(CuktechProtocol.RCV_RDY)) {
                _statusMessage.value = "Did not receive RCV_RDY before sending HMAC"
                return false
            }

            val hmacChunk1 = byteArrayOf(0x01, 0x00) + ourHmac.copyOfRange(0, 16)
            val hmacChunk2 = byteArrayOf(0x02, 0x00) + ourHmac.copyOfRange(16, 32)
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                hmacChunk1,
                withResponse = false
            )
            delay(50L)
            svc.enqueueBleWrite(
                CuktechProtocol.MI_SERVICE_UUID,
                CuktechProtocol.CHAR_AUTH_DATA,
                hmacChunk2,
                withResponse = false
            )

            val ok2 = withTimeoutOrNull(5000L) { authDataQueue.receive() }
            if (ok2 == null || !ok2.contentEquals(CuktechProtocol.RCV_OK)) {
                _statusMessage.value = "Did not receive RCV_OK after sending HMAC"
                return false
            }

            // Step 6: Phase 6 Challenge (0x0d) / Response (0x0c) Echo
            val challengeCmd = withTimeoutOrNull(3000L) { authDataQueue.receive() }
            if (challengeCmd != null &&
                challengeCmd.size >= 4 &&
                challengeCmd[0] == 0.toByte() &&
                challengeCmd[1] == 0.toByte() &&
                challengeCmd[2] == 0.toByte() &&
                challengeCmd[3] == 0x0d.toByte()
            ) {
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    CuktechProtocol.RCV_RDY,
                    withResponse = false
                )
                withTimeoutOrNull(3000L) { authDataQueue.receive() }
                svc.enqueueBleWrite(
                    CuktechProtocol.MI_SERVICE_UUID,
                    CuktechProtocol.CHAR_AUTH_DATA,
                    CuktechProtocol.RCV_OK,
                    withResponse = false
                )

                val respCmd = withTimeoutOrNull(3000L) { authDataQueue.receive() }
                if (respCmd != null &&
                    respCmd.size >= 4 &&
                    respCmd[0] == 0.toByte() &&
                    respCmd[1] == 0.toByte() &&
                    respCmd[2] == 0.toByte() &&
                    respCmd[3] == 0x0c.toByte()
                ) {
                    svc.enqueueBleWrite(
                        CuktechProtocol.MI_SERVICE_UUID,
                        CuktechProtocol.CHAR_AUTH_DATA,
                        CuktechProtocol.RCV_RDY,
                        withResponse = false
                    )
                    val responsePayload = withTimeoutOrNull(3000L) { authDataQueue.receive() }
                    svc.enqueueBleWrite(
                        CuktechProtocol.MI_SERVICE_UUID,
                        CuktechProtocol.CHAR_AUTH_DATA,
                        CuktechProtocol.RCV_OK,
                        withResponse = false
                    )
                    if (responsePayload != null && responsePayload.size >= 3) {
                        val echo = byteArrayOf(0x01, 0x00, 0x0c) + responsePayload.copyOfRange(3, responsePayload.size)
                        svc.enqueueBleWrite(
                            CuktechProtocol.MI_SERVICE_UUID,
                            CuktechProtocol.CHAR_AUTH_DATA,
                            echo,
                            withResponse = true
                        )
                        withTimeoutOrNull(2000L) { authDataQueue.receive() }
                    }
                }
            }

            // Step 7: Confirm 0x21 (or 0x11) on CHAR_AUTH_CTRL
            val ctrlResult = withTimeoutOrNull(4000L) { authCtrlQueue.receive() }
            if (ctrlResult != null && ctrlResult.isNotEmpty()) {
                val statusByte = ctrlResult[0].toInt() and 0xFF
                if (statusByte != 0x21 && statusByte != 0x11) {
                    _statusMessage.value = "Authentication status rejected: 0x${"%02X".format(statusByte)}"
                    return false
                }
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
            CuktechProtocol.CHAR_DEVICE_INFO -> {
                val info = PortTelemetryDecoder.decodeAsciiInfo(value)
                if (info.isNotBlank()) {
                    _deviceInfo.value = _deviceInfo.value.copy(serialNumber = info)
                }
            }
            CuktechProtocol.CHAR_CMD_RECV -> {
                handleEncryptedCmdRecv(value.copyOf())
            }
        }
    }

    private fun handleEncryptedCmdRecv(raw: ByteArray) {
        val keys = sessionKeys ?: return
        if (raw.size < 12) return
        if ((raw[0].toInt() and 0xFF) != 0x01 || (raw[1].toInt() and 0xFF) != 0x00) return

        val itLo = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        val ciphertextWithTag = raw.copyOfRange(4, raw.size)

        // Detect 16-bit counter rollover (`controller.py::_on_cmd_recv`)
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
                CuktechProtocol.aesCcmDecrypt(keys.devKey, nonce, ciphertextWithTag)
            }.getOrNull()
            if (attempt != null) {
                plaintext = attempt
                devItHi = counter and 0xFFFF0000L
                break
            }
        }

        val decrypted = plaintext ?: return
        val frame = PortTelemetryDecoder.parseMiotFrame(decrypted) ?: return

        // Complete any awaiting command deferred
        pendingMiotResponses.remove(frame.reqId)?.complete(frame)

        // Apply state update
        applyDecodedMiotFrame(frame)
    }

    private fun applyDecodedMiotFrame(frame: DecodedMiotFrame) {
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
                val curPorts = _ports.value.toMutableMap()
                val existing = curPorts[portId]
                val decoded = PortTelemetryDecoder.decodePortV2(portId, frame.payload, existing) ?: return

                // Integrate Wh & detect charging sessions
                val nowSec = System.currentTimeMillis() / 1000.0
                val integrator = energyIntegrators[portId]!!
                val deltaWh = integrator.update(decoded.powerW, nowSec)
                val cumulativeWh = integrator.totalWh

                val detector = sessionDetectors[portId]!!
                val finishedSession = detector.onSample(decoded.powerW, deltaWh, decoded.protocolName, nowSec)
                if (finishedSession != null) {
                    persistence.insertCompletedSession(finishedSession)
                    _completedSessions.value = persistence.getRecentSessions(50)
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
                _totalPowerWatts.value = roundedTotalW

                // Check per-port charge limit auto-cutoff
                checkAndEnforcePortChargeLimit(portId, cumulativeWh)

                // Record chart point & periodic SQLite history
                recordChartAndHistory(curPorts, roundedTotalW)

                onTelemetryUpdated?.invoke(curPorts, roundedTotalW, _deviceInfo.value)
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
                    val code = frame.payload[0].toInt() and 0xFF
                    val curPorts = _ports.value.toMutableMap()
                    val existing = curPorts[portId] ?: return
                    curPorts[portId] = existing.copy(countdownCode = code)
                    _ports.value = curPorts
                }
            }

            CuktechProtocol.PIID_LANGUAGE -> {
                if (frame.payload.isNotEmpty()) {
                    _settings.value = _settings.value.copy(language = frame.payload[0].toInt() and 0xFF)
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
                val d = if (frame.payload.size >= 15 && (frame.payload[0].toInt() and 0xFF) == 14) {
                    frame.payload.copyOfRange(1, 15)
                } else {
                    frame.payload
                }
                if (d.size >= 5) {
                    _settings.value = _settings.value.copy(
                        pdEnabled = d[0].toInt() != 0,
                        ppsEnabled = d[1].toInt() != 0,
                        ufcsEnabled = d[2].toInt() != 0,
                        scpEnabled = d[4].toInt() != 0,
                        rawProtocolExtend = if (d.size == 14) d.copyOf() else null
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

        val reqId = reqCounter and 0xFFFF
        reqCounter = (reqCounter + 1) and 0xFFFF
        if (reqCounter < 0x0100) reqCounter = 0x0300

        val currentCounter = appItCounter
        appItCounter++

        val tlvPlain = CuktechProtocol.buildMiotTlv(
            reqId = reqId,
            siid = siid,
            piid = piid,
            isSet = isSet,
            valueBytes = valueBytes,
            fmtOverride = fmtOverride
        )
        val packet = CuktechProtocol.buildEncryptedCmdPacket(
            appKey = keys.appKey,
            appIv = keys.appIv,
            appItCounter = currentCounter,
            tlvPlaintext = tlvPlain
        )

        val deferred = CompletableDeferred<DecodedMiotFrame>()
        pendingMiotResponses[reqId] = deferred
        totalTxCommands++

        val writeOk = svc.enqueueBleWrite(
            CuktechProtocol.MI_SERVICE_UUID,
            CuktechProtocol.CHAR_CMD_SEND,
            packet,
            withResponse = true
        )
        if (!writeOk) {
            pendingMiotResponses.remove(reqId)
            consecutiveFailures++
            return@withLock null
        }

        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingMiotResponses.remove(reqId)
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

    fun setPortCountdown(portId: Int, countdownCode: Int) {
        if (portId !in 1..4 || countdownCode !in CuktechProtocol.COUNTDOWN_OPTIONS.keys) return
        val piid = CuktechProtocol.PIID_COUNTDOWN_C1 + (portId - 1)
        scope.launch {
            val curPorts = _ports.value.toMutableMap()
            curPorts[portId]?.let { curPorts[portId] = it.copy(countdownCode = countdownCode) }
            _ports.value = curPorts
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = piid,
                isSet = true,
                valueBytes = byteArrayOf(countdownCode.toByte())
            )
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
            _settings.value = _settings.value.copy(orientationLock = locked)
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_ORIENTATION_LOCK,
                isSet = true,
                valueBytes = byteArrayOf(if (locked) 1 else 0)
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
            val cur = _settings.value
            val encoded14 = CuktechProtocol.encodeProtocolExtend(
                pd = pd,
                pps = pps,
                ufcs = ufcs,
                scp = scp,
                rawExisting = cur.rawProtocolExtend
            )
            _settings.value = cur.copy(
                pdEnabled = pd,
                ppsEnabled = pps,
                ufcsEnabled = ufcs,
                scpEnabled = scp,
                rawProtocolExtend = encoded14
            )
            val lengthPrefixed = byteArrayOf(encoded14.size.toByte()) + encoded14
            sendMiotCommand(
                siid = CuktechProtocol.SIID_CHARGER,
                piid = CuktechProtocol.PIID_PROTOCOL_EXTEND,
                isSet = true,
                valueBytes = lengthPrefixed,
                fmtOverride = 0x09
            )
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
}
