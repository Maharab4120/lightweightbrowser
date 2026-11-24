package com.lightweightbrowser

import android.content.Context
import android.webkit.WebResourceRequest
import java.net.URI
import java.util.Locale

data class BlockerRequest(
    val url: String,
    val pageUrl: String,
    val pageHost: String?,
    val resourceType: String,
    val method: String,
    val isForMainFrame: Boolean
)

/** Pure helpers shared by the WebView layer and JVM tests. */
internal object RequestContext {

    /**
     * WebView does not expose an initiator. The Referer header is the best
     * available signal for subframes and XHR; fall back to the tab URL.
     */
    fun pageUrl(headers: Map<String, String>, tabUrl: String?): String {
        val referer = header(headers, "Referer")
        return if (!referer.isNullOrBlank()) referer else tabUrl.orEmpty()
    }

    /** WebView header maps are not guaranteed to preserve header casing. */
    fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun hostOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return try {
            URI(url).host?.lowercase(Locale.ROOT)?.trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    /** Localhost/LAN hosts are left on HTTP so local dev servers still work. */
    fun isLocalNetworkHost(host: String?): Boolean {
        val value = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (value == "localhost" || value.endsWith(".localhost") ||
            value.endsWith(".local")
        ) return true
        if (value.contains(':')) return value == "::1" || value.startsWith("fe80:")
        val parts = value.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 127 || parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}

internal data class BlockedResponseSpec(
    val mimeType: String,
    val encoding: String?,
    val statusCode: Int,
    val reason: String,
    val body: ByteArray,
    val headers: Map<String, String> = mapOf("Cache-Control" to "no-store")
)

/**
 * Pure navigation policy. WebView should keep ownership of ordinary HTTPS
 * navigations; hand-written loadUrl calls drop request context and history.
 */
internal data class NavigationAction(
    val intercept: Boolean,
    val upgradeToHttps: Boolean,
    val openExternally: Boolean
)

internal object NavigationDecision {

    fun of(url: String?, blocked: Boolean): NavigationAction {
        if (url.isNullOrEmpty()) return NavigationAction(false, false, false)
        if (blocked) return NavigationAction(true, false, false)

        return when {
            url.startsWith("mailto:", ignoreCase = true) ||
                url.startsWith("tel:", ignoreCase = true) ->
                NavigationAction(true, false, true)

            url.startsWith("http://", ignoreCase = true) ->
                NavigationAction(true, true, false)

            // Already HTTPS: return false so WebView preserves Referer,
            // user-gesture state and the back stack.
            else -> NavigationAction(false, false, false)
        }
    }
}

/** Benign empty responses keep blocked subresources from retrying in a loop. */
internal object BlockedResponses {

    private val transparentGif = byteArrayOf(
        71, 73, 70, 56, 57, 97, 1, 0, 1, 0, 128.toByte(), 0, 0, 0, 0, 0,
        255.toByte(), 255.toByte(), 255.toByte(), 33, 249.toByte(), 4, 1, 0, 0, 0, 0, 44,
        0, 0, 0, 0,
        1, 0, 1, 0, 0, 2, 1, 68, 0, 59
    )

    fun spec(resourceType: String): BlockedResponseSpec = when (resourceType.lowercase(Locale.ROOT)) {
        "script" -> BlockedResponseSpec("application/javascript", "UTF-8", 200, "OK", ByteArray(0))
        "stylesheet" -> BlockedResponseSpec("text/css", "UTF-8", 200, "OK", ByteArray(0))
        "image" -> BlockedResponseSpec("image/gif", null, 200, "OK", transparentGif.copyOf())
        "xmlhttprequest" -> BlockedResponseSpec(
            "application/json", "UTF-8", 200, "OK", "{}".toByteArray(Charsets.UTF_8)
        )
        else -> BlockedResponseSpec("text/plain", "UTF-8", 200, "OK", ByteArray(0))
    }
}

interface BlockerEngine {
    val displayName: String
    fun shouldBlock(request: BlockerRequest): Boolean
}

/** Thin JNI wrapper around Brave's adblock-rust engine. */
object RustAdBlocker {

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("lw_adblock")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    external fun nativeInit(filters: String): Boolean
    external fun nativeShouldBlock(
        url: String,
        pageUrl: String,
        resourceType: String,
        method: String
    ): Boolean
    external fun nativeStats(): String
}

private class KotlinBlockerEngine : BlockerEngine {
    override val displayName = "Kotlin fallback"

    override fun shouldBlock(request: BlockerRequest): Boolean =
        AdBlocker.shouldBlock(request.url, request.isForMainFrame, request.pageHost)
}

private class RustBlockerEngine : BlockerEngine {
    override val displayName = "Brave Rust"

    override fun shouldBlock(request: BlockerRequest): Boolean {
        if (!RustAdBlocker.available) return false
        return RustAdBlocker.nativeShouldBlock(
            request.url,
            request.pageUrl,
            request.resourceType,
            request.method
        )
    }
}

/**
 * Chooses the active network-filtering engine. The Kotlin engine is the
 * default; the Rust engine is opt-in until it passes device testing.
 */
object BlockerEngineManager {

    private val kotlinEngine = KotlinBlockerEngine()
    private val rustEngine = RustBlockerEngine()

    @Volatile
    private var rustRequested = false

    @Volatile
    private var rustLoading = false

    @Volatile
    private var rustActive = false

    private val rustLoadLock = Any()

    // Some sites retry a blocked player-config request hundreds of times. After
    // a few blocks, let the same URL through for this page so playback survives
    // and the blocked counter does not explode.
    private val recentBlocks = HashMap<String, Int>()
    private var recentBlocksResetAt = System.currentTimeMillis()

    fun init(context: Context, preferRust: Boolean) {
        AdBlocker.init(context.applicationContext)
        rustRequested = preferRust
        if (preferRust) loadRust(context.applicationContext, null)
    }

    fun setEnabled(value: Boolean) = AdBlocker.setEnabled(value)

    fun isEnabled(): Boolean = AdBlocker.isEnabled()

    fun isRustRequested(): Boolean = rustRequested

    fun isRustActive(): Boolean = rustActive

    fun activeEngineName(): String = when {
        rustActive -> rustEngine.displayName
        rustLoading -> "${rustEngine.displayName} (loading)"
        else -> kotlinEngine.displayName
    }

    fun useKotlin() {
        synchronized(rustLoadLock) {
            rustRequested = false
            rustActive = false
            rustLoading = false
        }
    }

    fun loadRust(context: Context, onResult: ((Boolean, String) -> Unit)?) {
        val shouldStart = synchronized(rustLoadLock) {
            if (rustLoading) false else {
                rustRequested = true
                rustLoading = true
                true
            }
        }
        if (!shouldStart) return

        Thread({
            var success = false
            var detail = "Rust engine library is unavailable"
            try {
                if (RustAdBlocker.available) {
                    val filters = context.assets.open("adblock_filters.txt")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    success = RustAdBlocker.nativeInit(filters)
                    detail = if (success) {
                        "Brave Rust engine active"
                    } else {
                        "Rust engine could not load bundled filters"
                    }
                }
            } catch (e: Throwable) {
                detail = "Rust engine failed: ${e.message ?: e.javaClass.simpleName}"
            }

            synchronized(rustLoadLock) {
                rustActive = success
                rustLoading = false
                if (!success) rustRequested = false
            }
            onResult?.invoke(success, detail)
        }, "blocking-engine-init").start()
    }

    fun shouldBlock(request: BlockerRequest): Boolean {
        if (!isEnabled() || request.isForMainFrame) return false
        if (!engine().shouldBlock(request)) return false
        return !isRetryingTooOften(request.pageHost, request.url)
    }

    fun shouldBlockNavigation(url: String, pageHost: String?, pageUrl: String?): Boolean {
        if (!isEnabled()) return false
        if (!pageHost.isNullOrEmpty() && hostOf(url).equals(pageHost, ignoreCase = true)) return false

        val blocked = if (rustActive && RustAdBlocker.available) {
            RustAdBlocker.nativeShouldBlock(
                url,
                pageUrl ?: "https://${pageHost ?: "unknown.invalid"}/",
                "document",
                "GET"
            )
        } else {
            AdBlocker.shouldBlockNavigation(url, pageHost)
        }
        if (blocked) return !isRetryingTooOften(pageHost, "nav:$url")
        return false
    }

    /**
     * Host-only decision for the optional DNS filter. DNS queries have no page
     * context, so this uses the conservative host rules shared with Kotlin.
     */
    fun shouldBlockDns(host: String): Boolean = AdBlocker.shouldBlockDns(host)

    fun cosmeticJsFor(host: String): String = AdBlocker.cosmeticJsFor(host)

    private fun engine(): BlockerEngine = if (rustActive && RustAdBlocker.available) rustEngine else kotlinEngine

    private fun isRetryingTooOften(pageHost: String?, url: String): Boolean {
        synchronized(recentBlocks) {
            val now = System.currentTimeMillis()
            if (now - recentBlocksResetAt > 10_000L) {
                recentBlocks.clear()
                recentBlocksResetAt = now
            }
            val key = "${pageHost ?: "*"}|$url"
            val count = (recentBlocks[key] ?: 0) + 1
            recentBlocks[key] = count
            return count > 5
        }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.trimEnd('.')
    } catch (e: Exception) {
        null
    }
}

object ResourceType {

    fun from(request: WebResourceRequest): String = from(
        isForMainFrame = request.isForMainFrame,
        path = request.url.path,
        headers = request.requestHeaders
    )

    /**
     * Pure overload so WebView classification can be exercised by JVM tests.
     * Values are the lowercase resource types expected by adblock-rust.
     */
    fun from(isForMainFrame: Boolean, path: String?, headers: Map<String, String>): String {
        if (isForMainFrame) return "document"

        val lowerPath = path?.lowercase(Locale.ROOT).orEmpty()
        val accept = RequestContext.header(headers, "Accept")?.lowercase(Locale.ROOT).orEmpty()
        val requestedWith =
            RequestContext.header(headers, "X-Requested-With")?.lowercase(Locale.ROOT).orEmpty()
        val fetchDest = RequestContext.header(headers, "Sec-Fetch-Dest")?.lowercase(Locale.ROOT).orEmpty()

        if (requestedWith.contains("xmlhttprequest")) return "xmlhttprequest"
        when (fetchDest) {
            "empty" -> return "xmlhttprequest"
            "script" -> return "script"
            "style" -> return "stylesheet"
            "image", "imageset" -> return "image"
            "font" -> return "font"
            "media" -> return "media"
            "iframe", "frame", "subdocument", "sub_frame", "document" -> return "subdocument"
            "object", "embed" -> return "object"
            "ping", "beacon" -> return "ping"
            "csp_report" -> return "csp_report"
        }

        if (lowerPath.hasExtension(".js", ".mjs", ".cjs") || accept.contains("javascript")) {
            return "script"
        }
        if (lowerPath.hasExtension(".css") || accept.contains("text/css")) return "stylesheet"
        if (lowerPath.hasExtension(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif") ||
            accept.startsWith("image/")
        ) return "image"
        if (lowerPath.hasExtension(".mp4", ".webm", ".mp3", ".ogg", ".m4a", ".wav", ".m3u8") ||
            accept.startsWith("media/")
        ) return "media"
        if (lowerPath.hasExtension(".woff", ".woff2", ".ttf", ".otf", ".eot") ||
            accept.contains("font")
        ) return "font"
        return "other"
    }

    private fun String.hasExtension(vararg suffixes: String): Boolean =
        suffixes.any { endsWith(it) }
}
