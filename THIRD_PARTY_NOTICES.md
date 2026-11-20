# Third-party notices

LightweightBrowser bundles filter data and links one Rust crate. No filter-list
server is contacted at runtime and no JavaScript from a filter list is executed.

## Filter data

| Data | Source | License | Attribution |
| --- | --- | --- | --- |
| EasyList | https://easylist.to/easylist/easylist.txt | dual-licensed under Creative Commons Attribution-ShareAlike and the GNU General Public License | "The EasyList authors (https://easylist.to/)" |
| EasyPrivacy | https://easylist.to/easylist/easyprivacy.txt | dual-licensed under Creative Commons Attribution-ShareAlike and the GNU General Public License | "The EasyList authors (https://easylist.to/)" |
| uBlock Origin filters | https://github.com/uBlockOrigin/uAssets/blob/master/filters/filters.txt | GNU GPL v3 | "uBlock Origin contributors (https://github.com/uBlockOrigin/uAssets)" |
| Brave lists (specific, android-specific, social, cookie-specific, first-party, filters mirror, YouTube distracting/recommended/Shorts) | https://github.com/brave/adblock-lists | Mozilla Public License 2.0 | "Brave Software (https://github.com/brave/adblock-lists)" |

License references:

- CC BY-SA: https://creativecommons.org/licenses/by-sa/3.0/
- GNU GPL v3: https://www.gnu.org/licenses/gpl-3.0.html
- Mozilla Public License 2.0: https://www.mozilla.org/en-US/MPL/2.0/

The same attribution lines are embedded as comments in
`app/src/main/assets/adblock_filters.txt` and in the Kotlin fallback assets.
Canonical license texts are available at the URLs above.

## Rust dependency

- `adblock` (adblock-rust) 0.13.6 — MPL-2.0 — https://github.com/brave/adblock-rust
  Used unmodified as a Cargo dependency through the JNI bridge in `rust/`.
  `single-thread` is deliberately disabled because WebView interception runs on
  multiple threads; engine access is guarded by `RwLock`.

## Not bundled

- No AdGuard FiltersRegistry data is currently bundled.
- No scriptlet/snippet rules (`#?#`, `#$#`) are included in the Rust asset.
- No arbitrary JavaScript or `redirect`/`rewrite` resources are fetched or run.
