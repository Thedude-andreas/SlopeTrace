package com.slopetrace.ui.session

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slopetrace.data.model.PositionStreamItem
import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.Session
import com.slopetrace.data.model.TrackingPoint
import com.slopetrace.data.model.UserProfile
import com.slopetrace.data.remote.RealtimeConnectionState
import com.slopetrace.data.remote.RealtimeRepository
import com.slopetrace.domain.stats.SessionStats
import com.slopetrace.domain.stats.StatsEngine
import com.slopetrace.tracking.ActiveSessionState
import com.slopetrace.tracking.ActiveSessionStore
import com.slopetrace.tracking.TrackingRepository
import com.slopetrace.ui.login.AuthPreferencesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val ownSessions: List<Session> = emptyList(),
    val nearbyPublicSessions: List<Session> = emptyList(),
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
    val pendingResumeSessionId: String? = null,
    val presentUserIds: Set<String> = emptySet(),
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val lastSyncedAtMs: Long? = null,
    val syncErrorMessage: String? = null,
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val rememberMe: Boolean = true,
    val isAuthenticated: Boolean = false,
    val isRealtimeConnected: Boolean = false,
    val realtimeConnectionState: RealtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
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
    private var livePositionCollectJob: Job? = null
    private val storedSessionCandidate = activeSessionStore.load()

    init {
        _ui.update { it.copy(rememberMe = authPreferencesStore.isRememberMeEnabled()) }

        livePositionCollectJob = viewModelScope.launch {
            realtimeRepository.incomingPositions.collectLatest { point ->
                val activeSessionId = _ui.value.activeSessionId ?: return@collectLatest
                if (point.sessionId != activeSessionId) return@collectLatest

                _ui.update { state ->
                    val updatedTrail = (state.remoteTrailsByUser[point.userId].orEmpty() + point)
                        .sortedBy { it.timestamp }
                        .distinct()
                    state.copy(
                        remoteTrailsByUser = state.remoteTrailsByUser + (point.userId to updatedTrail),
                        members = mergeMemberIds(
                            existing = state.members,
                            trailUserIds = updatedTrail.map { it.userId }.toSet() + state.remoteTrailsByUser.keys,
                            presentUserIds = state.presentUserIds,
                            currentUserId = state.userId
                        ),
                        errorMessage = null
                    )
                }
            }
        }
        viewModelScope.launch {
            realtimeRepository.presenceByUser.collectLatest { presences ->
                _ui.update { state ->
                    state.copy(
                        presentUserIds = presences.keys,
                        members = mergeMemberIds(
                            existing = state.members,
                            trailUserIds = state.remoteTrailsByUser.keys,
                            presentUserIds = presences.keys,
                            currentUserId = state.userId
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            realtimeRepository.connectionState.collectLatest { connectionState ->
                _ui.update {
                    it.copy(
                        isRealtimeConnected = connectionState == RealtimeConnectionState.CONNECTED,
                        realtimeConnectionState = connectionState
                    )
                }
            }
        }
        viewModelScope.launch {
            realtimeRepository.syncStatus.collectLatest { syncStatus ->
                _ui.update { state ->
                    if (syncStatus.sessionId != null && state.activeSessionId != null && syncStatus.sessionId != state.activeSessionId) {
                        state
                    } else {
                        state.copy(
                            pendingSyncCount = syncStatus.pendingCount,
                            isSyncing = syncStatus.isSyncing,
                            lastSyncedAtMs = syncStatus.lastSyncedAtMs,
                            syncErrorMessage = syncStatus.lastErrorMessage
                        )
                    }
                }
            }
        }
    }

    fun initializeAuthState() {
        viewModelScope.launch {
            val remember = authPreferencesStore.isRememberMeEnabled()
            if (!remember) {
                activeSessionStore.clear()
                _ui.update {
                    it.copy(
                        rememberMe = false,
                        isAuthenticated = false,
                        userId = "local-user",
                        pendingResumeSessionId = null
                    )
                }
                return@launch
            }

            val userId = realtimeRepository.currentUserIdOrNull()
            if (userId != null) {
                val profile = runCatching { realtimeRepository.fetchUserProfiles(setOf(userId))[userId] }.getOrNull()
                val resumeCandidate = resolveResumeCandidate(remember, userId, storedSessionCandidate)
                if (storedSessionCandidate != null && resumeCandidate == null) {
                    activeSessionStore.clear()
                }
                _ui.update {
                    it.copy(
                        rememberMe = true,
                        isAuthenticated = true,
                        userId = userId,
                        pendingResumeSessionId = resumeCandidate?.sessionId,
                        userProfiles = profile?.let { p -> it.userProfiles + (userId to p) } ?: it.userProfiles,
                        errorMessage = null
                    )
                }
            } else {
                activeSessionStore.clear()
                _ui.update {
                    it.copy(
                        pendingResumeSessionId = null,
                        isAuthenticated = false,
                        userId = "local-user"
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
                    val ownSessions = realtimeRepository.listOwnSessions()
                    val location = withTimeoutOrNull(1_000L) { trackingRepository.currentLocationLatLon() }
                    val nearbyPublicSessions = if (location == null) {
                        emptyList()
                    } else {
                        realtimeRepository.listNearbyPublicSessions(
                            latitude = location.first,
                            longitude = location.second,
                            radiusMeters = 20_000.0,
                            excludeUserId = realtimeRepository.requireCurrentUserId()
                        )
                    }
                    ownSessions to nearbyPublicSessions
                }.onSuccess { (ownSessions, nearbyPublicSessions) ->
                    _ui.update {
                        it.copy(
                            ownSessions = ownSessions,
                            nearbyPublicSessions = nearbyPublicSessions,
                            errorMessage = null
                        )
                    }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(errorMessage = toUserMessage(error, "Could not load sessions."))
                    }
                }
            }
        }
    }

    fun createSessionAndJoin(sessionName: String, isPublic: Boolean) {
        val trimmedName = sessionName.trim()
        if (trimmedName.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Session name cannot be empty.") }
            return
        }

        _ui.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            withLoadingSuspend("Creating session...") {
                try {
                    val location = if (isPublic) {
                        withTimeoutOrNull(1_500L) { trackingRepository.currentLocationLatLon() }
                    } else {
                        null
                    }
                    val createdSession = realtimeRepository.createSession(
                        name = trimmedName,
                        isPublic = isPublic,
                        latitude = location?.first,
                        longitude = location?.second
                    )
                    joinSessionInternal(createdSession.id)
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
        val sessionId = _ui.value.activeSessionId ?: return
        if (!_ui.value.isTrackingActive) return

        viewModelScope.launch {
            withLoadingSuspend("Stopping tracking...") {
                trackingCollectJob?.cancel()
                trackingCollectJob = null
                realtimeRepository.syncPending(sessionId)
                realtimeRepository.updatePresence(isTracking = false)
                trackingRepository.stopTracking()
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

        val userId = realtimeRepository.requireCurrentUserId()
        val resolvedSessionId = realtimeRepository.joinSession(sessionId)
        val sessionJoinResult = coroutineScope {
            val membersDeferred = async {
                runCatching { realtimeRepository.fetchSessionMembers(resolvedSessionId) }
                    .getOrDefault(listOf(userId))
            }
            val pointsDeferred = async { trackingRepository.loadSessionPoints(resolvedSessionId) }
            val liftLabelsDeferred = async { trackingRepository.getLiftLabels(resolvedSessionId) }
            val connectDeferred = async {
                runCatching { realtimeRepository.connectRealtime(resolvedSessionId, userId, isTracking = false) }
                    .exceptionOrNull()
            }

            val triple = Triple(
                membersDeferred.await(),
                pointsDeferred.await(),
                liftLabelsDeferred.await()
            )
            triple to connectDeferred.await()
        }
        val (data, realtimeError) = sessionJoinResult
        val (members, historicalLocalPoints, liftLabels) = data

        val stats = statsEngine.compute(historicalLocalPoints)
        val profiles = realtimeRepository.fetchUserProfiles((members + userId).toSet())
        activeSessionStore.save(ActiveSessionState(sessionId = resolvedSessionId, userId = userId))
        realtimeRepository.refreshPendingSyncCount(resolvedSessionId)
        _ui.update {
            it.copy(
                activeSessionId = resolvedSessionId,
                userId = userId,
                members = mergeMemberIds(
                    existing = members,
                    trailUserIds = historicalLocalPoints.map { point -> point.userId }.toSet(),
                    presentUserIds = setOf(userId),
                    currentUserId = userId
                ),
                presentUserIds = setOf(userId),
                userProfiles = profiles,
                points = historicalLocalPoints,
                currentSegment = historicalLocalPoints.lastOrNull()?.segmentType ?: SegmentType.UNKNOWN,
                stats = stats,
                remoteTrailsByUser = emptyMap(),
                liftLabels = liftLabels,
                rawToPhysicalLiftId = stats.lifts.associate { lift -> lift.liftId to lift.physicalLiftId },
                isTrackingActive = false,
                pendingStartDistanceMeters = null,
                pendingResumeSessionId = null,
                errorMessage = realtimeError?.let {
                    toUserMessage(it, "Joined session, but live sync is reconnecting.")
                }
            )
        }
    }

    private fun startTrackingCollection(sessionId: String, userId: String) {
        if (_ui.value.isTrackingActive) return

        trackingCollectJob?.cancel()
        trackingCollectJob = viewModelScope.launch {
            realtimeRepository.updatePresence(isTracking = true)
            _ui.update { it.copy(isTrackingActive = true, pendingStartDistanceMeters = null) }
            var lastPointCount = 0
            trackingRepository.startTracking(sessionId, userId).collect { points ->
                val currentSegment = points.lastOrNull()?.segmentType ?: SegmentType.UNKNOWN
                val stats = statsEngine.compute(points)
                val delta = (points.size - lastPointCount).coerceAtLeast(0)
                lastPointCount = points.size
                _ui.update {
                    it.copy(
                        points = points,
                        currentSegment = currentSegment,
                        stats = stats,
                        rawToPhysicalLiftId = stats.lifts.associate { lift -> lift.liftId to lift.physicalLiftId },
                        pendingSyncCount = (it.pendingSyncCount + delta).coerceAtLeast(0)
                    )
                }
                if (points.size % 5 == 0 || points.size == 1) {
                    realtimeRepository.enqueueAndSync(sessionId)
                }
            }
        }
    }

    fun refreshLiveSnapshot() {
        val sessionId = _ui.value.activeSessionId ?: return
        viewModelScope.launch {
            withLoadingSuspend("Loading live view...") {
                fetchLiveSnapshot(sessionId)
            }
        }
    }

    fun refreshLiveSnapshotSilently() {
        val sessionId = _ui.value.activeSessionId ?: return
        viewModelScope.launch {
            fetchLiveSnapshot(sessionId)
        }
    }

    private suspend fun fetchLiveSnapshot(sessionId: String) {
        runCatching {
            realtimeRepository.fetchSessionTrails(sessionId)
        }.onSuccess { trails ->
            val userIds = trails.keys + _ui.value.userId + _ui.value.members + _ui.value.presentUserIds
            val profiles = runCatching { realtimeRepository.fetchUserProfiles(userIds.toSet()) }
                .getOrElse { _ui.value.userProfiles }
            _ui.update {
                it.copy(
                    remoteTrailsByUser = trails,
                    members = mergeMemberIds(
                        existing = it.members,
                        trailUserIds = trails.keys,
                        presentUserIds = it.presentUserIds,
                        currentUserId = it.userId
                    ),
                    userProfiles = profiles,
                    errorMessage = null,
                    pendingResumeSessionId = null
                )
            }
        }.onFailure { error ->
            _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not load live data.")) }
        }
    }

    fun leaveSession() {
        val sessionId = _ui.value.activeSessionId ?: return
        viewModelScope.launch {
            withLoadingSuspend("Leaving session...") {
                try {
                    trackingCollectJob?.cancel()
                    trackingCollectJob = null
                    realtimeRepository.syncPending(sessionId)
                    trackingRepository.stopTracking()
                    realtimeRepository.disconnectRealtime(sessionId)
                } finally {
                    runCatching {
                        trackingRepository.exportSessionToJson(sessionId)
                    }
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
                            presentUserIds = emptySet(),
                            isTrackingActive = false,
                            pendingStartDistanceMeters = null,
                            showGpsWaitingDialog = false,
                            pendingSyncCount = 0,
                            isSyncing = false,
                            syncErrorMessage = null,
                            realtimeConnectionState = RealtimeConnectionState.DISCONNECTED
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
                val userId = realtimeRepository.requireCurrentUserId()
                val profile = runCatching { realtimeRepository.fetchUserProfiles(setOf(userId))[userId] }.getOrNull()
                val resumeCandidate = resolveResumeCandidate(
                    rememberMe = _ui.value.rememberMe,
                    currentUserId = userId,
                    storedState = storedSessionCandidate
                )
                _ui.update {
                    it.copy(
                        userId = userId,
                        isAuthenticated = true,
                        pendingResumeSessionId = resumeCandidate?.sessionId,
                        userProfiles = profile?.let { p -> it.userProfiles + (userId to p) } ?: it.userProfiles,
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
                val userId = realtimeRepository.requireCurrentUserId()
                val profile = runCatching { realtimeRepository.fetchUserProfiles(setOf(userId))[userId] }.getOrNull()
                val resumeCandidate = resolveResumeCandidate(
                    rememberMe = _ui.value.rememberMe,
                    currentUserId = userId,
                    storedState = storedSessionCandidate
                )
                _ui.update {
                    it.copy(
                        userId = userId,
                        isAuthenticated = true,
                        pendingResumeSessionId = resumeCandidate?.sessionId,
                        userProfiles = if (profile != null) {
                            it.userProfiles + (userId to profile)
                        } else {
                            it.userProfiles + (userId to UserProfile(id = userId, alias = trimmedAlias, color = "#60A5FA"))
                        },
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
                val resumeCandidate = resolveResumeCandidate(
                    rememberMe = _ui.value.rememberMe,
                    currentUserId = userId,
                    storedState = storedSessionCandidate
                )
                _ui.update {
                    it.copy(
                        userId = userId,
                        isAuthenticated = true,
                        pendingResumeSessionId = resumeCandidate?.sessionId,
                        errorMessage = null
                    )
                }
                viewModelScope.launch {
                    val profile = runCatching { realtimeRepository.fetchUserProfiles(setOf(userId))[userId] }.getOrNull()
                    if (profile != null) {
                        _ui.update { it.copy(userProfiles = it.userProfiles + (userId to profile)) }
                    }
                }
            }
    }

    fun logout() {
        viewModelScope.launch {
            withLoadingSuspend("Signing out...") {
                val sessionId = _ui.value.activeSessionId
                trackingCollectJob?.cancel()
                trackingCollectJob = null
                trackingRepository.stopTracking()
                if (sessionId != null) {
                    realtimeRepository.syncPending(sessionId)
                    runCatching { realtimeRepository.disconnectRealtime(sessionId) }
                }
                activeSessionStore.clear()
                runCatching { realtimeRepository.logout() }
                _ui.update {
                    it.copy(
                        activeSessionId = null,
                        userId = "local-user",
                        points = emptyList(),
                        currentSegment = SegmentType.UNKNOWN,
                        members = emptyList(),
                        ownSessions = emptyList(),
                        nearbyPublicSessions = emptyList(),
                        remoteTrailsByUser = emptyMap(),
                        presentUserIds = emptySet(),
                        userProfiles = emptyMap(),
                        liftLabels = emptyMap(),
                        rawToPhysicalLiftId = emptyMap(),
                        isTrackingActive = false,
                        pendingStartDistanceMeters = null,
                        gpsReadyToStart = true,
                        gpsHorizontalAccuracyM = null,
                        gpsVerticalAccuracyM = null,
                        showGpsWaitingDialog = false,
                        mergePreview = null,
                        pendingResumeSessionId = null,
                        pendingSyncCount = 0,
                        isSyncing = false,
                        lastSyncedAtMs = null,
                        syncErrorMessage = null,
                        isAuthenticated = false,
                        isRealtimeConnected = false,
                        realtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
                        errorMessage = null
                    )
                }
            }
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
                    realtimeRepository.listOwnSessions()
                }.onSuccess { ownSessions ->
                    _ui.update { it.copy(ownSessions = ownSessions, errorMessage = null) }
                }.onFailure { error ->
                    _ui.update { it.copy(errorMessage = toUserMessage(error, "Could not rename session.")) }
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

    fun resumeStoredSession() {
        val sessionId = _ui.value.pendingResumeSessionId ?: return
        joinExistingSession(sessionId)
    }

    fun dismissPendingResumeSession() {
        activeSessionStore.clear()
        _ui.update { it.copy(pendingResumeSessionId = null) }
    }

    fun ensureRealtimeConnection() {
        val state = _ui.value
        val sessionId = state.activeSessionId ?: return
        if (!state.isAuthenticated) return
        viewModelScope.launch {
            runCatching {
                realtimeRepository.ensureRealtimeConnected(
                    sessionId = sessionId,
                    userId = state.userId,
                    isTracking = state.isTrackingActive
                )
            }.onFailure { error ->
                val message = error.message?.trim()
                _ui.update {
                    it.copy(
                        realtimeConnectionState = RealtimeConnectionState.RECONNECTING,
                        syncErrorMessage = if (message.isNullOrBlank()) {
                            "Live sync is reconnecting."
                        } else {
                            message
                        },
                        errorMessage = null
                    )
                }
            }
        }
    }

    private fun toUserMessage(error: Throwable, fallback: String): String {
        val rawMessage = error.message?.trim().orEmpty()
        val message = error.message?.lowercase().orEmpty()
        return when {
            "email not confirmed" in message -> "Email is not confirmed yet."
            "invalid login credentials" in message -> "Invalid email or password."
            "session id must be a valid uuid" in message -> "Invalid session ID."
            "location" in message && "permission" in message -> "Location permission is missing."
            "realtime subscribe timed out" in message -> rawMessage
            "realtime subscribe failed" in message -> rawMessage
            "network" in message || "timeout" in message -> "Network error. Check your connection and try again."
            else -> fallback
        }
    }

    override fun onCleared() {
        trackingCollectJob?.cancel()
        livePositionCollectJob?.cancel()
        super.onCleared()
    }
}

internal fun resolveResumeCandidate(
    rememberMe: Boolean,
    currentUserId: String?,
    storedState: ActiveSessionState?
): ActiveSessionState? {
    if (!rememberMe) return null
    if (currentUserId == null) return null
    val stored = storedState ?: return null
    return stored.takeIf { it.userId == currentUserId }
}

internal fun mergeMemberIds(
    existing: List<String>,
    trailUserIds: Set<String>,
    presentUserIds: Set<String>,
    currentUserId: String?
): List<String> {
    return buildSet {
        addAll(existing)
        addAll(trailUserIds)
        addAll(presentUserIds)
        currentUserId?.let(::add)
    }.sorted()
}
