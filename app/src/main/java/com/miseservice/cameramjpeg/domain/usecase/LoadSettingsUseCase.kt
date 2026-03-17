package com.miseservice.cameramjpeg.domain.usecase

import com.miseservice.cameramjpeg.data.repository.SettingsRepository

/**
 * Use case pour charger les paramètres administrateur depuis le repository.
 *
 * @param settingsRepository Repository de persistance des paramètres
 */
class LoadSettingsUseCase(private val settingsRepository: SettingsRepository) {
    /**
     * Charge les paramètres administrateur.
     * @return Paramètres administrateur actuels
     */
    suspend operator fun invoke() = settingsRepository.load()
}
