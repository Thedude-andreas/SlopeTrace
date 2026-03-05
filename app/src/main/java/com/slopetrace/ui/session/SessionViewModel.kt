package com.slopetrace.ui.session

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slopetrace.data.model.PositionStreamItem
import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.Session
import com.slopetrace.data.model.TrackingPoint
import com.slopetrace.data.model.UserProfile
import com.slopetrace.data.remote.RealtimeRepository
import com.slopetrace.domain.stats.SessionStats
import com.slopetrace.domain.stats.StatsEngine
import com.slopetrace.tracking.ActiveSessionState
import com.slopetrace.tracking.ActiveSessionStore
import com.slopetrace.tracking.TrackingRepository
import com.slopetrace.ui.login.AuthPreferencesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MergePreview(
    val sourceSessionIds: List<String>,
    val totalPoints: Int,
    val userCount: Int
)

data class SessionUiState(
    val activeSessionId: String? = null,
    val userId: String = "local-user",
    val points: List<TrackingPoint> = emptyList(),
    val currentSegment: SegmentType = SegmentType.UNKNOWN,
    val stats: SessionStats = SessionStats(
        totalRuns = 0,
        totalVerticalMeters = 0.0,
        totalSessionSeconds = 0L,
        liftTimeSeconds = 0L,
        liftMovingTimeSeconds = 0L,
        liftStationaryTimeSeconds = 0L,
        downhillTimeSeconds = 0L,
        downhillMovingTimeSeconds = 0L,
        downhillStationaryTimeSeconds = 0L,
        otherTimeSeconds = 0L,
        maxSessionSpeedMps = 0.0,
        runs = emptyList(),
        lifts = emptyList(),
        events = emptyList()
    ),
    val members: List<String> = emptyList(),
    val availableSessions: List<Session> = emptyList(),
    val remoteTrailsByUser: Map<String, List<PositionStreamItem>> = emptyMap(),
    val userProfiles: Map<String, UserProfile> = emptyMap(),
    val liftLabels: Map<String, String> = emptyMap(),
    val rawToPhysicalLiftId: Map<String, String> = emptyMap(),
    val isTrackingActive: Boolean = false,
    val pendingStartDistanceMeters: Double? = null,
    val gpsReadyToStart: Boolean = true,
    val gpsHorizontalAccuracyM: Float? = null,
    val gpsVerticalAccuracyM: Float? = null,
    val showGpsWaitingDialog: Boolean = false,
    val mergePreview: MergePreview? = null,
    val lastExportPath: String? = null,
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val rememberMe: Boolean = true,
    val isAuthenticated: Boolean = false,
    val isRealtimeConnected: Boolean = false,
    val errorMessage: String? = null
)

class SessionViewModel(
    private val trackingRepository: TrackingRepository,
    private val realtimeRepository: RealtimeRepository,
    private val activeSessionStore: ActiveSessionStore,
    private val authPreferencesStore: AuthPreferencesStore,
    private val statsEngine: StatsEngine
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    private var trackingCollectJob: Job? = null
    private var remoteTrailPollingJob: Job? = null

    init {
        _ui.update { it.copy(rememberMe = authPreferencesStore.isRememberMeEnabled()) }

        activeSessionStore.load()?.let { state ->
            _ui.update {
                it.copy(
                    activeSessionId = state.sessionId,
                    userId = state.userId,
                    isTrackingActive = false
                )
            }
            startTrackingCollection(sessionId = state.sessionId, userId = state.userId)
            startRemoteTrailPolling(sessionId = state.sessionId)
        }
    }

    fun initializeAuthState() {
        viewModelScope.launch {
            val remember = authPreferencesStore.isRememberMeEnabled()
            if (!remember) {
                _ui.update {
                    it.copy(
                        rememberMe = false,
                        isAuthenticated = false,
                        userId = "local-user"
                    )
                }
                return@launch
            }

            val userId = realtimeRepository.currentUserIdOrNull()
            if (userId != null) {
                _ui.update {
                    it.copy(
                        rememberMe = true,
                        isAuthenticated = true,
                        userId = userId,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun setRememberMe(enabled: Boolean) {
        authPreferencesStore.setRememberMeEnabled(enabled)
        _ui.update { it.copy(rememberMe = enabled) }
    }

    private suspend inline fun <T> withLoadingSuspend(message: String, crossinline action: suspend () -> T): T {
        _ui.update { it.copy(isLoading = true, loadingMessage = message) }
        return try {
            action()
        } finally {
            _ui.update { it.copy(isLoading = false, loadingMessage = null) }
        }
    }

    fun refreshAvailableSessions() {
        viewModelScope.launch {
            withLoadingSuspend("Loading sessions...") {
                runCatching {
                    realtimeRepository.listSessions()
                }.onSuccess { sessions ->
                    _ui.update { it.copy(availableSessions = sessions) }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(errorMessage = toUserMessage(error, "Could not load sessions."))
                    }
                }
            }
        }
    }

    fun createSessionAndJoin(sessionName: String) {
        val trimmedName = sessionName.trim()
        if (trimmedName.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Session name cannot be empty.") }
            return
        }

        _ui.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            withLoadingSuspend("Creating session...") {
                try {
                    val createdSession = realtimeRepository.createSession(trimmedName)
                    joinSessionInternal(createdSession.id)
                    refreshAvailableSessions()
                } catch (e: Exception) {
                    _ui.update {
                        it.copy(
                            isRealtimeConnected = false,
                            errorMessage = toUserMessage(e, "Could not create session.")
                        )
                    }
                }
            }
        }
    }

    fun joinExistingSession(sessionId: String) {
        val requestedSessionId = sessionId.trim()
        if (requestedSessionId.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Select a session to join.") }
            return
        }

        _ui.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            withLoadingSuspend("Joining session...") {
                try {
                    joinSessionInternal(requestedSessionId)
                    refreshAvailableSessions()
                } catch (e: Exception) {
                    _ui.update {
                        it.copy(
                            isRealtimeConnected = false,
                            errorMessage = toUserMessage(e, "Could not join the session.")
                        )
                    }
                }
            }
        }
    }

    fun requestStartTracking() {
        val state = _ui.value
        val sessionId = state.activeSessionId ?: return
        if (state.isTrackingActive) return

        viewModelScope.launch {
            withLoadingSuspend("Preparing tracking...") {
                val gpsReadiness = trackingRepository.currentGpsReadiness()
                if (!gpsReadiness.isReady) {
                    _ui.update {
                        it.copy(
                            gpsReadyToStart = false,
                            gpsHorizontalAccuracyM = gpsReadiness.horizontalAccuracyM,
                            gpsVerticalAccuracyM = gpsReadiness.verticalAccuracyM,
                            showGpsWaitingDialog = true,
                            errorMessage = "GPS signal is not accurate enough yet. Wait a few seconds and try again."
                        )
                    }
                    return@withLoadingSuspend
                }

                _ui.update {
                    it.copy(
                        gpsReadyToStart = true,
                        gpsHorizontalAccuracyM = gpsReadiness.horizontalAccuracyM,
                        gpsVerticalAccuracyM = gpsReadiness.verticalAccuracyM,
                        showGpsWaitingDialog = false,
                        errorMessage = null
                    )
                }

                val location = trackingRepository.currentLocationLatLon()
                if (location != null) {
                    val nearestMeters = trackingRepository.nearestSessionPointDistanceMeters(
                        sessionId = sessionId,
                        lat = location.first,
                        lon = location.second
                    )
                    if (nearestMeters != null && nearestMeters > 1000.0) {
                        _ui.update { it.copy(pendingStartDistanceMeters = nearestMeters) }
                        return@withLoadingSuspend
                    }
                }

                startTrackingCollection(sessionId = sessionId, userId = state.userId)
            }
        }
    }

    fun confirmStartTrackingAfterDistanceCheck() {
        val state = _ui.value
        val sessionId = state.activeSessionId ?: return
        if (state.isTrackingActive) {
            _ui.update { it.copy(pendingStartDistanceMeters = null) }
            return
        }

        _ui.update { it.copy(pendingStartDistanceMeters = null) }
        startTrackingCollection(sessionId = sessionId, userId = state.userId)
    }

    fun cancelStartTrackingAfterDistanceCheck() {
        _ui.update { it.copy(pendingStartDistanceMeters = null) }
    }

    fun stopTracking() {
        if (!_ui.value.isTrackingActive) return

        viewModelScope.launch {
            withLoadingSuspend("Stopping tracking...") {
                trackingCollectJob?.cancel()
                trackingCollectJob = null
                trackingRepository.stopTracking()
                activeSessionStore.clear()
                _ui.update {
                    it.copy(
                        isTrackingActive = false,
                        currentSegment = SegmentType.UNKNOWN,
                        pendingStartDistanceMeters = null
                    )
                }
            }
        }
    }

    private suspend fun joinSessionInternal(sessionId: String) {
        val previousSession = _ui.value.activeSessionId
        if (previousSession != null && previousSession != sessionId) {
            trackingCollectJob?.cancel()
            trackingCollectJob = null
            trackingRepository.stopTracking()
            realtimeRepository.disconnectRealtime(previousSession)
            activeSessionStore.clear()
        }

        remoteTrailPollingJob?.cancel()

        val userId = realtimeRepository.requireCurrentUserId()
        val resolvedSessionId = realtimeRepository.joinSession(sessionId)
        val members = realtimeRepository.fetchSessionMembers(resolvedSessionId)
        val historicalLocalPoints = trackingRepository.loadSessionPoints(resolvedSessionId)
        val liftLabels = trackingRepository.getLiftLabels(resolvedSessionId)
        realtimeRepository.connectRealtime(resolvedSessionId)

        val stats = statsEngine.compute(historicalLocalPoints)
        val profiles = realtimeRepository.fetchUserProfiles((members + userId).toSet())
        _ui.update {
            it.copy(
                activeSessionId = resolvedSessionId,
                userId = userId,
                members = members,
                userProfiles = profiles,
                isRealtimeConnected = true,
                points = historicalLocalPoints,
                currentSegment = historicalLocalPoints.lastOrNull()?.segmentType ?: SegmentType.UNKNOWN,
                stats = stats,
                remoteTrailsByUser = emptyMap(),
                liftLabels = liftLabels,
                rawToPhysicalLiftId = stats.lifts.associate { lift -> lift.liftId to lift.physicalLiftId },
                isTrackingActive = false,
                pendingStartDistanceMeters = null
            )
        }

        startRemoteTrailPolling(resolvedSessionId)
    }

    private fun startTrackingCollection(sessionId: String, userId: String) {
        if (_ui.value.isTrackingActive) return

        activeSessionStore.save(ActiveSessionState(sessionId = sessionId, userId = userId))
        trackingCollectJob?.cancel()
        trackingCollectJob = viewModelScope.launch {
            _ui.update { it.copy(isTrackingActive = true, pendingStartDistanceMeters = null) }
            trackingRepository.startTracking(sessionId, userId).collect { points ->
                val currentSegment = points.lastOrNull()?.segmentType ?: SegmentType.UNKNOWN
                val stats = statsEngine.compute(points)
                _ui.update {
                    it.copy(
                        points = points,
                        currentSegment = currentSegment,
                        stats = stats,
                        rawToPhysicalLiftId = stats.lifts.associate { lift -> lift.liftId to lift.physicalLiftId }
                    )
                }
                if (points.size % 5 == 0) {
                    realtimeRepository.enqueueAndSync(sessionId)
                }
            }
        }
    }

    private fun startRemoteTrailPolling(sessionId: String) {
        remoteTrailPollingJob?.cancel()
        remoteTrailPollingJob = viewModelScope.launch {
            while (isActive && _ui.value.activeSessionId == sessionId) {
                runCatching {
                    realtimeRepository.fetchSessionTrails(sessionId)
                }.onSuccess { trails ->
                    val userIds = trails.keys + _ui.value.userId
                    val profiles = runCatching { realtimeRepository.fetchUserProfiles(userIds.toSet()) }
                        .getOrElse { _ui.value.userProfiles }
                    _ui.update {
                        it.copy(
                            remoteTrailsByUser = trails,
                            members = trails.keys.sorted(),
                            userProfiles = profiles
                        )
                    }
                }
                delay(3_000L)
            }
        }
    }

    fun leaveSession() {
        val sessionId = _ui.value.activeSessionId ?: return
        viewModelScope.launch {
            withLoadingSuspend("Leaving session...") {
                try {
                    trackingCollectJob?.cancel()
                    trackingCollectJob = null
                    remoteTrailPollingJob?.cancel()
                    trackingRepository.stopTracking()
                    realtimeRepository.disconnectRealtime(sessionId)
                } finally {
                    val exportPath = runCatching {
                        trackingRepository.exportSessionToJson(sessionId)
                    }.getOrNull()

                    activeSessionStore.clear()
                    _ui.update {
                        it.copy(
                            activeSessionId = null,
                            isRealtimeConnected = false,
                            points = emptyList(),
                            currentSegment = SegmentType.UNKNOWN,
                            remoteTrailsByUser = emptyMap(),
                            userProfiles = emptyMap(),
                            liftLabels = emptyMap(),
                            rawToPhysicalLiftId = emptyMap(),
                            members = emptyList(),
                            isTrackingActive = false,
                            pendingStartDistanceMeters = null,
                            showGpsWaitingDialog = false,
                            lastExportPath = exportPath
                        )
                    }
                }
            }
        }
    }

    suspend fun login(email: String, password: String): Boolean {
        return withLoadingSuspend("Signing in...") {
            try {
                realtimeRepository.login(email, password)
                _ui.update {
                    it.copy(
                        userId = realtimeRepository.requireCurrentUserId(),
                        isAuthenticated = true,
                        errorMessage = null
                    )
                }
                true
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = toUserMessage(e, "Sign in failed."))
                }
                false
            }
        }
    }

    suspend fun signUp(email: String, password: String, alias: String): Boolean {
        val trimmedAlias = alias.trim()
        if (trimmedAlias.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Alias is required for sign up.") }
            return false
        }

        return withLoadingSuspend("Creating account...") {
            try {
                realtimeRepository.signUp(email, password)
                realtimeRepository.login(email, password)
                realtimeRepository.upsertCurrentUserProfile(trimmedAlias)
                _ui.update {
                    it.copy(
                        userId = realtimeRepository.requireCurrentUserId(),
                        isAuthenticated = true,
                        errorMessage = null
                    )
                }
                true
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = toUserMessage(e, "Sign up failed."))
                }
                false
            }
        }
    }

    fun handleAuthIntent(intent: Intent?) {
        if (!realtimeRepository.handleAuthIntent(intent)) return
        runCatching { realtimeRepository.requireCurrentUserId() }
            .onSuccess { userId ->
                _ui.update { it.copy(userId = userId, isAuthenticated = true, errorMessage = null) }
            }
    }

    fun renameLift(liftId: String, label: String) {
        val sessionId = _ui.value.activeSessionId ?: return
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            withLoadingSuspend("Saving lift label...") {
                runCatching {
                    trackingRepository.setLiftLabel(sessionId, liftId, trimmed)
                    trackingRepository.getLiftLabels(sessionId)
                }.onSuccess { labels ->
                    _ui.update { it.copy(liftLabels = labels) }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(errorMessage = toUserMessage(error, "Could not save lift label."))
                    }
                }
            }
        }
    }

    fun renameSession(sessionId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            _ui.update { it.copy(errorMessage = "Session name cannot be empty.") }
            return
        }
        viewModelScope.launch {
            withLoadingSuspend("Renaming session...") {
                runCatching {
                    // Ensure membership exists for older sessions before RPC authorization check.
                    realtimeRepository.joinSession(sessionId)
                    realtimeRepository.renameSession(sessionId, trimmed)
                    realtimeRepository.listSessions()
                }.onSuccess { sessions ->
                    _ui.update { it.copy(availableSessions = sessions, errorMessage = null) }
                }.onFailure { error ->
                    _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not rename session.")) }
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            withLoadingSuspend("Deleting session...") {
                runCatching {
                    realtimeRepository.deleteSession(sessionId)
                    trackingRepository.clearLocalSession(sessionId)
                }.onSuccess {
                    if (_ui.value.activeSessionId == sessionId) {
                        leaveSession()
                    }
                    refreshAvailableSessions()
                }.onFailure { error ->
                    _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not delete session.")) }
                }
            }
        }
    }

    fun deleteSelectedSessions(sessionIds: List<String>) {
        val uniqueIds = sessionIds.distinct()
        if (uniqueIds.isEmpty()) return
        viewModelScope.launch {
            withLoadingSuspend("Deleting selected sessions...") {
                runCatching {
                    // Ensure membership exists for older sessions before delete authorization check.
                    uniqueIds.forEach { realtimeRepository.joinSession(it) }
                    realtimeRepository.deleteSessions(uniqueIds)
                    uniqueIds.forEach { trackingRepository.clearLocalSession(it) }
                }.onSuccess {
                    if (_ui.value.activeSessionId in uniqueIds) {
                        leaveSession()
                    }
                    refreshAvailableSessions()
                }.onFailure { error ->
                    _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not delete selected sessions.")) }
                }
            }
        }
    }

    fun dismissGpsWaitingDialog() {
        _ui.update { it.copy(showGpsWaitingDialog = false) }
    }

    fun previewSessionMerge(sourceSessionIds: List<String>) {
        val uniqueIds = sourceSessionIds.distinct()
        if (uniqueIds.size < 2) {
            _ui.update { it.copy(errorMessage = "Select at least two sessions.") }
            return
        }
        viewModelScope.launch {
            withLoadingSuspend("Preparing merge preview...") {
                runCatching {
                    val trailsBySession = uniqueIds.associateWith { sessionId ->
                        realtimeRepository.fetchSessionTrails(sessionId)
                    }
                    val totalPoints = trailsBySession.values.sumOf { trails ->
                        trails.values.sumOf { it.size }
                    }
                    val users = trailsBySession.values.flatMap { it.keys }.toSet()
                    MergePreview(
                        sourceSessionIds = uniqueIds,
                        totalPoints = totalPoints,
                        userCount = users.size
                    )
                }.onSuccess { preview ->
                    _ui.update { it.copy(mergePreview = preview, errorMessage = null) }
                }.onFailure { error ->
                    _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not prepare merge preview.")) }
                }
            }
        }
    }

    fun saveMergePreviewAsNewSession(name: String) {
        val preview = _ui.value.mergePreview ?: run {
            _ui.update { it.copy(errorMessage = "Create a merge preview first.") }
            return
        }
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _ui.update { it.copy(errorMessage = "Name is required.") }
            return
        }

        viewModelScope.launch {
            withLoadingSuspend("Saving merged session...") {
                runCatching {
                    realtimeRepository.createMergedSessionFromSources(
                        sourceSessionIds = preview.sourceSessionIds,
                        mergedName = trimmed
                    )
                }.onSuccess { newSession ->
                    _ui.update { it.copy(mergePreview = null, errorMessage = null) }
                    refreshAvailableSessions()
                    joinExistingSession(newSession.id)
                }.onFailure { error ->
                    _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not save merged session.")) }
                }
            }
        }
    }

    fun clearMergePreview() {
        _ui.update { it.copy(mergePreview = null) }
    }

    private fun toUserMessage(error: Throwable, fallback: String): String {
        val message = error.message?.lowercase().orEmpty()
        return when {
            "email not confirmed" in message -> "Email is not confirmed yet."
            "invalid login credentials" in message -> "Invalid email or password."
            "session id must be a valid uuid" in message -> "Invalid session ID."
            "location" in message && "permission" in message -> "Location permission is missing."
            "network" in message || "timeout" in message -> "Network error. Check your connection and try again."
            else -> fallback
        }
    }
}
