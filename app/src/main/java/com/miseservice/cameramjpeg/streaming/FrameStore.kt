package com.miseservice.cameramjpeg.streaming

class FrameStore {
    private val lock = Object()
    @Volatile
    private var latestFrame: ByteArray? = null
    @Volatile
    private var sequence: Long = 0

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            latestFrame = jpeg
            sequence += 1
            lock.notifyAll()
        }
    }

    fun latest(): ByteArray? = latestFrame

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

