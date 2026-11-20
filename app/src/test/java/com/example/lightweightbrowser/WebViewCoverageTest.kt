package com.example.lightweightbrowser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the pure helpers used by the WebView interception path. */
class WebViewCoverageTest {

    @Test
    fun pageUrlPrefersRefererAndIsCaseInsensitive() {
        val pageUrl = RequestContext.pageUrl(
            mapOf("referer" to "https://news.example.com/article"),
            "https://portal.example.com/"
        )
        assertEquals("https://news.example.com/article", pageUrl)
    }

    @Test
    fun pageUrlFallsBackToTabUrl() {
        assertEquals(
            "https://portal.example.com/",
            RequestContext.pageUrl(emptyMap(), "https://portal.example.com/")
        )
        assertEquals("", RequestContext.pageUrl(emptyMap(), null))
    }

    @Test
    fun hostOfNormalizesCaseAndTrailingDot() {
        assertEquals("news.example.com", RequestContext.hostOf("https://NEWS.Example.com./article"))
        assertEquals(null, RequestContext.hostOf("not a url"))
    }

    @Test
    fun resourceTypeUsesRequestMetadata() {
        assertEquals("document", ResourceType.from(true, "/ad.js", emptyMap()))

        assertEquals(
            "xmlhttprequest",
            ResourceType.from(false, "/data", mapOf("x-requested-with" to "XMLHttpRequest"))
        )
        assertEquals(
            "xmlhttprequest",
            ResourceType.from(false, "/data", mapOf("Sec-Fetch-Dest" to "empty"))
        )
        assertEquals(
            "stylesheet",
            ResourceType.from(false, "/style", mapOf("sec-fetch-dest" to "style"))
        )
        assertEquals("script", ResourceType.from(false, "/bundle.min.js", emptyMap()))
        assertEquals("image", ResourceType.from(false, "/pixel", mapOf("accept" to "image/avif")))
        assertEquals("media", ResourceType.from(false, "/clip.m3u8", emptyMap()))
        assertEquals("other", ResourceType.from(false, "/config", emptyMap()))
    }

    @Test
    fun blockedResponsesAreBenignAndNotCacheable() {
        val script = BlockedResponses.spec("script")
        assertEquals("application/javascript", script.mimeType)
        assertEquals(200, script.statusCode)
        assertArrayEquals(ByteArray(0), script.body)
        assertEquals("no-store", script.headers["Cache-Control"])

        val stylesheet = BlockedResponses.spec("stylesheet")
        assertEquals("text/css", stylesheet.mimeType)
        assertArrayEquals(ByteArray(0), stylesheet.body)

        val image = BlockedResponses.spec("image")
        assertEquals("image/gif", image.mimeType)
        assertEquals(null, image.encoding)
        assertEquals(42, image.body.size)
        assertEquals("GIF89a", String(image.body.copyOfRange(0, 6), Charsets.US_ASCII))

        val xhr = BlockedResponses.spec("xmlhttprequest")
        assertEquals("application/json", xhr.mimeType)
        assertEquals("{}", String(xhr.body, Charsets.UTF_8))

        assertTrue(BlockedResponses.spec("other").statusCode == 200)
    }
}
