package com.slopetrace.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TrackingPointEntity): Long

    @Query("SELECT * FROM tracking_points WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun streamSessionPoints(sessionId: String): Flow<List<TrackingPointEntity>>

    @Query("SELECT * FROM tracking_points WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getSessionPoints(sessionId: String): List<TrackingPointEntity>

    @Query("SELECT * FROM tracking_points WHERE synced = 0 AND sessionId = :sessionId ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun pendingSync(sessionId: String, limit: Int): List<TrackingPointEntity>

    @Query("SELECT COUNT(*) FROM tracking_points WHERE synced = 0 AND sessionId = :sessionId")
    suspend fun countPendingSync(sessionId: String): Int

    @Query("UPDATE tracking_points SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>): Int

    @Query(
        """
        UPDATE tracking_points
        SET segmentType = :segmentType,
            segmentConfidence = :segmentConfidence,
            runId = :runId,
            liftId = :liftId
        WHERE id IN (:ids)
        """
    )
    suspend fun updateSegmentsForIds(
        ids: List<Long>,
        segmentType: com.slopetrace.data.model.SegmentType,
        segmentConfidence: Double,
        runId: String?,
        liftId: String?
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLiftLabel(label: LiftLabelEntity): Long

    @Query("SELECT * FROM lift_labels WHERE sessionId = :sessionId ORDER BY liftId ASC")
    suspend fun getLiftLabels(sessionId: String): List<LiftLabelEntity>

    @Query("DELETE FROM tracking_points WHERE sessionId = :sessionId")
    suspend fun deleteSessionPoints(sessionId: String): Int

    @Query("DELETE FROM lift_labels WHERE sessionId = :sessionId")
    suspend fun deleteLiftLabels(sessionId: String): Int

    @Query("UPDATE tracking_points SET sessionId = :targetSessionId WHERE sessionId = :sourceSessionId")
    suspend fun reassignSessionPoints(sourceSessionId: String, targetSessionId: String): Int

    @Query("SELECT COUNT(*) FROM tracking_points WHERE sessionId = :sessionId")
    suspend fun countSessionPoints(sessionId: String): Int
}
