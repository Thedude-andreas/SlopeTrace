package com.slopetrace.ui.session

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.TrackingPoint
import com.slopetrace.data.remote.RealtimeRepository
import com.slopetrace.domain.stats.SessionStats
import com.slopetrace.domain.stats.StatsEngine
import com.slopetrace.tracking.TrackingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionUiState(
    val activeSessionId: String? = null,
    val userId: String = "local-user",
    val points: List<TrackingPoint> = emptyList(),
    val currentSegment: SegmentType = SegmentType.UNKNOWN,
    val stats: SessionStats = SessionStats(0, 0.0, 0L, 0L, emptyList()),
    val members: List<String> = emptyList(),
    val isRealtimeConnected: Boolean = false,
    val errorMessage: String? = null
)

class SessionViewModel(
    private val trackingRepository: TrackingRepository,
    private val realtimeRepository: RealtimeRepository,
    private val statsEngine: StatsEngine
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    fun createOrJoinSession(sessionId: String) {
        val requestedSessionId = sessionId.trim()
        if (requestedSessionId.isEmpty()) {
            _ui.update { it.copy(errorMessage = "Session ID kan inte vara tomt.") }
            return
        }

        _ui.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            try {
                val userId = realtimeRepository.requireCurrentUserId()
                val resolvedSessionId = realtimeRepository.ensureSessionMembership(requestedSessionId)
                val members = realtimeRepository.fetchSessionMembers(resolvedSessionId)
                realtimeRepository.connectRealtime(resolvedSessionId)

                _ui.update {
                    it.copy(
                        activeSessionId = resolvedSessionId,
                        userId = userId,
                        members = members,
                        isRealtimeConnected = true
                    )
                }

                trackingRepository.startTracking(resolvedSessionId, userId).collect { points ->
                    val currentSegment = points.lastOrNull()?.segmentType ?: SegmentType.UNKNOWN
                    _ui.update {
                        it.copy(
                            points = points,
                            currentSegment = currentSegment,
                            stats = statsEngine.compute(points)
                        )
                    }
                    if (points.size % 5 == 0) {
                        realtimeRepository.enqueueAndSync(resolvedSessionId)
                    }
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        isRealtimeConnected = false,
                        errorMessage = toUserMessage(e, "Kunde inte ansluta till Supabase.")
                    )
                }
            }
        }
    }

    fun leaveSession() {
        val sessionId = _ui.value.activeSessionId ?: return
        viewModelScope.launch {
            try {
                trackingRepository.stopTracking()
                realtimeRepository.disconnectRealtime(sessionId)
            } finally {
                _ui.update {
                    it.copy(
                        activeSessionId = null,
                        isRealtimeConnected = false,
                        points = emptyList(),
                        currentSegment = SegmentType.UNKNOWN
                    )
                }
            }
        }
    }

    suspend fun login(email: String, password: String): Boolean {
        return try {
            realtimeRepository.login(email, password)
            _ui.update {
                it.copy(
                    userId = realtimeRepository.requireCurrentUserId(),
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

    suspend fun signUp(email: String, password: String): Boolean {
        return try {
            realtimeRepository.signUp(email, password)
            realtimeRepository.login(email, password)
            _ui.update {
                it.copy(
                    userId = realtimeRepository.requireCurrentUserId(),
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

    fun handleAuthIntent(intent: Intent?) {
        if (!realtimeRepository.handleAuthIntent(intent)) return
        runCatching { realtimeRepository.requireCurrentUserId() }
            .onSuccess { userId ->
                _ui.update { it.copy(userId = userId, errorMessage = null) }
            }
    }

    private fun toUserMessage(error: Throwable, fallback: String): String {
        val message = error.message?.lowercase().orEmpty()
        return when {
            "email not confirmed" in message -> "E-postadressen är inte bekräftad."
            "invalid login credentials" in message -> "Fel e-post eller lösenord."
            "session id måste vara ett giltigt uuid" in message -> "Session ID måste vara ett giltigt UUID."
            "location" in message && "permission" in message -> "Platstillstånd saknas. Tillåt plats i appen."
            "network" in message || "timeout" in message -> "Nätverksfel. Kontrollera uppkoppling och försök igen."
            else -> fallback
        }
    }
}
