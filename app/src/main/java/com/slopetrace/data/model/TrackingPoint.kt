package com.slopetrace.data.model

data class TrackingPoint(
    val sessionId: String,
    val userId: String,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val pressureHpa: Float,
    val altitudeM: Double,
    val speedMps: Double,
    val accelerationMagnitude: Float,
    val xEastM: Double,
    val yNorthM: Double,
    val zUpM: Double,
    val segmentType: SegmentType,
    val segmentConfidence: Double,
    val runId: String?,
    val liftId: String?
)
