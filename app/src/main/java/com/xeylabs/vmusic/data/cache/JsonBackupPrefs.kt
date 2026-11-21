package com.xeylabs.vmusic.data.cache

import android.content.SharedPreferences
import org.json.JSONArray

object JsonBackupPrefs {

    fun readJsonArray(prefs: SharedPreferences, key: String): String? {
        val primary = prefs.getString(key, null) ?: return null
        if (isJsonArray(primary)) return primary
        val backup = prefs.getString(backupKey(key), null) ?: return primary
        if (!isJsonArray(backup)) return primary
        prefs.edit().putString(key, backup).apply()
        return backup
    }

    fun writeJsonArray(prefs: SharedPreferences, key: String, json: String) {
        val editor = prefs.edit()
        val current = prefs.getString(key, null)
        if (current != null && current != json && isJsonArray(current)) {
            editor.putString(backupKey(key), current)
        }
        editor.putString(key, json).apply()
    }

    private fun backupKey(key: String): String {
        return "$key.bak"
    }

    private fun isJsonArray(raw: String): Boolean {
        return try {
            JSONArray(raw)
            true
        } catch (_: Exception) {
            false
        }
    }
}
