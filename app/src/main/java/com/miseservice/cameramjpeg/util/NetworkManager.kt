package com.miseservice.cameramjpeg.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NetworkManager
 *
 * Observes available network interfaces and binds the process to the one
 * the user prefers (Wi-Fi or Ethernet).
 *
 * IMPORTANT — why requestNetwork() is NOT used:
 * ConnectivityManager.requestNetwork() requires CHANGE_NETWORK_STATE, a
 * signature/privileged permission that a normal app cannot hold.  Calling it
 * crashes with SecurityException on affected devices.
 *
 * Instead we:
 *   1. Register a passive listener for BOTH transports via registerNetworkCallback().
 *      This only requires ACCESS_NETWORK_STATE and never throws.
 *   2. When the preferred transport is available we call bindProcessToNetwork()
 *      so all new sockets use that interface.
 *   3. setPreference() lets the UI express a preference; the binding is applied
 *      immediately if the network is already up, or deferred until onAvailable().
 *
 * Required permissions:
 *   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 *   <uses-permission android:name="android.permission.INTERNET" />
 */
class NetworkManager(private val context: Context) {

    enum class NetworkType { WIFI, ETHERNET, NONE }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // What the user wants — defaults to whatever is currently active.
    private var preferredType: NetworkType = getCurrentNetworkType()

    // Live networks seen by the passive callback, keyed by transport type.
    private val availableNetworks = mutableMapOf<NetworkType, Network>()

    private val _activeNetworkType = MutableStateFlow(getCurrentNetworkType())
    val activeNetworkType: StateFlow<NetworkType> = _activeNetworkType.asStateFlow()

    // -------------------------------------------------------------------------
    // Passive monitor — no privileged permission required
    // -------------------------------------------------------------------------

    private val monitorCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val type = transportOf(network) ?: return
            availableNetworks[type] = network
            applyPreference()
            _activeNetworkType.value = getCurrentNetworkType()
        }

        override fun onLost(network: Network) {
            val type = availableNetworks.entries.firstOrNull { it.value == network }?.key ?: return
            availableNetworks.remove(type)
            // If we lost the bound network, fall back to whatever is still up.
            if (type == preferredType) {
                connectivityManager.bindProcessToNetwork(null)
                _activeNetworkType.value = getCurrentNetworkType()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _activeNetworkType.value = getCurrentNetworkType()
        }
    }

    init {
        // Listen to both transports with a single passive callback.
        // registerNetworkCallback() does NOT require CHANGE_NETWORK_STATE.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, monitorCallback)
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the current active network type by inspecting live capabilities.
     * Ethernet takes priority over Wi-Fi when both are active.
     */
    fun getCurrentNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val caps    = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            else                                                       -> NetworkType.NONE
        }
    }

    /**
     * Expresses a preference for [networkType].
     *
     * If that transport is already available the process is bound to it immediately.
     * If it is not yet available the preference is stored and applied in [onAvailable].
     *
     * Never calls requestNetwork() — no privileged permission required.
     */
    fun switchTo(networkType: NetworkType) {
        if (networkType == NetworkType.NONE) return
        preferredType = networkType
        applyPreference()
    }

    /**
     * Unregisters all callbacks and clears the process network binding.
     * Call from AdminViewModel.onCleared().
     */
    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(monitorCallback)
        } catch (_: IllegalArgumentException) { }
        connectivityManager.bindProcessToNetwork(null)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Binds the process to the preferred network if it is currently available. */
    private fun applyPreference() {
        val network = availableNetworks[preferredType] ?: return
        connectivityManager.bindProcessToNetwork(network)
        _activeNetworkType.value = preferredType
    }

    /** Returns the [NetworkType] for a given [Network], or null if unknown. */
    private fun transportOf(network: Network): NetworkType? {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            else                                                       -> null
        }
    }
}