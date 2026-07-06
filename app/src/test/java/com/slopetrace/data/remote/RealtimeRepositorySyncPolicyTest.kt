package com.slopetrace.data.remote

import com.slopetrace.data.local.TrackingPointEntity
import com.slopetrace.data.model.SegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRepositorySyncPolicyTest {

    @Test
    fun uploadIntervalMatchesSpeedBands() {
        assertEquals(500L, uploadIntervalMsForSpeed(6.0))
        assertEquals(1_000L, uploadIntervalMsForSpeed(3.0))
        assertEquals(2_000L, uploadIntervalMsForSpeed(0.5))
    }

    @Test
    fun selectsAtMostTwoHighSpeedPointsPerSecond() {
        val pending = listOf(
            point(id = 1, timestampMs = 0L, speedMps = 8.0),
            point(id = 2, timestampMs = 250L, speedMps = 8.0),
            point(id = 3, timestampMs = 500L, speedMps = 8.0),
            point(id = 4, timestampMs = 750L, speedMps = 8.0),
            point(id = 5, timestampMs = 1_000L, speedMps = 8.0)
        )

        val selection = selectLiveUploadCandidates(pending = pending, previousAnchor = null)

        assertEquals(setOf(1L, 3L, 5L), selection.uploadIds)
        assertEquals(setOf(2L, 4L), selection.skipIds)
    }

    @Test
    fun selectsFewerPointsAtLowSpeed() {
        val pending = listOf(
            point(id = 1, timestampMs = 0L, speedMps = 0.3),
            point(id = 2, timestampMs = 500L, speedMps = 0.3),
            point(id = 3, timestampMs = 1_000L, speedMps = 0.3),
            point(id = 4, timestampMs = 1_500L, speedMps = 0.3),
            point(id = 5, timestampMs = 2_000L, speedMps = 0.3)
        )

        val selection = selectLiveUploadCandidates(pending = pending, previousAnchor = null)

        assertEquals(setOf(1L, 5L), selection.uploadIds)
        assertEquals(setOf(2L, 3L, 4L), selection.skipIds)
    }

    @Test
    fun alwaysUploadsOnSegmentChange() {
        val pending = listOf(
            point(id = 1, timestampMs = 0L, speedMps = 0.2, segmentType = SegmentType.UNKNOWN),
            point(id = 2, timestampMs = 500L, speedMps = 0.2, segmentType = SegmentType.UNKNOWN),
            point(id = 3, timestampMs = 700L, speedMps = 0.2, segmentType = SegmentType.LIFT)
        )

        val selection = selectLiveUploadCandidates(pending = pending, previousAnchor = null)

        assertTrue(3L in selection.uploadIds)
    }

    private fun point(
        id: Long,
        timestampMs: Long,
        speedMps: Double,
        segmentType: SegmentType = SegmentType.UNKNOWN
    ): TrackingPointEntity {
        return TrackingPointEntity(
            id = id,
            sessionId = "session-1",
            userId = "user-1",
            timestampMs = timestampMs,
            latitude = 0.0,
            longitude = 0.0,
            pressureHpa = 1013.25f,
            altitudeM = 0.0,
            speedMps = speedMps,
            accelerationMagnitude = 0f,
            xEastM = 0.0,
            yNorthM = 0.0,
            zUpM = 0.0,
            segmentType = segmentType,
            segmentConfidence = 1.0,
            runId = null,
            liftId = null,
            synced = false
        )
    }
}
