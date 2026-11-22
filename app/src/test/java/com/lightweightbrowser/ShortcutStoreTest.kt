package com.lightweightbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutStoreTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val original = Shortcut("Example", "https://example.com/path?q=1")
        val decoded = ShortcutStore.decode(ShortcutStore.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun separatorsInNamesDoNotCorruptEntries() {
        val original = Shortcut("News\u001fFeed", "https://news.example.com")
        val decoded = ShortcutStore.decode(ShortcutStore.encode(original))
        assertEquals(Shortcut("News Feed", "https://news.example.com"), decoded)
    }

    @Test
    fun malformedEntriesAreIgnored() {
        assertNull(ShortcutStore.decode("no-separator"))
        assertNull(ShortcutStore.decode("\u001fhttps://example.com"))
        assertNull(ShortcutStore.decode("Example\u001f"))
    }
}
