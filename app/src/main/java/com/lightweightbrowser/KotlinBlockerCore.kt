package com.example.lightweightbrowser

import java.net.URI
import java.util.ArrayDeque
import java.util.Locale

/**
 * Network-decision half of the Kotlin fallback engine.
 *
 * It has no Android dependencies so the same decisions can be exercised by
 * JVM unit tests and compared with the Rust engine over shared fixtures.
 */
internal class KotlinBlockerCore {

    private val blockHosts = HashSet<String>()
    private val allowHosts = HashSet<String>()
    private val thirdPartyHosts = HashSet<String>()
    private val domainBlockHosts = HashMap<String, HashSet<String>>()
    private val domainAllowHosts = HashMap<String, HashSet<String>>()
    private val blockMatcher = MultiPatternMatcher()
    private val allowMatcher = MultiPatternMatcher()
    private val thirdPartyMatcher = MultiPatternMatcher()

    private val essentialHosts = setOf(
        "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com",
        "googleusercontent.com", "youtube.com", "youtu.be"
    )

    // Player configuration that retries in a tight loop if blocked.
    private val compatHosts = setOf("cdn-media.brightline.tv")

    private val multiPartTlds = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au",
        "co.nz", "co.jp", "or.jp", "co.kr", "com.br", "com.cn", "com.hk",
        "com.sg", "com.tw", "co.in", "co.za"
    )

    private val fallbackHosts = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "adnxs.com", "adsrvr.org", "rubiconproject.com",
        "pubmatic.com", "criteo.com", "casalemedia.com", "openx.net", "yieldmo.com",
        "3lift.com", "adcolony.com", "applovin.com", "unityads.com", "vungle.com",
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "mixpanel.com",
        "amplitude.com", "segment.com", "fullstory.com", "taboola.com", "outbrain.com",
        "bat.bing.com", "clarity.ms", "newrelic.com", "facebook.net",
        "connect.facebook.net", "platform.twitter.com", "amazon-adsystem.com"
    )

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = true

    fun init(assetLines: Map<String, List<String>>) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            blockHosts.addAll(fallbackHosts)
            allowHosts.addAll(essentialHosts)
            allowHosts.addAll(compatHosts)

            assetLines["allow_hosts.txt"].orEmpty().forEach { allowHosts.add(it) }
            assetLines["block_hosts.txt"].orEmpty().forEach { blockHosts.add(it) }
            assetLines["third_party_hosts.txt"].orEmpty().forEach { thirdPartyHosts.add(it) }
            assetLines["domain_hosts.txt"].orEmpty().forEach { line ->
                val allow = line.startsWith("!")
                val body = if (allow) line.substring(1) else line
                val parts = body.split('\t', limit = 2)
                if (parts.size != 2) return@forEach
                val domainPart = parts[0]
                val host = parts[1]
                if (domainPart.isEmpty() || host.isEmpty()) return@forEach
                val target = if (allow) domainAllowHosts else domainBlockHosts
                domainPart.split(',').forEach { domain ->
                    if (domain.isNotEmpty()) {
                        target.getOrPut(domain) { HashSet() }.add(host)
                    }
                }
            }
            assetLines["allow_patterns.txt"].orEmpty().forEach { allowMatcher.add(it) }
            assetLines["block_patterns.txt"].orEmpty().forEach { blockMatcher.add(it) }
            assetLines["third_party_patterns.txt"].orEmpty().forEach { thirdPartyMatcher.add(it) }

            allowMatcher.build()
            blockMatcher.build()
            thirdPartyMatcher.build()
            initialized = true
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun shouldBlock(
        url: String,
        isForMainFrame: Boolean,
        pageHost: String? = null
    ): Boolean {
        if (!enabled || isForMainFrame) return false
        val parsed = parseHttpUrl(url) ?: return false
        val host = parsed.second

        if (isBlockedHost(host, pageHost)) return true
        // Allowlisted hosts win over URL-fragment rules too. This matches the
        // exception semantics used by adblock-rust.
        if (hostMatches(host, allowHosts)) return false

        val lowerUrl = url.lowercase(Locale.ROOT)
        if (allowMatcher.hasMatch(lowerUrl)) return false
        if (blockMatcher.hasMatch(lowerUrl)) return true
        if (isThirdParty(host, pageHost) && thirdPartyMatcher.hasMatch(lowerUrl)) return true
        return false
    }

    fun shouldBlockNavigation(url: String, pageHost: String?): Boolean {
        if (!enabled) return false
        val parsed = parseHttpUrl(url) ?: return false
        val host = parsed.second
        if (!pageHost.isNullOrEmpty() && host.equals(pageHost, ignoreCase = true)) return false
        return isBlockedHost(host, pageHost)
    }

    /**
     * Host-only decision for the optional DNS filter. There is no page context,
     * so third-party rules are deliberately not applied here.
     */
    fun shouldBlockDns(host: String): Boolean {
        if (!enabled) return false
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        if (normalized.isEmpty()) return false
        if (hostMatches(normalized, allowHosts)) return false
        return hostMatches(normalized, blockHosts)
    }

    private fun parseHttpUrl(url: String): Pair<String, String>? {
        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.isEmpty()) return null
        return scheme to host
    }

    private fun isBlockedHost(host: String, pageHost: String?): Boolean {
        if (hostMatches(host, allowHosts)) return false
        if (hostMatches(host, blockHosts)) return true

        if (!pageHost.isNullOrEmpty()) {
            var allowedCandidate = pageHost.lowercase(Locale.ROOT)
            while (true) {
                val allowed = domainAllowHosts[allowedCandidate]
                if (allowed != null && hostMatches(host, allowed)) return false
                val dot = allowedCandidate.indexOf('.')
                if (dot < 0) break
                allowedCandidate = allowedCandidate.substring(dot + 1)
            }

            if (isThirdParty(host, pageHost) && hostMatches(host, thirdPartyHosts)) return true

            var blockedCandidate = pageHost.lowercase(Locale.ROOT)
            while (true) {
                val blocked = domainBlockHosts[blockedCandidate]
                if (blocked != null && hostMatches(host, blocked)) return true
                val dot = blockedCandidate.indexOf('.')
                if (dot < 0) break
                blockedCandidate = blockedCandidate.substring(dot + 1)
            }
        } else if (hostMatches(host, thirdPartyHosts)) {
            return true
        }
        return false
    }

    private fun isThirdParty(host: String, pageHost: String?): Boolean {
        if (pageHost.isNullOrEmpty()) return true
        return registrableDomain(host) != registrableDomain(pageHost)
    }

    private fun registrableDomain(host: String): String {
        val parts = host.lowercase(Locale.ROOT).split('.')
        if (parts.size <= 2) return host
        val lastTwo = parts[parts.size - 2] + "." + parts[parts.size - 1]
        if (parts.size >= 3 && lastTwo in multiPartTlds) {
            return parts[parts.size - 3] + "." + lastTwo
        }
        return lastTwo
    }

    private fun hostMatches(host: String, set: Set<String>): Boolean {
        var candidate = host
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /** Small Aho-Corasick matcher for thousands of literal URL fragments. */
    private class MultiPatternMatcher {
        private data class Node(
            val next: HashMap<Char, Int> = HashMap(),
            var fail: Int = 0,
            var output: Boolean = false
        )

        private val nodes = ArrayList<Node>().apply { add(Node()) }

        fun add(pattern: String) {
            if (pattern.isEmpty()) return
            var state = 0
            for (ch in pattern) {
                val next = nodes[state].next[ch]
                if (next == null) {
                    nodes[state].next[ch] = nodes.size
                    nodes.add(Node())
                    state = nodes.size - 1
                } else {
                    state = next
                }
            }
            nodes[state].output = true
        }

        fun build() {
            val queue = ArrayDeque<Int>()
            nodes[0].next.values.forEach { queue.add(it) }

            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                val failState = nodes[current].fail
                if (nodes[failState].output) nodes[current].output = true

                for ((ch, child) in nodes[current].next) {
                    val candidate = nodes[failState].next[ch]
                    nodes[child].fail = if (candidate == null || candidate == child) 0 else candidate
                    queue.add(child)
                }
            }
        }

        fun hasMatch(input: String): Boolean {
            var state = 0
            for (ch in input) {
                while (state != 0 && !nodes[state].next.containsKey(ch)) {
                    state = nodes[state].fail
                }
                state = nodes[state].next[ch] ?: 0
                if (nodes[state].output) return true
            }
            return false
        }
    }
}
