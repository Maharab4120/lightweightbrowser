package com.lightweightbrowser

import android.content.Context

/** Shared, minimal state for the optional DNS filter. */
internal object DnsFilterPrefs {

    private const val NAME = "lightweight_browser_prefs"
    private const val KEY_ENABLED = "dns_filter_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
