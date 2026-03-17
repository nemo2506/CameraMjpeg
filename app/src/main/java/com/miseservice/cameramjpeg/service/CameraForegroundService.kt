package com.miseservice.cameramjpeg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Service de premier plan pour maintenir le streaming caméra actif (Doze/wakelock).
 * Affiche une notification persistante pendant le streaming.
 *
 * - Déclaré dans AndroidManifest.xml avec foregroundServiceType="camera".
 * - Démarré depuis MainActivity ou CameraStreamController.
 * - Canal de notification créé pour Android O+.
 * - Utiliser ACTION_STOP pour arrêter le service et retirer la notification.
 */
class CameraForegroundService : Service() {
    companion object {
        /** ID du canal de notification pour le service de premier plan. */
        const val CHANNEL_ID = "camera_foreground_channel"
        /** ID de la notification de premier plan. */
        const val NOTIFICATION_ID = 1001
        /** Action d'intent pour arrêter le service. */
        const val ACTION_STOP = "com.miseservice.cameramjpeg.STOP_FOREGROUND"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Gérer l'action d'arrêt
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()

        // Android 10+ exige le type de service de premier plan pour la caméra
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    override fun onBind(intent: Intent?): IBinder? = null

    // Appelé si le système tue le service — START_STICKY le relance automatiquement
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Optionnel : nettoyer les ressources caméra ici
    }

    /**
     * Construit la notification persistante pour le service de premier plan.
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming Caméra MJPEG")
            .setContentText("Streaming actif — protégé contre la mise en veille.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            // Empêche la notification d'être balayée sur les anciens APIs
            .setAutoCancel(false)
            // Priorité normale pour résister à la rétrogradation par l'OS
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Crée le canal de notification pour Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Caméra",
                // LOW permet au système de supprimer ; utiliser DEFAULT pour la caméra
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
