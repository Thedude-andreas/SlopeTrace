package com.slopetrace.domain.classification

import com.slopetrace.data.model.SegmentType
import kotlin.math.pow

class ClassificationEngine(
    private val windowSeconds: Int = 5
) {
    data class Sample(
        val timestampMs: Long,
        val speedMps: Double,
        val zMeters: Double,
        val accelerationMagnitude: Float
    )

    private val samples = ArrayDeque<Sample>()

    fun classify(sample: Sample): SegmentType {
        samples.addLast(sample)
        val cutoff = sample.timestampMs - windowSeconds * 1000L
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }

        if (samples.size < 2) return SegmentType.UNKNOWN

        val avgSpeed = samples.map { it.speedMps }.average()
        val dt = (samples.last().timestampMs - samples.first().timestampMs).coerceAtLeast(1L) / 1000.0
        val dzdt = (samples.last().zMeters - samples.first().zMeters) / dt
        val accValues = samples.map { it.accelerationMagnitude.toDouble() }
        val mean = accValues.average()
        val variance = accValues.map { (it - mean).pow(2) }.average()

        return when {
            dzdt > 0.5 && avgSpeed in 1.5..3.5 && variance < 0.2 -> SegmentType.LIFT
            dzdt < -1.0 && avgSpeed > 5.0 -> SegmentType.DOWNHILL
            else -> SegmentType.UNKNOWN
        }
    }
}
