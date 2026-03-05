package com.slopetrace.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slopetrace.data.model.Session
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessions: List<Session>,
    onRefreshSessions: () -> Unit,
    onCreateSession: (String) -> Unit,
    onJoinSession: (String) -> Unit,
    onLive: () -> Unit,
    errorMessage: String?,
    lastExportPath: String?,
    isLoading: Boolean
) {
    val defaultSessionName = remember {
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale("sv", "SE")))
    }
    var newSessionName by remember { mutableStateOf(defaultSessionName) }
    var selectedSessionId by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(sessions) {
        if (selectedSessionId.isBlank() && sessions.isNotEmpty()) {
            selectedSessionId = sessions.first().id
        }
    }

    val selectedSessionLabel = sessions.firstOrNull { it.id == selectedSessionId }?.let {
        "${it.name} (${formatSessionStart(it)})"
    } ?: "Välj befintlig session"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Create or Join Session")

        OutlinedTextField(
            value = newSessionName,
            onValueChange = { newSessionName = it },
            enabled = !isLoading,
            label = { Text("Nytt sessionsnamn") },
            modifier = Modifier.fillMaxWidth()
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!isLoading) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedSessionLabel,
                onValueChange = {},
                readOnly = true,
                enabled = !isLoading,
                label = { Text("Join befintlig session") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Text("${session.name} (${formatSessionStart(session)})")
                        },
                        onClick = {
                            selectedSessionId = session.id
                            expanded = false
                        }
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = Color(0xFFB00020)
            )
        }
        if (!lastExportPath.isNullOrBlank()) {
            Text(
                text = "Senaste export: $lastExportPath",
                color = Color(0xFF0B6E4F)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onCreateSession(newSessionName.trim()) },
                enabled = !isLoading && newSessionName.trim().isNotEmpty()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Create new")
                }
            }
            Button(
                onClick = { onJoinSession(selectedSessionId) },
                enabled = !isLoading && selectedSessionId.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Join")
                }
            }
            TextButton(onClick = onRefreshSessions, enabled = !isLoading) {
                Text("Refresh")
            }
            Button(onClick = onLive, enabled = !isLoading) {
                Text("Live View")
            }
        }
    }
}

private fun formatSessionStart(session: Session): String {
    val localDateTime = Instant
        .ofEpochMilli(session.startTime.toEpochMilliseconds())
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    return localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale("sv", "SE")))
}
