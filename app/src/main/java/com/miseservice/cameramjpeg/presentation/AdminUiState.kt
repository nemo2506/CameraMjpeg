package com.miseservice.cameramjpeg.presentation

import com.miseservice.cameramjpeg.domain.model.StreamQuality

data class AdminUiState(
    val isLoading: Boolean = true,
    val isStreaming: Boolean = false,
    val useFrontCamera: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val portInput: String = "8080",
    val selectedQuality: StreamQuality = StreamQuality.HIGH,
    val localIpAddress: String? = null,
    val wifiSsid: String? = null,
    val isWifiConnected: Boolean = false,
    val batteryLevelPercent: Int? = null,
    val isBatteryCharging: Boolean = false,
    val batteryStatusLabel: String? = null,
    val batteryTemperatureC: Float? = null,
    val batteryApiUrl: String? = null,
    val cameraFormatsApiUrl: String? = null,
    val streamUrl: String? = null,
    val viewerUrl: String? = null,
    val errorMessage: String? = null
)

