package com.slopetrace.tracking

import android.content.Context
import androidx.core.content.edit

data class ActiveSessionState(
    val sessionId: String,
    val userId: String
)

class ActiveSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun save(state: ActiveSessionState) {
        prefs.edit {
            putString(KEY_SESSION_ID, state.sessionId)
            putString(KEY_USER_ID, state.userId)
        }
    }

    fun load(): ActiveSessionState? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return ActiveSessionState(sessionId = sessionId, userId = userId)
    }

    fun clear() {
        prefs.edit {
            remove(KEY_SESSION_ID)
            remove(KEY_USER_ID)
        }
    }

    companion object {
        private const val PREF_NAME = "tracking_state"
        private const val KEY_SESSION_ID = "active_session_id"
        private const val KEY_USER_ID = "active_user_id"
    }
}
