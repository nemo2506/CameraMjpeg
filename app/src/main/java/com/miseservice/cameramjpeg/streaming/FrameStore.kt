package com.miseservice.cameramjpeg.streaming

/**
 * FrameStore
 *
 * Thread-safe store for the latest JPEG frame and its sequence number.
 * Used to publish and retrieve the most recent frame for streaming.
 */
class FrameStore {
    private val lock = Object()
    @Volatile
    private var latestFrame: ByteArray? = null
    @Volatile
    private var sequence: Long = 0

    /**
     * Publish a new JPEG frame and increment the sequence number.
     * Notifies all waiting threads.
     *
     * @param jpeg The JPEG frame to publish
     */
    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            latestFrame = jpeg
            sequence += 1
            lock.notifyAll()
        }
    }

    /**
     * Get the latest published JPEG frame.
     *
     * @return The latest JPEG frame, or null if none
     */
    fun latest(): ByteArray? = latestFrame

    /**
     * Wait for the next frame after a given sequence number, or until timeout.
     *
     * @param lastKnownSequence The last known sequence number
     * @param timeoutMs Timeout in milliseconds
     * @return Pair of new sequence number and frame, or null if timeout or no frame
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
