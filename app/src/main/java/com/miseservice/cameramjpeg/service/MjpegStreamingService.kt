package com.miseservice.cameramjpeg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.miseservice.cameramjpeg.R
import com.miseservice.cameramjpeg.domain.model.StreamQuality
import com.miseservice.cameramjpeg.streaming.BatteryStatus
import com.miseservice.cameramjpeg.streaming.CameraStreamController
import com.miseservice.cameramjpeg.streaming.FrameStore
import com.miseservice.cameramjpeg.streaming.MjpegHttpServer

/**
 * MjpegStreamingService
 *
 * Service Android principal pour la gestion du streaming MJPEG,
 * du contrôle caméra et de l'API HTTP.
 *
 * EXIF sur /snapshot.jpg
 * ──────────────────────
 * Les trois champs exposés par [CameraStreamController] sont transmis à
 * [MjpegHttpServer] au démarrage du streaming :
 *   • lastRotationAngle      → EXIF Orientation
 *   • lastCaptureResult      → EXIF ExposureTime + ISOSpeedRatings
 *   • currentCharacteristics → EXIF FNumber
 *
 * La caméra est démarrée AVANT le serveur HTTP dans [startStreaming] afin
 * que lastRotationAngle et currentCharacteristics soient déjà renseignés
 * au moment où MjpegHttpServer est construit.
 *
 * getCaptureResult est un lambda — il lit lastCaptureResult à chaque
 * requête snapshot, garantissant les métadonnées les plus fraîches.
 *
 * Après [ACTION_SWITCH_CAMERA], [restartServer] recrée MjpegHttpServer
 * avec les nouveaux paramètres EXIF de la caméra sélectionnée.
 */
class MjpegStreamingService : LifecycleService() {

    private val frameStore = FrameStore()
    private lateinit var streamController: CameraStreamController
    private var server: MjpegHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var latestBatteryStatus: BatteryStatus? = null
    private val faviconPngBytes by lazy { loadFaviconPngBytes() }

    /** Port courant — conservé pour pouvoir recréer le serveur après switchCamera. */
    private var currentPort: Int = 8080

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryStatus(intent)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        streamController = CameraStreamController(this, frameStore)
        createChannel()
        registerBatteryTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val port      = intent.getIntExtra(EXTRA_PORT, 8080)
                val useFront  = intent.getBooleanExtra(EXTRA_USE_FRONT, false)
                val keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)
                val quality   = intent.getStringExtra(EXTRA_QUALITY)
                    ?.let { runCatching { StreamQuality.valueOf(it) }.getOrNull() }
                    ?: StreamQuality.HIGH

                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(port),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                )
                startStreaming(port, useFront, quality, keepAwake)
            }

            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SWITCH_CAMERA -> {
                val useFront = intent.getBooleanExtra(EXTRA_USE_FRONT, false)
                streamController.switchCamera(useFront)
                // Recréer le serveur pour que la nouvelle rotation et les
                // nouvelles characteristics soient reflétées sur /snapshot.jpg.
                restartServer(currentPort)
            }

            ACTION_UPDATE_QUALITY -> {
                val quality = intent.getStringExtra(EXTRA_QUALITY)
                    ?.let { runCatching { StreamQuality.valueOf(it) }.getOrNull() }
                    ?: StreamQuality.HIGH
                streamController.updateQuality(quality.jpegQuality)
            }

            ACTION_UPDATE_WAKE_MODE -> {
                val keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)
                if (keepAwake) acquireWakeLock() else releaseWakeLock()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        unregisterBatteryTracking()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming control
    // ─────────────────────────────────────────────────────────────────────────

    private fun startStreaming(
        port: Int,
        useFront: Boolean,
        quality: StreamQuality,
        keepAwake: Boolean
    ) {
        currentPort = port
        server?.stop()

        // ① Démarrer la caméra EN PREMIER.
        //   lastRotationAngle et currentCharacteristics sont renseignés
        //   avant la construction de MjpegHttpServer.
        streamController.start(useFront, quality.jpegQuality)

        // ② Créer le serveur avec les paramètres EXIF frais.
        server = buildServer(port).also { it.start() }

        if (keepAwake) acquireWakeLock() else releaseWakeLock()
    }

    /**
     * Recrée uniquement le serveur HTTP avec les paramètres EXIF à jour.
     * Appelé après [ACTION_SWITCH_CAMERA].
     */
    private fun restartServer(port: Int) {
        server?.stop()
        server = buildServer(port).also { it.start() }
    }

    /**
     * Instancie [MjpegHttpServer] en branchant les champs EXIF de
     * [streamController].
     *
     * [getCaptureResult] est un lambda — il lit [CameraStreamController.lastCaptureResult]
     * au moment de chaque requête /snapshot.jpg, pas au moment de la construction.
     */
    private fun buildServer(port: Int): MjpegHttpServer = MjpegHttpServer(
        port                    = port,
        frameStore              = frameStore,
        batteryStatusProvider   = { latestBatteryStatus },
        faviconProvider         = { faviconPngBytes },
        cameraFormatsProvider   = { streamController.buildCameraOutputMapJson() },
        snapshotRotationDegrees = streamController.lastRotationAngle,
        getCaptureResult        = { streamController.lastCaptureResult },
        cameraCharacteristics   = streamController.currentCharacteristics
    )

    private fun stopStreaming() {
        streamController.stop()
        server?.stop()
        server = null
        releaseWakeLock()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WakeLock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraMjpeg::StreamWakeLock"
        ).apply { acquire(10 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Battery tracking
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerBatteryTracking() {
        val stickyIntent = registerReceiver(
            batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        updateBatteryStatus(stickyIntent)
    }

    private fun unregisterBatteryTracking() {
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    private fun updateBatteryStatus(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return

        val percent     = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        val statusCode  = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                              BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging  = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                          statusCode == BatteryManager.BATTERY_STATUS_FULL
        val statusLabel = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING     -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING  -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL         -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else                                       -> "unknown"
        }
        val temperatureC = intent
            .getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }

        latestBatteryStatus = BatteryStatus(
            levelPercent = percent,
            charging     = isCharging,
            status       = statusLabel,
            temperatureC = temperatureC,
            timestampMs  = System.currentTimeMillis()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadFaviconPngBytes(): ByteArray? = runCatching {
        val drawable = androidx.core.content.res.ResourcesCompat
            .getDrawable(resources, R.drawable.android_chrome_512x512, null)
        val bitmap = (drawable as android.graphics.drawable.BitmapDrawable).bitmap
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }.getOrNull()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Camera MJPEG", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(port: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Streaming MJPEG actif sur le port $port")
            .setOngoing(true)
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val CHANNEL_ID       = "camera_mjpeg_stream"
        private const val NOTIFICATION_ID  = 1001

        private const val EXTRA_PORT       = "extra_port"
        private const val EXTRA_USE_FRONT  = "extra_use_front"
        private const val EXTRA_QUALITY    = "extra_quality"
        private const val EXTRA_KEEP_AWAKE = "extra_keep_awake"

        private const val ACTION_START            = "com.miseservice.cameramjpeg.action.START"
        private const val ACTION_STOP             = "com.miseservice.cameramjpeg.action.STOP"
        private const val ACTION_SWITCH_CAMERA    = "com.miseservice.cameramjpeg.action.SWITCH_CAMERA"
        private const val ACTION_UPDATE_QUALITY   = "com.miseservice.cameramjpeg.action.UPDATE_QUALITY"
        private const val ACTION_UPDATE_WAKE_MODE = "com.miseservice.cameramjpeg.action.UPDATE_WAKE_MODE"

        fun startIntent(
            context: Context, port: Int, useFront: Boolean,
            quality: StreamQuality, keepAwake: Boolean
        ): Intent = Intent(context, MjpegStreamingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_USE_FRONT, useFront)
            putExtra(EXTRA_QUALITY, quality.name)
            putExtra(EXTRA_KEEP_AWAKE, keepAwake)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, MjpegStreamingService::class.java).apply { action = ACTION_STOP }

        fun switchCameraIntent(context: Context, useFront: Boolean): Intent =
            Intent(context, MjpegStreamingService::class.java).apply {
                action = ACTION_SWITCH_CAMERA
                putExtra(EXTRA_USE_FRONT, useFront)
            }

        fun updateQualityIntent(context: Context, quality: StreamQuality): Intent =
            Intent(context, MjpegStreamingService::class.java).apply {
                action = ACTION_UPDATE_QUALITY
                putExtra(EXTRA_QUALITY, quality.name)
            }

        fun updateWakeModeIntent(context: Context, keepAwake: Boolean): Intent =
            Intent(context, MjpegStreamingService::class.java).apply {
                action = ACTION_UPDATE_WAKE_MODE
                putExtra(EXTRA_KEEP_AWAKE, keepAwake)
            }
    }
}
