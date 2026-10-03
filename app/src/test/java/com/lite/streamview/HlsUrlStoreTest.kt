package com.lite.streamview

import com.lite.streamview.store.HlsStream
import com.lite.streamview.store.HlsUrlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HlsUrlStoreTest {

    private lateinit var store: HlsUrlStore

    @Before
    fun setUp() {
        store = HlsUrlStore()
    }

    @Test
    fun testInitialStoreIsEmpty() {
        assertNull(store.getLatestHls())
        assertFalse(store.hasHls())
    }

    @Test
    fun testSetAndRetrieveHls() {
        val stream = HlsStream("https://example.com/playlist.m3u8")
        val success = store.setLatestHls(stream)
        assertTrue(success)

        val retrieved = store.getLatestHls()
        assertNotNull(retrieved)
        assertEquals("https://example.com/playlist.m3u8", retrieved?.url)
        assertEquals("application/vnd.apple.mpegurl", retrieved?.contentType)
        assertTrue(store.hasHls())
    }

    @Test
    fun testCaptureFirstHlsOnly() {
        val masterStream = HlsStream("https://example.com/master.m3u8")
        val subStream1 = HlsStream("https://example.com/1080p.m3u8")
        val subStream2 = HlsStream("https://example.com/chunk_720p.m3u8")

        // First HLS stream should be stored
        val firstSaved = store.setLatestHls(masterStream)
        assertTrue(firstSaved)

        // Subsequent HLS streams should be ignored
        val secondSaved = store.setLatestHls(subStream1)
        val thirdSaved = store.setLatestHls(subStream2)
        assertFalse(secondSaved)
        assertFalse(thirdSaved)

        // The store must still contain only the first HLS stream
        assertEquals("https://example.com/master.m3u8", store.getLatestHls()?.url)
    }

    @Test
    fun testFirstHlsResetAfterClear() {
        val stream1 = HlsStream("https://example.com/master.m3u8")
        store.setLatestHls(stream1)
        assertEquals("https://example.com/master.m3u8", store.getLatestHls()?.url)

        store.clear()
        assertNull(store.getLatestHls())
        assertFalse(store.hasHls())

        val stream2 = HlsStream("https://example.com/next_page_master.m3u8")
        val saved = store.setLatestHls(stream2)
        assertTrue(saved)
        assertEquals("https://example.com/next_page_master.m3u8", store.getLatestHls()?.url)
    }

    @Test
    fun testWaitForHlsTimeout() {
        // When no HLS is added, waitForHls should return null after timeout
        val result = store.waitForHls(timeoutSeconds = 1)
        assertNull(result)
    }

    @Test
    fun testWaitForHlsConcurrentDetection() {
        val latch = CountDownLatch(1)
        var receivedStream: HlsStream? = null

        thread {
            receivedStream = store.waitForHls(timeoutSeconds = 3)
            latch.countDown()
        }

        // Simulate discovery after 200ms
        Thread.sleep(200)
        store.setLatestHls(HlsStream("https://example.com/live/master.m3u8"))

        latch.await(2, TimeUnit.SECONDS)
        assertNotNull(receivedStream)
        assertEquals("https://example.com/live/master.m3u8", receivedStream?.url)
    }

    @Test
    fun testPrepareForNewStreamSynchronization() {
        val waitLatch = store.prepareForNewStream()
        assertNull(store.getLatestHls())

        val completionLatch = CountDownLatch(1)
        var receivedStream: HlsStream? = null

        thread {
            receivedStream = store.waitForHls(timeoutSeconds = 3, targetLatch = waitLatch)
            completionLatch.countDown()
        }

        Thread.sleep(100)
        store.setLatestHls(HlsStream("https://example.com/session2/master.m3u8"))

        completionLatch.await(2, TimeUnit.SECONDS)
        assertNotNull(receivedStream)
        assertEquals("https://example.com/session2/master.m3u8", receivedStream?.url)
    }
}
