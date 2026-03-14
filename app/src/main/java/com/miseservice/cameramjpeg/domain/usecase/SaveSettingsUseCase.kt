package com.miseservice.cameramjpeg.domain.usecase

import com.miseservice.cameramjpeg.data.repository.SettingsRepository
import com.miseservice.cameramjpeg.domain.model.AdminSettings

class SaveSettingsUseCase(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke(settings: AdminSettings) = settingsRepository.save(settings)
}

