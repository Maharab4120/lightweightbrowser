package com.example.lightweightbrowser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var urlEditText: EditText
    private lateinit var goButton: TextView
    private lateinit var newTabButton: TextView
    private lateinit var shieldButton: TextView
    private lateinit var tabBarContainer: LinearLayout
    private lateinit var tabStrip: View
    private lateinit var topToolbar: View
    private lateinit var bottomToolbar: View
    private lateinit var chromePeek: View
    private lateinit var tabContentContainer: FrameLayout
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var reloadButton: TextView
    private lateinit var homeButton: TextView

    private val tabs = mutableListOf<Tab>()
    private var currentTabId: String? = null
    private val blockedCount = AtomicInteger(0)
    private var mediaPlaying = false
    private var appInBackground = false
    private var resumeAttempts = 0
    private var chromeVisible = true
    private val mediaHandler = Handler(Looper.getMainLooper())
    private val stopMediaRunnable = Runnable {
        MediaPlaybackService.stop(this@MainActivity)
    }
    private val resumeMediaRunnable = Runnable { retryBackgroundResume() }

    private val browserJsBridge = BrowserJsBridge()

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        registerBackHandler()

        BlockerEngineManager.init(
            applicationContext,
            prefs.getBoolean(KEY_USE_RUST_ENGINE, DEFAULT_USE_RUST_ENGINE)
        )
        BlockerEngineManager.setEnabled(prefs.getBoolean(KEY_BLOCKER_ENABLED, true))
        blockedCount.set(prefs.getInt(KEY_BLOCKED_COUNT, 0))

        setupListeners()
        updateBlockerUi()
        requestNotificationPermissionIfNeeded()

        val savedIds = savedInstanceState?.getStringArrayList(KEY_TAB_IDS)
        if (savedIds.isNullOrEmpty()) {
            createNewTab()

            val incomingUrl = intent?.data?.takeIf { data ->
                data.scheme?.equals("http", ignoreCase = true) == true ||
                    data.scheme?.equals("https", ignoreCase = true) == true
            }?.toString()

            if (!incomingUrl.isNullOrEmpty()) {
                loadUrlInCurrentTab(incomingUrl)
            }
        } else {
            restoreTabs(savedInstanceState, savedIds)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        } catch (e: Exception) {
            // Permission denial only hides the notification; playback still works.
        }
    }

    private inner class BrowserJsBridge {
        @JavascriptInterface
        fun onMediaState(playing: Boolean) {
            runOnUiThread {
                if (playing == mediaPlaying) return@runOnUiThread
                mediaPlaying = playing
                mediaHandler.removeCallbacks(stopMediaRunnable)
                mediaHandler.removeCallbacks(resumeMediaRunnable)
                if (playing) {
                    resumeAttempts = 0
                    MediaPlaybackService.userPaused = false
                    MediaPlaybackService.start(this@MainActivity)
                } else if (MediaPlaybackService.userPaused) {
                    // User paused from the notification: keep it so Play stays available.
                } else if (appInBackground && resumeAttempts < MAX_RESUME_ATTEMPTS) {
                    retryBackgroundResume()
                } else {
                    // Nothing left to resume; give a short grace for buffering.
                    mediaHandler.postDelayed(stopMediaRunnable, 5_000)
                }
            }
        }
    }

    /**
     * Some sites pause themselves when the page is hidden. Re-issue a play
     * command from native code (WebView timers are throttled in background).
     * This is best-effort: a site that refuses to resume will stop the service.
     */
    private fun retryBackgroundResume() {
        if (!appInBackground || MediaPlaybackService.userPaused) {
            mediaHandler.postDelayed(stopMediaRunnable, 5_000)
            return
        }
        if (resumeAttempts >= MAX_RESUME_ATTEMPTS) {
            mediaHandler.postDelayed(stopMediaRunnable, 5_000)
            return
        }
        resumeAttempts++
        MediaPlaybackService.resume(this)
        mediaHandler.postDelayed(resumeMediaRunnable, 2_500)
    }

    private fun bindViews() {
        urlEditText = findViewById(R.id.urlEditText)
        goButton = findViewById(R.id.goButton)
        newTabButton = findViewById(R.id.newTabButton)
        shieldButton = findViewById(R.id.shieldButton)
        tabBarContainer = findViewById(R.id.tabBarContainer)
        topToolbar = findViewById(R.id.topToolbar)
        tabStrip = findViewById(R.id.tabStrip)
        bottomToolbar = findViewById(R.id.bottomToolbar)
        chromePeek = findViewById(R.id.chromePeek)
        tabContentContainer = findViewById(R.id.tabContentContainer)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        reloadButton = findViewById(R.id.reloadButton)
        homeButton = findViewById(R.id.homeButton)
    }

    private fun setupListeners() {
        goButton.setOnClickListener { loadUrlInCurrentTab(urlEditText.text.toString()) }

        urlEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) urlEditText.selectAll()
        }

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (submit) {
                loadUrlInCurrentTab(urlEditText.text.toString())
                true
            } else {
                false
            }
        }

        newTabButton.setOnClickListener { createNewTab() }
        shieldButton.setOnClickListener { showBlockerDialog() }

        backButton.setOnClickListener { currentTab()?.webView?.goBack() }
        forwardButton.setOnClickListener { currentTab()?.webView?.goForward() }
        reloadButton.setOnClickListener {
            currentTab()?.webView?.let { web ->
                val target = web.url?.let { upgradeToHttps(it) } ?: return@let
                web.loadUrl(target, navigationHeaders())
            }
        }
        homeButton.setOnClickListener {
            currentTab()?.let { showSpeedDial(it) }
        }

        chromePeek.setOnClickListener { setChromeVisible(true) }
    }

    private fun restoreTabs(state: Bundle, ids: java.util.ArrayList<String>) {
        val urls = state.getStringArrayList(KEY_TAB_URLS) ?: arrayListOf()
        val titles = state.getStringArrayList(KEY_TAB_TITLES) ?: arrayListOf()

        ids.forEachIndexed { index, id ->
            createNewTab(
                url = urls.getOrNull(index)?.takeIf { it.isNotEmpty() },
                id = id,
                restoredTitle = titles.getOrNull(index)
            )
        }

        val savedCurrent = state.getString(KEY_CURRENT_TAB)
        val current = savedCurrent?.takeIf { id -> tabs.any { it.id == id } } ?: ids.last()
        switchToTab(current)
    }

    private fun createNewTab(
        url: String? = null,
        id: String? = null,
        restoredTitle: String? = null
    ) {
        val tab = Tab(id = id ?: UUID.randomUUID().toString())
        if (!restoredTitle.isNullOrBlank()) tab.title = restoredTitle
        tabs.add(tab)

        if (url.isNullOrEmpty()) {
            val speedDial = createSpeedDialView(tab)
            tab.rootView = speedDial
            tabContentContainer.addView(speedDial)
        } else {
            val webView = ensureWebView(tab)
            val secureUrl = upgradeToHttps(url)
            tab.url = secureUrl
            tab.currentHost = hostFrom(secureUrl)
            webView.loadUrl(secureUrl, navigationHeaders())
        }

        switchToTab(tab.id)
    }

    private fun createSpeedDialView(tab: Tab): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.speed_dial_view, tabContentContainer, false)

        // The large field is a launcher for the one real omnibox, not a second
        // text input with its own separate state.
        view.findViewById<View>(R.id.startSearchBox).setOnClickListener {
            setChromeVisible(true)
            urlEditText.setText("")
            urlEditText.requestFocus()
            urlEditText.setSelection(0)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(urlEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        refreshShortcutGrid(view, tab)
        return view
    }

    private fun refreshShortcutGrid(view: View, tab: Tab) {
        val grid = view.findViewById<GridLayout>(R.id.shortcutGrid) ?: return
        grid.removeAllViews()

        ShortcutStore.load(this).forEachIndexed { index, shortcut ->
            val tile = LayoutInflater.from(this)
                .inflate(R.layout.shortcut_item, grid, false)
            tile.findViewById<TextView>(R.id.shortcutBadge).text =
                shortcut.name.take(1).uppercase(Locale.getDefault())
            tile.findViewById<TextView>(R.id.shortcutLabel).text = shortcut.name
            tile.setOnClickListener { loadUrlInTab(tab, normalizeUrl(shortcut.url)) }
            tile.setOnLongClickListener {
                showShortcutOptions(view, tab, shortcut, index)
                true
            }
            grid.addView(tile, shortcutTileParams(index))
        }

        val addTile = LayoutInflater.from(this)
            .inflate(R.layout.shortcut_item, grid, false)
        addTile.findViewById<TextView>(R.id.shortcutBadge).apply {
            text = "+"
            textSize = 22f
            background = resources.getDrawable(R.drawable.shortcut_badge_add_background, theme)
        }
        addTile.findViewById<TextView>(R.id.shortcutLabel).text = "Add"
        addTile.setOnClickListener { showShortcutDialog(view, tab, -1, null) }
        grid.addView(addTile, shortcutTileParams(ShortcutStore.load(this).size))
    }

    private fun shortcutTileParams(index: Int): GridLayout.LayoutParams {
        val column = index % SHORTCUT_COLUMNS
        val row = index / SHORTCUT_COLUMNS
        return GridLayout.LayoutParams(
            GridLayout.spec(row, GridLayout.FILL, 0f),
            GridLayout.spec(column, 1, 1f)
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun showShortcutOptions(view: View, tab: Tab, shortcut: Shortcut, index: Int) {
        AlertDialog.Builder(this)
            .setTitle(shortcut.name)
            .setItems(arrayOf("Edit site", "Remove")) { _, which ->
                when (which) {
                    0 -> showShortcutDialog(view, tab, index, shortcut)
                    1 -> confirmRemoveShortcut(view, tab, shortcut)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveShortcut(view: View, tab: Tab, shortcut: Shortcut) {
        AlertDialog.Builder(this)
            .setTitle("Remove shortcut?")
            .setMessage("Remove ${shortcut.name} from the home page?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                ShortcutStore.save(this, ShortcutStore.load(this).filterNot { it.url == shortcut.url })
                refreshShortcutGrid(view, tab)
                Toast.makeText(this, "Shortcut removed", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Reused for both adding a new tile and editing an existing one. */
    private fun showShortcutDialog(
        view: View,
        tab: Tab,
        editingIndex: Int,
        existing: Shortcut?
    ) {
        val isEditing = editingIndex >= 0 && existing != null
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_shortcut, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.shortcutNameInput)
        val urlInput = dialogView.findViewById<EditText>(R.id.shortcutUrlInput)
        if (existing != null) {
            nameInput.setText(existing.name)
            urlInput.setText(existing.url)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEditing) "Edit shortcut" else "Add shortcut")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (isEditing) "Save" else "Add", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val url = normalizeUrl(urlInput.text.toString())
                val shortcuts = ShortcutStore.load(this)
                val duplicate = shortcuts.indices.any { index ->
                    index != editingIndex && shortcuts[index].url.equals(url, ignoreCase = true)
                }
                when {
                    name.isEmpty() -> nameInput.error = "Enter a name"
                    url.isEmpty() -> urlInput.error = "Enter a website"
                    duplicate -> urlInput.error = "Shortcut already exists"
                    else -> {
                        if (isEditing && editingIndex in shortcuts.indices) {
                            shortcuts[editingIndex] = Shortcut(name, url)
                        } else {
                            shortcuts.add(Shortcut(name, url))
                        }
                        ShortcutStore.save(this, shortcuts)
                        dialog.dismiss()
                        refreshShortcutGrid(view, tab)
                        Toast.makeText(
                            this,
                            if (isEditing) "Shortcut updated" else "Shortcut added",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showSpeedDial(tab: Tab) {
        tab.rootView?.let { tabContentContainer.removeView(it) }
        tab.webView?.destroy()
        tab.webView = null
        tab.progressBar = null
        tab.rootView = null
        tab.url = ""
        tab.title = "New Tab"
        tab.currentHost = null

        val speedDial = createSpeedDialView(tab)
        tab.rootView = speedDial
        tabContentContainer.addView(speedDial)
        switchToTab(tab.id)
    }

    @Suppress("DEPRECATION")
    private fun ensureWebView(tab: Tab): WebView {
        tab.webView?.let { return it }

        tab.rootView?.let { tabContentContainer.removeView(it) }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progressDrawable = resources.getDrawable(R.drawable.progress_drawable, theme)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.TOP)
        }

        val webView = WebView(this).apply {
            setBackgroundColor(resources.getColor(R.color.surface, theme))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                textZoom = 100
                cacheMode = if (prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)) {
                    WebSettings.LOAD_NO_CACHE
                } else {
                    WebSettings.LOAD_DEFAULT
                }
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                setAllowFileAccessFromFileURLs(false)
                setAllowUniversalAccessFromFileURLs(false)
                javaScriptCanOpenWindowsAutomatically = false
                setGeolocationEnabled(false)
                setSavePassword(false)
                setSaveFormData(false)
                mediaPlaybackRequiresUserGesture = true
            }

            // Privacy: never send cookies to third-party sites.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            addJavascriptInterface(browserJsBridge, "LWBridge")
            attachChromeGesture(this)

            webChromeClient = object : WebChromeClient() {
                // Swallow popups opened with window.open()/target="_blank".
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean = true

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    updateNavButtons()
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) {
                        tab.title = title
                        updateTabBar()
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null) return null

                    val headers: Map<String, String> = request.requestHeaders
                    val pageUrl = RequestContext.pageUrl(headers, tab.url)
                    val resourceType = ResourceType.from(request)
                    val blockerRequest = BlockerRequest(
                        url = request.url.toString(),
                        pageUrl = pageUrl,
                        pageHost = RequestContext.hostOf(pageUrl) ?: tab.currentHost,
                        resourceType = resourceType,
                        method = request.method ?: "GET",
                        isForMainFrame = request.isForMainFrame
                    )
                    if (BlockerEngineManager.shouldBlock(blockerRequest)) {
                        blockedCount.incrementAndGet()
                        view?.post { updateBlockerUi() }
                        val spec = BlockedResponses.spec(resourceType)
                        return WebResourceResponse(
                            spec.mimeType,
                            spec.encoding,
                            spec.statusCode,
                            spec.reason,
                            spec.headers,
                            ByteArrayInputStream(spec.body)
                        )
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return true
                    val headers: Map<String, String> = request.requestHeaders
                    val sourceUrl = RequestContext.pageUrl(headers, tab.url)
                    val sourceHost = RequestContext.hostOf(tab.url) ?: tab.currentHost

                    if (BlockerEngineManager.shouldBlockNavigation(url, sourceHost, sourceUrl)) {
                        blockedCount.incrementAndGet()
                        view?.post { updateBlockerUi() }
                        return true
                    }

                    if (url.startsWith("http://", ignoreCase = true) ||
                        url.startsWith("https://", ignoreCase = true)
                    ) {
                        val secureUrl = upgradeToHttps(url)
                        val loadHere = {
                            tab.lastFailedHttps = null
                            tab.url = secureUrl
                            tab.currentHost = hostFrom(secureUrl)
                            view?.loadUrl(secureUrl, navigationHeaders())
                            Unit
                        }
                        // Never let a subframe or ad iframe launch the YouTube app.
                        if (request.isForMainFrame &&
                            handleYouTubeHandoff(secureUrl, loadHere)
                        ) return true
                        loadHere()
                        return true
                    }

                    if (url.startsWith("mailto:", ignoreCase = true) ||
                        url.startsWith("tel:", ignoreCase = true)
                    ) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, "No app for this link", Toast.LENGTH_SHORT).show()
                        }
                    }

                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrEmpty()) {
                        tab.url = url
                        tab.currentHost = hostFrom(url)
                    }
                    tab.title = hostFrom(url) ?: "Loading..."
                    progressBar.visibility = View.VISIBLE
                    if (tab.id == currentTabId) urlEditText.setText(url ?: "")
                    if (BlockerEngineManager.isEnabled()) {
                        // Early injection: page scripts see DNT/GPC and no-referrer.
                        view?.evaluateJavascript(AdBlocker.privacyJs(), null)
                    }
                    updateTabBar()
                    updateNavButtons()
                    if (tab.id == currentTabId) setChromeVisible(false)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrEmpty()) {
                        tab.url = url
                        tab.currentHost = hostFrom(url)
                    }
                    tab.lastFailedHttps = null
                    val pageTitle = view?.title?.takeIf { it.isNotBlank() }
                    tab.title = pageTitle ?: hostFrom(url) ?: "Web page"
                    progressBar.visibility = View.GONE
                    if (tab.id == currentTabId) urlEditText.setText(tab.url)
                    updateTabBar()
                    updateNavButtons()

                    if (BlockerEngineManager.isEnabled()) {
                        // Repeat with its own guard in case the early injection was too soon.
                        view?.evaluateJavascript(AdBlocker.privacyJs(), null)
                        val pageHost = hostFrom(url).orEmpty()
                        view?.evaluateJavascript(AdBlocker.cosmeticJsFor(pageHost), null)
                    }
                    // Media monitor is independent of ad blocking.
                    view?.evaluateJavascript(AdBlocker.mediaMonitorJs(), null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val failedUrl = request?.url?.toString() ?: return
                    if (!request.isForMainFrame || !failedUrl.startsWith("https://", ignoreCase = true)) return
                    if (isFinishing || isDestroyed || tab.lastFailedHttps == failedUrl) return

                    val errorCode = error?.errorCode ?: 0
                    tab.lastFailedHttps = failedUrl

                    if (errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Secure connection failed")
                            .setMessage(
                                "The site's HTTPS certificate could not be verified. " +
                                    "This browser will not fall back to HTTP."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        return
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("HTTPS unavailable")
                        .setMessage(
                            "Could not open this page securely (error $errorCode). " +
                                "Opening it over HTTP would expose it to your ISP and " +
                                "anyone else on the network."
                        )
                        .setPositiveButton("Open over HTTP anyway") { _, _ ->
                            val httpUrl = "http://" + failedUrl.removePrefix("https://")
                            tab.lastFailedHttps = null
                            tab.currentHost = hostFrom(httpUrl)
                            view?.loadUrl(httpUrl, navigationHeaders())
                        }
                        .setNegativeButton("Stay secure", null)
                        .show()
                }
            }
        }

        val wrapper = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(progressBar)
        }

        tab.webView = webView
        tab.progressBar = progressBar
        tab.rootView = wrapper
        tabContentContainer.addView(wrapper)
        MediaPlaybackService.registerWebView(webView)

        return webView
    }

    /**
     * YouTube cannot play reliably in a WebView: it pauses itself in the
     * background and its ads are part of the video stream. Hand it to the
     * native app, which supports real background playback and media controls.
     */
    private fun handleYouTubeHandoff(url: String, openHere: () -> Unit): Boolean {
        val target = YouTubeTarget.from(url) ?: return false

        when (prefs.getString(KEY_YOUTUBE_HANDOFF, HANDOFF_ASK)) {
            HANDOFF_ALWAYS -> {
                openInYouTubeApp(target, url, openHere)
                return true
            }
            HANDOFF_NEVER -> return false
        }

        AlertDialog.Builder(this)
            .setTitle("Open in ${target.displayName}?")
            .setMessage(
                "The ${target.displayName} app supports background playback, screen-off audio, " +
                    "and real media controls. WebView cannot."
            )
            .setPositiveButton("Open in app") { _, _ -> openInYouTubeApp(target, url, openHere) }
            .setNeutralButton("Always in app") { _, _ ->
                prefs.edit().putString(KEY_YOUTUBE_HANDOFF, HANDOFF_ALWAYS).apply()
                openInYouTubeApp(target, url, openHere)
            }
            .setNegativeButton("Open here") { _, _ ->
                prefs.edit().putString(KEY_YOUTUBE_HANDOFF, HANDOFF_NEVER).apply()
                openHere()
            }
            .show()
        return true
    }

    private fun openInYouTubeApp(
        target: YouTubeAppTarget,
        url: String,
        fallback: () -> Unit
    ) {
        for (packageName in target.packageNames) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(packageName)
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next available app package.
            } catch (e: SecurityException) {
                // Package is restricted on this device; try the next one.
            }
        }
        Toast.makeText(this, "${target.displayName} app not available - opening here", Toast.LENGTH_SHORT)
            .show()
        fallback()
    }

    private fun loadUrlInCurrentTab(input: String) {
        currentTab()?.let { loadUrlInTab(it, input) }
    }

    private fun loadUrlInTab(tab: Tab, input: String) {
        val url = normalizeUrl(input)
        if (url.isEmpty()) return

        val secureUrl = upgradeToHttps(url)
        val loadHere = {
            tab.lastFailedHttps = null
            ensureWebView(tab).loadUrl(secureUrl, navigationHeaders())
            tab.url = secureUrl
            tab.currentHost = hostFrom(secureUrl)
            tab.title = hostFrom(secureUrl) ?: "Loading..."
            switchToTab(tab.id)
            hideKeyboard()
        }
        if (handleYouTubeHandoff(secureUrl, loadHere)) return
        loadHere()
    }

    private fun switchToTab(tabId: String) {
        currentTabId = tabId

        tabs.forEach { tab ->
            tab.rootView?.visibility = if (tab.id == tabId) View.VISIBLE else View.GONE
        }
        MediaPlaybackService.registerWebView(currentTab()?.webView)

        urlEditText.setText(currentTab()?.url.orEmpty())
        updateTabBar()
        updateNavButtons()
        setChromeVisible(currentTab()?.webView == null)
    }

    private fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return

        val tab = tabs[index]
        tab.rootView?.let { tabContentContainer.removeView(it) }
        tab.webView?.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createNewTab()
            return
        }

        if (currentTabId == tabId) {
            val next = tabs.getOrNull(index) ?: tabs.last()
            switchToTab(next.id)
        } else {
            updateTabBar()
        }
    }

    private fun updateTabBar() {
        tabBarContainer.removeAllViews()

        tabs.forEach { tab ->
            val item = LayoutInflater.from(this)
                .inflate(R.layout.tab_item, tabBarContainer, false)
            val row = item.findViewById<View>(R.id.tabRow)
            val titleView = item.findViewById<TextView>(R.id.tabTitle)
            val closeButton = item.findViewById<ImageButton>(R.id.closeTabButton)
            val indicator = item.findViewById<View>(R.id.tabIndicator)
            val selected = tab.id == currentTabId

            titleView.text = tab.title.ifBlank { "New Tab" }
            row.setBackgroundResource(
                if (selected) R.drawable.tab_background_selected else R.drawable.tab_background
            )
            indicator.visibility = if (selected) View.VISIBLE else View.GONE

            item.setOnClickListener { switchToTab(tab.id) }
            closeButton.setOnClickListener { closeTab(tab.id) }

            tabBarContainer.addView(item)
        }
    }

    private fun updateNavButtons() {
        val webView = currentTab()?.webView
        setButtonEnabled(backButton, webView?.canGoBack() == true)
        setButtonEnabled(forwardButton, webView?.canGoForward() == true)
        setButtonEnabled(reloadButton, webView != null)
    }

    private fun setButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    /**
     * Collapse the browser chrome while a page is being read. The WebView then
     * gets the full height instead of permanently losing 156dp to fixed bars.
     */
    private fun setChromeVisible(visible: Boolean) {
        if (chromeVisible == visible) return
        chromeVisible = visible
        val state = if (visible) View.VISIBLE else View.GONE
        topToolbar.visibility = state
        tabStrip.visibility = state
        bottomToolbar.visibility = state
        chromePeek.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun attachChromeGesture(webView: WebView) {
        var startY = 0f
        var moved = false
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    moved = false
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.y - startY
                    if (kotlin.math.abs(dy) > dp(30)) {
                        // Finger up reveals more page, finger down brings controls back.
                        setChromeVisible(dy > 0)
                    } else if (!moved && event.y <= dp(100)) {
                        setChromeVisible(true)
                    }
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                kotlin.math.abs(event.y - startY) > dp(8)
            ) {
                moved = true
            }
            false
        }
    }

    private fun showBlockerDialog() {
        val enabled = BlockerEngineManager.isEnabled()
        val state = if (enabled) "ON" else "OFF"
        val clearOnExit = prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)
        val handoffMode = prefs.getString(KEY_YOUTUBE_HANDOFF, HANDOFF_ASK) ?: HANDOFF_ASK
        val youtubeLabel = when (handoffMode) {
            HANDOFF_ALWAYS -> "YouTube: always in app"
            HANDOFF_NEVER -> "YouTube: open in browser"
            else -> "YouTube: ask each time"
        }
        val dnsLabel = "DNS filter: ${if (DnsFilterService.isRunning) "ON" else "OFF"} (tap to switch)"

        // Keep the option order stable: handleBlockerOption() maps by index.
        val options = arrayOf(
            if (enabled) "Turn blocking off" else "Turn blocking on",
            "Reset blocked count",
            "Clear browsing data now",
            "Clear data on exit: ${if (clearOnExit) "ON" else "OFF"}",
            youtubeLabel,
            "Engine: ${BlockerEngineManager.activeEngineName()} (tap to switch)",
            dnsLabel,
            "Close"
        )
        val icons = arrayOf(
            R.drawable.ic_shield_small,
            R.drawable.ic_counter,
            R.drawable.ic_delete,
            R.drawable.ic_delete,
            R.drawable.ic_arrow_forward,
            R.drawable.ic_tune,
            R.drawable.ic_tune,
            R.drawable.ic_close
        )

        val padding = dp(16)
        val rowPadding = dp(14)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        container.addView(TextView(this).apply {
            text = "Blocking $state  ·  ${blockedCount.get()} blocked"
            textSize = 15f
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setPadding(rowPadding, 0, rowPadding, dp(12))
        })
        container.addView(View(this).apply {
            setBackgroundColor(resources.getColor(R.color.divider, theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { bottomMargin = dp(12) }
        })

        lateinit var dialog: AlertDialog
        options.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = resources.getDrawable(R.drawable.settings_row_background, theme)
                setPadding(rowPadding, rowPadding, rowPadding, rowPadding)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            row.addView(ImageView(this).apply {
                setImageResource(icons[index])
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    rightMargin = rowPadding
                }
            })
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_primary, theme))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                setPadding(dp(8), 0, 0, 0)
            })
            row.setOnClickListener {
                dialog.dismiss()
                handleBlockerOption(index, handoffMode, clearOnExit)
            }
            container.addView(row)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(container) })
            .create()
        dialog.show()
    }

    private fun handleBlockerOption(which: Int, handoffMode: String, clearOnExit: Boolean) {
        when (which) {
            0 -> toggleBlocker()
            1 -> {
                blockedCount.set(0)
                prefs.edit().putInt(KEY_BLOCKED_COUNT, 0).apply()
                updateBlockerUi()
            }
            2 -> clearBrowsingData()
            3 -> {
                val next = !clearOnExit
                prefs.edit().putBoolean(KEY_CLEAR_ON_EXIT, next).apply()
                tabs.forEach { tab ->
                    tab.webView?.settings?.cacheMode = if (next) {
                        WebSettings.LOAD_NO_CACHE
                    } else {
                        WebSettings.LOAD_DEFAULT
                    }
                }
                Toast.makeText(
                    this,
                    if (next) "Data clears when app closes" else "Data kept after exit",
                    Toast.LENGTH_SHORT
                ).show()
            }
            4 -> {
                val next = when (handoffMode) {
                    HANDOFF_ASK -> HANDOFF_ALWAYS
                    HANDOFF_ALWAYS -> HANDOFF_NEVER
                    else -> HANDOFF_ASK
                }
                prefs.edit().putString(KEY_YOUTUBE_HANDOFF, next).apply()
                Toast.makeText(
                    this,
                    when (next) {
                        HANDOFF_ALWAYS -> "YouTube links open in the app"
                        HANDOFF_NEVER -> "YouTube links open in this browser"
                        else -> "YouTube will ask each time"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            5 -> toggleFilterEngine()
            6 -> toggleDnsFilter()
        }
    }

    private fun toggleBlocker() {
        val next = !BlockerEngineManager.isEnabled()
        BlockerEngineManager.setEnabled(next)
        prefs.edit().putBoolean(KEY_BLOCKER_ENABLED, next).apply()
        if (!next && DnsFilterService.isRunning) disableDnsFilter(showToast = false)
        updateBlockerUi()
        reloadCurrentPage()
        Toast.makeText(
            this,
            if (next) "Ad blocking on" else "Ad blocking off",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleFilterEngine() {
        val useRust = !prefs.getBoolean(KEY_USE_RUST_ENGINE, DEFAULT_USE_RUST_ENGINE)
        prefs.edit().putBoolean(KEY_USE_RUST_ENGINE, useRust).apply()

        if (!useRust) {
            BlockerEngineManager.useKotlin()
            updateBlockerUi()
            reloadCurrentPage()
            Toast.makeText(this, "Kotlin filter engine active", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Loading Brave Rust engine...", Toast.LENGTH_SHORT).show()
        BlockerEngineManager.loadRust(applicationContext) { success, detail ->
            runOnUiThread {
                if (!success) {
                    prefs.edit().putBoolean(KEY_USE_RUST_ENGINE, false).apply()
                }
                if (!isFinishing && !isDestroyed) {
                    updateBlockerUi()
                    reloadCurrentPage()
                    Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleDnsFilter() {
        if (DnsFilterService.isRunning) {
            disableDnsFilter()
            return
        }

        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN)
            return
        }
        enableDnsFilter()
    }

    private fun enableDnsFilter() {
        if (!BlockerEngineManager.isEnabled()) {
            Toast.makeText(this, "Turn ad blocking on first", Toast.LENGTH_SHORT).show()
            return
        }
        DnsFilterPrefs.setEnabled(this, true)
        val intent = Intent(this, DnsFilterService::class.java).apply {
            action = DnsFilterService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Starting DNS filter...", Toast.LENGTH_SHORT).show()
    }

    private fun disableDnsFilter(showToast: Boolean = true) {
        DnsFilterPrefs.setEnabled(this, false)
        val intent = Intent(this, DnsFilterService::class.java).apply {
            action = DnsFilterService.ACTION_STOP
        }
        startService(intent)
        if (showToast) {
            Toast.makeText(this, "DNS filter off", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            enableDnsFilter()
        } else {
            DnsFilterPrefs.setEnabled(this, false)
            Toast.makeText(this, "DNS filter not enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadCurrentPage() {
        currentTab()?.webView?.let { web ->
            val target = web.url?.let { upgradeToHttps(it) } ?: return
            currentTab()?.currentHost = hostFrom(target)
            web.loadUrl(target, navigationHeaders())
        }
    }

    private fun clearBrowsingData(silent: Boolean = false) {
        tabs.forEach { tab ->
            tab.webView?.clearCache(true)
            tab.webView?.clearHistory()
            tab.webView?.clearFormData()
        }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        blockedCount.set(0)
        prefs.edit().putInt(KEY_BLOCKED_COUNT, 0).apply()
        updateBlockerUi()
        if (!silent) Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateBlockerUi() {
        val enabled = BlockerEngineManager.isEnabled()
        shieldButton.text = if (enabled) {
            "SHIELD ${blockedCount.get()}"
        } else {
            "OFF ${blockedCount.get()}"
        }
        shieldButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun normalizeUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.equals("about:blank", ignoreCase = true)) return "about:blank"
        if (text.startsWith("data:", ignoreCase = true)) return ""

        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            return upgradeToHttps(text)
        }

        val looksLikeUrl = !text.contains(" ") && text.contains(".")
        return if (looksLikeUrl) {
            "https://$text"
        } else {
            "https://duckduckgo.com/?q=${Uri.encode(text)}"
        }
    }

    private fun navigationHeaders(): Map<String, String> {
        return mapOf(
            "DNT" to "1",
            "Sec-GPC" to "1",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    private fun upgradeToHttps(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true)) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }
    }

    private fun hostFrom(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }
    }

    private fun currentTab(): Tab? = tabs.find { it.id == currentTabId }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
        urlEditText.clearFocus()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerOnBackInvoked()
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerOnBackInvoked() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            if (!handleBack()) finish()
        }
    }

    /**
     * Consumes the back gesture when possible: reveal hidden chrome, step back
     * in history, or fall back to the speed dial. Returns false when the
     * activity itself should close.
     */
    private fun handleBack(): Boolean {
        if (!chromeVisible) {
            setChromeVisible(true)
            return true
        }

        val webView = currentTab()?.webView
        if (webView?.canGoBack() == true) {
            webView.goBack()
            return true
        }

        val tab = currentTab()
        if (tab != null && tab.url.isNotEmpty()) {
            showSpeedDial(tab)
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Fallback when predictive back is not opted in for this activity.
        if (!handleBack()) super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_TAB_IDS, ArrayList(tabs.map { it.id }))
        outState.putStringArrayList(KEY_TAB_URLS, ArrayList(tabs.map { it.url }))
        outState.putStringArrayList(KEY_TAB_TITLES, ArrayList(tabs.map { it.title }))
        outState.putString(KEY_CURRENT_TAB, currentTabId)
        outState.putInt(KEY_BLOCKED_COUNT, blockedCount.get())
    }

    override fun onStart() {
        super.onStart()
        appInBackground = false
        resumeAttempts = 0
        mediaHandler.removeCallbacks(resumeMediaRunnable)
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putInt(KEY_BLOCKED_COUNT, blockedCount.get()).apply()
    }

    override fun onStop() {
        appInBackground = true
        super.onStop()
    }

    override fun onDestroy() {
        mediaPlaying = false
        mediaHandler.removeCallbacks(stopMediaRunnable)
        mediaHandler.removeCallbacks(resumeMediaRunnable)
        MediaPlaybackService.registerWebView(null)
        MediaPlaybackService.stop(this)
        if (prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)) {
            clearBrowsingData(silent = true)
        }
        tabs.forEach { it.webView?.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val SHORTCUT_COLUMNS = 4
        private const val PREFS_NAME = "lightweight_browser_prefs"
        private const val KEY_BLOCKER_ENABLED = "blocker_enabled"
        private const val KEY_USE_RUST_ENGINE = "use_rust_engine"
        private const val KEY_BLOCKED_COUNT = "blocked_count"
        private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
        // Fresh installs stay on the verified Kotlin engine; Rust remains opt-in
        // through Privacy shield -> Engine (tap to switch).
        private const val DEFAULT_USE_RUST_ENGINE = false
        private const val KEY_TAB_IDS = "tab_ids"
        private const val KEY_TAB_URLS = "tab_urls"
        private const val KEY_TAB_TITLES = "tab_titles"
        private const val KEY_CURRENT_TAB = "current_tab"
        private const val REQUEST_POST_NOTIFICATIONS = 42
        private const val REQUEST_VPN = 43
        private const val MAX_RESUME_ATTEMPTS = 8
        private const val KEY_YOUTUBE_HANDOFF = "youtube_handoff"
        private const val HANDOFF_ASK = "ask"
        private const val HANDOFF_ALWAYS = "always"
        private const val HANDOFF_NEVER = "never"
    }
}
