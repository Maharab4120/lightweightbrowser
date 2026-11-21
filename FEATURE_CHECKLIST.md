# Essential browser feature checklist

Tested build: `app-debug.apk` from commit `6665e81` (`Fix attempted patch regressions`).
Device: `Infinix_X6831`, serial `099274036T005430`.
Date: 2026-11-21.

Statuses: **works**, **partly works**, **missing**.

## Results

| Feature | Status | How I tested it | Notes |
| --- | --- | --- | --- |
| Open/navigate to pages | **works** | Typed `https://example.com` and `https://example.org` into the URL bar; both rendered with the correct URL shown after revealing chrome. Typed a search phrase; it opened `https://duckduckgo.com/?q=...`. | Direct URLs, bare-domain input, and DuckDuckGo search all work. The URL field does **not** select the existing URL when focused, so a second navigation appends text unless the field is cleared manually. Chrome auto-hides after load and the peek handle restores it. |
| Back | **works** | With chrome visible, navigated `example.org` → Back → `example.com`. Also pressed hardware Back after a cleared session; it correctly returned to the speed dial when no WebView history remained. | Toolbar Back uses WebView history. Hardware Back first reveals hidden chrome, then steps back, then returns to speed dial, matching the intended behavior. |
| Forward | **works** | From `example.com`, tapped the bottom Forward button and confirmed the URL became `https://example.org/`. | Requires chrome to be visible before tapping the toolbar button; after navigation WebView chrome auto-hides again. |
| Reload | **works** | Tapped the bottom Reload button on `example.org`; page re-rendered and the URL stayed `https://example.org/`. | No crash or blank page observed. |
| Tabs | **partly works** | Tapped New Tab, confirmed two tabs (`Example Domain`, `New Tab`), switched back to the first tab, then closed the second tab and confirmed one tab remained. Force-stopped and relaunched the app; only a fresh New Tab returned. | In-session create/switch/close works. Tabs are **not persisted** across a full app restart; only `savedInstanceState` restore exists, so force-stop loses them. |
| Bookmarks | **missing** | Swept the top toolbar, bottom toolbar, speed dial, and shield Settings dialog with `uiautomator`; no bookmark UI. Source search found no bookmark model, storage, or action. | No add/remove/list/bookmark shortcut feature exists. |
| History | **missing** | Swept all visible UI and the shield Settings dialog; no history screen. Source search found only `WebView.clearHistory()` and back/forward navigation, no persisted history list. | WebView's internal back/forward list works, but there is no browsable history feature. |
| Downloads | **missing** | Navigated to a GitHub zip URL that returns `Content-Disposition: attachment`. No file appeared in `/sdcard/Download`, no UI or notification appeared, and logcat showed no download handling. Source search found no `setDownloadListener` or `DownloadManager` use. | Download links are effectively dead ends today. |
| Clear browsing data | **works** | Shield → `Clear browsing data now`. Shield count went `114 → 0`; prefs showed `blocked_count=0`; Back/Forward buttons became disabled after `clearHistory()`. | Clears WebView cache/history/form data, WebStorage, and cookies in one tap. There is no confirmation prompt, so the action is immediate. |

## Recommendation

Core page navigation, toolbar controls, and in-session tabs are already usable. The next best step is **reliability on the existing essentials** before adding large new surfaces:

1. Make the URL field select all on focus.
2. Persist tabs across restarts.
3. Handle download URLs explicitly instead of leaving them as silent no-ops.

Then add the missing basics in this order: **History → Bookmarks → Downloads**. History is the highest-value addition because back/forward already depends on a WebView history list that users currently cannot inspect.
