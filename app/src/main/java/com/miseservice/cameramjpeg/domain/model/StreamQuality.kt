package com.miseservice.cameramjpeg.domain.model

/**
 * Qualité de streaming MJPEG.
 *
 * @property label Libellé lisible pour l’utilisateur
 * @property jpegQuality Qualité JPEG associée (0-100)
 */
enum class StreamQuality(val label: String, val jpegQuality: Int) {
    LOW("Faible", 55),
    MEDIUM("Moyenne", 75),
    HIGH("Optimale", 92)
}
