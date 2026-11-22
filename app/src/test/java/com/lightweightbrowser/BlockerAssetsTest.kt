package com.lightweightbrowser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlockerAssetsTest {

    private val assetsDir: File
        get() {
            val local = File("src/main/assets")
            return if (local.isDirectory) local else File("app/src/main/assets")
        }

    private fun lines(name: String): List<String> = File(assetsDir, name).readLines()

    @Test
    fun blockListCoversMajorTrackers() {
        val hosts = lines("block_hosts.txt").toSet()
        assertTrue(hosts.contains("doubleclick.net"))
        assertTrue(hosts.contains("googleadservices.com"))
        assertTrue(hosts.contains("googlesyndication.com"))
        assertTrue(hosts.contains("google-analytics.com"))
        assertTrue(hosts.contains("adnxs.com"))
        assertTrue(hosts.size in 10_000..30_000)
    }

    @Test
    fun allowListKeepsCommonSitesUsable() {
        val hosts = lines("allow_hosts.txt").toSet()
        assertTrue(hosts.contains("google.com"))
    }

    @Test
    fun patternAndCosmeticListsArePresent() {
        val patterns = lines("block_patterns.txt")
        val cosmetic = lines("cosmetic_rules.txt")
        assertTrue(patterns.contains("gtm.js"))
        assertTrue(patterns.contains("/ads/"))
        assertTrue(patterns.size in 1_000..5_000)
        assertTrue(cosmetic.size in 5_000..30_000)
        assertTrue(cosmetic.any { it.startsWith("*\t.ad_banner") })
    }

    @Test
    fun domainSpecificHostRulesArePresent() {
        val rules = lines("domain_hosts.txt")
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.size in 500..10_000)
        assertTrue(rules.all { it.contains('\t') })
    }

    @Test
    fun rustFilterAssetIsBundled() {
        val filters = lines("adblock_filters.txt")
        assertTrue(filters.size > 100_000)
        assertTrue(filters.any { it == "||ad.doubleclick.net^" })
        assertTrue(filters.any { it.contains("googlevideo.com") })
    }

    @Test
    fun braveFilterDataIsBundled() {
        val thirdPartyHosts = lines("third_party_hosts.txt").toSet()
        assertTrue(thirdPartyHosts.contains("taboolasyndication.com"))
        assertTrue(thirdPartyHosts.size in 1_000..6_000)

        val cosmetic = lines("cosmetic_rules.txt")
        assertTrue(cosmetic.any { it.startsWith("m.youtube.com\tytm-topbar-menu-button-renderer") })
        assertTrue(cosmetic.any { it.startsWith("youtube.com\t.masthead-ad-control") })
    }
}
