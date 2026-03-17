package com.miseservice.cameramjpeg.util

import android.content.Context
import android.net.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NetworkManager
 *
 * Handles detection and switching between Ethernet and Wi-Fi connections.
 * Provides current network type as a StateFlow for UI observation.
 *
 * Usage:
 * - Call getCurrentNetworkType() to get the active network type.
 * - Call switchTo(NetworkType) to request a switch to Ethernet or Wi-Fi.
 * - Observe activeNetworkType for UI updates.
 */
class NetworkManager(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _activeNetworkType = MutableStateFlow(getCurrentNetworkType())
    val activeNetworkType: StateFlow<NetworkType> = _activeNetworkType

    enum class NetworkType { WIFI, ETHERNET, NONE }

    /**
     * Returns the current active network type (WIFI, ETHERNET, NONE).
     */
    fun getCurrentNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            else -> NetworkType.NONE
        }
    }

    /**
     * Requests a switch to the specified network type (Ethernet or Wi-Fi).
     * Binds the process to the selected network if available.
     */
    fun switchTo(networkType: NetworkType) {
        val request = NetworkRequest.Builder()
            .addTransportType(
                if (networkType == NetworkType.ETHERNET)
                    NetworkCapabilities.TRANSPORT_ETHERNET
                else
                    NetworkCapabilities.TRANSPORT_WIFI
            ).build()
        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                _activeNetworkType.value = networkType
            }
        })
    }
}

