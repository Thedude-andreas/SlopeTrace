package com.slopetrace.data.remote

import android.content.Intent
import com.slopetrace.BuildConfig
import com.slopetrace.data.local.TrackingDao
import com.slopetrace.data.model.PositionStreamItem
import com.slopetrace.data.model.Session
import com.slopetrace.data.model.SegmentType
import com.slopetrace.data.model.UserProfile
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.handleDeeplinks
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
            ?: error("Not signed in. Sign in before joining a session.")
    }

    fun currentUserIdOrNull(): String? {
        return client.auth.currentUserOrNull()?.id
    }

    suspend fun listSessions(): List<Session> {
        val sessions = client.postgrest["sessions"]
            .select()
            .decodeList<JsonObject>()

        return sessions.mapNotNull { row ->
            row.toSessionOrNull()
        }.sortedByDescending { it.startTime }
    }

    suspend fun createSession(name: String, isPublic: Boolean, latitude: Double?, longitude: Double?): Session {
        val sessionName = name.trim().ifEmpty { "Slope session" }
        val id = UUID.randomUUID().toString()
        val now = Clock.System.now()
        val userId = requireCurrentUserId()

        client.postgrest["sessions"].insert(
            value = buildJsonObject {
                put("id", id)
                put("name", sessionName)
                put("start_time", now.toString())
                put("created_by", userId)
                put("is_public", isPublic)
                if (latitude != null) put("latitude", latitude)
                if (longitude != null) put("longitude", longitude)
            }
        )

        return Session(
            id = id,
            name = sessionName,
            startTime = now,
            endTime = null,
            createdBy = userId,
            isPublic = isPublic,
            latitude = latitude,
            longitude = longitude
        )
    }

    suspend fun listOwnSessions(): List<Session> {
        val currentUserId = requireCurrentUserId()
        val rows = client.postgrest["sessions"]
            .select {
                filter {
                    eq("created_by", currentUserId)
                }
            }
            .decodeList<JsonObject>()

        return rows.mapNotNull { it.toSessionOrNull() }
            .sortedByDescending { it.startTime }
    }

    suspend fun listNearbyPublicSessions(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        excludeUserId: String
    ): List<Session> {
        val rpcRows = runCatching {
            client.postgrest.rpc(
                function = "list_nearby_public_sessions",
                parameters = buildJsonObject {
                    put("p_lat", latitude)
                    put("p_lon", longitude)
                    put("p_radius_m", radiusMeters)
                }
            ).decodeList<JsonObject>()
        }.getOrNull()

        if (rpcRows != null) {
            return rpcRows.mapNotNull { row ->
                val base = row.toSessionOrNull() ?: return@mapNotNull null
                val distance = row["distance_m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                base.copy(distanceMeters = distance)
            }.filter { it.createdBy != excludeUserId }
                .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        }

        val sessions = client.postgrest["sessions"]
            .select {
                filter {
                    eq("is_public", true)
                    neq("created_by", excludeUserId)
                }
            }
            .decodeList<JsonObject>()
            .mapNotNull { row -> row.toSessionOrNull() }

        return sessions.mapNotNull { session ->
            val lat = session.latitude ?: return@mapNotNull null
            val lon = session.longitude ?: return@mapNotNull null
            val distance = haversineMeters(latitude, longitude, lat, lon)
            if (distance <= radiusMeters) session.copy(distanceMeters = distance) else null
        }.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    suspend fun createMergedSessionFromSources(sourceSessionIds: List<String>, mergedName: String): Session {
        val normalizedIds = sourceSessionIds.map(::normalizeSessionId).distinct()
        require(normalizedIds.size >= 2) { "At least two source sessions are required." }
        val finalName = mergedName.trim().ifEmpty { "Merged session" }

        val result = client.postgrest.rpc(
            function = "create_merged_session",
            parameters = buildJsonObject {
                put("source_session_ids", buildJsonArray {
                    normalizedIds.forEach { add(JsonPrimitive(it)) }
                })
                put("merged_name", finalName)
            }
        ).decodeSingle<JsonObject>()

        val newId = result["session_id"]?.jsonPrimitive?.contentOrNull
            ?: error("Merge RPC did not return a session id.")

        val sessions = listSessions()
        return sessions.firstOrNull { it.id == newId }
            ?: Session(id = newId, name = finalName, startTime = Clock.System.now(), endTime = null)
    }

    suspend fun renameSession(sessionId: String, newName: String) {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Session name cannot be empty." }
        client.postgrest.rpc(
            function = "rename_session_for_member",
            parameters = buildJsonObject {
                put("session_id", normalizedSessionId)
                put("new_name", trimmed)
            }
        )
    }

    suspend fun deleteSession(sessionId: String) {
        deleteSessions(listOf(sessionId))
    }

    suspend fun deleteSessions(sessionIds: List<String>) {
        val normalizedIds = sessionIds.map(::normalizeSessionId).distinct()
        if (normalizedIds.isEmpty()) return

        runCatching {
            client.postgrest.rpc(
                function = "delete_sessions_for_member",
                parameters = buildJsonObject {
                    put("session_ids", buildJsonArray {
                        normalizedIds.forEach { add(JsonPrimitive(it)) }
                    })
                }
            )
            return
        }

        normalizedIds.forEach { normalizedSessionId ->
            deleteSingleSessionFallback(normalizedSessionId)
        }
    }

    private suspend fun deleteSingleSessionFallback(normalizedSessionId: String) {
        val currentUser = requireCurrentUserId()

        client.postgrest["session_members"].delete {
            filter {
                eq("session_id", normalizedSessionId)
                eq("user_id", currentUser)
            }
        }

        runCatching {
            client.postgrest["position_stream"].delete {
                filter {
                    eq("session_id", normalizedSessionId)
                    eq("user_id", currentUser)
                }
            }
        }

        runCatching {
            client.postgrest["sessions"].delete {
                filter {
                    eq("id", normalizedSessionId)
                }
            }
        }
    }

    suspend fun upsertCurrentUserProfile(alias: String) {
        val userId = requireCurrentUserId()
        val safeAlias = alias.trim().ifEmpty { "Rider" }
        client.postgrest["users_profile"].upsert(
            value = buildJsonObject {
                put("id", userId)
                put("name", safeAlias)
                put("color", defaultColorForUser(userId))
            },
            onConflict = "id",
            ignoreDuplicates = false
        )
    }

    suspend fun fetchUserProfiles(userIds: Set<String>): Map<String, UserProfile> {
        if (userIds.isEmpty()) return emptyMap()
        val rows = client.postgrest["users_profile"].select().decodeList<JsonObject>()
        return rows.mapNotNull { row ->
            val id = row["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (id !in userIds) return@mapNotNull null
            val alias = row["name"]?.jsonPrimitive?.contentOrNull ?: "Rider"
            val color = row["color"]?.jsonPrimitive?.contentOrNull ?: defaultColorForUser(id)
            id to UserProfile(id = id, alias = alias, color = color)
        }.toMap()
    }

    suspend fun joinSession(sessionId: String): String {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val userId = requireCurrentUserId()

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

    suspend fun fetchSessionTrails(sessionId: String): Map<String, List<PositionStreamItem>> {
        val rows = client.postgrest["position_stream"]
            .select {
                filter {
                    eq("session_id", sessionId)
                }
            }
            .decodeList<JsonObject>()

        val points = rows.mapNotNull { row ->
            val userId = row["user_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawSessionId = row["session_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawTimestamp = row["timestamp"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val segmentRaw = row["segment_type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val x = row["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val y = row["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val z = row["z"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val speed = row["speed"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

            runCatching {
                PositionStreamItem(
                    userId = userId,
                    sessionId = rawSessionId,
                    timestamp = Instant.parse(rawTimestamp),
                    x = x,
                    y = y,
                    z = z,
                    speed = speed,
                    segmentType = SegmentType.valueOf(segmentRaw.uppercase())
                )
            }.getOrNull()
        }

        return points
            .sortedBy { it.timestamp }
            .groupBy { it.userId }
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
            error("Session ID must be a valid UUID, for example 123e4567-e89b-12d3-a456-426614174000")
        }
    }

    private fun JsonObject.toSessionOrNull(): Session? {
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = this["name"]?.jsonPrimitive?.contentOrNull ?: "Session $id"
        val startRaw = this["start_time"]?.jsonPrimitive?.contentOrNull ?: return null
        val endRaw = this["end_time"]?.jsonPrimitive?.contentOrNull
        val createdBy = this["created_by"]?.jsonPrimitive?.contentOrNull
        val isPublic = this["is_public"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val latitude = this["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val longitude = this["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

        return runCatching {
            Session(
                id = id,
                name = name,
                startTime = Instant.parse(startRaw),
                endTime = endRaw?.let { Instant.parse(it) },
                createdBy = createdBy,
                isPublic = isPublic,
                latitude = latitude,
                longitude = longitude
            )
        }.getOrNull()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow2() +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow2()
        val c = 2 * asin(sqrt(a))
        return earthRadius * c
    }

    private fun Double.pow2(): Double = this * this

    private fun defaultColorForUser(userId: String): String {
        val palette = listOf("#60A5FA", "#F97316", "#22C55E", "#E879F9", "#FBBF24", "#14B8A6", "#FB7185")
        val idx = (userId.hashCode().toUInt().toInt() and Int.MAX_VALUE) % palette.size
        return palette[idx]
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
