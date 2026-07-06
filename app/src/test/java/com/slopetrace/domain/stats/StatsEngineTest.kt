package com.slopetrace.domain.stats

import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.TrackingPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun clustersNearbyRawLiftsIntoSamePhysicalLift() {
        val points = listOf(
            point(t = 0, z = 100.0, speed = 2.1, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-a", x = 0.0, y = 0.0),
            point(t = 1_000, z = 105.0, speed = 2.2, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-a", x = 10.0, y = 0.0),
            point(t = 2_000, z = 110.0, speed = 2.3, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-a", x = 20.0, y = 0.0),
            point(t = 3_000, z = 110.0, speed = 0.3, acc = 0.2f, segment = SegmentType.UNKNOWN, x = 20.0, y = 0.0),
            point(t = 4_000, z = 111.0, speed = 2.0, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-b", x = 2.0, y = 3.0),
            point(t = 5_000, z = 116.0, speed = 2.1, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-b", x = 12.0, y = 3.0),
            point(t = 6_000, z = 121.0, speed = 2.2, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-b", x = 22.0, y = 3.0)
        )

        val stats = StatsEngine().compute(points)

        assertEquals(2, stats.lifts.size)
        assertEquals(stats.lifts[0].physicalLiftId, stats.lifts[1].physicalLiftId)
    }

    @Test
    fun keepsDistantRawLiftsAsSeparatePhysicalLifts() {
        val points = listOf(
            point(t = 0, z = 100.0, speed = 2.1, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-a", x = 0.0, y = 0.0),
            point(t = 1_000, z = 105.0, speed = 2.2, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-a", x = 10.0, y = 0.0),
            point(t = 2_000, z = 110.0, speed = 2.3, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-a", x = 20.0, y = 0.0),
            point(t = 3_000, z = 110.0, speed = 0.3, acc = 0.2f, segment = SegmentType.UNKNOWN, x = 20.0, y = 0.0),
            point(t = 4_000, z = 111.0, speed = 2.0, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-b", x = 250.0, y = 0.0),
            point(t = 5_000, z = 116.0, speed = 2.1, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-b", x = 260.0, y = 0.0),
            point(t = 6_000, z = 121.0, speed = 2.2, acc = 0.1f, segment = SegmentType.LIFT, liftId = "lift-b", x = 270.0, y = 0.0)
        )

        val stats = StatsEngine().compute(points)

        assertEquals(2, stats.lifts.size)
        assertNotEquals(stats.lifts[0].physicalLiftId, stats.lifts[1].physicalLiftId)
    }

    @Test
    fun detectsAirtimeDuringDownhillRun() {
        val points = listOf(
            point(t = 0, z = 100.0, speed = 7.0, acc = 1.0f, segment = SegmentType.DOWNHILL, runId = "run-1", x = 0.0, y = 0.0),
            point(t = 100, z = 99.0, speed = 8.0, acc = 0.8f, segment = SegmentType.DOWNHILL, runId = "run-1", x = 8.0, y = 0.0),
            point(t = 200, z = 98.0, speed = 9.0, acc = 0.1f, segment = SegmentType.DOWNHILL, runId = "run-1", x = 16.0, y = 0.0),
            point(t = 300, z = 97.0, speed = 9.0, acc = 0.12f, segment = SegmentType.DOWNHILL, runId = "run-1", x = 24.0, y = 0.0),
            point(t = 400, z = 96.0, speed = 9.0, acc = 1.2f, segment = SegmentType.DOWNHILL, runId = "run-1", x = 32.0, y = 0.0),
            point(t = 500, z = 95.0, speed = 8.0, acc = 0.4f, segment = SegmentType.DOWNHILL, runId = "run-1", x = 40.0, y = 0.0)
        )

        val stats = StatsEngine().compute(points)

        assertEquals(1, stats.runs.size)
        assertEquals(1, stats.runs.first().airtimes.size)
        assertEquals(200L, stats.runs.first().airtimes.first().durationMs)
    }

    private fun point(
        t: Long,
        z: Double,
        speed: Double,
        acc: Float,
        segment: SegmentType,
        runId: String? = null,
        liftId: String? = null,
        x: Double = t.toDouble() / 100.0,
        y: Double = t.toDouble() / 200.0
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
            xEastM = x,
            yNorthM = y,
            zUpM = z,
            segmentType = segment,
            segmentConfidence = 0.9,
            runId = runId,
            liftId = liftId
        )
    }
}
