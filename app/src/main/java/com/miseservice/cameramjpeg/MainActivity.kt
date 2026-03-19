package com.miseservice.cameramjpeg

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.core.net.toUri
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
 * - Guides user to disable battery optimization via system settings (Play Store compliant).
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

        // Guider l'utilisateur vers les paramètres batterie si nécessaire
        // (Play Store compliant — on redirige vers les settings, on ne force pas)
        checkBatteryOptimization()

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
        startForegroundService(intent)
    }

    /**
     * Checks if battery optimization is active for this app.
     * If so, redirects the user to the app's battery settings page
     * so they can manually disable optimization.
     *
     * Play Store compliant:
     * - NO REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission needed
     * - Opens ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (liste globale des apps)
     * - Fallback sur ACTION_APPLICATION_DETAILS_SETTINGS
     * - L'utilisateur garde le contrôle total
     */
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // try/catch remplace resolveActivity() déprécié depuis API 33
            // et contourne le package visibility filtering (Android 11+)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Fallback : détail de l'app dans les paramètres système
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:$packageName".toUri()
                    }
                )
            }
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
 * Composable principal de l'application. Gère les permissions et affiche l'écran d'administration.
 *
 * @param viewModel Instance du ViewModel d'administration
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
