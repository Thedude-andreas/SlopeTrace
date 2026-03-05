package com.slopetrace.domain.classification

import com.slopetrace.data.model.SegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationEngineTest {

    @Test
    fun detectsLiftThenDownhillWithStableIds() {
        val engine = ClassificationEngine(windowSeconds = 5)
        var t = 0L
        var z = 0.0
        var result = engine.classify(
            ClassificationEngine.Sample(
                timestampMs = t,
                speedMps = 0.0,
                zMeters = z,
                accelerationMagnitude = 0f,
                horizontalAccuracyM = 8f
            )
        )

        repeat(10) {
            t += 1000L
            z += 0.9
            result = engine.classify(
                ClassificationEngine.Sample(
                    timestampMs = t,
                    speedMps = 2.6,
                    zMeters = z,
                    accelerationMagnitude = 0.08f,
                    horizontalAccuracyM = 8f
                )
            )
        }

        assertEquals(SegmentType.LIFT, result.segmentType)
        assertNotNull(result.liftId)
        assertNull(result.runId)

        repeat(6) {
            t += 1000L
            z -= 1.6
            result = engine.classify(
                ClassificationEngine.Sample(
                    timestampMs = t,
                    speedMps = 10.0,
                    zMeters = z,
                    accelerationMagnitude = 1.4f,
                    horizontalAccuracyM = 8f
                )
            )
        }

        assertEquals(SegmentType.DOWNHILL, result.segmentType)
        assertNotNull(result.runId)
        assertNull(result.liftId)
    }

    @Test
    fun lowersConfidenceWhenAccuracyIsPoor() {
        val engine = ClassificationEngine(windowSeconds = 5)
        var t = 0L
        var z = 0.0
        var confidence = 0.0

        repeat(8) {
            t += 1000L
            z -= 1.1
            confidence = engine.classify(
                ClassificationEngine.Sample(
                    timestampMs = t,
                    speedMps = 9.0,
                    zMeters = z,
                    accelerationMagnitude = 1.2f,
                    horizontalAccuracyM = 95f
                )
            ).confidence
        }

        assertTrue(confidence < 0.5)
    }
}
