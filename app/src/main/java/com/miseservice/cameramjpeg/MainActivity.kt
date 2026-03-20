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
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.miseservice.cameramjpeg.presentation.AdminViewModel
import com.miseservice.cameramjpeg.service.CameraForegroundService
import com.miseservice.cameramjpeg.ui.screen.AdminScreen
import com.miseservice.cameramjpeg.ui.theme.CameraMjpegTheme
import com.miseservice.cameramjpeg.util.NetworkManager

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
 * Crash fix (RemoteServiceException — "did not then call startForeground"):
 * [CameraForegroundService] now calls startForeground() inside onCreate() so the
 * 5-second ANR window is respected even when the launcher callback fires while
 * the app is briefly in the background (e.g. after the permission dialog closes).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AdminViewModel by viewModels()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on — set before any rendering.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Redirect user to battery-optimisation settings if needed.
        // Play Store-compliant: we open the system settings page, we never
        // call REQUEST_IGNORE_BATTERY_OPTIMIZATIONS directly.
        requestBatteryOptimisationExemption()

        enableEdgeToEdge()

        setContent {
            CameraMjpegTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onCameraPermissionGranted = ::startCameraForegroundService
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Release the screen-on flag before stopping the service.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ask the foreground service to shut itself down cleanly.
        stopCameraForegroundService()

        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Service control
    // -------------------------------------------------------------------------

    /**
     * Starts [CameraForegroundService].
     *
     * Must be called only after the CAMERA permission has been granted;
     * Android 14+ rejects a foreground service of type `camera` without it.
     *
     * Note: [CameraForegroundService.onCreate] now calls startForeground()
     * immediately, so the 5-second deadline imposed by startForegroundService()
     * is always met regardless of what the caller's state is at that moment.
     */
    private fun startCameraForegroundService() {
        startForegroundService(
            Intent(this, CameraForegroundService::class.java)
        )
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
     *
     * We use try/catch instead of the deprecated resolveActivity() to work
     * around package-visibility filtering introduced in Android 11 (API 30).
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
 * once the CAMERA permission is available, either because it was already
 * granted in a previous session or after the user accepts the dialog.
 */
@Composable
private fun MainContent(
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier,
    onCameraPermissionGranted: () -> Unit = {}
) {
    val context = LocalContext.current
    val networkManager = remember { NetworkManager(context) }

    // Permissions required by the application.
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
        // Start the service only when CAMERA is granted.
        // Android 14+: foreground service type "camera" is rejected otherwise.
        if (results[Manifest.permission.CAMERA] == true) {
            onCameraPermissionGranted()
        }
        viewModel.refreshNetworkInfo()
    }

    // On first composition: start the service if already permitted, otherwise
    // show the permission dialog.
    LaunchedEffect(Unit) {
        if (viewModel.hasRequiredPermissions()) {
            onCameraPermissionGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    AdminScreen(
        viewModel = viewModel,
        networkManager = networkManager,
        modifier = modifier
    )
}