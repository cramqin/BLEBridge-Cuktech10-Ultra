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
}

data class ChargerSettingsState(
    val sceneMode: Int = 1,
    val screenTimeout: Int = 1,
    val language: Int = 0,
    val usbALowCurrent: Boolean = false,
    val idleScreenOff: Boolean = false,
    val orientationLock: Boolean = false,
    val portSwitchBitmap: Int = 0x0F, // Bit 0=C1, 1=C2, 2=C3, 3=A
    val pdEnabled: Boolean = true,
    val ppsEnabled: Boolean = true,
    val ufcsEnabled: Boolean = true,
    val scpEnabled: Boolean = true,
    val rawProtocolExtend: ByteArray? = null
)

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
     */
    fun parseMiotFrame(plaintext: ByteArray): DecodedMiotFrame? {
        if (plaintext.size < 10) return null
        val reqId = (plaintext[0].toInt() and 0xFF) or ((plaintext[1].toInt() and 0xFF) shl 8)
        val b2 = plaintext[2].toInt() and 0xFF
        val b4 = plaintext[4].toInt() and 0xFF
        val siid = plaintext[6].toInt() and 0xFF
        val piid = (plaintext[7].toInt() and 0xFF) or ((plaintext[8].toInt() and 0xFF) shl 8)
        val fmt = plaintext[9].toInt() and 0xFF
        val value = if (plaintext.size > 10) plaintext.copyOfRange(10, plaintext.size) else ByteArray(0)
        return DecodedMiotFrame(
            reqId = reqId,
            b2 = b2,
            b4 = b4,
            siid = siid,
            piid = piid,
            fmt = fmt,
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
        val slice = payload.copyOfRange(startIdx, payload.size)
        val cleanBytes = slice.takeWhile { it != 0.toByte() }.toByteArray()
        return String(cleanBytes, Charsets.UTF_8).trim()
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
