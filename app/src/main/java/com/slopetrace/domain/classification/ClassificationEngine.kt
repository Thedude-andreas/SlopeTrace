package com.slopetrace.domain.classification

import com.slopetrace.data.model.SegmentType
import kotlin.math.exp
import kotlin.math.pow

class ClassificationEngine(
    private val windowSeconds: Int = 5
) {
    data class Sample(
        val timestampMs: Long,
        val speedMps: Double,
        val zMeters: Double,
        val accelerationMagnitude: Float,
        val horizontalAccuracyM: Float?
    )

    data class ClassificationResult(
        val segmentType: SegmentType,
        val confidence: Double,
        val runId: String?,
        val liftId: String?
    )

    private val samples = ArrayDeque<Sample>()
    private var stableState = SegmentType.UNKNOWN
    private var pendingState = SegmentType.UNKNOWN
    private var pendingSinceMs = 0L

    private var runCounter = 0
    private var liftCounter = 0
    private var activeRunId: String? = null
    private var activeLiftId: String? = null

    fun reset() {
        samples.clear()
        stableState = SegmentType.UNKNOWN
        pendingState = SegmentType.UNKNOWN
        pendingSinceMs = 0L
        activeRunId = null
        activeLiftId = null
        runCounter = 0
        liftCounter = 0
    }

    fun classify(sample: Sample): ClassificationResult {
        samples.addLast(sample)
        val cutoff = sample.timestampMs - windowSeconds * 1000L
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }

        if (samples.size < 2) {
            return ClassificationResult(
                segmentType = stableState,
                confidence = 0.0,
                runId = activeRunId,
                liftId = activeLiftId
            )
        }

        val avgSpeed = samples.map { it.speedMps }.average()
        val dt = (samples.last().timestampMs - samples.first().timestampMs).coerceAtLeast(1L) / 1000.0
        val dzdt = (samples.last().zMeters - samples.first().zMeters) / dt
        val accValues = samples.map { it.accelerationMagnitude.toDouble() }
        val mean = accValues.average()
        val variance = accValues.map { (it - mean).pow(2) }.average()
        val avgAccuracyM = samples.mapNotNull { it.horizontalAccuracyM?.toDouble() }.average().takeIf { !it.isNaN() }

        val liftScore = liftScore(avgSpeed, dzdt, variance)
        val downhillScore = downhillScore(avgSpeed, dzdt, variance)
        val qualityFactor = when {
            avgAccuracyM == null -> 1.0
            avgAccuracyM <= 20.0 -> 1.0
            avgAccuracyM >= 80.0 -> 0.35
            else -> 1.0 - ((avgAccuracyM - 20.0) / 60.0) * 0.65
        }

        val (candidateState, rawConfidence) = when {
            liftScore >= downhillScore && liftScore > 0.52 -> SegmentType.LIFT to liftScore
            downhillScore > liftScore && downhillScore > 0.52 -> SegmentType.DOWNHILL to downhillScore
            else -> SegmentType.UNKNOWN to (1.0 - maxOf(liftScore, downhillScore))
        }

        val confidence = (rawConfidence * qualityFactor).coerceIn(0.0, 1.0)
        updateStableState(candidateState, sample.timestampMs)

        return ClassificationResult(
            segmentType = stableState,
            confidence = confidence,
            runId = activeRunId,
            liftId = activeLiftId
        )
    }

    private fun updateStableState(candidate: SegmentType, timestampMs: Long) {
        if (candidate == stableState) {
            pendingState = stableState
            pendingSinceMs = timestampMs
            return
        }

        if (candidate != pendingState) {
            pendingState = candidate
            pendingSinceMs = timestampMs
            return
        }

        val requiredMs = when (candidate) {
            SegmentType.LIFT -> 6_000L
            SegmentType.DOWNHILL -> 3_000L
            SegmentType.UNKNOWN -> 4_000L
        }

        if (timestampMs - pendingSinceMs < requiredMs) return

        stableState = candidate
        when (stableState) {
            SegmentType.DOWNHILL -> {
                activeLiftId = null
                if (activeRunId == null) {
                    runCounter += 1
                    activeRunId = "run-$runCounter"
                }
            }
            SegmentType.LIFT -> {
                activeRunId = null
                if (activeLiftId == null) {
                    liftCounter += 1
                    activeLiftId = "lift-$liftCounter"
                }
            }
            SegmentType.UNKNOWN -> {
                activeRunId = null
                activeLiftId = null
            }
        }
    }

    private fun liftScore(avgSpeed: Double, dzdt: Double, variance: Double): Double {
        val speedBand = gaussian(avgSpeed, mean = 2.6, sigma = 1.6)
        val climbBand = gaussian(dzdt, mean = 0.9, sigma = 0.9)
        val lowVariance = 1.0 / (1.0 + exp((variance - 0.35) * 4.5))
        return (0.45 * speedBand + 0.45 * climbBand + 0.10 * lowVariance).coerceIn(0.0, 1.0)
    }

    private fun downhillScore(avgSpeed: Double, dzdt: Double, variance: Double): Double {
        val speedBand = 1.0 / (1.0 + exp(-(avgSpeed - 5.2) * 0.9))
        val descentBand = 1.0 / (1.0 + exp((dzdt + 0.7) * 1.6))
        val movementBand = 1.0 / (1.0 + exp(-(variance - 0.08) * 12.0))
        return (0.45 * speedBand + 0.45 * descentBand + 0.10 * movementBand).coerceIn(0.0, 1.0)
    }

    private fun gaussian(value: Double, mean: Double, sigma: Double): Double {
        val z = (value - mean) / sigma
        return exp(-0.5 * z * z)
    }
}
