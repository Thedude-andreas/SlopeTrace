package com.slopetrace.tracking

import android.content.Context
import android.os.Environment
import com.slopetrace.data.local.TrackingDao
import com.slopetrace.data.local.LiftLabelEntity
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.File

class TrackingRepository(
    private val appContext: Context,
    private val sensorRepository: SensorRepository,
    private val dao: TrackingDao,
    private val classifier: ClassificationEngine,
    private val converter: CoordinateConverter,
    private val scope: CoroutineScope
) {
    private data class PendingSegmentGate(
        val segmentType: SegmentType,
        val startZUpM: Double,
        val pendingIds: MutableList<Long> = mutableListOf(),
        var latestConfidence: Double = 0.0,
        var runId: String? = null,
        var liftId: String? = null,
        var confirmed: Boolean = false
    )

    private var trackingJob: Job? = null
    private var barometerBaselineAltitudeM: Double? = null
    private var fusedAltitudeOffsetM: Double? = null
    private var filteredSpeedMps = 0.0
    private var lastSpeedTimestampMs = 0L
    private var pendingSegmentGate: PendingSegmentGate? = null

    fun startTracking(sessionId: String, userId: String): Flow<List<TrackingPoint>> {
        classifier.reset()
        resetFusionState()

        if (trackingJob?.isActive != true) {
            trackingJob = scope.launch(Dispatchers.Default) {
                sensorRepository.trackingFlow().collectLatest { raw ->
                    if (!isActive) return@collectLatest

                    // Drop GNSS spikes with very poor horizontal precision.
                    if (raw.horizontalAccuracyM != null && raw.horizontalAccuracyM > 120f) {
                        return@collectLatest
                    }

                    val altitude = fuseAltitude(
                        pressureHpa = raw.pressureHpa,
                        gpsAltitudeM = raw.gpsAltitudeM,
                        gpsVerticalAccuracyM = raw.gpsVerticalAccuracyM
                    )
                    val enu = converter.toEnu(raw.latitude, raw.longitude, altitude)
                    val smoothedSpeedMps = smoothSpeed(raw.speedMps, raw.timestampMs)
                    val classification = classifier.classify(
                        ClassificationEngine.Sample(
                            timestampMs = raw.timestampMs,
                            speedMps = smoothedSpeedMps,
                            zMeters = enu.upM,
                            accelerationMagnitude = raw.accelerationMagnitude,
                            horizontalAccuracyM = raw.horizontalAccuracyM
                        )
                    )

                    val gated = applyZGate(
                        classificationSegment = classification.segmentType,
                        zUpM = enu.upM,
                        confidence = classification.confidence,
                        runId = classification.runId,
                        liftId = classification.liftId
                    )

                    val entity = TrackingPointEntity(
                        sessionId = sessionId,
                        userId = userId,
                        timestampMs = raw.timestampMs,
                        latitude = raw.latitude,
                        longitude = raw.longitude,
                        pressureHpa = raw.pressureHpa,
                        altitudeM = altitude,
                        speedMps = smoothedSpeedMps,
                        accelerationMagnitude = raw.accelerationMagnitude,
                        xEastM = enu.eastM,
                        yNorthM = enu.northM,
                        zUpM = enu.upM,
                        segmentType = gated.segmentType,
                        segmentConfidence = gated.segmentConfidence,
                        runId = gated.runId,
                        liftId = gated.liftId,
                        synced = false
                    )
                    val insertedId = dao.insertPoint(entity)
                    registerPendingAndBackfillIfNeeded(
                        insertedId = insertedId,
                        zUpM = enu.upM,
                        currentClassificationSegment = classification.segmentType,
                        currentConfidence = classification.confidence,
                        currentRunId = classification.runId,
                        currentLiftId = classification.liftId
                    )
                }
            }
        }

        return dao.streamSessionPoints(sessionId).map { entities ->
            entities.map(::toTrackingPoint)
        }
    }

    suspend fun loadSessionPoints(sessionId: String): List<TrackingPoint> {
        return dao.getSessionPoints(sessionId).map(::toTrackingPoint)
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        sensorRepository.shutdown()
        converter.reset()
        classifier.reset()
        resetFusionState()
    }

    private fun resetFusionState() {
        barometerBaselineAltitudeM = null
        fusedAltitudeOffsetM = null
        filteredSpeedMps = 0.0
        lastSpeedTimestampMs = 0L
        pendingSegmentGate = null
    }

    private data class GatedSegment(
        val segmentType: SegmentType,
        val segmentConfidence: Double,
        val runId: String?,
        val liftId: String?
    )

    private fun applyZGate(
        classificationSegment: SegmentType,
        zUpM: Double,
        confidence: Double,
        runId: String?,
        liftId: String?
    ): GatedSegment {
        if (classificationSegment == SegmentType.UNKNOWN) {
            pendingSegmentGate = null
            return GatedSegment(SegmentType.UNKNOWN, confidence, null, null)
        }

        val gate = pendingSegmentGate
        if (gate == null || gate.segmentType != classificationSegment) {
            pendingSegmentGate = PendingSegmentGate(
                segmentType = classificationSegment,
                startZUpM = zUpM,
                latestConfidence = confidence,
                runId = runId,
                liftId = liftId
            )
            return GatedSegment(SegmentType.UNKNOWN, confidence, null, null)
        }

        gate.latestConfidence = confidence
        gate.runId = runId ?: gate.runId
        gate.liftId = liftId ?: gate.liftId

        return if (gate.confirmed) {
            GatedSegment(classificationSegment, confidence, gate.runId, gate.liftId)
        } else {
            GatedSegment(SegmentType.UNKNOWN, confidence, null, null)
        }
    }

    private suspend fun registerPendingAndBackfillIfNeeded(
        insertedId: Long,
        zUpM: Double,
        currentClassificationSegment: SegmentType,
        currentConfidence: Double,
        currentRunId: String?,
        currentLiftId: String?
    ) {
        val gate = pendingSegmentGate ?: return
        if (gate.segmentType != currentClassificationSegment) return

        gate.pendingIds.add(insertedId)
        gate.latestConfidence = currentConfidence
        gate.runId = currentRunId ?: gate.runId
        gate.liftId = currentLiftId ?: gate.liftId

        if (gate.confirmed) return

        val dz = zUpM - gate.startZUpM
        val reached = when (gate.segmentType) {
            SegmentType.LIFT -> dz >= 10.0
            SegmentType.DOWNHILL -> dz <= -10.0
            SegmentType.UNKNOWN -> false
        }

        if (!reached) return

        gate.confirmed = true
        val idsToBackfill = gate.pendingIds.toList()
        if (idsToBackfill.isEmpty()) return

        dao.updateSegmentsForIds(
            ids = idsToBackfill,
            segmentType = gate.segmentType,
            segmentConfidence = gate.latestConfidence,
            runId = gate.runId,
            liftId = gate.liftId
        )
    }

    private fun smoothSpeed(rawSpeedMps: Double, timestampMs: Long): Double {
        if (lastSpeedTimestampMs == 0L) {
            lastSpeedTimestampMs = timestampMs
            filteredSpeedMps = rawSpeedMps
            return filteredSpeedMps
        }

        val dtSec = ((timestampMs - lastSpeedTimestampMs).coerceAtLeast(1L)) / 1000.0
        lastSpeedTimestampMs = timestampMs
        val alpha = (dtSec / (1.2 + dtSec)).coerceIn(0.12, 0.65)
        filteredSpeedMps += alpha * (rawSpeedMps - filteredSpeedMps)
        return filteredSpeedMps
    }

    private fun fuseAltitude(
        pressureHpa: Float,
        gpsAltitudeM: Double?,
        gpsVerticalAccuracyM: Float?
    ): Double {
        val baroAltitude = pressureToAltitude(pressureHpa)

        if (barometerBaselineAltitudeM == null) {
            barometerBaselineAltitudeM = baroAltitude
        }
        val relativeBaro = baroAltitude - (barometerBaselineAltitudeM ?: baroAltitude)

        val gpsReliable = gpsAltitudeM != null && (gpsVerticalAccuracyM == null || gpsVerticalAccuracyM <= 18f)
        if (gpsReliable) {
            val gps = gpsAltitudeM!!
            val targetOffset = gps - relativeBaro
            fusedAltitudeOffsetM = when (val existing = fusedAltitudeOffsetM) {
                null -> targetOffset
                else -> existing + 0.05 * (targetOffset - existing)
            }
        }

        return relativeBaro + (fusedAltitudeOffsetM ?: 0.0)
    }

    private fun pressureToAltitude(pressureHpa: Float): Double {
        return 44330.0 * (1.0 - (pressureHpa / 1013.25).toDouble().pow(0.1903))
    }

    suspend fun exportSessionToJson(sessionId: String): String {
        val points = dao.getSessionPoints(sessionId)
        val exportDir = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "session_exports"
        )
        if (!exportDir.exists()) exportDir.mkdirs()

        val safeSessionId = sessionId.replace("[^A-Za-z0-9_-]".toRegex(), "_")
        val file = File(exportDir, "session_${safeSessionId}_${System.currentTimeMillis()}.json")

        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("exportedAtMs", System.currentTimeMillis())
            put("pointCount", points.size)
            put("points", JsonArray(points.map { pointToJson(it) }))
        }

        file.writeText(payload.toString())
        return file.absolutePath
    }

    suspend fun currentLocationLatLon(): Pair<Double, Double>? {
        return sensorRepository.currentLocationLatLon()
    }

    suspend fun nearestSessionPointDistanceMeters(sessionId: String, lat: Double, lon: Double): Double? {
        val points = dao.getSessionPoints(sessionId)
        if (points.isEmpty()) return null

        var minDistance = Double.MAX_VALUE
        points.forEach { point ->
            val distance = haversineMeters(
                lat1 = lat,
                lon1 = lon,
                lat2 = point.latitude,
                lon2 = point.longitude
            )
            if (distance < minDistance) {
                minDistance = distance
            }
        }
        return minDistance.takeIf { it.isFinite() }
    }

    suspend fun getLiftLabels(sessionId: String): Map<String, String> {
        return dao.getLiftLabels(sessionId)
            .associate { it.liftId to it.label }
    }

    suspend fun setLiftLabel(sessionId: String, liftId: String, label: String) {
        dao.upsertLiftLabel(
            LiftLabelEntity(
                sessionId = sessionId,
                liftId = liftId,
                label = label.trim()
            )
        )
    }

    private fun pointToJson(point: TrackingPointEntity) = buildJsonObject {
        put("id", point.id)
        put("sessionId", point.sessionId)
        put("userId", point.userId)
        put("timestampMs", point.timestampMs)
        put("latitude", point.latitude)
        put("longitude", point.longitude)
        put("pressureHpa", point.pressureHpa)
        put("altitudeM", point.altitudeM)
        put("speedMps", point.speedMps)
        put("accelerationMagnitude", point.accelerationMagnitude)
        put("xEastM", point.xEastM)
        put("yNorthM", point.yNorthM)
        put("zUpM", point.zUpM)
        put("segmentType", point.segmentType.name)
        put("segmentConfidence", point.segmentConfidence)
        point.runId?.let { put("runId", it) }
        point.liftId?.let { put("liftId", it) }
        put("synced", if (point.synced) JsonPrimitive(1) else JsonPrimitive(0))
    }

    private fun toTrackingPoint(entity: TrackingPointEntity): TrackingPoint {
        return TrackingPoint(
            sessionId = entity.sessionId,
            userId = entity.userId,
            timestampMs = entity.timestampMs,
            latitude = entity.latitude,
            longitude = entity.longitude,
            pressureHpa = entity.pressureHpa,
            altitudeM = entity.altitudeM,
            speedMps = entity.speedMps,
            accelerationMagnitude = entity.accelerationMagnitude,
            xEastM = entity.xEastM,
            yNorthM = entity.yNorthM,
            zUpM = entity.zUpM,
            segmentType = entity.segmentType,
            segmentConfidence = entity.segmentConfidence,
            runId = entity.runId,
            liftId = entity.liftId
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return r * c
    }

    private fun Double.pow(p: Double): Double = kotlin.math.exp(p * ln(this))
}
