package com.slopetrace.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.slopetrace.data.model.SegmentType

@Entity(tableName = "tracking_points")
data class TrackingPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val synced: Boolean = false
)
