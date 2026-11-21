package com.example.lightweightbrowser

import android.content.Context

internal data class Shortcut(val name: String, val url: String)

/**
 * Persists home-page shortcuts separately from browsing data so clearing
 * browsing data never removes the user's own shortcuts.
 */
internal object ShortcutStore {
    private const val PREFS_NAME = "lightweight_shortcuts_prefs"
    private const val KEY_SHORTCUTS = "shortcuts"
    private const val SEPARATOR = '\u001f'

    private val defaultShortcuts = listOf(
        Shortcut("Google", "https://www.google.com"),
        Shortcut("YouTube", "https://www.youtube.com"),
        Shortcut("GitHub", "https://github.com"),
        Shortcut("Reddit", "https://www.reddit.com")
    )

    fun load(context: Context): MutableList<Shortcut> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_SHORTCUTS, null)
            ?: return defaultShortcuts.toMutableList()
        return stored.mapNotNull(::decode)
            .distinctBy { it.url.lowercase() }
            .toMutableList()
    }

    fun save(context: Context, shortcuts: List<Shortcut>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_SHORTCUTS, shortcuts.map(::encode).toSet())
            .apply()
    }

    internal fun encode(shortcut: Shortcut): String {
        return "${sanitize(shortcut.name)}$SEPARATOR${sanitize(shortcut.url)}"
    }

    internal fun decode(raw: String): Shortcut? {
        val parts = raw.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val name = parts[0].trim()
        val url = parts[1].trim()
        if (name.isEmpty() || url.isEmpty()) return null
        return Shortcut(name, url)
    }

    private fun sanitize(value: String): String {
        return value.replace(SEPARATOR, ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
    }
}
