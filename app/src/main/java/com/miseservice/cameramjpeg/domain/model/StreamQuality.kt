package com.miseservice.cameramjpeg.domain.model

enum class StreamQuality(val label: String, val jpegQuality: Int) {
    LOW("Faible", 55),
    MEDIUM("Moyenne", 75),
    HIGH("Optimale", 92)
}

