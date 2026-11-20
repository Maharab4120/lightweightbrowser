package com.example.lightweightbrowser

import android.content.Context
import android.webkit.WebResourceRequest
import java.util.Locale

/**
 * Privacy shield: compact subsets of Brave's public filter lists, EasyList,
 * EasyPrivacy and uBO filters compiled into local assets. Everything is parsed
 * once at startup; no network, no filter-list downloads, no background services.
 *
 * Network decisions live in [KotlinBlockerCore] so they can be unit-tested and
 * compared with the optional Rust engine. This object keeps the cosmetic and
 * media JavaScript plus the public API used by the browser.
 */
object AdBlocker {

    private val core = KotlinBlockerCore()

    private val genericSelectors = ArrayList<String>()
    private val genericExceptions = HashSet<String>()
    private val domainSelectors = HashMap<String, MutableList<String>>()
    private val domainCosmeticExceptions = HashMap<String, MutableList<String>>()

    // High-signal selectors that are always injected first, even if the
    // generated list is truncated.
    private val extraCosmeticSelectors = listOf(
        "[id*=\"ad-banner\"]", "[class*=\"ad-banner\"]",
        "[id*=\"ad-container\"]", "[class*=\"ad-container\"]",
        "[id*=\"ad-overlay\"]", "[class*=\"ad-overlay\"]",
        "[id*=\"ad-popup\"]", "[class*=\"ad-popup\"]",
        "[id*=\"ad-slot\"]", "[class*=\"ad-slot\"]",
        "[id*=\"advert\"]", "[class*=\"advert\"]",
        "[id*=\"banner-ad\"]", "[class*=\"banner-ad\"]",
        "[id*=\"popup-ad\"]", "[class*=\"popup-ad\"]",
        "[data-ad-slot]", "[data-ad-client]", "[data-ad-format]",
        "iframe[src*=\"ads\"]", "iframe[src*=\"advert\"]",
        "iframe[src*=\"banner\"]", "iframe[src*=\"popup\"]",
        "iframe[src*=\"pixel\"]", "iframe[src*=\"track\"]"
    )

    // Known non-content popups/overlays on ad-heavy streaming sites.
    private val siteCosmeticSelectors = mapOf(
        "watchseries.moe" to listOf(
            "#ws-subscribe", ".ws-subscribe", "#ws-notify", ".ws-notify",
            ".ws-log-moved", "#ws-log-tip"
        ),
        "watchseries.*" to listOf(
            "#ws-subscribe", ".ws-subscribe", ".subscribe-popup", ".popunder-overlay"
        )
    )

    private val privacyJs = """
        try {
            if (!document.getElementById('__lw_referrer')) {
                var referrer = document.createElement('meta');
                referrer.id = '__lw_referrer';
                referrer.name = 'referrer';
                referrer.content = 'no-referrer';
                (document.head || document.documentElement).appendChild(referrer);
            }
        } catch (e) {}
        try {
            if (navigator.doNotTrack !== '1') {
                Object.defineProperty(navigator, 'doNotTrack', {
                    configurable: true, get: function () { return '1'; }
                });
            }
        } catch (e) {}
        try {
            Object.defineProperty(navigator, 'globalPrivacyControl', {
                configurable: true, get: function () { return true; }
            });
        } catch (e) {}
        try {
            Object.defineProperty(window, 'RTCPeerConnection', {
                configurable: true, value: undefined
            });
        } catch (e) {}
    """.trimIndent()

    fun init(context: Context) {
        val networkAssets = listOf(
            "allow_hosts.txt", "block_hosts.txt", "third_party_hosts.txt",
            "domain_hosts.txt", "allow_patterns.txt", "block_patterns.txt",
            "third_party_patterns.txt"
        )
        core.init(networkAssets.associateWith { loadLines(context, it) })

        loadLines(context, "cosmetic_rules.txt") { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) return@loadLines
            val prefix = parts[0].trim()
            val selector = parts[1].trim()
            if (selector.isEmpty()) return@loadLines

            when {
                prefix.startsWith("!") -> {
                    val domains = prefix.substring(1)
                    if (domains.isEmpty() || domains == "*") {
                        genericExceptions.add(selector)
                    } else {
                        domains.split(',').forEach { domain ->
                            if (domain.isNotEmpty()) {
                                domainCosmeticExceptions
                                    .getOrPut(domain) { ArrayList() }
                                    .add(selector)
                            }
                        }
                    }
                }
                prefix == "*" -> genericSelectors.add(selector)
                else -> prefix.split(',').forEach { domain ->
                    if (domain.isNotEmpty()) {
                        domainSelectors.getOrPut(domain) { ArrayList() }.add(selector)
                    }
                }
            }
        }
    }

    private fun loadLines(context: Context, assetName: String): List<String> {
        val result = ArrayList<String>()
        loadLines(context, assetName) { result.add(it) }
        return result
    }

    private fun loadLines(context: Context, assetName: String, consume: (String) -> Unit) {
        try {
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim().lowercase(Locale.ROOT)
                    if (line.isNotEmpty() && !line.startsWith("#")) consume(line)
                }
            }
        } catch (e: Exception) {
            // Missing/corrupt asset: the small fallback lists still work.
        }
    }

    fun setEnabled(value: Boolean) {
        core.setEnabled(value)
    }

    fun isEnabled(): Boolean = core.isEnabled()

    /** Injected at page start so scripts see privacy signals before they run. */
    fun privacyJs(): String = """
        (function () {
            if (window.__lwPrivacyInjected) return;
            window.__lwPrivacyInjected = true;
            $privacyJs
        })();
    """.trimIndent()

    /**
     * Watches HTML5 media so the foreground service can run only while a page
     * is actually playing.
     */
    fun mediaMonitorJs(): String = """
        (function () {
            if (window.__lwMediaMonitor) return;
            window.__lwMediaMonitor = true;
            function report() {
                var playing = false;
                var media = document.querySelectorAll('video,audio');
                for (var i = 0; i < media.length; i++) {
                    var m = media[i];
                    if (!m.paused && !m.ended && m.currentTime > 0) {
                        playing = true;
                        break;
                    }
                }
                try { LWBridge.onMediaState(playing); } catch (e) {}
            }
            document.addEventListener('play', report, true);
            document.addEventListener('pause', report, true);
            document.addEventListener('ended', report, true);
            setInterval(report, 2000);
            report();
        })();
    """.trimIndent()

    /**
     * Play/pause from a notification control. Pause also blocks the page's own
     * play() calls until Play is pressed, otherwise a site such as YouTube
     * immediately resumes itself.
     */
    fun mediaControlJs(action: String): String {
        if (action == "pause") {
            return """
                (function () {
                    window.__lwForcePaused = true;
                    var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
                    if (proto && !window.__lwPatchedPlay) {
                        window.__lwPatchedPlay = true;
                        window.__lwOrigPlay = proto.play;
                        proto.play = function () {
                            if (window.__lwForcePaused) {
                                try { return Promise.resolve(); } catch (e) { return undefined; }
                            }
                            return window.__lwOrigPlay.apply(this, arguments);
                        };
                    }
                    var media = document.querySelectorAll('video,audio');
                    for (var i = 0; i < media.length; i++) {
                        try { media[i].pause(); } catch (e) {}
                    }
                })();
            """.trimIndent()
        }
        return """
            (function () {
                window.__lwForcePaused = false;
                var media = document.querySelectorAll('video,audio');
                for (var i = 0; i < media.length; i++) {
                    try { media[i].play(); } catch (e) {}
                }
            })();
        """.trimIndent()
    }

    fun shouldBlock(request: WebResourceRequest, pageHost: String? = null): Boolean {
        if (request.isForMainFrame) return false
        return shouldBlock(request.url.toString(), isForMainFrame = false, pageHost = pageHost)
    }

    fun shouldBlock(
        url: String,
        isForMainFrame: Boolean,
        pageHost: String? = null
    ): Boolean = core.shouldBlock(url, isForMainFrame, pageHost)

    /**
     * Top-level ad redirects. Only host rules are used here; URL fragments are
     * too broad for full-page navigation. The page's own host is never blocked.
     */
    fun shouldBlockNavigation(url: String, pageHost: String?): Boolean =
        core.shouldBlockNavigation(url, pageHost)

    private fun hostMatchesDomain(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }

    /** Cosmetic CSS injected after each page load. */
    fun cosmeticJsFor(host: String): String {
        val selectors = LinkedHashSet<String>()
        selectors.addAll(extraCosmeticSelectors)
        selectors.addAll(genericSelectors)
        for ((domain, rules) in domainSelectors) {
            if (hostMatchesDomain(host, domain)) {
                selectors.addAll(rules)
            }
        }
        for ((domain, rules) in siteCosmeticSelectors) {
            val matches = when {
                domain == "*" -> true
                domain.endsWith(".*") -> host.startsWith(domain.removeSuffix(".*") + ".")
                else -> hostMatchesDomain(host, domain)
            }
            if (matches) selectors.addAll(rules)
        }
        selectors.removeAll(genericExceptions)
        for ((domain, rules) in domainCosmeticExceptions) {
            if (hostMatchesDomain(host, domain)) {
                selectors.removeAll(rules)
            }
        }

        val chosen = selectors.take(2200)
        val selectorList = chosen.joinToString(",")
        val css = if (selectorList.isEmpty()) {
            ""
        } else {
            "$selectorList{display:none!important;visibility:hidden!important;height:0!important;overflow:hidden!important;}"
        }

        return """
            (function () {
                if (window.__lwShieldInjected) return;
                window.__lwShieldInjected = true;

                var cssText = ${jsString(css)};
                var selectorText = ${jsString(selectorList)};

                if (cssText && !document.getElementById('__lw_shield_style')) {
                    var style = document.createElement('style');
                    style.id = '__lw_shield_style';
                    style.type = 'text/css';
                    style.textContent = cssText;
                    (document.head || document.documentElement).appendChild(style);
                }

                var adWords = ['ads', 'advert', 'banner', 'popup', 'popunder', 'sponsor', 'pixel', 'track'];
                var hasAdWord = function (value) {
                    var lower = String(value || '').toLowerCase();
                    for (var k = 0; k < adWords.length; k++) {
                        if (lower.indexOf(adWords[k]) >= 0) return true;
                    }
                    return false;
                };

                var cleanupFrames = function () {
                    var frames = document.querySelectorAll('iframe,frame,embed,object');
                    for (var i = 0; i < frames.length; i++) {
                        var frame = frames[i];
                        var src = frame.src || frame.getAttribute('src') ||
                            frame.getAttribute('data-src') || '';
                        var bad = hasAdWord(src);
                        if (!bad) {
                            try {
                                bad = hasAdWord(new URL(src, window.location.href).hostname);
                            } catch (e) {}
                        }
                        if (bad && frame.parentNode) frame.parentNode.removeChild(frame);
                    }
                };
                cleanupFrames();

                var apply = function () {
                    cleanupFrames();
                    if (!selectorText) return;
                    try {
                        var nodes = document.querySelectorAll(selectorText);
                        for (var i = 0; i < nodes.length; i++) {
                            nodes[i].style.setProperty('display', 'none', 'important');
                            nodes[i].style.setProperty('visibility', 'hidden', 'important');
                        }
                    } catch (e) {}
                };
                apply();

                if (window.MutationObserver && document.documentElement) {
                    var scheduled = false;
                    new MutationObserver(function () {
                        if (scheduled) return;
                        scheduled = true;
                        var run = function () { scheduled = false; apply(); };
                        if (window.requestAnimationFrame) window.requestAnimationFrame(run);
                        else setTimeout(run, 50);
                    }).observe(document.documentElement, {childList: true, subtree: true});
                }
            })();
            ${siteScriptFor(host)}
        """.trimIndent()
    }

    /**
     * Site-specific helpers injected alongside the cosmetic CSS.
     * watchseries.moe shows a first-party ad interstitial that must be clicked
     * to start the video. We click it automatically (the popup it opens is
     * swallowed by onCreateWindow) so the user never sees the ad.
     */
    private fun siteScriptFor(host: String): String {
        if (host != "watchseries.moe" && !host.endsWith(".watchseries.moe")) return ""
        return """
            (function () {
                if (window.__lwWsAutoClick) return;
                window.__lwWsAutoClick = true;
                var done = false;
                var tryClick = function () {
                    if (done) return true;
                    var el = document.getElementById('ws-interstitial');
                    if (!el || !el.style) return false;
                    var display = String(el.style.display || '').toLowerCase();
                    if (display !== 'flex' && display !== 'block') return false;
                    done = true;
                    setTimeout(function () {
                        try { el.click(); } catch (e) {}
                        try { el.style.display = 'none'; } catch (e) {}
                    }, 200);
                    return true;
                };
                if (tryClick()) return;
                var observer = new MutationObserver(function () {
                    if (tryClick()) observer.disconnect();
                });
                observer.observe(document.documentElement || document, {
                    childList: true, subtree: true, attributes: true,
                    attributeFilter: ['style']
                });
            })();
        """.trimIndent()
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
        append('"')
    }
}
