# Essential browser feature checklist

Updated: 2026-11-22, against commit `af44944`.

Statuses: **works**, **partly works**, **missing**.

## Results

| Feature | Status | Notes |
| --- | --- | --- |
| Open/navigate to pages | **works** | Direct URLs, bare domains, and DuckDuckGo search all work. The omnibox selects its existing text on focus. Chrome auto-hides after load and the peek handle restores it. |
| Back / Forward / Reload | **works** | WebView history controls behave as expected. The buttons are dimmed when unavailable. |
| Tabs | **partly works** | Create, switch, and close work in-session. Tabs are not persisted across a force-stop; only `savedInstanceState` restore exists. |
| Clear browsing data | **works** | Clears WebView cache/history/form data, WebStorage, and cookies. Shortcuts are stored separately and survive. |
| Downloads | **works** | A `Content-Disposition: attachment` response now starts a `DownloadManager` download with a completion notification. `data:` and `blob:` URLs are rejected. |
| File uploads | **works** | `<input type="file">` is wired through `onShowFileChooser` and returns the selected URI(s) to WebView. |
| Popups | **works** | `target="_blank"` opens a real tab through `WebViewTransport`; `window.close()` closes it. |
| Bookmarks | **missing** | No bookmark model, storage, or UI. |
| History | **missing** | WebView back/forward works, but there is no browsable history list. |
| WebRTC privacy | **works** | Blocked by default via document-start script; can be toggled in Privacy shield. |
| DNS filter | **works with limits** | Opt-in, DNS-only, IPv4 UDP system-resolver traffic through `10.23.42.2/32`. DoH/DoT/Private DNS and TCP DNS can bypass it. |
| YouTube | **works with limits** | Links open in the browser, not the YouTube app. Ad endpoints are filtered; video/player hosts stay allowed. Stream-stitched ads cannot be removed without breaking playback. |

## Recommendation

Core navigation, toolbar controls, downloads, uploads, and in-session tabs are
usable. The next best steps are:

1. Persist tabs across restarts.
2. Add a browsable History screen.
3. Add Bookmarks.

These match the remaining gaps above; downloads and the omnibox focus issue from
the original checklist are now resolved.
