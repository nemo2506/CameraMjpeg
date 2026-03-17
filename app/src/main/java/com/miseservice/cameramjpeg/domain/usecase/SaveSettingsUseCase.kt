package com.miseservice.cameramjpeg.domain.usecase

import com.miseservice.cameramjpeg.data.repository.SettingsRepository
import com.miseservice.cameramjpeg.domain.model.AdminSettings

/**
 * Use case pour sauvegarder les paramètres administrateur dans le repository.
 *
 * @param settingsRepository Repository de persistance des paramètres
 */
class SaveSettingsUseCase(private val settingsRepository: SettingsRepository) {
    /**
     * Sauvegarde les paramètres administrateur.
     * @param settings Paramètres à sauvegarder
     */
    suspend operator fun invoke(settings: AdminSettings) = settingsRepository.save(settings)
}
