package com.miseservice.cameramjpeg.domain.usecase

import com.miseservice.cameramjpeg.data.repository.NetworkRepository

class FetchNetworkInfoUseCase(private val networkRepository: NetworkRepository) {
    operator fun invoke(port: Int) = networkRepository.detect(port)
}

