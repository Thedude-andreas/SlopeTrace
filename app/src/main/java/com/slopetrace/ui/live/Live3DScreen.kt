package com.slopetrace.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.slopetrace.render.RendererView
import com.slopetrace.ui.session.SessionUiState

@Composable
fun Live3DScreen(
    state: SessionUiState,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConfirmStartFarAway: () -> Unit,
    onCancelStartFarAway: () -> Unit,
    onLeave: () -> Unit,
    onStats: () -> Unit
) {
    val context = LocalContext.current
    val rendererView = remember { RendererView(context) }

    LaunchedEffect(state.remoteTrailsByUser, state.points, state.userId) {
        val trails = state.remoteTrailsByUser.mapValues { (_, points) ->
            points.map { point ->
                floatArrayOf(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
            }
        }

        val localPoints = state.points.map {
            floatArrayOf(it.xEastM.toFloat(), it.yNorthM.toFloat(), it.zUpM.toFloat())
        }

        rendererView.replaceTrails(trails + (state.userId to localPoints))
    }

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
                title = { Text("Bekräfta start av spårning") },
                text = {
                    Text(
                        "Du är cirka ${farDistanceMeters.toInt()} m från närmaste tidigare punkt i sessionen. Vill du ändå starta spårning här?"
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmStartFarAway) {
                        Text("Starta ändå")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelStartFarAway) {
                        Text("Avbryt")
                    }
                }
            )
        }
    }
}
