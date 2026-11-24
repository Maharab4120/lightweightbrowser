package com.lightweightbrowser

import java.net.URI
import java.util.Locale

/** Pure helpers for detecting and naming direct media downloads. */
internal object VideoDownloads {

    private val EXTENSIONS = setOf(
        "mp4", "webm", "mkv", "mov", "m4v", "ogv", "ogg", "3gp", "avi",
        "mp3", "m4a", "wav", "aac", "flac", "opus"
    )

    fun isDirectMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val parsed = URI(url)
            val path = (parsed.path ?: return false).lowercase(Locale.ROOT)
            val host = (parsed.host ?: return false).lowercase(Locale.ROOT)
            if (host.isEmpty()) return false
            EXTENSIONS.any { path.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }

    fun fileNameFrom(url: String, contentDisposition: String?, mimeType: String?): String {
        val dispositionName = Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition.orEmpty())
            ?.groupValues?.get(1)
            ?.trim('"')
            ?.substringAfterLast('/')
        if (!dispositionName.isNullOrBlank()) return dispositionName

        val pathName = try {
            URI(url).path?.substringAfterLast('/')
        } catch (e: Exception) {
            null
        }
        if (!pathName.isNullOrBlank() && pathName.contains('.')) return pathName

        val extension = when {
            mimeType?.contains("video/mp4", ignoreCase = true) == true -> "mp4"
            mimeType?.contains("video/webm", ignoreCase = true) == true -> "webm"
            mimeType?.contains("audio/mpeg", ignoreCase = true) == true -> "mp3"
            mimeType?.startsWith("video/", ignoreCase = true) == true -> "mp4"
            mimeType?.startsWith("audio/", ignoreCase = true) == true -> "mp3"
            else -> "bin"
        }
        return "lw-download-$extension"
    }
}
