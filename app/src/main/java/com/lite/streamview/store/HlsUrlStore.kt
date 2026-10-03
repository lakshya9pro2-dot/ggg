package com.lite.streamview.store

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class HlsStream(
    val url: String,
    val contentType: String = "application/vnd.apple.mpegurl",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe in-memory store for detected HLS streams.
 * Enforces first-detected stream persistence per session to avoid overwriting
 * the primary master playlist with subsequent child/variant playlists.
 */
class HlsUrlStore {
    private val firstHls = AtomicReference<HlsStream?>(null)
    private val latch = AtomicReference(CountDownLatch(1))
    private var onHlsDetectedListener: ((HlsStream) -> Unit)? = null

    fun setListener(listener: ((HlsStream) -> Unit)?) {
        this.onHlsDetectedListener = listener
    }

    /**
     * Atomically records the first HLS stream detected for the session.
     * If an HLS stream has already been captured, subsequent streams (e.g. child
     * playlists, resolution variants, segment refreshes) are ignored.
     *
     * @return true if this was the first stream and was saved; false if ignored.
     */
    fun setLatestHls(stream: HlsStream): Boolean {
        if (firstHls.compareAndSet(null, stream)) {
            latch.get().countDown()
            onHlsDetectedListener?.invoke(stream)
            return true
        }
        return false
    }

    fun getLatestHls(): HlsStream? = firstHls.get()

    fun hasHls(): Boolean = firstHls.get() != null

    @Synchronized
    fun clear() {
        firstHls.set(null)
        val oldLatch = latch.getAndSet(CountDownLatch(1))
        oldLatch.countDown()
    }

    /**
     * Prepares the store for a new navigation session by resetting the first HLS
     * stream and returning a fresh latch to avoid race conditions with asynchronous loading.
     */
    @Synchronized
    fun prepareForNewStream(): CountDownLatch {
        firstHls.set(null)
        val newLatch = CountDownLatch(1)
        val oldLatch = latch.getAndSet(newLatch)
        oldLatch.countDown()
        return newLatch
    }

    /**
     * Waits up to [timeoutSeconds] for the first HLS stream to be detected.
     * Useful for synchronous extraction calls e.g. /extract?url=...
     */
    fun waitForHls(timeoutSeconds: Long = 5, targetLatch: CountDownLatch? = null): HlsStream? {
        firstHls.get()?.let { return it }
        val l = targetLatch ?: latch.get()
        try {
            l.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return firstHls.get()
    }
}
