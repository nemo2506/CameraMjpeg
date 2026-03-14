package com.miseservice.cameramjpeg.domain.model

data class AdminSettings(
    val isStreaming: Boolean = false,
    val useFrontCamera: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val port: Int = 8080,
    val quality: StreamQuality = StreamQuality.HIGH
)

