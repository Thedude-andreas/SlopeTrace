package com.slopetrace.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slopetrace.domain.stats.SessionStats
import kotlin.math.roundToInt

@Composable
fun StatsScreen(stats: SessionStats) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Runs: ${stats.totalRuns}")
        Text("Vertical meters: ${stats.totalVerticalMeters.roundToInt()} m")
        Text("Lift time: ${stats.liftTimeSeconds}s")
        Text("Downhill time: ${stats.downhillTimeSeconds}s")

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(stats.runs) { index, run ->
                Text(
                    "Run ${index + 1}: avg ${run.avgSpeedMps.roundToInt()} m/s, max ${run.maxSpeedMps.roundToInt()} m/s, slope ${run.avgSlopeRad} rad"
                )
            }
        }
    }
}
