package com.slopetrace.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
    onLeave: () -> Unit,
    onStats: () -> Unit
) {
    val context = LocalContext.current
    val rendererView = remember { RendererView(context) }

    LaunchedEffect(state.points) {
        val localPoints = state.points.map {
            floatArrayOf(it.xEastM.toFloat(), it.yNorthM.toFloat(), it.zUpM.toFloat())
        }
        rendererView.updateTrail(state.userId, localPoints)
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
            Button(onClick = onStats) { Text("View Stats") }
            Button(onClick = onLeave) { Text("Leave Session") }
        }
    }
}
