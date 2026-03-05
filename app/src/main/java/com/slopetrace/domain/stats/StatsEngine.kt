package com.slopetrace.domain.stats

import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.TrackingPoint
import kotlin.math.atan2
import kotlin.math.sqrt

data class AirtimeStats(
    val index: Int,
    val durationMs: Long
)

data class RunStats(
    val runId: String,
    val index: Int,
    val relatedLiftId: String?,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val meanAngleDeg: Double,
    val maxAngleDeg: Double,
    val verticalDropMeters: Double,
    val durationSeconds: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val airtimes: List<AirtimeStats>
)

data class LiftStats(
    val liftId: String,
    val physicalLiftId: String,
    val index: Int,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val verticalGainMeters: Double,
    val durationSeconds: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long
)

sealed class SessionEvent {
    data class Lift(val stats: LiftStats) : SessionEvent()
    data class Downhill(val stats: RunStats) : SessionEvent()
}

data class SessionStats(
    val totalRuns: Int,
    val totalVerticalMeters: Double,
    val totalSessionSeconds: Long,
    val liftTimeSeconds: Long,
    val downhillTimeSeconds: Long,
    val otherTimeSeconds: Long,
    val maxSessionSpeedMps: Double,
    val runs: List<RunStats>,
    val lifts: List<LiftStats>,
    val events: List<SessionEvent>
)

class StatsEngine {

    private data class RawLiftEvent(
        val rawLiftId: String,
        val points: List<TrackingPoint>,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        val verticalGainMeters: Double,
        val startTimestampMs: Long,
        val endTimestampMs: Long,
        val sampledPath: List<Pair<Double, Double>>
    )

    fun compute(points: List<TrackingPoint>): SessionStats {
        if (points.size < 2) {
            return SessionStats(
                totalRuns = 0,
                totalVerticalMeters = 0.0,
                totalSessionSeconds = 0L,
                liftTimeSeconds = 0L,
                downhillTimeSeconds = 0L,
                otherTimeSeconds = 0L,
                maxSessionSpeedMps = 0.0,
                runs = emptyList(),
                lifts = emptyList(),
                events = emptyList()
            )
        }

        var totalVertical = 0.0
        var liftTime = 0L
        var downhillTime = 0L

        val runBuckets = linkedMapOf<String, MutableList<TrackingPoint>>()
        val liftBuckets = linkedMapOf<String, MutableList<TrackingPoint>>()
        var fallbackRunIndex = 0
        var fallbackLiftIndex = 0

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val dt = ((cur.timestampMs - prev.timestampMs) / 1000L).coerceAtLeast(0L)

            if (cur.segmentType == SegmentType.LIFT) liftTime += dt
            if (cur.segmentType == SegmentType.DOWNHILL) downhillTime += dt

            val dz = cur.zUpM - prev.zUpM
            if (cur.segmentType == SegmentType.DOWNHILL && dz < 0) totalVertical += -dz

            if (cur.segmentType == SegmentType.DOWNHILL) {
                val runId = cur.runId ?: "fallback-run-${++fallbackRunIndex}"
                runBuckets.getOrPut(runId) { mutableListOf(prev) }.add(cur)
            }

            if (cur.segmentType == SegmentType.LIFT) {
                val liftId = cur.liftId ?: "fallback-lift-${++fallbackLiftIndex}"
                liftBuckets.getOrPut(liftId) { mutableListOf(prev) }.add(cur)
            }
        }

        val rawLiftEvents = liftBuckets.mapNotNull { (liftId, lift) ->
            if (lift.size < 2) return@mapNotNull null
            val avgSpeed = lift.map { it.speedMps }.average()
            val maxSpeed = lift.maxOf { it.speedMps }
            var verticalGain = 0.0
            for (i in 1 until lift.size) {
                val dz = lift[i].zUpM - lift[i - 1].zUpM
                if (dz > 0) verticalGain += dz
            }
            RawLiftEvent(
                rawLiftId = liftId,
                points = lift,
                avgSpeedMps = avgSpeed,
                maxSpeedMps = maxSpeed,
                verticalGainMeters = verticalGain,
                startTimestampMs = lift.first().timestampMs,
                endTimestampMs = lift.last().timestampMs,
                sampledPath = samplePath(lift)
            )
        }.sortedBy { it.startTimestampMs }

        val rawToPhysical = clusterPhysicalLifts(rawLiftEvents)

        val liftStats = rawLiftEvents
            .mapIndexed { idx, raw ->
                LiftStats(
                    liftId = raw.rawLiftId,
                    physicalLiftId = rawToPhysical[raw.rawLiftId] ?: raw.rawLiftId,
                    index = idx + 1,
                    avgSpeedMps = raw.avgSpeedMps,
                    maxSpeedMps = raw.maxSpeedMps,
                    verticalGainMeters = raw.verticalGainMeters,
                    durationSeconds = ((raw.endTimestampMs - raw.startTimestampMs) / 1000L).coerceAtLeast(0L),
                    startTimestampMs = raw.startTimestampMs,
                    endTimestampMs = raw.endTimestampMs
                )
            }

        val runStats = runBuckets.mapNotNull { (runId, run) ->
            if (run.size < 2) return@mapNotNull null
            val avgSpeed = run.map { it.speedMps }.average()
            val maxSpeed = run.maxOf { it.speedMps }
            var maxAngleDeg = 0.0
            var verticalDrop = 0.0

            for (i in 1 until run.size) {
                val prev = run[i - 1]
                val cur = run[i]
                val dz = cur.zUpM - prev.zUpM
                if (dz < 0) verticalDrop += -dz
                val dx = cur.xEastM - prev.xEastM
                val dy = cur.yNorthM - prev.yNorthM
                val horizontal = sqrt(dx * dx + dy * dy)
                if (horizontal > 0.5) {
                    val angleDeg = Math.toDegrees(atan2((-dz).coerceAtLeast(0.0), horizontal))
                    if (angleDeg > maxAngleDeg) maxAngleDeg = angleDeg
                }
            }

            val start = run.first()
            val end = run.last()
            val totalHorizontal = sqrt(
                (end.xEastM - start.xEastM).let { it * it } +
                    (end.yNorthM - start.yNorthM).let { it * it }
            )
            val netDrop = (start.zUpM - end.zUpM).coerceAtLeast(0.0)
            val meanAngleDeg = if (totalHorizontal > 0.5) {
                Math.toDegrees(atan2(netDrop, totalHorizontal))
            } else {
                0.0
            }

            val startTs = start.timestampMs
            val endTs = end.timestampMs
            val relatedLift = liftStats.lastOrNull { it.endTimestampMs <= startTs }

            RunStats(
                runId = runId,
                index = 0,
                relatedLiftId = relatedLift?.physicalLiftId,
                avgSpeedMps = avgSpeed,
                maxSpeedMps = maxSpeed,
                meanAngleDeg = meanAngleDeg,
                maxAngleDeg = maxAngleDeg,
                verticalDropMeters = verticalDrop,
                durationSeconds = ((endTs - startTs) / 1000L).coerceAtLeast(0L),
                startTimestampMs = startTs,
                endTimestampMs = endTs,
                airtimes = detectAirtimes(run)
            )
        }.sortedBy { it.startTimestampMs }
            .mapIndexed { idx, stats -> stats.copy(index = idx + 1) }

        val events = buildList {
            liftStats.forEach { add(SessionEvent.Lift(it)) }
            runStats.forEach { add(SessionEvent.Downhill(it)) }
        }.sortedBy {
            when (it) {
                is SessionEvent.Lift -> it.stats.startTimestampMs
                is SessionEvent.Downhill -> it.stats.startTimestampMs
            }
        }

        val totalSessionSeconds = ((points.last().timestampMs - points.first().timestampMs) / 1000L).coerceAtLeast(0L)
        val otherTime = (totalSessionSeconds - liftTime - downhillTime).coerceAtLeast(0L)
        val maxSessionSpeed = points.maxOfOrNull { it.speedMps } ?: 0.0

        return SessionStats(
            totalRuns = runStats.size,
            totalVerticalMeters = totalVertical,
            totalSessionSeconds = totalSessionSeconds,
            liftTimeSeconds = liftTime,
            downhillTimeSeconds = downhillTime,
            otherTimeSeconds = otherTime,
            maxSessionSpeedMps = maxSessionSpeed,
            runs = runStats,
            lifts = liftStats,
            events = events
        )
    }

    private fun samplePath(points: List<TrackingPoint>, maxSamples: Int = 200): List<Pair<Double, Double>> {
        if (points.isEmpty()) return emptyList()
        if (points.size <= maxSamples) return points.map { it.xEastM to it.yNorthM }

        val step = (points.size.toDouble() / maxSamples).coerceAtLeast(1.0)
        val sampled = mutableListOf<Pair<Double, Double>>()
        var index = 0.0
        while (index < points.size) {
            val i = index.toInt().coerceIn(0, points.lastIndex)
            val p = points[i]
            sampled.add(p.xEastM to p.yNorthM)
            index += step
        }
        return sampled
    }

    private fun clusterPhysicalLifts(rawEvents: List<RawLiftEvent>): Map<String, String> {
        if (rawEvents.isEmpty()) return emptyMap()

        val parent = IntArray(rawEvents.size) { it }

        fun find(x: Int): Int {
            var current = x
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in rawEvents.indices) {
            for (j in i + 1 until rawEvents.size) {
                if (isSamePhysicalLift(rawEvents[i], rawEvents[j])) {
                    union(i, j)
                }
            }
        }

        val groups = rawEvents.indices.groupBy { find(it) }
        val orderedGroupRoots = groups.keys.sortedBy { root ->
            groups[root]!!.minOf { idx -> rawEvents[idx].startTimestampMs }
        }

        val rootToPhysicalId = orderedGroupRoots.mapIndexed { idx, root ->
            root to "physical-lift-${idx + 1}"
        }.toMap()

        return rawEvents.indices.associate { idx ->
            val root = find(idx)
            rawEvents[idx].rawLiftId to (rootToPhysicalId[root] ?: rawEvents[idx].rawLiftId)
        }
    }

    private fun isSamePhysicalLift(a: RawLiftEvent, b: RawLiftEvent): Boolean {
        if (a.sampledPath.isEmpty() || b.sampledPath.isEmpty()) return false

        val aCoverage = coverageWithinDistance(a.sampledPath, b.sampledPath, maxDistanceM = 20.0)
        val bCoverage = coverageWithinDistance(b.sampledPath, a.sampledPath, maxDistanceM = 20.0)

        return aCoverage >= 0.80 && bCoverage >= 0.80
    }

    private fun coverageWithinDistance(
        source: List<Pair<Double, Double>>,
        target: List<Pair<Double, Double>>,
        maxDistanceM: Double
    ): Double {
        if (source.isEmpty() || target.isEmpty()) return 0.0

        var within = 0
        source.forEach { point ->
            val minDistance = nearestDistance(point, target)
            if (minDistance <= maxDistanceM) within++
        }
        return within.toDouble() / source.size
    }

    private fun nearestDistance(point: Pair<Double, Double>, target: List<Pair<Double, Double>>): Double {
        var minSq = Double.MAX_VALUE
        for (candidate in target) {
            val dx = candidate.first - point.first
            val dy = candidate.second - point.second
            val sq = dx * dx + dy * dy
            if (sq < minSq) minSq = sq
        }
        return sqrt(minSq)
    }

    private fun detectAirtimes(run: List<TrackingPoint>): List<AirtimeStats> {
        if (run.size < 3) return emptyList()

        val airtimes = mutableListOf<AirtimeStats>()
        var inAirStartIndex: Int? = null

        for (i in run.indices) {
            val point = run[i]
            val acc = point.accelerationMagnitude.toDouble()
            val speed = point.speedMps

            if (inAirStartIndex == null) {
                if (speed > 4.0 && acc < 0.15) {
                    inAirStartIndex = i
                }
                continue
            }

            val startIndex = inAirStartIndex
            val stillAirborne = acc < 0.25 && speed > 3.0
            if (stillAirborne) continue

            val endIndex = i
            val start = run[startIndex]
            val end = run[endIndex]
            val durationMs = (end.timestampMs - start.timestampMs).coerceAtLeast(0L)

            val preWindowStart = (startIndex - 2).coerceAtLeast(0)
            val postWindowEnd = (endIndex + 3).coerceAtMost(run.lastIndex)
            val takeoffPeak = run.subList(preWindowStart, startIndex + 1).maxOf { it.accelerationMagnitude.toDouble() }
            val landingPeak = run.subList(endIndex, postWindowEnd + 1).maxOf { it.accelerationMagnitude.toDouble() }

            if (durationMs in 120L..5000L && takeoffPeak > 0.7 && landingPeak > 0.9) {
                airtimes.add(
                    AirtimeStats(
                        index = airtimes.size + 1,
                        durationMs = durationMs
                    )
                )
            }

            inAirStartIndex = null
        }

        return airtimes
    }
}
