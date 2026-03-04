package com.slopetrace.data.remote

import android.content.Intent
import com.slopetrace.BuildConfig
import com.slopetrace.data.local.TrackingDao
import com.slopetrace.data.model.PositionStreamItem
import com.slopetrace.data.model.SegmentType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.handleDeeplinks
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * MVP repository for Supabase auth + session scoped realtime sharing.
 * Inserts are persisted to Postgres and offline points are retried in batches.
 */
class RealtimeRepository(
    private val dao: TrackingDao,
    private val scope: CoroutineScope
) {
    private val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth) {
            scheme = "slopetrace"
            host = "auth"
        }
        install(Postgrest)
        install(Realtime)
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    suspend fun login(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    fun requireCurrentUserId(): String {
        return client.auth.currentUserOrNull()?.id
            ?: error("Inte inloggad. Logga in innan du går med i en session.")
    }

    suspend fun ensureSessionMembership(sessionId: String): String {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val userId = requireCurrentUserId()

        client.postgrest["sessions"].upsert(
            value = buildJsonObject {
                put("id", normalizedSessionId)
                put("name", "Slope session ${normalizedSessionId.take(8)}")
            },
            onConflict = "id",
            ignoreDuplicates = true
        )

        client.postgrest["session_members"].upsert(
            value = buildJsonObject {
                put("session_id", normalizedSessionId)
                put("user_id", userId)
                put("left_at", JsonNull)
            },
            onConflict = "session_id,user_id",
            ignoreDuplicates = true
        )

        return normalizedSessionId
    }

    suspend fun logout() {
        client.auth.signOut()
    }

    fun handleAuthIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching {
            client.handleDeeplinks(intent)
            true
        }.getOrDefault(false)
    }

    suspend fun fetchSessionMembers(sessionId: String): List<String> {
        val members = client.postgrest["session_members"]
            .select {
                filter {
                    eq("session_id", sessionId)
                }
            }
            .decodeList<JsonObject>()

        return members.mapNotNull { row ->
            row["user_id"]?.jsonPrimitive?.contentOrNull
        }.distinct()
    }

    suspend fun connectRealtime(sessionId: String) {
        client.realtime.connect()
        _connected.value = true

        // NOTE: for production, subscribe to session filtered postgres changes and map to PositionStreamItem.
        // This MVP keeps outbound sending and assumes inbound via a dedicated session channel.
        client.realtime.channel("session:$sessionId").subscribe()
    }

    suspend fun disconnectRealtime(sessionId: String) {
        client.realtime.channel("session:$sessionId").unsubscribe()
        _connected.value = false
    }

    fun enqueueAndSync(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            val pending = dao.pendingSync(sessionId = sessionId, limit = 500)
            if (pending.isEmpty()) return@launch

            val payload = pending.map {
                buildJsonObject {
                    put("user_id", it.userId)
                    put("session_id", it.sessionId)
                    put("timestamp", Instant.fromEpochMilliseconds(it.timestampMs).toString())
                    put("x", JsonPrimitive(it.xEastM))
                    put("y", JsonPrimitive(it.yNorthM))
                    put("z", JsonPrimitive(it.zUpM))
                    put("speed", JsonPrimitive(it.speedMps))
                    put("segment_type", it.segmentType.name.lowercase())
                }
            }

            try {
                client.postgrest["position_stream"].insert(payload)
                dao.markSynced(pending.map { it.id })
            } catch (_: Exception) {
                // Keep pending points for a later retry attempt.
            }
        }
    }

    private fun normalizeSessionId(sessionId: String): String {
        return try {
            UUID.fromString(sessionId.trim()).toString()
        } catch (_: IllegalArgumentException) {
            error("Session ID måste vara ett giltigt UUID, t.ex. 123e4567-e89b-12d3-a456-426614174000")
        }
    }

    fun mapRemotePosition(raw: Map<String, Any?>): PositionStreamItem? {
        return try {
            PositionStreamItem(
                userId = raw["user_id"] as String,
                sessionId = raw["session_id"] as String,
                timestamp = Clock.System.now(),
                x = (raw["x"] as Number).toDouble(),
                y = (raw["y"] as Number).toDouble(),
                z = (raw["z"] as Number).toDouble(),
                speed = (raw["speed"] as Number).toDouble(),
                segmentType = SegmentType.valueOf((raw["segment_type"] as String).uppercase())
            )
        } catch (_: Exception) {
            null
        }
    }
}
