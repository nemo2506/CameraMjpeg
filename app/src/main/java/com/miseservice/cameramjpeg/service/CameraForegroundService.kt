package com.miseservice.cameramjpeg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep the camera streaming active and prevent Doze/wakelock issues.
 * Shows a persistent notification while streaming is active.
 *
 * @constructor Default constructor for Service.
 * @see android.app.Service
 *
 * Usage:
 * - Declared in AndroidManifest.xml with foregroundServiceType="camera".
 * - Started from MainActivity or CameraStreamController.
 * - Notification channel created for Android O+.
 * - Use ACTION_STOP to stop the service and remove notification.
 */
class CameraForegroundService : Service() {
    companion object {
        /** Notification channel ID for foreground service. */
        const val CHANNEL_ID = "camera_foreground_channel"
        /** Notification ID for foreground notification. */
        const val NOTIFICATION_ID = 1001
        /** Intent action to stop foreground service. */
        const val ACTION_STOP = "com.miseservice.cameramjpeg.STOP_FOREGROUND"
    }

    /** Called when the service is created. Sets up notification channel. */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    /** No binding supported. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Builds the persistent notification for foreground service.
     * @return Notification instance.
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera MJPEG Streaming")
            .setContentText("Streaming is active and protected from sleep mode.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    /**
     * Creates the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))
                ?.createNotificationChannel(channel)
        }
    }
}
