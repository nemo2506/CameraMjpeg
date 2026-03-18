package com.miseservice.cameramjpeg

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.miseservice.cameramjpeg.presentation.AdminViewModel
import com.miseservice.cameramjpeg.service.CameraForegroundService
import com.miseservice.cameramjpeg.ui.screen.AdminScreen
import com.miseservice.cameramjpeg.ui.theme.CameraMjpegTheme
import com.miseservice.cameramjpeg.util.NetworkManager

/**
 * Main activity for the CameraMjpeg application.
 * Entry point: handles permissions and initializes the main Compose UI.
 *
 * Usage:
 * - Starts CameraForegroundService for camera streaming protection.
 * - Sets up Compose UI with AdminViewModel.
 * - Handles lifecycle events (onCreate, onDestroy).
 *
 * @constructor Default constructor for ComponentActivity.
 */
class MainActivity : ComponentActivity() {
    /** ViewModel for administration and streaming state. */
    private val viewModel: AdminViewModel by viewModels()

    /**
     * Called when the activity is created. Initializes UI and handles permissions.
     * Starts the foreground camera service.
     * @param savedInstanceState Bundle for saved state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Flags fenêtre en premier, avant tout rendu
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        // Démarrage du service foreground
        val intent = Intent(this, CameraForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Called when the activity is destroyed. Stops the foreground camera service.
     */
    override fun onDestroy() {
        super.onDestroy()

        // Libérer le flag écran allumé
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Stop foreground camera service
        val intent = Intent(this, CameraForegroundService::class.java)
        intent.action = CameraForegroundService.ACTION_STOP
        startService(intent)
    }
}

/**
 * Composable principal de l’application. Gère les permissions et affiche l’écran d’administration.
 *
 * @param viewModel Instance du ViewModel d’administration
 * @param modifier Modificateur de layout
 */
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
    val context = LocalContext.current
    val networkManager = remember { NetworkManager(context) }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(requestedPermissions)
        }
    }
    AdminScreen(viewModel = viewModel, networkManager = networkManager, modifier = modifier)
}
