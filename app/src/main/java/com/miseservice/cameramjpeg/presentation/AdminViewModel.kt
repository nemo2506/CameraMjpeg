package com.miseservice.cameramjpeg.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miseservice.cameramjpeg.data.repository.NetworkRepository
import com.miseservice.cameramjpeg.data.repository.SettingsRepository
import com.miseservice.cameramjpeg.domain.model.AdminSettings
import com.miseservice.cameramjpeg.domain.model.StreamQuality
import com.miseservice.cameramjpeg.domain.usecase.FetchNetworkInfoUseCase
import com.miseservice.cameramjpeg.domain.usecase.LoadSettingsUseCase
import com.miseservice.cameramjpeg.domain.usecase.SaveSettingsUseCase
import com.miseservice.cameramjpeg.service.MjpegStreamingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val loadSettingsUseCase = LoadSettingsUseCase(SettingsRepository(appContext))
    private val saveSettingsUseCase = SaveSettingsUseCase(SettingsRepository(appContext))
    private val fetchNetworkInfoUseCase = FetchNetworkInfoUseCase(NetworkRepository(appContext))

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryInfo(intent)
        }
    }

    init {
        registerBatteryReceiver()
        viewModelScope.launch {
            val settings = loadSettingsUseCase()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isStreaming = settings.isStreaming,
                    useFrontCamera = settings.useFrontCamera,
                    keepScreenAwake = settings.keepScreenAwake,
                    selectedQuality = settings.quality,
                    portInput = settings.port.toString()
                )
            }
            refreshNetworkInfo()
            if (settings.isStreaming) {
                startStreamingInternal(settings)
            }
        }
    }

    override fun onCleared() {
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        super.onCleared()
    }

    fun refreshNetworkInfo() {
        val port = currentPort() ?: 8080
        val network = fetchNetworkInfoUseCase(port)
        val ssidError = when {
            !network.isWifiConnected -> "Aucun Wi-Fi actif"
            network.wifiSsid == null && !network.hasLocationPermission -> "Autorisez la localisation pour afficher le SSID"
            network.wifiSsid == null && !network.isLocationEnabled -> "Activez la localisation de l'appareil pour afficher le SSID"
            network.wifiSsid == null -> "SSID indisponible sur ce reseau"
            else -> null
        }
        _uiState.update {
            it.copy(
                localIpAddress = network.localIpAddress,
                wifiSsid = network.wifiSsid,
                isWifiConnected = network.isWifiConnected,
                batteryApiUrl = network.batteryApiUrl,
                streamUrl = network.streamUrl,
                viewerUrl = network.viewerUrl,
                errorMessage = ssidError
            )
        }
    }


    fun setStreamingPort(rawPort: String) {
        val validatedPort = rawPort.toIntOrNull()?.takeIf { it in 1..65535 }
        if (validatedPort == null) {
            _uiState.update { it.copy(errorMessage = "Port invalide (1-65535)") }
            return
        }

        val currentPort = _uiState.value.portInput.toIntOrNull()
        if (currentPort == validatedPort) return

        _uiState.update {
            it.copy(
                portInput = validatedPort.toString(),
                errorMessage = null
            )
        }

        val settings = currentSettings()
        persist(settings)

        if (_uiState.value.isStreaming) {
            startStreamingInternal(settings)
        }

        refreshNetworkInfo()
    }

    fun setCamera(useFront: Boolean) {
        _uiState.update { it.copy(useFrontCamera = useFront) }
        if (_uiState.value.isStreaming) {
            appContext.startService(MjpegStreamingService.switchCameraIntent(appContext, useFront))
        }
        persist()
    }

    fun setQuality(quality: StreamQuality) {
        _uiState.update { it.copy(selectedQuality = quality) }
        if (_uiState.value.isStreaming) {
            appContext.startService(MjpegStreamingService.updateQualityIntent(appContext, quality))
        }
        persist()
    }

    fun setKeepAwake(enabled: Boolean) {
        _uiState.update { it.copy(keepScreenAwake = enabled) }
        if (_uiState.value.isStreaming) {
            appContext.startService(MjpegStreamingService.updateWakeModeIntent(appContext, enabled))
        }
        persist()
    }

    fun startStreaming() {
        val port = currentPort()
        if (port == null) {
            _uiState.update { it.copy(errorMessage = "Port invalide (1-65535)") }
            return
        }

        val settings = currentSettings(isStreaming = true)
        _uiState.update { it.copy(isStreaming = true, errorMessage = null) }
        startStreamingInternal(settings)
        persist(settings)
        refreshNetworkInfo()
    }

    fun stopStreaming() {
        appContext.startService(MjpegStreamingService.stopIntent(appContext))
        _uiState.update { it.copy(isStreaming = false) }
        persist(currentSettings(isStreaming = false))
    }

    fun hasRequiredPermissions(): Boolean {
        val cameraOk = ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val locationOk = ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cameraOk && locationOk
        } else {
            cameraOk
        }
    }

    private fun registerBatteryReceiver() {
        val stickyIntent = appContext.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        updateBatteryInfo(stickyIntent)
    }

    private fun updateBatteryInfo(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            _uiState.update {
                it.copy(
                    batteryLevelPercent = null,
                    isBatteryCharging = false,
                    batteryStatusLabel = null,
                    batteryTemperatureC = null
                )
            }
            return
        }

        val percent = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        val statusCode = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusCode == BatteryManager.BATTERY_STATUS_FULL
        val statusLabel = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "En charge"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Décharge"
            BatteryManager.BATTERY_STATUS_FULL -> "Pleine"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Branchée"
            else -> "Indisponible"
        }
        val rawTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = rawTemperature
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }

        _uiState.update {
            it.copy(
                batteryLevelPercent = percent,
                isBatteryCharging = isCharging,
                batteryStatusLabel = statusLabel,
                batteryTemperatureC = temperatureC
            )
        }
    }

    private fun startStreamingInternal(settings: AdminSettings) {
        ContextCompat.startForegroundService(
            appContext,
            MjpegStreamingService.startIntent(
                context = appContext,
                port = settings.port,
                useFront = settings.useFrontCamera,
                quality = settings.quality,
                keepAwake = settings.keepScreenAwake
            )
        )
    }

    private fun currentPort(): Int? {
        return _uiState.value.portInput.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun currentSettings(isStreaming: Boolean = _uiState.value.isStreaming): AdminSettings {
        return AdminSettings(
            isStreaming = isStreaming,
            useFrontCamera = _uiState.value.useFrontCamera,
            keepScreenAwake = _uiState.value.keepScreenAwake,
            port = currentPort() ?: 8080,
            quality = _uiState.value.selectedQuality
        )
    }

    private fun persist(settings: AdminSettings = currentSettings()) {
        viewModelScope.launch {
            saveSettingsUseCase(settings)
        }
    }
}
