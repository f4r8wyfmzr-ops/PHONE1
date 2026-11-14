package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.phonePayDataStore: DataStore<Preferences> by preferencesDataStore(name = "phonepay_prefs")

class PreferencesStore(private val context: Context) {
    private val languageKey = stringPreferencesKey("preferred_language")
    private val backendUrlKey = stringPreferencesKey("custom_psp_backend_url")

    val preferredLanguage: Flow<String> = context.phonePayDataStore.data.map { prefs ->
        prefs[languageKey]?.takeIf { it == "hi" || it == "en" } ?: "en"
    }

    val customBackendUrl: Flow<String> = context.phonePayDataStore.data.map { prefs ->
        prefs[backendUrlKey].orEmpty()
    }

    suspend fun setLanguage(languageCode: String) {
        val normalized = if (languageCode == "hi") "hi" else "en"
        context.phonePayDataStore.edit { prefs ->
            prefs[languageKey] = normalized
        }
    }

    suspend fun setCustomBackendUrl(url: String) {
        val trimmed = url.trim()
        context.phonePayDataStore.edit { prefs ->
            prefs[backendUrlKey] = trimmed
        }
    }
}
