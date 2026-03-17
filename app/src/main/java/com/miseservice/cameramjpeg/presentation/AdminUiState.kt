package com.miseservice.cameramjpeg.presentation

import com.miseservice.cameramjpeg.domain.model.StreamQuality

/**
 * État de l’interface administrateur pour le streaming MJPEG.
 *
 * @property isLoading Indique si les données sont en cours de chargement
 * @property isStreaming Indique si le streaming est actif
 * @property useFrontCamera Caméra avant utilisée si vrai, arrière sinon
 * @property keepScreenAwake Garde l’écran allumé si vrai
 * @property portInput Port TCP utilisé pour le serveur HTTP
 * @property selectedQuality Qualité de streaming sélectionnée
 * @property localIpAddress Adresse IP locale de l’appareil
 * @property wifiSsid SSID du réseau Wi-Fi
 * @property isWifiConnected Indique si l’appareil est connecté en Wi-Fi
 * @property batteryLevelPercent Niveau de batterie en pourcentage
 * @property isBatteryCharging Indique si la batterie est en charge
 * @property batteryStatusLabel Libellé du statut batterie
 * @property batteryTemperatureC Température de la batterie en °C
 * @property batteryApiUrl URL de l’API batterie
 * @property cameraFormatsApiUrl URL de l’API formats caméra
 * @property streamUrl URL du flux MJPEG
 * @property viewerUrl URL du viewer web
 * @property errorMessage Message d’erreur éventuel
 */
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

