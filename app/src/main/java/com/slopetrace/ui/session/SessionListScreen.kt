package com.slopetrace.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
fun SessionListScreen(
    onJoin: (String) -> Unit,
    onLive: () -> Unit,
    errorMessage: String?
) {
    var sessionId by remember { mutableStateOf("") }
    val trimmedSessionId = sessionId.trim()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Create or Join Session")
        OutlinedTextField(
            value = sessionId,
            onValueChange = { sessionId = it },
            label = { Text("Session ID (UUID)") },
            modifier = Modifier.fillMaxWidth()
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = Color(0xFFB00020)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onJoin(trimmedSessionId) },
                enabled = trimmedSessionId.isNotEmpty()
            ) {
                Text("Join")
            }
            TextButton(
                onClick = {
                    val generated = UUID.randomUUID().toString()
                    sessionId = generated
                    onJoin(generated)
                }
            ) {
                Text("Create new")
            }
            Button(onClick = onLive) {
                Text("Live View")
            }
        }
    }
}
