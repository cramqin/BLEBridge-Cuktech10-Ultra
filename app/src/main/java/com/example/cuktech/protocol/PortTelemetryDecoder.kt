package com.example.cuktech.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

data class PdoCapabilities(
    val fixedVoltagesMv: List<Int> = emptyList(),
    val ppsMaxVoltageMv: Int? = null
) {
    fun formatSummary(): String {
        if (fixedVoltagesMv.isEmpty() && ppsMaxVoltageMv == null) return "No PDO"
        val fixedStr = fixedVoltagesMv.joinToString("/") { "${it / 1000}V" }
        val ppsStr = ppsMaxVoltageMv?.let { "PPS(${it / 1000}V)" } ?: ""
        return listOf(fixedStr, ppsStr).filter { it.isNotEmpty() }.joinToString(" + ")
    }
}

data class PortTelemetry(
    val portId: Int,                // 1=USB-C1, 2=USB-C2, 3=USB-C3, 4=USB-A
    val portLabel: String,          // "USB-C1", "USB-C2", "USB-C3", "USB-A"
    val voltageV: Double = 0.0,
    val currentA: Double = 0.0,
    val powerW: Double = 0.0,
    val status: Int = 0,            // 0=Idle, 1=Attached/Idle, 2=Outputting
    val rawPowerMw: Int = 0,
    val rawProtocolCode: Int = 0,
    val mijiaProtocolCode: Int? = null,
    val protocolName: String = "Idle",
    val enabled: Boolean = true,
    val countdownCode: Int = 0,
    val pdoCaps: PdoCapabilities? = null,
    val cumulativeWh: Double = 0.0,
    val lastUpdatedMs: Long = 0L
) {
    val isCharging: Boolean
        get() = status == 2 || powerW >= 0.3 || currentA >= 0.05

    val maxPowerW: Int
        get() = when (portId) {
            1 -> 120 // USB-C1: 120W Max (PD 100W / Xiaomi 120W)
            2 -> 120 // USB-C2: 120W Max (PD 100W / Xiaomi 120W)
            3 -> 44  // USB-C3: 44W Max
            4 -> 33  // USB-A:  33W Max
            else -> 120
        }
}

data class ChargerSettingsState(
    val sceneMode: Int = 1,
    val screenTimeout: Int = 1,
    val language: Int = 0,
    val usbALowCurrent: Boolean = false,
    val idleScreenOff: Boolean = false,
    val orientationLock: Boolean = false,
    val orientationDegrees: Int = -1, // -1 = Auto, 0 = 0°, 90 = 90°, 180 = 180°, 270 = 270°
    val activeLcdPage: Int = 1, // 1=Total Power, 2=ADC Assistant, 3=AI Temp, 4=Ports Detail, 5=System Info
    val portSwitchBitmap: Int = 0x0F, // Bit 0=C1, 1=C2, 2=C3, 3=A
    // Per-port fast charge protocol flags (PIID 21 uint32):
    val c1Pd: Boolean = true,
    val c1Pps: Boolean = true,
    val c1Ufcs: Boolean = true,
    val c2Pd: Boolean = true,
    val c2Pps: Boolean = true,
    val c2Ufcs: Boolean = true,
    val c3Ufcs: Boolean = true,
    val c3Scp: Boolean = true,
    val aUfcs: Boolean = true,
    val aScp: Boolean = true,
    val rawProtocolExtendVal: Long = 0x03030F0FL,
    val rawProtocolExtend: ByteArray? = null
) {
    val pdEnabled: Boolean get() = c1Pd && c2Pd
    val ppsEnabled: Boolean get() = c1Pps && c2Pps
    val ufcsEnabled: Boolean get() = c1Ufcs && c2Ufcs && c3Ufcs && aUfcs
    val scpEnabled: Boolean get() = c3Scp && aScp
}

data class DecodedMiotFrame(
    val reqId: Int,
    val b2: Int,
    val b4: Int,          // 0x04 = push notification, 0x03 = GET response, 0x01 = SET response
    val siid: Int,
    val piid: Int,
    val fmt: Int,
    val payload: ByteArray
)

/**
 * Ported directly from `cuktech-ble-server/src/cuktech_ble/state_protocol_v2.py` and `state.py`.
 * Decodes CUKTECH 10 Ultra Protocol v2 8-byte port frames, Mijia PIID 17/18 protocol codes,
 * PDO capability frames (PIID 7/8), and device info strings.
 */
object PortTelemetryDecoder {

    val PORT_NAMES = mapOf(
        1 to "USB-C1",
        2 to "USB-C2",
        3 to "USB-C3",
        4 to "USB-A"
    )

    val PORT_MAX_WATTS = mapOf(
        1 to 120, // USB-C1: 120W Max (PD 100W / Xiaomi 120W)
        2 to 120, // USB-C2: 120W Max (PD 100W / Xiaomi 120W)
        3 to 44,  // USB-C3: 44W Max
        4 to 33   // USB-A:  33W Max
    )

    // Exact Mijia protocol code map from `state_protocol_v2.py::MIJIA_PROTOCOL_MAP`
    val MIJIA_PROTOCOL_MAP = mapOf(
        0 to "Idle",
        1 to "QC2.0",
        2 to "QC3.0",
        3 to "QC3+",
        4 to "QC4.0",
        5 to "QC4+",
        6 to "PD2.0 Fixed",
        7 to "PD2.0 PPS",
        8 to "PD3.0 Fixed",
        9 to "PD3.0 PPS",
        10 to "PD3.1 Fixed",
        11 to "PD3.1 EPR AVS",
        12 to "Apple 2.4A",
        13 to "BC1.2 DCP",
        14 to "AFC",
        15 to "FCP",
        16 to "SCP",
        17 to "Xiaomi 67W",
        18 to "Xiaomi 90W",
        19 to "Xiaomi 120W",
        20 to "UFCS",
        21 to "PE+1.1",
        22 to "PE+2.0",
        23 to "VOOC",
        24 to "SuperVOOC"
    )

    private val STANDARD_PD_VOLTAGES = listOf(5.0, 9.0, 12.0, 15.0, 20.0, 28.0)

    /**
     * Parses a decrypted MiOT TLV response or push notification from `CHAR_CMD_RECV`.
     * Standard layout: [totalLen:1B][0x20:1B][seq:1B][0x00:1B][opcode:1B][cnt:1B][siid:1B][piid:2B LE]...
     */
    fun parseMiotFrame(plaintext: ByteArray): DecodedMiotFrame? {
        if (plaintext.size < 8) return null
        val frameType = plaintext[1].toInt() and 0xFF
        val seq = plaintext[2].toInt() and 0xFF
        val opcode = plaintext[4].toInt() and 0xFF
        val siid = if (plaintext.size > 6) plaintext[6].toInt() and 0xFF else -1
        val piid = if (plaintext.size > 8) (plaintext[7].toInt() and 0xFF) or ((plaintext[8].toInt() and 0xFF) shl 8) else -1
        // In SET ACK (opcode 0x01), there is no value returned by device
        // In GET response (opcode 0x03), error bytes are at 9..10, value starts at index 13
        // In Push notification (opcode 0x04), no error bytes, value starts at index 11
        val value = when (opcode) {
            0x01 -> ByteArray(0) // SET ACK has no property payload
            0x03 -> if (plaintext.size > 13) plaintext.copyOfRange(13, plaintext.size) else ByteArray(0)
            0x04 -> if (plaintext.size > 11) plaintext.copyOfRange(11, plaintext.size) else ByteArray(0)
            else -> if (plaintext.size > 12) plaintext.copyOfRange(12, plaintext.size) else ByteArray(0)
        }
        val valLen = value.size

        return DecodedMiotFrame(
            reqId = seq,
            b2 = frameType,
            b4 = opcode,
            siid = siid,
            piid = piid,
            fmt = valLen,
            payload = value
        )
    }

    /**
     * Decodes the 8-byte (or 9-byte length-prefixed `0x08`) port telemetry payload for PIID 1..4.
     * Exact match with `state_protocol_v2.py::decode_port_v2`:
     * - If `payload.size >= 9 && payload[0] == 0x08`, strips leading length byte.
     * - `d[0]`: status (0=Idle, 1=Attached, 2=Outputting)
     * - `d[1..2]` LE uint16: voltage in 10mV units (`V = raw / 100.0`)
     * - `d[3..4]` LE uint16: current in 10mA units (`A = raw / 100.0`)
     * - `d[5..6]` LE uint16: power in mW (if `< 65500` and within 10% of `V * A`, else `V * A`)
     * - `d[7]`: raw protocol code
     */
    fun decodePortV2(
        portId: Int,
        rawPayload: ByteArray,
        existingPort: PortTelemetry? = null
    ): PortTelemetry? {
        if (rawPayload.isEmpty()) return null

        val status: Int
        val voltageV: Double
        val currentA: Double
        val powerW: Double
        val powerMw: Int
        val protoRaw: Int

        if (rawPayload.size >= 9 && (rawPayload[0].toInt() and 0xFF) == 0x08) {
            // Legacy 9-byte length-prefixed frame
            val d = rawPayload.copyOfRange(1, 9)
            status = d[0].toInt() and 0xFF
            val voltageRaw = (d[1].toInt() and 0xFF) or ((d[2].toInt() and 0xFF) shl 8)
            val currentRaw = (d[3].toInt() and 0xFF) or ((d[4].toInt() and 0xFF) shl 8)
            powerMw = (d[5].toInt() and 0xFF) or ((d[6].toInt() and 0xFF) shl 8)
            protoRaw = d[7].toInt() and 0xFF
            voltageV = round2(voltageRaw / 100.0)
            currentA = round2(currentRaw / 100.0)
            val calcW = voltageV * currentA
            powerW = if (powerMw in 1..65499) {
                val mwAsW = powerMw / 1000.0
                if (calcW > 0.1 && abs(mwAsW - calcW) / calcW < 0.10) round2(mwAsW) else round2(calcW)
            } else {
                round2(calcW)
            }
        } else if (rawPayload.size >= 4) {
            // Native CUKTECH 10 Ultra MiOT format (state_protocol_v2.py::RawPortData)
            // Last 4 bytes: [in_use/status_raw, code, current*10 (100mA), voltage*10 (100mV)]
            val b = rawPayload.copyOfRange(rawPayload.size - 4, rawPayload.size)
            val statusRaw = b[0].toInt() and 0xFF
            val inUse = statusRaw != 0
            val code = b[1].toInt() and 0xFF
            val currentRaw = b[2].toInt() and 0xFF
            val voltageRaw = b[3].toInt() and 0xFF

            voltageV = round2(voltageRaw / 10.0)
            currentA = round2(currentRaw / 10.0)
            val calcW = round2(voltageV * currentA)
            powerW = calcW
            powerMw = (powerW * 1000.0).roundToInt()
            protoRaw = code

            val isCharging = inUse || powerW >= 0.3 || currentA >= 0.05
            status = if (!inUse && voltageV < 0.5 && currentA < 0.02) 0 else if (isCharging) 2 else 1
        } else {
            return null
        }

        val mijiaCode = if (status == 2 || powerW > 0.2) existingPort?.mijiaProtocolCode else null
        val protocolName = resolveProtocolName(
            portId = portId,
            status = status,
            voltageV = voltageV,
            currentA = currentA,
            mijiaProtocolCode = mijiaCode
        )

        return PortTelemetry(
            portId = portId,
            portLabel = PORT_NAMES[portId] ?: "Port $portId",
            voltageV = voltageV,
            currentA = currentA,
            powerW = powerW,
            status = status,
            rawPowerMw = powerMw,
            rawProtocolCode = protoRaw,
            mijiaProtocolCode = mijiaCode,
            protocolName = protocolName,
            enabled = existingPort?.enabled ?: true,
            countdownCode = existingPort?.countdownCode ?: 0,
            pdoCaps = existingPort?.pdoCaps,
            cumulativeWh = existingPort?.cumulativeWh ?: 0.0,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    /**
     * Resolves human-readable fast-charging protocol using Mijia PIID 17/18 code first
     * (`_protocol_from_mijia`), then heuristic fallback (`_detect_protocol`).
     */
    fun resolveProtocolName(
        portId: Int,
        status: Int,
        voltageV: Double,
        currentA: Double,
        mijiaProtocolCode: Int?
    ): String {
        if (status == 0 && voltageV < 0.5 && currentA < 0.02) {
            return "Idle"
        }
        if (mijiaProtocolCode != null && mijiaProtocolCode > 0) {
            MIJIA_PROTOCOL_MAP[mijiaProtocolCode]?.let { return it }
        }
        return detectProtocolFallback(portId, voltageV, currentA, status)
    }

    /**
     * Heuristic fallback protocol inference from `state_protocol_v2.py::_detect_protocol`.
     */
    fun detectProtocolFallback(
        portId: Int,
        voltageV: Double,
        currentA: Double,
        status: Int
    ): String {
        if (status == 0 && voltageV < 0.5) return "Idle"
        if (voltageV < 3.0 && currentA < 0.02) return "Connected (Standby)"
        if (portId == 4) {
            // USB-A port
            return when {
                voltageV > 10.5 -> "QC3.0 / FCP (12V)"
                voltageV > 7.5 -> "QC2.0 / AFC (9V)"
                currentA > 2.2 -> "SCP / UFCS"
                currentA > 0.05 -> "USB-A 5V"
                else -> "Standby (5V)"
            }
        }
        // USB-C1/C2/C3
        if (voltageV > 21.5) {
            return "PD3.1 EPR (28V)"
        }
        val nearestStandard = STANDARD_PD_VOLTAGES.minByOrNull { abs(it - voltageV) } ?: 5.0
        val deviation = abs(voltageV - nearestStandard)
        return when {
            voltageV >= 6.0 && deviation > 0.65 && currentA > 0.3 -> "PPS / Mi Turbo (${"%.1f".format(voltageV)}V)"
            nearestStandard >= 20.0 -> "PD 20V"
            nearestStandard >= 15.0 -> "PD 15V"
            nearestStandard >= 12.0 -> "PD 12V"
            nearestStandard >= 9.0 -> "PD 9V"
            currentA > 0.05 -> "PD 5V"
            else -> "Standby (5V)"
        }
    }

    /**
     * Decodes PDO capability payload (`PIID 7` for C1, `PIID 8` for C2):
     * Up to 6 x uint16 LE fixed voltages in mV + optional PPS max voltage at bytes 12..13.
     */
    fun decodePdoCaps(rawPayload: ByteArray): PdoCapabilities? {
        val d = if (rawPayload.size >= 15 && (rawPayload[0].toInt() and 0xFF) == 14) {
            rawPayload.copyOfRange(1, 15)
        } else if (rawPayload.size >= 2) {
            rawPayload
        } else {
            return null
        }

        val fixed = mutableListOf<Int>()
        val maxFixedIndex = minOf(6, d.size / 2)
        for (i in 0 until maxFixedIndex) {
            val vMv = (d[i * 2].toInt() and 0xFF) or ((d[i * 2 + 1].toInt() and 0xFF) shl 8)
            if (vMv in 3000..30000) {
                fixed.add(vMv)
            }
        }
        var ppsMv: Int? = null
        if (d.size >= 14) {
            val rawPps = (d[12].toInt() and 0xFF) or ((d[13].toInt() and 0xFF) shl 8)
            if (rawPps in 3000..30000) {
                ppsMv = rawPps
            }
        }
        return PdoCapabilities(fixedVoltagesMv = fixed, ppsMaxVoltageMv = ppsMv)
    }

    /**
     * Extracts null-terminated ASCII string from `CHAR_DEVICE_INFO` or `SIID=1` TLV responses.
     */
    fun decodeAsciiInfo(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val startIdx = if (payload.size > 1 && (payload[0].toInt() and 0xFF) == payload.size - 1) 1 else 0
        val slice = payload.copyOfRange(startIdx, payload.size).takeWhile { it != 0.toByte() }.toByteArray()
        if (slice.isEmpty()) return ""

        // Check if slice is clean printable ASCII
        val isPrintable = slice.all { it.toInt() and 0xFF in 32..126 }
        if (isPrintable) {
            val s = String(slice, Charsets.US_ASCII).trim()
            if (s.isNotBlank() && !s.contains('\uFFFD')) return s
        }

        // If slice is 1-4 small binary integers (e.g. version bytes [1, 0, 5])
        if (slice.size in 1..4 && slice.all { (it.toInt() and 0xFF) < 32 }) {
            return slice.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }

        // Filter printable characters
        val filtered = slice.filter { it.toInt() and 0xFF in 32..126 }.toByteArray()
        val s = String(filtered, Charsets.US_ASCII).trim()
        return if (s.isNotBlank() && !s.contains('\uFFFD')) s else ""
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    /**
     * Identifies the connected device brand and protocol based on negotiated voltage, power,
     * Xiaomi/CUKTECH protocol codes, and PPS profiles.
     */
    fun detectDeviceBrandAndProtocol(port: PortTelemetry): String? {
        if (port.powerW < 0.2 && port.voltageV < 1.0) return null
        val proto = port.protocolName
        val v = port.voltageV
        val w = port.powerW
        val a = port.currentA
        val code = port.mijiaProtocolCode ?: 0

        return when {
            // Xiaomi / Redmi / POCO (120W / 90W / 67W / Mi Turbo Charge)
            // High-voltage PPS (14V-20V, e.g. 17.2V) or Xiaomi protocol code or Mi Turbo in name
            code in listOf(17, 18, 19) ||
            proto.contains("Xiaomi", ignoreCase = true) ||
            proto.contains("Mi Turbo", ignoreCase = true) ||
            (proto.contains("PPS", ignoreCase = true) && v >= 14.0) -> {
                when {
                    w >= 100.0 -> "Xiaomi (120W HyperCharge)"
                    w >= 75.0 -> "Xiaomi (90W HyperCharge)"
                    w >= 50.0 -> "Xiaomi (67W HyperCharge)"
                    v >= 14.0 -> "Xiaomi (Mi Turbo / HyperCharge)"
                    else -> "Xiaomi Fast Charge"
                }
            }

            // Samsung Galaxy Super Fast Charging (SFC / SFC 2.0)
            (code in listOf(7, 9) || proto.contains("PPS", ignoreCase = true)) && (v in 8.0..12.5) -> {
                if (w >= 30.0 || a >= 3.5) "Samsung Galaxy (Super Fast Charging 2.0)"
                else "Samsung Galaxy (Super Fast Charging)"
            }

            // Apple hardware divider (D+/D- 2.4A resistor handshake)
            code == 12 || proto.contains("Apple", ignoreCase = true) -> "Apple Device (iPhone / iPad)"

            // 12V fixed voltage: Typical signature of semiconductor phone coolers (Bluewow, Black Shark, Flydigi)
            (v in 11.5..12.5) && !proto.contains("PPS", ignoreCase = true) ->
                "12V Fast Charge (Phone Cooler / Accessory)"

            // Laptop / MacBook / Steam Deck (Fixed PD 20V / 28V high power)
            proto.contains("PD", ignoreCase = true) && !proto.contains("PPS", ignoreCase = true) && (v >= 19.0 || w >= 45.0) ->
                "Laptop / MacBook (USB-PD High Power)"

            // Standard Fixed USB-PD (Used by iPhones, Android phones, phone coolers, power banks)
            proto.contains("PD", ignoreCase = true) && !proto.contains("PPS", ignoreCase = true) -> {
                when {
                    v in 8.5..9.5 -> "USB-PD 9V (Phone / Cooler / Accessory)"
                    v in 14.5..15.5 -> "USB-PD 15V (Tablet / Cooler / Accessory)"
                    else -> "USB-PD Device"
                }
            }

            // UFCS (Universal Fast Charging Spec - Vivo / iQOO / OPPO / OnePlus / Huawei)
            code == 20 || proto.contains("UFCS", ignoreCase = true) ->
                "UFCS Device (Vivo / OPPO / Huawei)"

            // Generic PPS
            code in listOf(7, 9) || proto.contains("PPS", ignoreCase = true) ->
                "PPS Fast Charge Device"

            // Generic USB-PD
            proto.contains("PD", ignoreCase = true) ->
                "USB-PD Device"

            port.isCharging && proto.isNotBlank() && proto != "Idle" && proto != "Normal" ->
                "$proto Device"

            else -> null
        }
    }

    /**
     * Compact brand name for status notifications and compact summary tables.
     */
    fun detectDeviceBrandShort(port: PortTelemetry): String? {
        val full = detectDeviceBrandAndProtocol(port) ?: return null
        return when {
            full.startsWith("Xiaomi") -> "Xiaomi"
            full.startsWith("Samsung") -> "Samsung"
            full.startsWith("Apple") -> "Apple"
            full.startsWith("Laptop") -> "Laptop"
            full.startsWith("UFCS") -> "UFCS"
            full.contains("Cooler") -> "Cooler"
            full.startsWith("PPS") -> "PPS"
            else -> full.split(" ").firstOrNull() ?: full
        }
    }
}
