package com.slopetrace.tracking

import com.slopetrace.data.local.TrackingDao
import com.slopetrace.data.local.TrackingPointEntity
import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.TrackingPoint
import com.slopetrace.domain.classification.ClassificationEngine
import com.slopetrace.domain.coords.CoordinateConverter
import com.slopetrace.sensor.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln

class TrackingRepository(
    private val sensorRepository: SensorRepository,
    private val dao: TrackingDao,
    private val classifier: ClassificationEngine,
    private val converter: CoordinateConverter,
    private val scope: CoroutineScope
) {
    private var trackingJob: Job? = null

    fun startTracking(sessionId: String, userId: String): Flow<List<TrackingPoint>> {
        if (trackingJob?.isActive != true) {
            trackingJob = scope.launch(Dispatchers.Default) {
                var lastMovementMs = 0L
                sensorRepository.trackingFlow().collectLatest { raw ->
                    if (!isActive) return@collectLatest

                    val altitude = pressureToAltitude(raw.pressureHpa)
                    val enu = converter.toEnu(raw.latitude, raw.longitude, altitude)
                    val segment = classifier.classify(
                        ClassificationEngine.Sample(
                            timestampMs = raw.timestampMs,
                            speedMps = raw.speedMps,
                            zMeters = enu.upM,
                            accelerationMagnitude = raw.accelerationMagnitude
                        )
                    )

                    val moving = raw.speedMps >= 1.0
                    if (moving) lastMovementMs = raw.timestampMs

                    if (lastMovementMs != 0L && raw.timestampMs - lastMovementMs > 120_000) {
                        stopTracking()
                        return@collectLatest
                    }

                    val entity = TrackingPointEntity(
                        sessionId = sessionId,
                        userId = userId,
                        timestampMs = raw.timestampMs,
                        latitude = raw.latitude,
                        longitude = raw.longitude,
                        pressureHpa = raw.pressureHpa,
                        altitudeM = altitude,
                        speedMps = raw.speedMps,
                        accelerationMagnitude = raw.accelerationMagnitude,
                        xEastM = enu.eastM,
                        yNorthM = enu.northM,
                        zUpM = enu.upM,
                        segmentType = segment,
                        synced = false
                    )
                    dao.insertPoint(entity)
                }
            }
        }

        return dao.streamSessionPoints(sessionId).map { entities ->
            entities.map {
                TrackingPoint(
                    sessionId = it.sessionId,
                    userId = it.userId,
                    timestampMs = it.timestampMs,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    pressureHpa = it.pressureHpa,
                    altitudeM = it.altitudeM,
                    speedMps = it.speedMps,
                    accelerationMagnitude = it.accelerationMagnitude,
                    xEastM = it.xEastM,
                    yNorthM = it.yNorthM,
                    zUpM = it.zUpM,
                    segmentType = it.segmentType
                )
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        sensorRepository.shutdown()
        converter.reset()
    }

    private fun pressureToAltitude(pressureHpa: Float): Double {
        return 44330.0 * (1.0 - (pressureHpa / 1013.25).toDouble().pow(0.1903))
    }

    private fun Double.pow(p: Double): Double = kotlin.math.exp(p * ln(this))
}
