package com.example.lightweightbrowser

import java.net.URI
import java.util.Locale

/** Which installed app should receive a YouTube navigation, in priority order. */
internal data class YouTubeAppTarget(
    val displayName: String,
    val packageNames: List<String>
)

/**
 * Pure YouTube link detection for the native-app handoff.
 *
 * It has no Android dependencies so host matching and app fallback order can be
 * covered by JVM unit tests. Only real YouTube hosts are accepted; lookalikes
 * such as `youtube.com.example.net` must stay in the browser.
 */
internal object YouTubeTarget {

    private const val PACKAGE_YOUTUBE = "com.google.android.youtube"
    private const val PACKAGE_YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    private val youtube = YouTubeAppTarget("YouTube", listOf(PACKAGE_YOUTUBE))
    private val youtubeMusic = YouTubeAppTarget(
        "YouTube Music",
        listOf(PACKAGE_YOUTUBE_MUSIC, PACKAGE_YOUTUBE)
    )

    fun from(url: String?): YouTubeAppTarget? {
        val host = hostOf(url) ?: return null

        return when {
            host == "music.youtube.com" || host.endsWith(".music.youtube.com") -> youtubeMusic
            host == "youtube.com" || host.endsWith(".youtube.com") -> youtube
            host == "youtu.be" || host.endsWith(".youtu.be") -> youtube
            else -> null
        }
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val parsed = URI(url.trim())
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
