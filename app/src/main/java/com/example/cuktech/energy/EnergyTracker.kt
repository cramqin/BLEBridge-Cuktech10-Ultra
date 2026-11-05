package com.example.cuktech.energy

import kotlin.math.max
import kotlin.math.min

data class ChargeSessionRecord(
    val id: Long = 0L,
    val portId: Int,
    val portLabel: String,
    val startTimeSec: Double,
    val endTimeSec: Double? = null,
    val whAccumulated: Double = 0.0,
    val peakWatts: Double = 0.0,
    val avgWatts: Double = 0.0,
    val protocolUsed: String = "Idle",
    val isActive: Boolean = true
) {
    val durationSeconds: Long
        get() {
            val end = endTimeSec ?: (System.currentTimeMillis() / 1000.0)
            return max(0L, (end - startTimeSec).toLong())
        }
}

data class PortChargeLimit(
    val portId: Int,
    val whTarget: Double,
    val mode: String = "once",      // "once" or "always"
    val baselineWh: Double = 0.0,
    val enabled: Boolean = true,
    val triggered: Boolean = false
) {
    fun currentUsedWh(cumulativePortWh: Double): Double =
        max(0.0, cumulativePortWh - baselineWh)

    fun progressFraction(cumulativePortWh: Double): Float {
        if (whTarget <= 0.0) return 0f
        return min(1.0, currentUsedWh(cumulativePortWh) / whTarget).toFloat()
    }
}

/**
 * Ported directly from `cuktech-ble-server/src/cuktech_ble/energy.py`:
 * - `AdaptiveEnergyIntegrator`: Trapezoidal energy integration (`0.5 * (lastW + curW) * dt / 3600.0`)
 *   with outlier time-gap clamping (`max_dt = 30.0s`).
 * - `ChargeSessionDetector`: Median window filter (`p_fast` 3 samples, `p_slow` 7 samples)
 *   detecting session start (`>= 0.5W`), full-charge trickling (`< 0.3W` for 60s), and unplug.
 */
class AdaptiveEnergyIntegrator(private val maxDtSec: Double = 30.0) {

    private var lastTimestampSec: Double? = null
    private var lastWatts: Double = 0.0
    var totalWh: Double = 0.0
        private set

    fun update(watts: Double, timestampSec: Double = System.currentTimeMillis() / 1000.0): Double {
        val cleanW = if (watts < 0.15) 0.0 else watts
        val prevT = lastTimestampSec
        var deltaWh = 0.0
        if (prevT != null && timestampSec > prevT) {
            val dt = min(timestampSec - prevT, maxDtSec)
            val avgW = 0.5 * (lastWatts + cleanW)
            deltaWh = (avgW * dt) / 3600.0
            totalWh += deltaWh
        }
        lastTimestampSec = timestampSec
        lastWatts = cleanW
        return deltaWh
    }

    fun reset(initialWh: Double = 0.0) {
        lastTimestampSec = null
        lastWatts = 0.0
        totalWh = initialWh
    }
}

class ChargeSessionDetector(
    val portId: Int,
    val portLabel: String
) {
    private val fastWindow = ArrayDeque<Double>(3)
    private val slowWindow = ArrayDeque<Double>(7)

    var activeSession: ChargeSessionRecord? = null
        private set

    private var lowPowerStartSec: Double? = null
    private var sampleSumWatts: Double = 0.0
    private var sampleCount: Int = 0

    /**
     * Feeds a new telemetry sample and returns a completed `ChargeSessionRecord` if an active
     * charging session just finished, or `null` otherwise.
     */
    fun onSample(
        watts: Double,
        deltaWh: Double,
        protocolName: String,
        timestampSec: Double = System.currentTimeMillis() / 1000.0
    ): ChargeSessionRecord? {
        pushWindow(fastWindow, 3, watts)
        pushWindow(slowWindow, 7, watts)

        val pFast = median(fastWindow)
        val pSlow = median(slowWindow)

        val current = activeSession
        if (current == null) {
            if (pFast >= START_THRESHOLD_W) {
                sampleSumWatts = watts
                sampleCount = 1
                lowPowerStartSec = null
                activeSession = ChargeSessionRecord(
                    portId = portId,
                    portLabel = portLabel,
                    startTimeSec = timestampSec,
                    whAccumulated = deltaWh,
                    peakWatts = watts,
                    avgWatts = watts,
                    protocolUsed = protocolName.ifBlank { "PD" },
                    isActive = true
                )
            }
            return null
        } else {
            sampleSumWatts += watts
            sampleCount++
            val nextWh = current.whAccumulated + deltaWh
            val nextPeak = max(current.peakWatts, watts)
            val nextAvg = sampleSumWatts / sampleCount
            val nextProto = if (protocolName != "Idle" && protocolName != "空闲" && protocolName.isNotBlank()) {
                protocolName
            } else {
                current.protocolUsed
            }

            activeSession = current.copy(
                whAccumulated = nextWh,
                peakWatts = nextPeak,
                avgWatts = nextAvg,
                protocolUsed = nextProto
            )

            // Check end condition: unplug (`pFast < 0.15W`) or sustained trickle (`pSlow < 0.3W` for 45s)
            if (pFast < UNPLUG_THRESHOLD_W) {
                val lowStart = lowPowerStartSec ?: timestampSec.also { lowPowerStartSec = it }
                if (timestampSec - lowStart >= UNPLUG_CONFIRM_SEC) {
                    val finished = activeSession!!.copy(
                        endTimeSec = timestampSec,
                        isActive = false
                    )
                    activeSession = null
                    lowPowerStartSec = null
                    return if (finished.durationSeconds >= MIN_SESSION_DURATION_SEC) finished else null
                }
            } else if (pSlow < TRICKLE_END_W) {
                val lowStart = lowPowerStartSec ?: timestampSec.also { lowPowerStartSec = it }
                if (timestampSec - lowStart >= TRICKLE_CONFIRM_SEC) {
                    val finished = activeSession!!.copy(
                        endTimeSec = timestampSec,
                        isActive = false
                    )
                    activeSession = null
                    lowPowerStartSec = null
                    return if (finished.durationSeconds >= MIN_SESSION_DURATION_SEC) finished else null
                }
            } else {
                lowPowerStartSec = null
            }
            return null
        }
    }

    /**
     * Forces immediate finalization of the currently active session (e.g. when logging is paused,
     * phone screen locks, or user toggles off port logging). Returns the completed session record if valid.
     */
    fun forceFinalizeActiveSession(timestampSec: Double = System.currentTimeMillis() / 1000.0): ChargeSessionRecord? {
        val current = activeSession ?: return null
        val finished = current.copy(
            endTimeSec = timestampSec,
            isActive = false
        )
        reset()
        return if (finished.durationSeconds >= MIN_SESSION_DURATION_SEC) finished else null
    }

    fun reset() {
        activeSession = null
        lowPowerStartSec = null
        sampleSumWatts = 0.0
        sampleCount = 0
        fastWindow.clear()
        slowWindow.clear()
    }

    private fun pushWindow(deque: ArrayDeque<Double>, maxSize: Int, value: Double) {
        if (deque.size >= maxSize) deque.removeFirst()
        deque.addLast(value)
    }

    private fun median(deque: ArrayDeque<Double>): Double {
        if (deque.isEmpty()) return 0.0
        val sorted = deque.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        private const val START_THRESHOLD_W = 0.50
        private const val TRICKLE_END_W = 0.30
        private const val UNPLUG_THRESHOLD_W = 0.15
        private const val UNPLUG_CONFIRM_SEC = 1.5
        private const val TRICKLE_CONFIRM_SEC = 30.0
        private const val MIN_SESSION_DURATION_SEC = 3L
    }
}
