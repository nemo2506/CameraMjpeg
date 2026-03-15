package com.miseservice.cameramjpeg.ui.screen

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miseservice.cameramjpeg.domain.model.StreamQuality
import com.miseservice.cameramjpeg.presentation.AdminViewModel
import kotlinx.coroutines.launch

@Composable
fun AdminScreen(viewModel: AdminViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Administration MJPEG", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        StatusCard(isStreaming = uiState.isStreaming, errorMessage = uiState.errorMessage)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.startStreaming() }, modifier = Modifier.weight(1f), enabled = !uiState.isStreaming) {
                Text("Démarrer")
            }
            Button(onClick = { viewModel.stopStreaming() }, modifier = Modifier.weight(1f), enabled = uiState.isStreaming) {
                Text("Arrêter")
            }
        }

        ConfigCard(
            currentPort = uiState.portInput.toIntOrNull() ?: 8080,
            isStreaming = uiState.isStreaming,
            onPortSaved = viewModel::setStreamingPort,
            selectedQuality = uiState.selectedQuality,
            onQualityChange = viewModel::setQuality,
            useFrontCamera = uiState.useFrontCamera,
            onSetFront = { viewModel.setCamera(true) },
            onSetBack = { viewModel.setCamera(false) },
            keepScreenAwake = uiState.keepScreenAwake,
            onKeepAwakeChange = viewModel::setKeepAwake
        )

        NetworkCard(
            isWifiConnected = uiState.isWifiConnected,
            ssid = uiState.wifiSsid,
            localIp = uiState.localIpAddress,
            batteryLevelPercent = uiState.batteryLevelPercent,
            isBatteryCharging = uiState.isBatteryCharging,
            batteryStatusLabel = uiState.batteryStatusLabel,
            batteryTemperatureC = uiState.batteryTemperatureC,
            batteryApiUrl = uiState.batteryApiUrl,
            streamUrl = uiState.streamUrl,
            viewerUrl = uiState.viewerUrl,
            onRefresh = viewModel::refreshNetworkInfo,
            onCopy = { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipData.newPlainText("url", text).toClipEntry())
                }
            }
        )
    }
}

@Composable
private fun StatusCard(isStreaming: Boolean, errorMessage: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (isStreaming) "Streaming actif" else "Streaming arrêté",
                color = if (isStreaming) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConfigCard(
    currentPort: Int,
    isStreaming: Boolean,
    onPortSaved: (String) -> Unit,
    selectedQuality: StreamQuality,
    onQualityChange: (StreamQuality) -> Unit,
    useFrontCamera: Boolean,
    onSetFront: () -> Unit,
    onSetBack: () -> Unit,
    keepScreenAwake: Boolean,
    onKeepAwakeChange: (Boolean) -> Unit
) {
    var portInput by remember(currentPort) { mutableStateOf(currentPort.toString()) }
    val parsedPort = portInput.toIntOrNull()
    val isValidPort = parsedPort != null && parsedPort in 1..65535
    val isChanged = parsedPort != null && parsedPort != currentPort

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Réglages stream", fontWeight = FontWeight.SemiBold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Port (1-65535)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portInput.isNotBlank() && !isValidPort
                )

                Button(
                    onClick = { onPortSaved(portInput) },
                    enabled = isValidPort && isChanged,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(width = 64.dp, height = 56.dp),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Sauvegarder le port"
                    )
                }
            }

            if (isStreaming) {
                Text(
                    text = "Port applique en direct",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Qualité JPEG")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamQuality.entries.forEach { quality ->
                    FilterChip(
                        selected = quality == selectedQuality,
                        onClick = { onQualityChange(quality) },
                        label = { Text(quality.label) }
                    )
                }
            }

            Text("Caméra")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSetFront,
                    modifier = Modifier.weight(1f),
                    enabled = !useFrontCamera
                ) {
                    Icon(Icons.Default.CameraFront, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Avant")
                }
                Button(
                    onClick = onSetBack,
                    modifier = Modifier.weight(1f),
                    enabled = useFrontCamera
                ) {
                    Icon(Icons.Default.CameraRear, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Arrière")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Mode veille")
                    Text("Maintenir le stream en tâche de fond", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = keepScreenAwake, onCheckedChange = onKeepAwakeChange)
            }
        }
    }
}

@Composable
private fun NetworkCard(
    isWifiConnected: Boolean,
    ssid: String?,
    localIp: String?,
    batteryLevelPercent: Int?,
    isBatteryCharging: Boolean,
    batteryStatusLabel: String?,
    batteryTemperatureC: Float?,
    batteryApiUrl: String?,
    streamUrl: String?,
    viewerUrl: String?,
    onRefresh: () -> Unit,
    onCopy: (String) -> Unit
) {
    val batteryColor = when {
        batteryLevelPercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        batteryLevelPercent < 20 -> MaterialTheme.colorScheme.error
        batteryLevelPercent < 50 -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF2E7D32)
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Réseau", fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isWifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = if (isWifiConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(if (isWifiConnected) (ssid ?: "Wi-Fi connecte (SSID indisponible)") else "Wi-Fi non connecte")
            }

            Text("IP locale: ${localIp ?: "indisponible"}", fontFamily = FontFamily.Monospace)
            Text(
                buildString {
                    append("Batterie: ")
                    if (batteryLevelPercent == null) {
                        append("indisponible")
                    } else {
                        append(batteryLevelPercent)
                        append("%")
                        if (!batteryStatusLabel.isNullOrBlank()) {
                            append(" • ")
                            append(batteryStatusLabel)
                        }
                        batteryTemperatureC?.let {
                            append(" • ")
                            append(String.format(java.util.Locale.US, "%.1f°C", it))
                        }
                        if (isBatteryCharging) {
                            append(" ⚡")
                        }
                    }
                },
                fontFamily = FontFamily.Monospace,
                color = batteryColor
            )
            UrlRow(label = "API Batterie (JSON)", value = batteryApiUrl, onCopy = onCopy)
            UrlRow(label = "Flux MJPEG", value = streamUrl, onCopy = onCopy)
            UrlRow(label = "Viewer", value = viewerUrl, onCopy = onCopy)
        }
    }
}

@Composable
private fun UrlRow(label: String, value: String?, onCopy: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value ?: "indisponible", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            IconButton(onClick = { value?.let(onCopy) }, enabled = !value.isNullOrEmpty()) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copier")
            }
        }
    }
}

