package com.wembassy.promptcam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings data model and persistence layer for PromptCam.
 * 
 * Uses Jetpack DataStore for async, type-safe preference storage.
 * All settings are exposed as reactive Flows for Compose observation.
 */

// Extension property for DataStore singleton
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "promptcam_settings")

/**
 * Camera lens selection.
 * Maps to Camera2 CameraCharacteristics.LENS_FACING constants.
 */
enum class CameraLens(val displayName: String, val facing: Int) {
    FRONT("Front Camera", android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT),
    BACK("Back Camera", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK);

    companion object {
        fun fromName(name: String): CameraLens = 
            entries.find { it.name == name } ?: BACK
    }
}

/**
 * Supported video resolutions.
 */
enum class VideoResolution(val displayName: String, val width: Int, val height: Int) {
    P720("720p (1280×720)", 1280, 720),
    P1080("1080p (1920×1080)", 1920, 1080);

    companion object {
        fun fromName(name: String): VideoResolution = 
            entries.find { it.name == name } ?: P720
    }
}

/**
 * Application settings state.
 * Immutable data class used throughout the app.
 */
data class AppSettings(
    val cameraLens: CameraLens = CameraLens.BACK,
    val resolution: VideoResolution = VideoResolution.P720,
    val bitrate: Int = 2_500_000,         // 2.5 Mbps default
    val videoPort: Int = Protocol.DEFAULT_VIDEO_PORT,
    val controlPort: Int = Protocol.DEFAULT_CONTROL_PORT
)

/**
 * Settings manager — reads and writes settings via DataStore.
 * 
 * Usage:
 *   val settingsManager = SettingsManager(context)
 *   settingsManager.settingsFlow.collect { settings -> ... }
 *   settingsManager.updateCameraLens(CameraLens.FRONT)
 */
class SettingsManager(private val context: Context) {

    // DataStore keys
    private object Keys {
        val CAMERA_LENS = stringPreferencesKey("camera_lens")
        val RESOLUTION = stringPreferencesKey("resolution")
        val BITRATE = intPreferencesKey("bitrate")
        val VIDEO_PORT = intPreferencesKey("video_port")
        val CONTROL_PORT = intPreferencesKey("control_port")
    }

    /**
     * Reactive flow of current settings. Emits on every change.
     */
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            cameraLens = prefs[Keys.CAMERA_LENS]?.let { CameraLens.fromName(it) } ?: CameraLens.BACK,
            resolution = prefs[Keys.RESOLUTION]?.let { VideoResolution.fromName(it) } ?: VideoResolution.P720,
            bitrate = prefs[Keys.BITRATE] ?: 2_500_000,
            videoPort = prefs[Keys.VIDEO_PORT] ?: Protocol.DEFAULT_VIDEO_PORT,
            controlPort = prefs[Keys.CONTROL_PORT] ?: Protocol.DEFAULT_CONTROL_PORT
        )
    }

    // Individual update methods

    suspend fun updateCameraLens(lens: CameraLens) {
        context.dataStore.edit { it[Keys.CAMERA_LENS] = lens.name }
    }

    suspend fun updateResolution(resolution: VideoResolution) {
        context.dataStore.edit { it[Keys.RESOLUTION] = resolution.name }
    }

    suspend fun updateBitrate(bitrate: Int) {
        context.dataStore.edit { it[Keys.BITRATE] = bitrate }
    }

    suspend fun updateVideoPort(port: Int) {
        context.dataStore.edit { it[Keys.VIDEO_PORT] = port }
    }

    suspend fun updateControlPort(port: Int) {
        context.dataStore.edit { it[Keys.CONTROL_PORT] = port }
    }

    /**
     * Update all settings at once.
     */
    suspend fun updateAll(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CAMERA_LENS] = settings.cameraLens.name
            prefs[Keys.RESOLUTION] = settings.resolution.name
            prefs[Keys.BITRATE] = settings.bitrate
            prefs[Keys.VIDEO_PORT] = settings.videoPort
            prefs[Keys.CONTROL_PORT] = settings.controlPort
        }
    }
}
