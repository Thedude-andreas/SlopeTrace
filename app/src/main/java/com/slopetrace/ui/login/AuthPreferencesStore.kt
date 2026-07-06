package com.slopetrace.ui.login

import android.content.Context
import androidx.core.content.edit

class AuthPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isRememberMeEnabled(): Boolean = prefs.getBoolean(KEY_REMEMBER_ME, true)

    fun setRememberMeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REMEMBER_ME, enabled) }
    }

    fun cacheAlias(userId: String, alias: String) {
        prefs.edit {
            putString(KEY_LAST_ALIAS_USER_ID, userId)
            putString(KEY_LAST_ALIAS, alias.trim())
        }
    }

    fun cachedAliasFor(userId: String): String? {
        val cachedUserId = prefs.getString(KEY_LAST_ALIAS_USER_ID, null) ?: return null
        if (cachedUserId != userId) return null
        return prefs.getString(KEY_LAST_ALIAS, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val PREF_NAME = "auth_prefs"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_LAST_ALIAS_USER_ID = "last_alias_user_id"
        private const val KEY_LAST_ALIAS = "last_alias"
    }
}
