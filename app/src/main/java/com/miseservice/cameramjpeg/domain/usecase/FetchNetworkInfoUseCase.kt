package com.miseservice.cameramjpeg.domain.usecase

import com.miseservice.cameramjpeg.data.repository.NetworkRepository

/**
 * Use case pour récupérer un snapshot réseau (IP, SSID, URLs).
 *
 * @param networkRepository Repository réseau
 */
class FetchNetworkInfoUseCase(private val networkRepository: NetworkRepository) {
    /**
     * Récupère un snapshot réseau pour un port donné.
     * @param port Port TCP utilisé
     * @return Snapshot réseau
     */
    operator fun invoke(port: Int) = networkRepository.detect(port)
}
