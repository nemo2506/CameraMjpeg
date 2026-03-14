package com.miseservice.cameramjpeg.domain.usecase

import com.miseservice.cameramjpeg.data.repository.SettingsRepository

class LoadSettingsUseCase(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke() = settingsRepository.load()
}

