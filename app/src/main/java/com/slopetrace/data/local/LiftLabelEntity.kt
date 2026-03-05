package com.slopetrace.data.local

import androidx.room.Entity

@Entity(
    tableName = "lift_labels",
    primaryKeys = ["sessionId", "liftId"]
)
data class LiftLabelEntity(
    val sessionId: String,
    val liftId: String,
    val label: String
)
