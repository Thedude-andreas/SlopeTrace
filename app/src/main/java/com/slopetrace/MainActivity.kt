package com.slopetrace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slopetrace.di.AppContainer
import com.slopetrace.service.TrackingForegroundService
import com.slopetrace.ui.live.Live3DScreen
import com.slopetrace.ui.login.LoginScreen
import com.slopetrace.ui.login.SignUpScreen
import com.slopetrace.ui.session.EditSessionsScreen
import com.slopetrace.ui.session.SessionViewModel
import com.slopetrace.ui.stats.StatsScreen
import com.slopetrace.ui.theme.AppPalette
import com.slopetrace.ui.theme.SlopeTraceTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var vm: SessionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as SlopeTraceApp).appContainer
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
            var hasLocationPermission by remember { mutableStateOf(context.hasRequiredLocationPermission()) }
            var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val drawerScope = rememberCoroutineScope()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: "login"
            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                hasLocationPermission = context.hasRequiredLocationPermission()
                if (hasLocationPermission) {
                    pendingLocationAction?.invoke()
                    pendingLocationAction = null
                }
            }
            val backgroundLocationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { _ ->
                hasLocationPermission = context.hasRequiredLocationPermission()
                if (hasLocationPermission) {
                    pendingLocationAction?.invoke()
                    pendingLocationAction = null
                }
            }

            fun ensureLocationPermission(onGranted: () -> Unit) {
                if (hasLocationPermission) {
                    onGranted()
                } else {
                    pendingLocationAction = onGranted
                    val hasForeground = context.hasForegroundLocationPermission()
                    when {
                        !hasForeground -> {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED -> {
                            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }

                        else -> {
                            hasLocationPermission = context.hasRequiredLocationPermission()
                            if (hasLocationPermission) {
                                pendingLocationAction?.invoke()
                                pendingLocationAction = null
                            }
                        }
                    }
                }
            }

            SlopeTraceTheme {
                LaunchedEffect(Unit) {
                    vm.initializeAuthState()
                }

                LaunchedEffect(state.isRealtimeConnected, state.activeSessionId) {
                    if (state.isRealtimeConnected && state.activeSessionId != null && navController.currentDestination?.route != "live") {
                        navController.navigate("live")
                    }
                }

                LaunchedEffect(state.isAuthenticated) {
                    if (state.isAuthenticated && navController.currentDestination?.route == "login") {
                        navController.navigate("edit-sessions")
                    }
                }

                LaunchedEffect(currentRoute) {
                    if (currentRoute != "edit-sessions") {
                        vm.clearMergePreview()
                    }
                }

                LaunchedEffect(state.activeSessionId, state.userId, state.isTrackingActive) {
                    if (state.activeSessionId != null && state.isTrackingActive) {
                        TrackingForegroundService.start(
                            context = appContext,
                            sessionId = state.activeSessionId,
                            userId = state.userId
                        )
                    } else {
                        TrackingForegroundService.stop(appContext)
                    }
                }

                LaunchedEffect(state.activeSessionId, state.userId, state.isAuthenticated, state.isTrackingActive) {
                    if (state.activeSessionId == null || !state.isAuthenticated) return@LaunchedEffect
                    while (isActive) {
                        vm.ensureRealtimeConnection()
                        delay(5_000L)
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = false,
                    drawerContent = {
                        ModalDrawerSheet {
                            Text(
                                text = "SlopeTrace",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                            DrawerItem("Login") {
                                navController.navigate("login")
                                drawerScope.launch { drawerState.close() }
                            }
                            DrawerItem("Live") {
                                navController.navigate("live")
                                drawerScope.launch { drawerState.close() }
                            }
                            DrawerItem("Stats") {
                                navController.navigate("stats")
                                drawerScope.launch { drawerState.close() }
                            }
                            DrawerItem("Edit Sessions") {
                                navController.navigate("edit-sessions")
                                drawerScope.launch { drawerState.close() }
                            }
                            if (state.isAuthenticated) {
                                DrawerItem("Log out") {
                                    vm.logout()
                                    navController.navigate("login")
                                    drawerScope.launch { drawerState.close() }
                                }
                            }
                        }
                    }
                ) {
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            val alias = state.userProfiles[state.userId]?.alias?.trim().orEmpty()
                            val resolvedAlias = alias.ifBlank { "Rider" }
                            TopBar(
                                currentRoute = currentRoute,
                                loggedInAlias = if (state.isAuthenticated) resolvedAlias else null,
                                onMenu = { drawerScope.launch { drawerState.open() } }
                            )
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = "login",
                            modifier = Modifier.padding(innerPadding)
                        ) {
                            composable("login") {
                                LoginScreen(
                                    onLogin = vm::login,
                                    rememberMe = state.rememberMe,
                                    onRememberMeChange = vm::setRememberMe,
                                    onNavigateSignUp = { navController.navigate("signup") },
                                    onSuccessNavigate = { navController.navigate("edit-sessions") },
                                    errorMessage = state.errorMessage,
                                    isLoading = state.isLoading
                                )
                            }
                            composable("signup") {
                                SignUpScreen(
                                    onSignUp = vm::signUp,
                                    onNavigateBackToLogin = { navController.navigate("login") },
                                    onSuccessNavigate = { navController.navigate("edit-sessions") },
                                    errorMessage = state.errorMessage,
                                    isLoading = state.isLoading
                                )
                            }
                            composable("live") {
                                LaunchedEffect(state.activeSessionId) {
                                    vm.refreshLiveSnapshot()
                                }
                                LaunchedEffect(state.activeSessionId, state.isRealtimeConnected) {
                                    if (!state.isRealtimeConnected || state.activeSessionId == null) return@LaunchedEffect
                                    while (isActive) {
                                        delay(2_000L)
                                        vm.refreshLiveSnapshotSilently()
                                    }
                                }
                                Live3DScreen(
                                    state = state,
                                    onStartTracking = vm::requestStartTracking,
                                    onStopTracking = vm::stopTracking,
                                    onConfirmStartFarAway = vm::confirmStartTrackingAfterDistanceCheck,
                                    onCancelStartFarAway = vm::cancelStartTrackingAfterDistanceCheck,
                                    onDismissGpsWaiting = vm::dismissGpsWaitingDialog,
                                    onLeave = {
                                        vm.leaveSession()
                                        navController.navigate("edit-sessions")
                                    },
                                    onStats = { navController.navigate("stats") }
                                )
                            }
                            composable("stats") {
                                StatsScreen(
                                    stats = state.stats,
                                    liftLabels = state.liftLabels,
                                    onRenameLift = vm::renameLift
                                )
                            }
                            composable("edit-sessions") {
                                LaunchedEffect(Unit) {
                                    vm.refreshAvailableSessions()
                                }
                                EditSessionsScreen(
                                    ownSessions = state.ownSessions,
                                    nearbyPublicSessions = state.nearbyPublicSessions,
                                    activeSessionId = state.activeSessionId,
                                    pendingResumeSessionId = state.pendingResumeSessionId,
                                    mergePreview = state.mergePreview,
                                    isLoading = state.isLoading,
                                    errorMessage = state.errorMessage,
                                    onCreateSession = { sessionName, isPublic ->
                                        ensureLocationPermission {
                                            vm.createSessionAndJoin(sessionName, isPublic)
                                        }
                                    },
                                    onOpenSession = { vm.joinExistingSession(it) },
                                    onResumeSession = vm::resumeStoredSession,
                                    onDismissResumeSession = vm::dismissPendingResumeSession,
                                    onRenameSession = vm::renameSession,
                                    onDeleteSelected = vm::deleteSelectedSessions,
                                    onPreviewMerge = vm::previewSessionMerge,
                                    onSaveMergedAsNew = vm::saveMergePreviewAsNewSession,
                                    onRefresh = vm::refreshAvailableSessions
                                )
                            }
                        }

                        if (state.isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(state.loadingMessage ?: "Please wait...")
                                }
                            }
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

@Composable
private fun DrawerItem(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(currentRoute: String, loggedInAlias: String?, onMenu: () -> Unit) {
    val baseTitle = when (currentRoute) {
        "login" -> "Login"
        "signup" -> "Sign up"
        "live" -> "Live"
        "stats" -> "Stats"
        "edit-sessions" -> "Edit Sessions"
        else -> "SlopeTrace"
    }
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = baseTitle,
                    modifier = Modifier.weight(1f)
                )
                if (loggedInAlias != null) {
                    Text(
                        text = "[${loggedInAlias}]",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.End
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenu, modifier = Modifier.padding(start = 8.dp)) {
                Text("☰", color = AppPalette.Accent)
            }
        }
    )
}

private fun Context.hasForegroundLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.hasRequiredLocationPermission(): Boolean {
    if (!hasForegroundLocationPermission()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
