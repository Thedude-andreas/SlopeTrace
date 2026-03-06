package com.slopetrace.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.slopetrace.data.model.UserProfile
import com.slopetrace.render.RenderTrailPoint
import com.slopetrace.render.RendererView
import com.slopetrace.ui.session.SessionUiState
import com.slopetrace.ui.theme.AppPalette
import kotlinx.datetime.Clock
import kotlin.math.sqrt

@Composable
fun Live3DScreen(
    state: SessionUiState,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConfirmStartFarAway: () -> Unit,
    onCancelStartFarAway: () -> Unit,
    onDismissGpsWaiting: () -> Unit,
    onLeave: () -> Unit,
    onStats: () -> Unit
) {
    val context = LocalContext.current
    val rendererView = remember { RendererView(context) }
    val userVisibility = remember { mutableStateMapOf<String, Boolean>() }

    val allUsers = remember(state.remoteTrailsByUser, state.userId) {
        (state.remoteTrailsByUser.keys + state.userId).distinct().sorted()
    }

    val localTrail = remember(state.points, state.rawToPhysicalLiftId) {
        state.points.map { point ->
            val physicalLiftId = point.liftId?.let { raw -> state.rawToPhysicalLiftId[raw] ?: raw }
            RenderTrailPoint(
                x = point.xEastM.toFloat(),
                y = point.yNorthM.toFloat(),
                z = point.zUpM.toFloat(),
                rgba = AppPalette.toGlRgba(AppPalette.segmentColor(point.segmentType, physicalLiftId))
            )
        }
    }

    val remoteTrails = remember(state.remoteTrailsByUser) {
        state.remoteTrailsByUser.mapValues { (_, points) ->
            points.map { point ->
                RenderTrailPoint(
                    x = point.x.toFloat(),
                    y = point.y.toFloat(),
                    z = point.z.toFloat(),
                    rgba = AppPalette.toGlRgba(
                        AppPalette.segmentColor(
                            segmentType = point.segmentType,
                            physicalLiftId = null
                        )
                    )
                )
            }
        }
    }

    val fullTrailsByUser = remember(remoteTrails, state.userId, localTrail) {
        remoteTrails + (state.userId to localTrail)
    }

    val latestRemoteByUser = remember(state.remoteTrailsByUser) {
        state.remoteTrailsByUser.mapValues { (_, points) -> points.lastOrNull() }
    }

    val latestByUser = remember(latestRemoteByUser, state.userId, localTrail, state.points) {
        buildMap {
            latestRemoteByUser.forEach { (userId, point) ->
                if (point != null) {
                    put(userId, LivePoint(point.x, point.y, point.z, point.speed, point.timestamp.toEpochMilliseconds()))
                }
            }
            val localLast = state.points.lastOrNull()
            if (localLast != null) {
                put(
                    state.userId,
                    LivePoint(
                        x = localLast.xEastM,
                        y = localLast.yNorthM,
                        z = localLast.zUpM,
                        speedMps = localLast.speedMps,
                        timestampMs = localLast.timestampMs
                    )
                )
            }
        }
    }

    val trackingActiveUserIds = remember(latestByUser, state.userId, state.isTrackingActive, state.points) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val activeWindowMs = 20_000L
        latestByUser.filter { (userId, point) ->
            if (userId == state.userId) {
                state.isTrackingActive && state.points.isNotEmpty()
            } else {
                nowMs - point.timestampMs <= activeWindowMs
            }
        }.keys
    }

    LaunchedEffect(allUsers) {
        allUsers.forEach { userId ->
            if (!userVisibility.containsKey(userId)) {
                userVisibility[userId] = true
            }
        }
        val removedKeys = userVisibility.keys.filter { it !in allUsers }
        removedKeys.forEach { userVisibility.remove(it) }
    }

    LaunchedEffect(fullTrailsByUser, latestByUser, trackingActiveUserIds, userVisibility.toMap(), state.userProfiles) {
        val trailsByUser = fullTrailsByUser.filter { (userId, _) -> userVisibility[userId] != false }
        val currentPositionsByUser = trackingActiveUserIds
            .filter { userId -> userVisibility[userId] != false }
            .mapNotNull { userId ->
                val last = latestByUser[userId] ?: return@mapNotNull null
                userId to floatArrayOf(last.x.toFloat(), last.y.toFloat(), last.z.toFloat())
            }
            .toMap()

        val userColorById = trailsByUser.keys.associateWith { userId ->
            AppPalette.toGlRgba(profileColor(state.userProfiles[userId], userId))
        }

        rendererView.replaceTrails(
            trailsByUser = trailsByUser,
            currentPositionsByUser = currentPositionsByUser,
            userColorById = userColorById
        )
    }

    val localLastPoint = latestByUser[state.userId]

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Segment: ${state.currentSegment} | Members: ${state.members.size} | Realtime: ${state.isRealtimeConnected}",
            modifier = Modifier.padding(12.dp)
        )
        if (!state.errorMessage.isNullOrBlank()) {
            Text(
                text = state.errorMessage,
                color = Color(0xFFB00020),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        val gpsAcc = state.gpsHorizontalAccuracyM
        if (gpsAcc != null) {
            Text(
                text = "GPS horizontal accuracy: ${"%.1f".format(gpsAcc)} m",
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Legend", style = MaterialTheme.typography.labelLarge)
            allUsers.forEach { userId ->
                val profile = state.userProfiles[userId]
                val latest = latestByUser[userId]
                val isActive = userId in trackingActiveUserIds
                val speedKmh = if (isActive) latest?.speedMps?.times(3.6) else null
                val distanceM = if (isActive && localLastPoint != null && latest != null && userId != state.userId) {
                    distanceMeters(localLastPoint, latest)
                } else {
                    null
                }
                val status = if (isActive) "active" else "inactive"

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = userVisibility[userId] != false,
                        onCheckedChange = { checked ->
                            userVisibility[userId] = checked
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(profileColor(profile, userId))
                    )
                    Text(
                        "${aliasFor(profile, userId)} ($status) | ${speedKmh?.let { "%.1f km/h".format(it) } ?: "-"} | " +
                            "${distanceM?.let { "%.0f m".format(it) } ?: "-"}"
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { rendererView }
        )
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (state.isTrackingActive) onStopTracking() else onStartTracking()
                },
                enabled = !state.isLoading
            ) {
                Text(if (state.isTrackingActive) "Stop Tracking" else "Start Tracking")
            }
            Button(onClick = onStats, enabled = !state.isLoading) { Text("View Stats") }
            Button(onClick = onLeave, enabled = !state.isLoading) { Text("Leave Session") }
        }

        val farDistanceMeters = state.pendingStartDistanceMeters
        if (farDistanceMeters != null) {
            AlertDialog(
                onDismissRequest = onCancelStartFarAway,
                title = { Text("Confirm tracking start") },
                text = {
                    Text(
                        "You are about ${farDistanceMeters.toInt()} m from the closest previous point in this session. Start tracking anyway?"
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmStartFarAway) {
                        Text("Start anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelStartFarAway) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (state.showGpsWaitingDialog) {
            AlertDialog(
                onDismissRequest = onDismissGpsWaiting,
                title = { Text("Waiting for GPS...") },
                text = {
                    Text(
                        "Waiting for better GPS accuracy before tracking starts. " +
                            "Current horizontal accuracy: ${state.gpsHorizontalAccuracyM?.let { "%.1f".format(it) } ?: "-"} m"
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismissGpsWaiting) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

private data class LivePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val speedMps: Double,
    val timestampMs: Long
)

private fun distanceMeters(a: LivePoint, b: LivePoint): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun aliasFor(profile: UserProfile?, userId: String): String {
    return profile?.alias?.takeIf { it.isNotBlank() } ?: "User ${userId.take(6)}"
}

private fun profileColor(profile: UserProfile?, userId: String): Color {
    profile?.color?.let { hex ->
        parseHexColorOrNull(hex)?.let { return it }
    }
    return userDotColor(userId)
}

private fun parseHexColorOrNull(value: String): Color? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 && normalized.length != 8) return null
    return runCatching {
        val argb = when (normalized.length) {
            6 -> (0xFF000000 or normalized.toLong(16)).toInt()
            else -> normalized.toLong(16).toInt()
        }
        Color(argb)
    }.getOrNull()
}

private fun userDotColor(userId: String): Color {
    val hue = ((userId.hashCode() and Int.MAX_VALUE) % 360).toFloat()
    val s = 0.72f
    val v = 0.95f
    val c = v * s
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        hue < 60f -> floatArrayOf(c, x, 0f)
        hue < 120f -> floatArrayOf(x, c, 0f)
        hue < 180f -> floatArrayOf(0f, c, x)
        hue < 240f -> floatArrayOf(0f, x, c)
        hue < 300f -> floatArrayOf(x, 0f, c)
        else -> floatArrayOf(c, 0f, x)
    }
    return Color(r + m, g + m, b + m, 1f)
}
