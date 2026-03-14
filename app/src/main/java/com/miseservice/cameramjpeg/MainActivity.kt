package com.miseservice.cameramjpeg

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.miseservice.cameramjpeg.presentation.AdminViewModel
import com.miseservice.cameramjpeg.ui.screen.AdminScreen
import com.miseservice.cameramjpeg.ui.theme.CameraMjpegTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AdminViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraMjpegTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun MainContent(viewModel: AdminViewModel, modifier: Modifier = Modifier) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshNetworkInfo()
    }

    val requestedPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val hasPermissions = viewModel.hasRequiredPermissions()

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(requestedPermissions)
        }
    }

    val context = LocalContext.current

    if (!hasPermissions) {
        PermissionFallback(
            onRequestPermissions = { launcher.launch(requestedPermissions) },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            },
            modifier = modifier
        )
        return
    }

    AdminScreen(viewModel = viewModel, modifier = modifier)
}

internal const val PERMISSION_RETRY_BUTTON_TAG = "permission_retry_button"
internal const val PERMISSION_SETTINGS_BUTTON_TAG = "permission_settings_button"

@Composable
internal fun PermissionFallback(
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Les permissions Camera et Localisation sont necessaires pour le streaming et la detection du SSID Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag(PERMISSION_RETRY_BUTTON_TAG)
        ) {
            Text("Reessayer")
        }
        Button(
            onClick = onOpenAppSettings,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag(PERMISSION_SETTINGS_BUTTON_TAG)
        ) {
            Text("Ouvrir les parametres de l'app")
        }
    }
}