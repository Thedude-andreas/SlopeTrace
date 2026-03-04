package com.slopetrace.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TrackingPointEntity)

    @Query("SELECT * FROM tracking_points WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun streamSessionPoints(sessionId: String): Flow<List<TrackingPointEntity>>

    @Query("SELECT * FROM tracking_points WHERE synced = 0 AND sessionId = :sessionId ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun pendingSync(sessionId: String, limit: Int = 500): List<TrackingPointEntity>

    @Query("UPDATE tracking_points SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}
