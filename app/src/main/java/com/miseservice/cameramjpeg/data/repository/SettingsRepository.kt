package com.miseservice.cameramjpeg.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miseservice.cameramjpeg.domain.model.AdminSettings
import com.miseservice.cameramjpeg.domain.model.StreamQuality
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "camera_mjpeg_settings")

/**
 * Repository pour la gestion de la persistance des paramètres administrateur.
 * Utilise DataStore pour stocker et charger les préférences de streaming MJPEG.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val isStreaming = booleanPreferencesKey("is_streaming")
        val useFrontCamera = booleanPreferencesKey("use_front_camera")
        val keepScreenAwake = booleanPreferencesKey("keep_screen_awake")
        val port = intPreferencesKey("stream_port")
        val quality = stringPreferencesKey("stream_quality")
    }

    suspend fun load(): AdminSettings {
        val prefs = context.dataStore.data.first()
        return AdminSettings(
            isStreaming = prefs[Keys.isStreaming] ?: false,
            useFrontCamera = prefs[Keys.useFrontCamera] ?: false,
            keepScreenAwake = prefs[Keys.keepScreenAwake] ?: false,
            port = prefs[Keys.port] ?: 8080,
            quality = prefs[Keys.quality]
                ?.let { runCatching { StreamQuality.valueOf(it) }.getOrNull() }
                ?: StreamQuality.HIGH
        )
    }

    suspend fun save(settings: AdminSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.isStreaming] = settings.isStreaming
            prefs[Keys.useFrontCamera] = settings.useFrontCamera
            prefs[Keys.keepScreenAwake] = settings.keepScreenAwake
            prefs[Keys.port] = settings.port
            prefs[Keys.quality] = settings.quality.name
        }
    }
}
