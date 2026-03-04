package com.slopetrace.data.model

import kotlinx.datetime.Instant

data class AppUser(
    val id: String,
    val name: String,
    val color: String
)

data class Session(
    val id: String,
    val name: String,
    val startTime: Instant,
    val endTime: Instant? = null
)

data class PositionStreamItem(
    val userId: String,
    val sessionId: String,
    val timestamp: Instant,
    val x: Double,
    val y: Double,
    val z: Double,
    val speed: Double,
    val segmentType: SegmentType
)
