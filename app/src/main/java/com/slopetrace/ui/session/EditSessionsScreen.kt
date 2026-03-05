package com.slopetrace.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slopetrace.data.model.Session
import kotlinx.datetime.Instant
import java.time.Instant as JavaInstant
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EditSessionsScreen(
    sessions: List<Session>,
    activeSessionId: String?,
    mergePreview: MergePreview?,
    isLoading: Boolean,
    errorMessage: String?,
    onCreateSession: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSelected: (List<String>) -> Unit,
    onPreviewMerge: (List<String>) -> Unit,
    onSaveMergedAsNew: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val selectedForMerge = remember { mutableStateListOf<String>() }
    val defaultSessionName = remember {
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH))
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newSessionNameDraft by remember { mutableStateOf(defaultSessionName) }
    var mergedNameDraft by remember { mutableStateOf("Merged ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(java.time.LocalDateTime.now())}") }
    var renameSessionId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    LaunchedEffect(sessions) {
        val validIds = sessions.map { it.id }.toSet()
        selectedForMerge.removeAll { it !in validIds }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Edit Sessions")

        val allSelected = sessions.isNotEmpty() && selectedForMerge.size == sessions.size
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = { checked ->
                    selectedForMerge.clear()
                    if (checked) {
                        selectedForMerge.addAll(sessions.map { it.id })
                    }
                }
            )
            Text("All")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { showCreateDialog = true },
                enabled = !isLoading
            ) {
                Text("New")
            }
            Button(
                onClick = { onPreviewMerge(selectedForMerge.toList()) },
                enabled = !isLoading && selectedForMerge.size >= 2
            ) {
                Text("Preview merge")
            }
            Button(
                onClick = { onDeleteSelected(selectedForMerge.toList()) },
                enabled = !isLoading && selectedForMerge.isNotEmpty()
            ) {
                Text("Delete selected")
            }
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) CircularProgressIndicator(strokeWidth = 2.dp) else Text("Refresh")
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(errorMessage)
        }

        if (mergePreview != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Merge preview is temporary until you save")
                    Text("Selected sessions: ${mergePreview.sourceSessionIds.size}")
                    Text("Points: ${mergePreview.totalPoints}")
                    Text("Users: ${mergePreview.userCount}")
                    OutlinedTextField(
                        value = mergedNameDraft,
                        onValueChange = { mergedNameDraft = it },
                        label = { Text("Name for saved merged session") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { onSaveMergedAsNew(mergedNameDraft) }, enabled = !isLoading) {
                        Text("Save as new session")
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions) { session ->
                val selected = session.id in selectedForMerge
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (session.id !in selectedForMerge) selectedForMerge.add(session.id)
                                    } else {
                                        selectedForMerge.remove(session.id)
                                    }
                                }
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(session.name)
                                Text("Started: ${session.startTime.toDisplayString()}")
                                if (session.id == activeSessionId) {
                                    Text("Current active session")
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onOpenSession(session.id) },
                                enabled = !isLoading
                            ) { Text("Join") }
                            Button(
                                onClick = {
                                    renameSessionId = session.id
                                    renameDraft = session.name
                                },
                                enabled = !isLoading
                            ) { Text("Rename") }
                        }
                    }
                }
            }
        }
    }

    val currentRenameId = renameSessionId
    if (currentRenameId != null) {
        AlertDialog(
            onDismissRequest = { renameSessionId = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Session name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(currentRenameId, renameDraft)
                        renameSessionId = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameSessionId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create session") },
            text = {
                OutlinedTextField(
                    value = newSessionNameDraft,
                    onValueChange = { newSessionNameDraft = it },
                    label = { Text("Session name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreateSession(newSessionNameDraft.trim())
                        showCreateDialog = false
                    },
                    enabled = newSessionNameDraft.trim().isNotEmpty() && !isLoading
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun Instant.toDisplayString(): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        .format(JavaInstant.ofEpochMilli(toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}
