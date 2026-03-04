package com.slopetrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Scaffold
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.slopetrace.di.AppContainer
import com.slopetrace.service.TrackingForegroundService
import com.slopetrace.ui.live.Live3DScreen
import com.slopetrace.ui.login.LoginScreen
import com.slopetrace.ui.session.SessionListScreen
import com.slopetrace.ui.session.SessionViewModel
import com.slopetrace.ui.stats.StatsScreen
import com.slopetrace.ui.theme.SlopeTraceTheme

class MainActivity : ComponentActivity() {
    private lateinit var vm: SessionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = AppContainer(this)
        vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return appContainer.createSessionViewModel() as T
            }
        })[SessionViewModel::class.java]
        vm.handleAuthIntent(intent)

        setContent {
            val navController = rememberNavController()
            val state by vm.ui.collectAsState()
            val context = this
            val appContext = LocalContext.current.applicationContext
            var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                hasLocationPermission = context.hasLocationPermission()
            }

            fun ensureLocationPermission(onGranted: () -> Unit) {
                if (hasLocationPermission) {
                    onGranted()
                } else {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            SlopeTraceTheme {
                LaunchedEffect(state.isRealtimeConnected, state.activeSessionId) {
                    if (state.isRealtimeConnected && state.activeSessionId != null && navController.currentDestination?.route != "live") {
                        navController.navigate("live")
                    }
                }

                LaunchedEffect(state.activeSessionId) {
                    if (state.activeSessionId != null) {
                        TrackingForegroundService.start(appContext)
                    } else {
                        TrackingForegroundService.stop(appContext)
                    }
                }

                Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "login",
                        modifier = androidx.compose.ui.Modifier.padding(innerPadding)
                    ) {
                        composable("login") {
                            LoginScreen(
                                onLogin = vm::login,
                                onSignUp = vm::signUp,
                                onSuccessNavigate = { navController.navigate("sessions") },
                                errorMessage = state.errorMessage
                            )
                        }
                        composable("sessions") {
                            SessionListScreen(
                                onJoin = { sessionId ->
                                    ensureLocationPermission {
                                        vm.createOrJoinSession(sessionId)
                                    }
                                },
                                onLive = {
                                    ensureLocationPermission {
                                        navController.navigate("live")
                                    }
                                },
                                errorMessage = state.errorMessage
                            )
                        }
                        composable("live") {
                            Live3DScreen(
                                state = state,
                                onLeave = {
                                    vm.leaveSession()
                                    navController.navigate("sessions")
                                },
                                onStats = { navController.navigate("stats") }
                            )
                        }
                        composable("stats") {
                            StatsScreen(state.stats)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleAuthIntent(intent)
    }
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
