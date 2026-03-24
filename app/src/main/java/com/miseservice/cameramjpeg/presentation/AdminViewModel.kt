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
import com.miseservice.cameramjpeg.util.NetworkManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AdminViewModel
 *
 * Primary ViewModel for the MJPEG streaming administration screen.
 * Owns [NetworkManager] and exposes its state via [activeNetworkType].
 *
 * Responsibilities:
 * - Loads and persists [AdminSettings].
 * - Controls the [MjpegStreamingService] lifecycle.
 * - Tracks battery status via a sticky broadcast receiver.
 * - Manages network interface selection via [NetworkManager].
 * - Fires [networkUnavailableEvent] when the user tries to switch to an
 *   interface that is not currently connected, so the UI can show an alert.
 * - Refreshes and exposes network info (IP, SSID, API URLs).
 *
 * @param application Android Application context
 */
class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val loadSettingsUseCase     = LoadSettingsUseCase(SettingsRepository(appContext))
    private val saveSettingsUseCase     = SaveSettingsUseCase(SettingsRepository(appContext))
    private val fetchNetworkInfoUseCase = FetchNetworkInfoUseCase(NetworkRepository(appContext))

    // NetworkManager is owned here — its lifecycle is tied to this ViewModel.
    private val networkManager = NetworkManager(appContext)

    /** Observed by the UI to display and react to the active network interface. */
    val activeNetworkType: StateFlow<NetworkManager.NetworkType> =
        networkManager.activeNetworkType

    /**
     * Fired (once per tap) when the user requests a switch to a network type
     * that is not currently available.  The UI collects this to show an alert.
     * Carries the [NetworkManager.NetworkType] that was unavailable.
     */
    private val _networkUnavailableEvent = MutableSharedFlow<NetworkManager.NetworkType>()
    val networkUnavailableEvent: SharedFlow<NetworkManager.NetworkType> =
        _networkUnavailableEvent.asSharedFlow()

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    // -------------------------------------------------------------------------
    // Battery receiver
    // -------------------------------------------------------------------------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryInfo(intent)
        }
    }

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    init {
        registerBatteryReceiver()
        viewModelScope.launch {
            val settings = loadSettingsUseCase()
            _uiState.update {
                it.copy(
                    isLoading       = false,
                    isStreaming      = settings.isStreaming,
                    useFrontCamera   = settings.useFrontCamera,
                    keepScreenAwake  = settings.keepScreenAwake,
                    selectedQuality  = settings.quality,
                    portInput        = settings.port.toString()
                )
            }
            refreshNetworkInfo()
            if (settings.isStreaming) {
                startStreamingInternal(settings)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCleared() {
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        networkManager.release()
        super.onCleared()
    }

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    /**
     * Requests process-level binding to [networkType].
     *
     * If the target interface is not currently available a
     * [networkUnavailableEvent] is emitted so the UI can show an alert.
     * No crash, no silent failure.
     */
    fun switchNetwork(networkType: NetworkManager.NetworkType) {
        if (!networkManager.isAvailable(networkType)) {
            viewModelScope.launch {
                _networkUnavailableEvent.emit(networkType)
            }
            return
        }
        networkManager.switchTo(networkType)
    }

    /**
     * Refreshes network info (local IP, SSID, API/stream URLs) and updates [uiState].
     */
    fun refreshNetworkInfo() {
        val port    = currentPort() ?: 8080
        val network = fetchNetworkInfoUseCase(port)
        val ssidError = when {
            !network.isWifiConnected                                   -> "Aucun Wi-Fi actif"
            network.wifiSsid == null && !network.hasLocationPermission -> "Autorisez la localisation pour afficher le SSID"
            network.wifiSsid == null && !network.isLocationEnabled     -> "Activez la localisation de l'appareil pour afficher le SSID"
            network.wifiSsid == null                                   -> "SSID indisponible sur ce réseau"
            else                                                       -> null
        }
        _uiState.update {
            it.copy(
                localIpAddress      = network.localIpAddress,
                wifiSsid            = network.wifiSsid,
                isWifiConnected     = network.isWifiConnected,
                batteryApiUrl       = network.batteryApiUrl,
                cameraFormatsApiUrl = network.cameraFormatsApiUrl,
                streamUrl           = network.streamUrl,
                viewerUrl           = network.viewerUrl,
                errorMessage        = ssidError
            )
        }
    }

    // -------------------------------------------------------------------------
    // Streaming controls
    // -------------------------------------------------------------------------

    /**
     * Starts the MJPEG streaming service with the current settings.
     */
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

    /**
     * Stops the MJPEG streaming service.
     */
    fun stopStreaming() {
        appContext.startService(MjpegStreamingService.stopIntent(appContext))
        _uiState.update { it.copy(isStreaming = false) }
        persist(currentSettings(isStreaming = false))
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Validates and applies a new streaming port.
     * If streaming is active the service is restarted on the new port immediately.
     */
    fun setStreamingPort(rawPort: String) {
        val validatedPort = rawPort.toIntOrNull()?.takeIf { it in 1..65535 }
        if (validatedPort == null) {
            _uiState.update { it.copy(errorMessage = "Port invalide (1-65535)") }
            return
        }
        if (_uiState.value.portInput.toIntOrNull() == validatedPort) return

        _uiState.update { it.copy(portInput = validatedPort.toString(), errorMessage = null) }

        val settings = currentSettings()
        persist(settings)
        if (_uiState.value.isStreaming) startStreamingInternal(settings)
        refreshNetworkInfo()
    }

    /**
     * Switches the active camera (front / back).
     * Notifies the service immediately if streaming is active.
     */
    fun setCamera(useFront: Boolean) {
        _uiState.update { it.copy(useFrontCamera = useFront) }
        if (_uiState.value.isStreaming) {
            appContext.startService(MjpegStreamingService.switchCameraIntent(appContext, useFront))
        }
        persist()
    }

    /**
     * Changes the JPEG stream quality.
     * Notifies the service immediately if streaming is active.
     */
    fun setQuality(quality: StreamQuality) {
        _uiState.update { it.copy(selectedQuality = quality) }
        if (_uiState.value.isStreaming) {
            appContext.startService(MjpegStreamingService.updateQualityIntent(appContext, quality))
        }
        persist()
    }

    /**
     * Toggles the keep-screen-awake / background wake-lock mode.
     * Notifies the service immediately if streaming is active.
     */
    fun setKeepAwake(enabled: Boolean) {
        _uiState.update { it.copy(keepScreenAwake = enabled) }
        if (_uiState.value.isStreaming) {
            appContext.startService(MjpegStreamingService.updateWakeModeIntent(appContext, enabled))
        }
        persist()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Returns true when all permissions required for streaming are granted.
     */
    fun hasRequiredPermissions(): Boolean {
        val cameraOk = ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val locationOk = ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cameraOk && locationOk
        else cameraOk
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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
                    isBatteryCharging   = false,
                    batteryStatusLabel  = null,
                    batteryTemperatureC = null
                )
            }
            return
        }
        val percent    = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        val statusCode = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                         statusCode == BatteryManager.BATTERY_STATUS_FULL
        val statusLabel = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING     -> "En charge"
            BatteryManager.BATTERY_STATUS_DISCHARGING  -> "Décharge"
            BatteryManager.BATTERY_STATUS_FULL         -> "Pleine"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Branchée"
            else                                       -> "Indisponible"
        }
        val rawTemp      = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = rawTemp.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        _uiState.update {
            it.copy(
                batteryLevelPercent = percent,
                isBatteryCharging   = isCharging,
                batteryStatusLabel  = statusLabel,
                batteryTemperatureC = temperatureC
            )
        }
    }

    private fun startStreamingInternal(settings: AdminSettings) {
        ContextCompat.startForegroundService(
            appContext,
            MjpegStreamingService.startIntent(
                context   = appContext,
                port      = settings.port,
                useFront  = settings.useFrontCamera,
                quality   = settings.quality,
                keepAwake = settings.keepScreenAwake
            )
        )
    }

    private fun currentPort(): Int? =
        _uiState.value.portInput.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun currentSettings(isStreaming: Boolean = _uiState.value.isStreaming): AdminSettings =
        AdminSettings(
            isStreaming     = isStreaming,
            useFrontCamera  = _uiState.value.useFrontCamera,
            keepScreenAwake = _uiState.value.keepScreenAwake,
            port            = currentPort() ?: 8080,
            quality         = _uiState.value.selectedQuality
        )

    private fun persist(settings: AdminSettings = currentSettings()) {
        viewModelScope.launch { saveSettingsUseCase(settings) }
    }
}
