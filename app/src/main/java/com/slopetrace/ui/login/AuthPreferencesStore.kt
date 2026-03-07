package com.slopetrace.ui.login

import android.content.Context
import androidx.core.content.edit

class AuthPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isRememberMeEnabled(): Boolean = prefs.getBoolean(KEY_REMEMBER_ME, true)

    fun setRememberMeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REMEMBER_ME, enabled) }
    }

    companion object {
        private const val PREF_NAME = "auth_prefs"
        private const val KEY_REMEMBER_ME = "remember_me"
    }
}
