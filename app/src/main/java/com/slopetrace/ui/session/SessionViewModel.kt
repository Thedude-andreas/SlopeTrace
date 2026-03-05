package com.slopetrace.ui.session

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slopetrace.data.model.PositionStreamItem
import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.Session
import com.slopetrace.data.model.TrackingPoint
import com.slopetrace.data.remote.RealtimeRepository
import com.slopetrace.domain.stats.SessionStats
import com.slopetrace.domain.stats.StatsEngine
import com.slopetrace.ui.login.AuthPreferencesStore
import com.slopetrace.tracking.ActiveSessionState
import com.slopetrace.tracking.ActiveSessionStore
import com.slopetrace.tracking.TrackingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        downhillTimeSeconds = 0L,
        otherTimeSeconds = 0L,
        maxSessionSpeedMps = 0.0,
        runs = emptyList(),
        lifts = emptyList(),
        events = emptyList()
    ),
    val members: List<String> = emptyList(),
    val availableSessions: List<Session> = emptyList(),
    val remoteTrailsByUser: Map<String, List<PositionStreamItem>> = emptyMap(),
    val liftLabels: Map<String, String> = emptyMap(),
    val isTrackingActive: Boolean = false,
    val pendingStartDistanceMeters: Double? = null,
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
            withLoadingSuspend("Hämtar sessioner...") {
                runCatching {
                    realtimeRepository.listSessions()
                }.onSuccess { sessions ->
                    _ui.update { it.copy(availableSessions = sessions) }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(errorMessage = toUserMessage(error, "Kunde inte hämta sessioner. (${error.message ?: "okänt fel"})"))
                    }
                }
            }
        }
    }

    fun createSessionAndJoin(sessionName: String) {
        val trimmedName = sessionName.trim()
        if (trimmedName.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Sessionsnamn kan inte vara tomt.") }
            return
        }

        _ui.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            withLoadingSuspend("Skapar session...") {
                try {
                    val createdSession = realtimeRepository.createSession(trimmedName)
                    joinSessionInternal(createdSession.id)
                    refreshAvailableSessions()
                } catch (e: Exception) {
                    _ui.update {
                        it.copy(
                            isRealtimeConnected = false,
                            errorMessage = toUserMessage(e, "Kunde inte skapa session. (${e.message ?: "okänt fel"})")
                        )
                    }
                }
            }
        }
    }

    fun joinExistingSession(sessionId: String) {
        val requestedSessionId = sessionId.trim()
        if (requestedSessionId.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Välj en session att gå med i.") }
            return
        }

        _ui.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            withLoadingSuspend("Ansluter till session...") {
                try {
                    joinSessionInternal(requestedSessionId)
                    refreshAvailableSessions()
                } catch (e: Exception) {
                    _ui.update {
                        it.copy(
                            isRealtimeConnected = false,
                            errorMessage = toUserMessage(e, "Kunde inte gå med i sessionen. (${e.message ?: "okänt fel"})")
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
            withLoadingSuspend("Förbereder spårning...") {
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
            withLoadingSuspend("Stoppar spårning...") {
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

        _ui.update {
            it.copy(
                activeSessionId = resolvedSessionId,
                userId = userId,
                members = members,
                isRealtimeConnected = true,
                points = historicalLocalPoints,
                currentSegment = historicalLocalPoints.lastOrNull()?.segmentType ?: SegmentType.UNKNOWN,
                stats = statsEngine.compute(historicalLocalPoints),
                remoteTrailsByUser = emptyMap(),
                liftLabels = liftLabels,
                isTrackingActive = false,
                pendingStartDistanceMeters = null,
                lastExportPath = null
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
                _ui.update {
                    it.copy(
                        points = points,
                        currentSegment = currentSegment,
                        stats = statsEngine.compute(points)
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
                    _ui.update {
                        it.copy(
                            remoteTrailsByUser = trails,
                            members = trails.keys.sorted()
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
            withLoadingSuspend("Lämnar session...") {
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
                            liftLabels = emptyMap(),
                            members = emptyList(),
                            isTrackingActive = false,
                            pendingStartDistanceMeters = null,
                            lastExportPath = exportPath
                        )
                    }
                }
            }
        }
    }

    suspend fun login(email: String, password: String): Boolean {
        return withLoadingSuspend("Loggar in...") {
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
                    it.copy(errorMessage = toUserMessage(e, "Inloggning misslyckades."))
                }
                false
            }
        }
    }

    suspend fun signUp(email: String, password: String): Boolean {
        return withLoadingSuspend("Skapar konto...") {
            try {
                realtimeRepository.signUp(email, password)
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
                    it.copy(errorMessage = toUserMessage(e, "Registrering misslyckades."))
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
            withLoadingSuspend("Sparar liftnamn...") {
                runCatching {
                    trackingRepository.setLiftLabel(sessionId, liftId, trimmed)
                    trackingRepository.getLiftLabels(sessionId)
                }.onSuccess { labels ->
                    _ui.update { it.copy(liftLabels = labels) }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(errorMessage = toUserMessage(error, "Kunde inte spara liftnamn."))
                    }
                }
            }
        }
    }

    private fun toUserMessage(error: Throwable, fallback: String): String {
        val message = error.message?.lowercase().orEmpty()
        return when {
            "email not confirmed" in message -> "E-postadressen är inte bekräftad."
            "invalid login credentials" in message -> "Fel e-post eller lösenord."
            "session id måste vara ett giltigt uuid" in message -> "Session-ID är ogiltigt."
            "location" in message && "permission" in message -> "Platstillstånd saknas. Tillåt plats i appen."
            "network" in message || "timeout" in message -> "Nätverksfel. Kontrollera uppkoppling och försök igen."
            else -> fallback
        }
    }
}
