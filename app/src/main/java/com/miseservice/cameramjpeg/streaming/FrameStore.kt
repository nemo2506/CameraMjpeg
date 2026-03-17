package com.miseservice.cameramjpeg.streaming

/**
 * FrameStore
 *
 * Stocke de façon thread-safe la dernière image JPEG et son numéro de séquence.
 * Permet de publier et de récupérer la dernière image pour le streaming MJPEG.
 */
class FrameStore {
    private val lock = Object()
    @Volatile
    private var latestFrame: ByteArray? = null
    @Volatile
    private var sequence: Long = 0

    /**
     * Publie une nouvelle image JPEG et incrémente le numéro de séquence.
     * Notifie tous les threads en attente.
     *
     * @param jpeg Image JPEG à publier
     */
    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            latestFrame = jpeg
            sequence += 1
            lock.notifyAll()
        }
    }

    /**
     * Retourne la dernière image JPEG publiée.
     *
     * @return Dernière image JPEG ou null
     */
    fun latest(): ByteArray? = latestFrame

    /**
     * Attend la prochaine image après un numéro de séquence donné, ou jusqu’au timeout.
     *
     * @param lastKnownSequence Dernier numéro de séquence connu
     * @param timeoutMs Timeout en millisecondes
     * @return Paire (nouveau numéro de séquence, image) ou null si timeout ou aucune image
     */
    fun awaitNext(lastKnownSequence: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        synchronized(lock) {
            if (sequence <= lastKnownSequence) {
                lock.wait(timeoutMs)
            }
            val frame = latestFrame ?: return null
            return sequence to frame
        }
    }
}
