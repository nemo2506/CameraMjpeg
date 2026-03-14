package com.miseservice.cameramjpeg.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkSnapshot(
    val isWifiConnected: Boolean,
    val wifiSsid: String?,
    val hasLocationPermission: Boolean,
    val isLocationEnabled: Boolean,
    val localIpAddress: String?,
    val streamUrl: String?,
    val viewerUrl: String?
)

class NetworkRepository(private val context: Context) {

    fun detect(port: Int): NetworkSnapshot {
        val wifiConnected = isWifiConnected()
        val hasLocationPermission = hasLocationPermission()
        val locationEnabled = isLocationEnabled()
        val ip = localIpAddress()
        return NetworkSnapshot(
            isWifiConnected = wifiConnected,
            wifiSsid = if (wifiConnected) wifiSsid(hasLocationPermission, locationEnabled) else null,
            hasLocationPermission = hasLocationPermission,
            isLocationEnabled = locationEnabled,
            localIpAddress = ip,
            streamUrl = ip?.let { "http://$it:$port/stream.mjpeg" },
            viewerUrl = ip?.let { "http://$it:$port/" }
        )
    }

    private fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun localIpAddress(): String? {
        val activeWifiIp = activeWifiIpv4Address()
        if (!activeWifiIp.isNullOrBlank()) return activeWifiIp

        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .mapNotNull { address ->
                    val host = address.hostAddress ?: return@mapNotNull null
                    if (host.contains(":")) null else host
                }
                .firstOrNull()
        }.getOrNull()
    }

    private fun activeWifiIpv4Address(): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties: LinkProperties = manager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { !it.isNullOrBlank() }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun wifiSsid(hasLocationPermission: Boolean, locationEnabled: Boolean): String? {
        if (!hasLocationPermission || !locationEnabled) {
            return null
        }

        val ssidFromCapabilities = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = manager?.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                (capabilities?.transportInfo as? WifiInfo)?.ssid?.sanitizeSsid()
            } else {
                null
            }
        }.getOrNull()

        if (!ssidFromCapabilities.isNullOrBlank()) return ssidFromCapabilities

        return runCatching {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo?.ssid?.sanitizeSsid()
        }.getOrNull()
    }

    private fun String.sanitizeSsid(): String? {
        val clean = trim().trim('"')
        if (clean.isBlank()) return null
        if (clean.equals("<unknown ssid>", ignoreCase = true)) return null
        if (clean.equals("unknown ssid", ignoreCase = true)) return null
        return clean
    }
}

