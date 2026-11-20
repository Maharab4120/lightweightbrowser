package com.example.lightweightbrowser

import android.content.Context
import android.webkit.WebResourceRequest
import java.net.URI

data class BlockerRequest(
    val url: String,
    val pageUrl: String,
    val pageHost: String?,
    val resourceType: String,
    val method: String,
    val isForMainFrame: Boolean
)

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
        rustRequested = false
        rustActive = false
        rustLoading = false
    }

    fun loadRust(context: Context, onResult: ((Boolean, String) -> Unit)?) {
        if (rustLoading) return
        rustRequested = true
        rustLoading = true

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

            rustActive = success
            rustLoading = false
            if (!success) rustRequested = false
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
                "Document",
                "GET"
            )
        } else {
            AdBlocker.shouldBlockNavigation(url, pageHost)
        }
        if (blocked) return !isRetryingTooOften(pageHost, "nav:$url")
        return false
    }

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
    fun from(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "Document"

        val path = request.url.path?.lowercase().orEmpty()
        val accept = request.requestHeaders["Accept"]?.lowercase().orEmpty()
        if (request.requestHeaders["X-Requested-With"]?.contains("XMLHttpRequest", true) == true) {
            return "Xmlhttprequest"
        }
        if (path.hasExtension(".js", ".mjs", ".cjs") || accept.contains("javascript")) return "Script"
        if (path.hasExtension(".css") || accept.contains("text/css")) return "Stylesheet"
        if (path.hasExtension(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif") ||
            accept.startsWith("image/")
        ) return "Image"
        if (path.hasExtension(".mp4", ".webm", ".mp3", ".ogg", ".m4a", ".wav") ||
            accept.startsWith("media/")
        ) return "Media"
        if (path.hasExtension(".woff", ".woff2", ".ttf", ".otf", ".eot") || accept.contains("font")) {
            return "Font"
        }
        return "Other"
    }

    private fun String.hasExtension(vararg suffixes: String): Boolean =
        suffixes.any { endsWith(it) }
}
