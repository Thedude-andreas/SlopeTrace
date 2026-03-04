package com.slopetrace.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLogin: suspend (String, String) -> Boolean,
    onSignUp: suspend (String, String) -> Boolean,
    onSuccessNavigate: () -> Unit,
    errorMessage: String?
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SlopeTrace Login")
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = Color(0xFFB00020)
            )
        }
        Button(
            onClick = {
                scope.launch {
                    if (onLogin(email.trim(), password)) {
                        onSuccessNavigate()
                    }
                }
            }
        ) { Text("Sign in") }
        Button(
            onClick = {
                scope.launch {
                    if (onSignUp(email.trim(), password)) {
                        onSuccessNavigate()
                    }
                }
            }
        ) { Text("Sign up") }
    }
}
