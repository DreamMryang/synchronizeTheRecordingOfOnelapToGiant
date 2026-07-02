package com.dreammryang.onelaptogiant.data.auth

import android.content.SharedPreferences

enum class Platform { ONELAP, GIANT }

class TokenStore(private val prefs: SharedPreferences) {
    private fun key(platform: Platform) = "token_${platform.name.lowercase()}"

    fun get(platform: Platform): String? = prefs.getString(key(platform), null)

    fun set(platform: Platform, token: String) {
        prefs.edit().putString(key(platform), token).apply()
    }

    fun clear(platform: Platform) {
        prefs.edit().remove(key(platform)).apply()
    }
}
