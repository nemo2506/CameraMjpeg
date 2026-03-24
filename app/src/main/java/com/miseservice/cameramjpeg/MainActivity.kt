package com.miseservice.cameramjpeg

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.core.net.toUri
import com.miseservice.cameramjpeg.presentation.AdminViewModel
import com.miseservice.cameramjpeg.service.CameraForegroundService
import com.miseservice.cameramjpeg.ui.screen.AdminScreen
import com.miseservice.cameramjpeg.ui.theme.CameraMjpegTheme

/**
 * MainActivity — Entry point for the CameraMjpeg application.
 *
 * Responsibilities:
 * - Requests CAMERA, LOCATION and (API 33+) POST_NOTIFICATIONS permissions.
 * - Starts [CameraForegroundService] once the CAMERA permission is granted.
 * - Keeps the screen on while the activity is visible.
 * - Guides the user to disable battery optimisation (Play Store-compliant redirect).
 * - Stops the foreground service when the activity is destroyed.
 *
 * [AdminViewModel] owns [NetworkManager] — no reference to it is needed here.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AdminViewModel by viewModels()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestBatteryOptimisationExemption()
        enableEdgeToEdge()

        setContent {
            CameraMjpegTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        viewModel = viewModel,
                        modifier  = Modifier.padding(innerPadding),
                        onCameraPermissionGranted = ::startCameraForegroundService
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopCameraForegroundService()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Service control
    // -------------------------------------------------------------------------

    /**
     * Starts [CameraForegroundService].
     * Must be called only after CAMERA permission has been granted.
     */
    private fun startCameraForegroundService() {
        startForegroundService(Intent(this, CameraForegroundService::class.java))
    }

    /**
     * Sends [CameraForegroundService.ACTION_STOP] to the running service.
     * The service removes its notification and calls stopSelf().
     */
    private fun stopCameraForegroundService() {
        startService(
            Intent(this, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_STOP
            }
        )
    }

    // -------------------------------------------------------------------------
    // Battery optimisation
    // -------------------------------------------------------------------------

    /**
     * Redirects the user to the system battery-optimisation screen when the app
     * is not already exempt.
     *
     * Strategy (Play Store compliant):
     * 1. Try ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (global list).
     * 2. Fall back to ACTION_APPLICATION_DETAILS_SETTINGS for this package.
     */
    private fun requestBatteryOptimisationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:$packageName".toUri()
                }
            )
        }
    }
}

// =============================================================================
// Composables
// =============================================================================

/**
 * Root composable.
 *
 * Handles runtime-permission requests and calls [onCameraPermissionGranted]
 * once the CAMERA permission is available — either because it was already
 * granted in a previous session or after the user accepts the dialog.
 *
 * [NetworkManager] is no longer created here; it lives inside [AdminViewModel].
 */
@Composable
private fun MainContent(
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
    onCameraPermissionGranted: () -> Unit = {}
) {
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            onCameraPermissionGranted()
        }
        viewModel.refreshNetworkInfo()
    }

    LaunchedEffect(Unit) {
        if (viewModel.hasRequiredPermissions()) {
            onCameraPermissionGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // NetworkManager parameter is gone — AdminViewModel owns it.
    AdminScreen(
        viewModel = viewModel,
        modifier  = modifier
    )
}
