package com.lightweightbrowser

import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import java.util.UUID

/** One browser tab. Holds its own WebView so tab switching never reloads pages. */
class Tab(
    val id: String = UUID.randomUUID().toString()
) {
    var title: String = "New Tab"

    /** Read by WebView callbacks that may run off the main thread. */
    @Volatile
    var url: String = ""
    var webView: WebView? = null
    var progressBar: ProgressBar? = null
    var lastFailedHttps: String? = null

    /** Page host, updated on the main thread and read by the blocker's background callback. */
    @Volatile
    var currentHost: String? = null

    /** Root view currently placed in the content container (speed dial or WebView wrapper). */
    var rootView: View? = null

    /** True when androidx.webkit document-start scripts are installed on this WebView. */
    var documentStartInstalled: Boolean = false
}
