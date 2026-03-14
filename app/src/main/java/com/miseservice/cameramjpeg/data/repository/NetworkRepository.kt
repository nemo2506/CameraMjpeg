package com.miseservice.cameramjpeg.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.NetworkInterface

data class NetworkSnapshot(
    val isWifiConnected: Boolean,
    val wifiSsid: String?,
    val localIpAddress: String?,
    val streamUrl: String?,
    val viewerUrl: String?
)

class NetworkRepository(private val context: Context) {

    fun detect(port: Int): NetworkSnapshot {
        val wifiConnected = isWifiConnected()
        val ip = localIpAddress()
        return NetworkSnapshot(
            isWifiConnected = wifiConnected,
            wifiSsid = if (wifiConnected) wifiSsid() else null,
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

    private fun wifiSsid(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = manager?.activeNetwork
                val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                val ssid = (capabilities?.transportInfo as? android.net.wifi.WifiInfo)?.ssid
                ssid?.trim('"')?.takeUnless { it.equals("<unknown ssid>", ignoreCase = true) }
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiManager?.connectionInfo?.ssid?.trim('"')
            }
        }.getOrNull()
    }
}

