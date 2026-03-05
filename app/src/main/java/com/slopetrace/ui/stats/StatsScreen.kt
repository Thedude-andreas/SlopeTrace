package com.slopetrace.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slopetrace.domain.stats.SessionEvent
import com.slopetrace.domain.stats.SessionStats
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    stats: SessionStats,
    liftLabels: Map<String, String>,
    onRenameLift: (liftId: String, label: String) -> Unit
) {
    var renameTargetLiftId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Session", fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                Text("Runs: ${stats.totalRuns}")
                Text("Total downhill time: ${formatDuration(stats.downhillTimeSeconds)}")
                Text("Total lift time: ${formatDuration(stats.liftTimeSeconds)}")
                Text("Total other time: ${formatDuration(stats.otherTimeSeconds)}")
                Text("Max speed (session): ${toKmh(stats.maxSessionSpeedMps).roundToInt()} km/h")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(stats.events) { event ->
                when (event) {
                    is SessionEvent.Lift -> {
                        val label = liftLabels[event.stats.physicalLiftId] ?: "Lift ${event.stats.index}"
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFDDF3E8),
                                contentColor = Color(0xFF133728)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    renameTargetLiftId = event.stats.physicalLiftId
                                    renameDraft = label
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("LIFT ${event.stats.index}", fontWeight = FontWeight.SemiBold)
                                Text("Lift id: ${event.stats.physicalLiftId}")
                                Text("Namn: $label")
                                Text("Lift speed: ${event.stats.avgSpeedMps.roundToInt()} m/s")
                                Text("Time in lift: ${formatDuration(event.stats.durationSeconds)}")
                                Text("Tryck för att byta namn", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    is SessionEvent.Downhill -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFE3ECFB),
                                contentColor = Color(0xFF10243E)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("DOWNHILL ${event.stats.index}", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Fallhöjd: ${event.stats.verticalDropMeters.roundToInt()} m")
                                    Text("Tid: ${formatDuration(event.stats.durationSeconds)}")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Maxfart: ${toKmh(event.stats.maxSpeedMps).roundToInt()} km/h")
                                    Text("Medelfart: ${toKmh(event.stats.avgSpeedMps).roundToInt()} km/h")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Medelvinkel: ${event.stats.meanAngleDeg.roundToInt()}°")
                                    Text("Maxvinkel: ${event.stats.maxAngleDeg.roundToInt()}°")
                                }
                                if (event.stats.airtimes.isEmpty()) {
                                    Text("Air time: -")
                                } else {
                                    event.stats.airtimes.forEach { airtime ->
                                        Text("Air time ${airtime.index}: ${airtime.durationMs} ms")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val liftId = renameTargetLiftId
    if (liftId != null) {
        AlertDialog(
            onDismissRequest = { renameTargetLiftId = null },
            title = { Text("Döp lift") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Liftnamn") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameLift(liftId, renameDraft)
                        renameTargetLiftId = null
                    }
                ) {
                    Text("Spara")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetLiftId = null }) {
                    Text("Avbryt")
                }
            }
        )
    }
}

private fun toKmh(mps: Double): Double = mps * 3.6

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val mm = safe / 60
    val ss = safe % 60
    return "%02d:%02d".format(mm, ss)
}
