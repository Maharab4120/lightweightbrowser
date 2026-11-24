package com.lightweightbrowser

import android.content.Context
import android.util.AttributeSet
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * SwipeRefreshLayout only checks its direct child by default. Our direct child
 * is a FrameLayout wrapper, so it must ask the activity whether the real
 * WebView (or the New Tab ScrollView) is already scrolled.
 */
class TabSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    var canScrollUpProvider: (() -> Boolean)? = null

    override fun canChildScrollUp(): Boolean =
        canScrollUpProvider?.invoke() ?: super.canChildScrollUp()
}
