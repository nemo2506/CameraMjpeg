package com.miseservice.cameramjpeg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.miseservice.cameramjpeg.R
import com.miseservice.cameramjpeg.domain.model.StreamQuality
import com.miseservice.cameramjpeg.streaming.CameraStreamController
import com.miseservice.cameramjpeg.streaming.FrameStore
import com.miseservice.cameramjpeg.streaming.MjpegHttpServer
import kotlinx.coroutines.launch

class MjpegStreamingService : LifecycleService() {

    private val frameStore = FrameStore()
    private lateinit var streamController: CameraStreamController
    private var server: MjpegHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        streamController = CameraStreamController(this, this, frameStore)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val useFront = intent.getBooleanExtra(EXTRA_USE_FRONT, false)
                val keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)
                val quality = intent.getStringExtra(EXTRA_QUALITY)
                    ?.let { runCatching { StreamQuality.valueOf(it) }.getOrNull() }
                    ?: StreamQuality.HIGH
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(port),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    } else {
                        0
                    }
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
        super.onDestroy()
    }

    private fun startStreaming(port: Int, useFront: Boolean, quality: StreamQuality, keepAwake: Boolean) {
        server?.stop()
        server = MjpegHttpServer(port, frameStore).also { it.start() }

        lifecycleScope.launch {
            streamController.start(useFront, quality.jpegQuality)
        }

        if (keepAwake) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun stopStreaming() {
        streamController.stop()
        server?.stop()
        server = null
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraMjpeg::StreamWakeLock").apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera MJPEG",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Streaming MJPEG actif sur le port $port")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "camera_mjpeg_stream"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_USE_FRONT = "extra_use_front"
        private const val EXTRA_QUALITY = "extra_quality"
        private const val EXTRA_KEEP_AWAKE = "extra_keep_awake"

        private const val ACTION_START = "com.miseservice.cameramjpeg.action.START"
        private const val ACTION_STOP = "com.miseservice.cameramjpeg.action.STOP"
        private const val ACTION_SWITCH_CAMERA = "com.miseservice.cameramjpeg.action.SWITCH_CAMERA"
        private const val ACTION_UPDATE_QUALITY = "com.miseservice.cameramjpeg.action.UPDATE_QUALITY"
        private const val ACTION_UPDATE_WAKE_MODE = "com.miseservice.cameramjpeg.action.UPDATE_WAKE_MODE"

        fun startIntent(context: Context, port: Int, useFront: Boolean, quality: StreamQuality, keepAwake: Boolean): Intent {
            return Intent(context, MjpegStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USE_FRONT, useFront)
                putExtra(EXTRA_QUALITY, quality.name)
                putExtra(EXTRA_KEEP_AWAKE, keepAwake)
            }
        }

        fun stopIntent(context: Context): Intent = Intent(context, MjpegStreamingService::class.java).apply {
            action = ACTION_STOP
        }

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

