package com.lightweightbrowser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URI

class BlockerParityTest {

    private val assetsDir: File
        get() {
            val local = File("src/main/assets")
            return if (local.isDirectory) local else File("app/src/main/assets")
        }

    private val fixtureFile: File
        get() {
            val local = File("src/test/resources/blocker_parity_cases.tsv")
            return if (local.isFile) local else File("app/src/test/resources/blocker_parity_cases.tsv")
        }

    private fun core(): KotlinBlockerCore {
        val names = listOf(
            "allow_hosts.txt", "block_hosts.txt", "third_party_hosts.txt",
            "domain_hosts.txt", "allow_patterns.txt", "block_patterns.txt",
            "third_party_patterns.txt"
        )
        val lines = names.associateWith { name ->
            File(assetsDir, name).readLines().map { it.trim().lowercase() }
        }
        return KotlinBlockerCore().apply { init(lines) }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.trimEnd('.')
    } catch (e: Exception) {
        null
    }

    @Test
    fun kotlinNetworkDecisionsMatchSharedFixtures() {
        val blocker = core()
        fixtureFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split('\t')
                assertEquals("bad fixture line: $line", 4, parts.size)
                val (url, pageUrl, _, expected) = parts
                val actual = blocker.shouldBlock(url, false, hostOf(pageUrl))
                assertEquals("$url on $pageUrl", expected == "1", actual)
            }
    }

    @Test
    fun essentialHostsAreNeverBlocked() {
        val blocker = core()
        val pageUrl = "https://www.youtube.com/watch?v=abc"
        listOf(
            "https://rr1---sn.googlevideo.com/videoplayback?expire=1",
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://lh3.googleusercontent.com/a/abc",
            "https://www.gstatic.com/js/abc.js"
        ).forEach { url ->
            assertEquals(url, false, blocker.shouldBlock(url, false, hostOf(pageUrl)))
        }
    }
}
