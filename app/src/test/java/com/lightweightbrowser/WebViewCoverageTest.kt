package com.lightweightbrowser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun navigationPolicyOnlyInterceptsWhenNecessary() {
        val plainHttps = NavigationDecision.of("https://example.com/page", false)
        assertFalse(plainHttps.intercept)
        assertFalse(plainHttps.upgradeToHttps)
        assertFalse(plainHttps.openExternally)

        val upgrade = NavigationDecision.of("http://example.com/page", false)
        assertTrue(upgrade.intercept)
        assertTrue(upgrade.upgradeToHttps)

        val blocked = NavigationDecision.of("https://ads.example.net/banner", true)
        assertTrue(blocked.intercept)
        assertFalse(blocked.upgradeToHttps)

        val external = NavigationDecision.of("mailto:hello@example.com", false)
        assertTrue(external.intercept)
        assertTrue(external.openExternally)

        assertFalse(NavigationDecision.of(null, false).intercept)
    }

    @Test
    fun cosmeticFrameCleanupUsesExactHostAndPathLabels() {
        val js = AdBlocker.cosmeticJsFor("example.com")
        assertTrue(js.contains("adHostLabels"))
        assertTrue(js.contains("adPathSegments"))
        assertTrue(js.contains("__lwLocalScriptlets"))
        assertFalse(js.contains("indexOf(adWords"))
        assertFalse(js.contains("iframe[src*="))
    }

    @Test
    fun privacyScriptCanToggleWebRtcBlocking() {
        val blocked = AdBlocker.privacyJs(true)
        val allowed = AdBlocker.privacyJs(false)
        assertTrue(blocked.contains("if (true)"))
        assertTrue(allowed.contains("if (false)"))
        assertTrue(blocked.contains("__lwRtcOriginal"))
    }

    @Test
    fun mediaMonitorUsesScopedMessageBridge() {
        val js = AdBlocker.mediaMonitorJs()
        assertTrue(js.contains("LWBridge.postMessage(playing ? 'media:1' : 'media:0')"))
        assertFalse(js.contains("LWBridge.onMediaState"))
    }

    @Test
    fun localAndLanHostsAreExemptFromHttpsUpgrade() {
        assertTrue(RequestContext.isLocalNetworkHost("localhost"))
        assertTrue(RequestContext.isLocalNetworkHost("127.0.0.1"))
        assertTrue(RequestContext.isLocalNetworkHost("192.168.1.10"))
        assertTrue(RequestContext.isLocalNetworkHost("10.0.0.5"))
        assertTrue(RequestContext.isLocalNetworkHost("172.20.1.2"))
        assertTrue(RequestContext.isLocalNetworkHost("printer.local"))
        assertFalse(RequestContext.isLocalNetworkHost("example.com"))
        assertFalse(RequestContext.isLocalNetworkHost("8.8.8.8"))
    }

    @Test
    fun inactiveTabsOnlyPauseVideo() {
        val js = AdBlocker.pauseBackgroundVideosJs()
        assertTrue(js.contains("querySelectorAll('video')"))
        assertFalse(js.contains("audio"))
    }

    @Test
    fun patternMatcherFollowsFailureChain() {
        val core = KotlinBlockerCore().apply {
            init(mapOf("block_patterns.txt" to listOf("aa", "bbaaa")))
        }
        assertTrue(core.shouldBlock("https://example.com/bbaabcbcac", false, "news.test"))
    }

    @Test
    fun cosmeticCssHasNoSelectorSweepLoop() {
        val js = AdBlocker.cosmeticJsFor("example.com")
        assertFalse(js.contains("var apply = function ()"))
        assertFalse(js.contains("querySelectorAll(selectorText)"))
    }

    @Test
    fun youtubeWatchHtmlAdArraysAreStripped() {
        val html = """var cfg={"videoDetails":{"title":"x"},"adPlacements":[{"ad":"a"}],"playerAds":[{"id":1}],"nested":{"x":"]"}};"""
        val stripped = AdBlocker.stripYouTubeAdsFromHtml(html)
        assertFalse(stripped.contains("\"adPlacements\":[{\"ad\""))
        assertTrue(stripped.contains("\"adPlacements\":[]"))
        assertTrue(stripped.contains("\"playerAds\":[]"))
        assertTrue(stripped.contains("\"nested\":{\"x\":\"]\"}"))
    }

    @Test
    fun youtubeAdStrippingScriptIsGatedAndTargetsPlayerResponse() {
        val script = AdBlocker.youtubeAdScript(true)
        assertTrue(script.contains("adPlacements"))
        assertTrue(script.contains("playerAds"))
        assertTrue(script.contains("youtubei/v1/player"))
        assertTrue(script.contains("bootstrapPlayerResponse"))
        assertTrue(script.contains("youtubei/v1/get_watch"))
        assertTrue(script.contains("ytp-ad-module"))
        assertTrue(script.contains("ytm-companion-slot"))
        assertEquals("", AdBlocker.youtubeAdScript(false))
    }

    @Test
    fun watchseriesBarGetsTargetedAdCleanup() {
        val js = AdBlocker.cosmeticJsFor("watchseries.bar")
        assertTrue(js.contains("hdaujcw.online"))
        assertTrue(js.contains("krikya11.me"))
        assertTrue(js.contains("bk666"))
        assertFalse(AdBlocker.cosmeticJsFor("example.com").contains("hdaujcw.online"))
    }
}
