package com.miseservice.cameramjpeg.domain.model

/**
 * Paramètres administrateur pour le service de streaming MJPEG.
 *
 * @property isStreaming Indique si le streaming est actif
 * @property useFrontCamera Caméra avant utilisée si vrai, arrière sinon
 * @property keepScreenAwake Garde l’écran allumé si vrai
 * @property port Port TCP utilisé pour le serveur HTTP
 * @property quality Qualité de streaming sélectionnée
 */
data class AdminSettings(
    val isStreaming: Boolean = false,
    val useFrontCamera: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val port: Int = 8080,
    val quality: StreamQuality = StreamQuality.HIGH
)
