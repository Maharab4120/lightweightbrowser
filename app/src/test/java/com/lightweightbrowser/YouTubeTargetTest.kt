package com.lightweightbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTargetTest {

    @Test
    fun standardYouTubeHostsOpenTheYouTubeApp() {
        listOf(
            "https://www.youtube.com/watch?v=abc&t=30",
            "https://youtube.com/shorts/abc",
            "https://m.youtube.com/live/abc",
            "https://youtu.be/abc?t=30",
            "https://WWW.YOUTUBE.COM./watch?v=abc"
        ).forEach { url ->
            val target = YouTubeTarget.from(url)
            assertNotNull(url, target)
            assertEquals(url, "YouTube", target!!.displayName)
            assertEquals(url, listOf("com.google.android.youtube"), target.packageNames)
        }
    }

    @Test
    fun musicHostsPreferMusicAppThenRegularApp() {
        listOf(
            "https://music.youtube.com/watch?v=abc",
            "https://www.music.youtube.com/playlist?list=abc"
        ).forEach { url ->
            val target = YouTubeTarget.from(url)
            assertNotNull(url, target)
            assertEquals(url, "YouTube Music", target!!.displayName)
            assertEquals(
                url,
                listOf(
                    "com.google.android.apps.youtube.music",
                    "com.google.android.youtube"
                ),
                target.packageNames
            )
        }
    }

    @Test
    fun lookalikeAndNonWebUrlsAreRejected() {
        listOf(
            "https://youtube.com.example.net/watch?v=abc",
            "https://notyoutube.com/watch?v=abc",
            "https://music.youtube.com.example.net/watch?v=abc",
            // Privacy-enhanced embeds stay in WebView; the app has no filter for them.
            "https://www.youtube-nocookie.com/embed/abc",
            "market://details?id=com.google.android.youtube",
            "not a url",
            ""
        ).forEach { url ->
            assertNull(url, YouTubeTarget.from(url))
        }
    }
}
