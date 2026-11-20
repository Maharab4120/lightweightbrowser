package com.example.lightweightbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DnsHostFilterTest {

    private val assetsDir: File
        get() {
            val local = File("src/main/assets")
            return if (local.isDirectory) local else File("app/src/main/assets")
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

    @Test
    fun dnsFilterBlocksTrackersButKeepsPlaybackHosts() {
        val blocker = core()

        listOf("doubleclick.net", "adnxs.com", "googleadservices.com").forEach { host ->
            assertTrue(host, blocker.shouldBlockDns(host))
        }

        listOf(
            "youtube.com",
            "www.youtube.com",
            "youtu.be",
            "i.ytimg.com",
            "rr1---sn.googlevideo.com",
            "www.gstatic.com",
            "lh3.googleusercontent.com",
            "example.com"
        ).forEach { host ->
            assertFalse(host, blocker.shouldBlockDns(host))
        }
    }

    @Test
    fun disabledFilterNeverBlocksDns() {
        val blocker = core().apply { setEnabled(false) }
        assertFalse(blocker.shouldBlockDns("doubleclick.net"))
        assertFalse(blocker.shouldBlockDns("adnxs.com"))
    }
}
