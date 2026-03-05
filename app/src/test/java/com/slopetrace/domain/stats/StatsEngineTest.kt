package com.slopetrace.domain.stats

import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.TrackingPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsEngineTest {

    @Test
    fun computesSessionSummaryAndEvents() {
        val points = listOf(
            point(t = 0, z = 100.0, speed = 2.0, acc = 0.9f, segment = SegmentType.LIFT, liftId = "lift-1"),
            point(t = 1_000, z = 104.0, speed = 2.3, acc = 0.2f, segment = SegmentType.LIFT, liftId = "lift-1"),
            point(t = 2_000, z = 108.0, speed = 2.5, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-1"),
            point(t = 3_000, z = 108.0, speed = 1.0, acc = 0.3f, segment = SegmentType.UNKNOWN),
            point(t = 4_000, z = 104.0, speed = 8.0, acc = 1.2f, segment = SegmentType.DOWNHILL, runId = "run-1"),
            point(t = 5_000, z = 99.0, speed = 10.0, acc = 0.1f, segment = SegmentType.DOWNHILL, runId = "run-1"),
            point(t = 6_000, z = 93.0, speed = 12.0, acc = 1.3f, segment = SegmentType.DOWNHILL, runId = "run-1")
        )

        val stats = StatsEngine().compute(points)

        assertEquals(1, stats.totalRuns)
        assertEquals(2L, stats.liftTimeSeconds)
        assertEquals(3L, stats.downhillTimeSeconds)
        assertEquals(1L, stats.otherTimeSeconds)
        assertTrue(stats.maxSessionSpeedMps >= 12.0)

        val run = stats.runs.first()
        assertEquals("run-1", run.runId)
        assertEquals("physical-lift-1", run.relatedLiftId)
        assertTrue(run.maxSpeedMps >= 12.0)
        assertTrue(run.meanAngleDeg > 0.0)
        assertTrue(run.maxAngleDeg > 0.0)

        val lift = stats.lifts.first()
        assertEquals("lift-1", lift.liftId)
        assertEquals("physical-lift-1", lift.physicalLiftId)
        assertTrue(lift.verticalGainMeters >= 8.0)

        assertTrue(stats.events.isNotEmpty())
    }

    private fun point(
        t: Long,
        z: Double,
        speed: Double,
        acc: Float,
        segment: SegmentType,
        runId: String? = null,
        liftId: String? = null
    ): TrackingPoint {
        return TrackingPoint(
            sessionId = "s",
            userId = "u",
            timestampMs = t,
            latitude = 0.0,
            longitude = 0.0,
            pressureHpa = 1010f,
            altitudeM = z,
            speedMps = speed,
            accelerationMagnitude = acc,
            xEastM = t.toDouble() / 100.0,
            yNorthM = t.toDouble() / 200.0,
            zUpM = z,
            segmentType = segment,
            segmentConfidence = 0.9,
            runId = runId,
            liftId = liftId
        )
    }
}
