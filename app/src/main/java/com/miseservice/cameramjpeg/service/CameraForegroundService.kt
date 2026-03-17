package com.miseservice.cameramjpeg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * CameraForegroundService
 *
 * Foreground service for MJPEG camera streaming.
 * Ensures the camera remains active and protected from Doze/sleep by acquiring a WakeLock.
 * Displays a persistent notification to comply with Android foreground service requirements.
 *
 * Usage:
 * - Declare in AndroidManifest.xml with foregroundServiceType="camera".
 * - Start from MainActivity or CameraStreamController.
 * - Notification channel is created for Android O+ (API 26+).
 * - Use ACTION_STOP intent to stop the service and remove the notification.
 *
 * WakeLock:
 * - Acquired in onCreate() with a 1-hour timeout for safety.
 * - Released in onDestroy() to avoid battery drain.
 * - Requires android.permission.WAKE_LOCK in the manifest.
 *
 * Foreground Notification:
 * - Uses NotificationCompat for compatibility.
 * - Channel: camera_foreground_channel (importance DEFAULT).
 * - Not dismissible, protects streaming from OS background restrictions.
 *
 * API Compatibility:
 * - Uses ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA on API 30+.
 * - Fallback to classic startForeground for lower APIs.
 *
 * @author CameraMjpeg contributors
 * @since 2026-03-17
 */
class CameraForegroundService : Service() {
    // WakeLock for keeping device awake during streaming
    private var wakeLock: PowerManager.WakeLock? = null
    companion object {
        /** Notification channel ID for foreground service. */
        const val CHANNEL_ID = "camera_foreground_channel"
        /** Notification ID for foreground notification. */
        const val NOTIFICATION_ID = 1001
        /** Intent action to stop foreground service. */
        const val ACTION_STOP = "com.miseservice.cameramjpeg.STOP_FOREGROUND"
    }

    /**
     * Called when the service is created.
     * Sets up notification channel and acquires WakeLock.
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Acquire WakeLock (1 hour timeout for safety)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraStream::WakeLock")
        wakeLock?.acquire(60 * 60 * 1000)
    }

    /**
     * Called when the service is started.
     * Starts foreground notification, handles stop action.
     * @param intent Intent for service action.
     * @param flags Service flags.
     * @param startId Service start ID.
     * @return Service start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()

        // Android 10+ requires foreground service type for camera
        if (Build.VERSION.SDK_INT >= 30) {
            // Only call on API 30+
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    /** No binding supported. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the service is destroyed.
     * Releases WakeLock to avoid battery drain.
     */
    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Called if the system kills the service (START_STICKY relaunches automatically).
     * Optionally clean up camera resources here.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Optionally clean up camera resources
    }

    /**
     * Builds the persistent notification for foreground service.
     * @return Notification instance.
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming Caméra MJPEG")
            .setContentText("Streaming actif — protégé contre la mise en veille.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Creates the notification channel for Android O+ (API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Caméra",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
