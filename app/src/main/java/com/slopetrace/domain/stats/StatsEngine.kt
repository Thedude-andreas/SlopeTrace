package com.slopetrace.domain.stats

import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.TrackingPoint
import kotlin.math.atan

data class RunStats(
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val avgSlopeRad: Double
)

data class SessionStats(
    val totalRuns: Int,
    val totalVerticalMeters: Double,
    val liftTimeSeconds: Long,
    val downhillTimeSeconds: Long,
    val runs: List<RunStats>
)

class StatsEngine {

    fun compute(points: List<TrackingPoint>): SessionStats {
        if (points.size < 2) return SessionStats(0, 0.0, 0L, 0L, emptyList())

        var totalVertical = 0.0
        var liftTime = 0L
        var downhillTime = 0L
        val runBuckets = mutableListOf<MutableList<TrackingPoint>>()
        var currentRun = mutableListOf<TrackingPoint>()

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val dt = ((cur.timestampMs - prev.timestampMs) / 1000L).coerceAtLeast(0L)

            if (cur.segmentType == SegmentType.LIFT) liftTime += dt
            if (cur.segmentType == SegmentType.DOWNHILL) downhillTime += dt

            val dz = cur.zUpM - prev.zUpM
            if (dz < 0) totalVertical += -dz

            if (cur.segmentType == SegmentType.DOWNHILL) {
                currentRun.add(cur)
            } else if (currentRun.isNotEmpty()) {
                runBuckets.add(currentRun)
                currentRun = mutableListOf()
            }
        }
        if (currentRun.isNotEmpty()) runBuckets.add(currentRun)

        val runs = runBuckets.map { run ->
            val avgSpeed = run.map { it.speedMps }.average()
            val maxSpeed = run.maxOf { it.speedMps }
            var slopeAccumulator = 0.0
            var slopeSamples = 0

            for (i in 1 until run.size) {
                val prev = run[i - 1]
                val cur = run[i]
                val dz = cur.zUpM - prev.zUpM
                val dx = cur.xEastM - prev.xEastM
                val dy = cur.yNorthM - prev.yNorthM
                val horizontal = kotlin.math.sqrt(dx * dx + dy * dy)
                if (horizontal > 0.5) {
                    slopeAccumulator += atan(dz / horizontal)
                    slopeSamples++
                }
            }

            RunStats(
                avgSpeedMps = avgSpeed,
                maxSpeedMps = maxSpeed,
                avgSlopeRad = if (slopeSamples == 0) 0.0 else slopeAccumulator / slopeSamples
            )
        }

        return SessionStats(
            totalRuns = runs.size,
            totalVerticalMeters = totalVertical,
            liftTimeSeconds = liftTime,
            downhillTimeSeconds = downhillTime,
            runs = runs
        )
    }
}
