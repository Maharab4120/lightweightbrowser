# LightweightBrowser

A small, privacy-focused Android browser built with the Android framework plus
Kotlin. It uses `Activity + WebView`, keeps filtering decisions local, and does
not depend on Material, AppCompat, or any UI toolkit.

The Kotlin layer owns UI, tabs, settings, history-free browsing, notifications,
and lifecycle. The Rust library answers ad-block decisions through JNI. Fresh
installs now use the Brave Rust engine by default, with the Kotlin fallback used
only if the native library or filter load fails. You can still switch engines via
Privacy shield → **Engine**.

## Features

- Brave/EasyList/uBO-based filtering with bundled full filter assets, larger Kotlin fallback tables, and locally authored scriptlets; no filter-list downloads or remote script execution.
- DuckDuckGo-style search from a single omnibox.
- Customizable home shortcuts (add, edit, remove) stored separately from browsing data.
- Optional DNS filter using a local VPN route only for `10.23.42.2/32`.
- YouTube opens in the browser like any other site; no native-app handoff.
- YouTube ad endpoints are filtered while player/video hosts stay allowed.
- Media notification controls, file uploads, downloads, and popup/tab support.
- Real document-start privacy injection through `androidx.webkit`.

## Architecture

- `app/src/main/java/com/lightweightbrowser/` — Kotlin UI/browser layer.
- `rust/src/lib.rs` — JNI bridge to `adblock-rust`; panics are caught before the JVM boundary.
- `app/src/main/assets/` — local filter and host rules.
- `app/src/main/jniLibs/` — Rust libraries for four ABIs (rebuilt by CI; also committable for local builds).

`namespace` is `com.lightweightbrowser`. The Play/`applicationId` is
`io.github.maharab4120.lightweightbrowser` and deliberately does not use
`com.example`.

## Prerequisites

- JDK 17 (Gradle 8.13 requires it)
- Android SDK platform 36 and build-tools 36.x
- Android NDK `25.2.9519653` (other versions may work, but this one is tested)
- Rust via rustup
- `cargo-ndk` (`cargo install cargo-ndk`)

Create `local.properties` in the repository root, or export `ANDROID_SDK_ROOT`:

```properties
sdk.dir=/home/your-user/Android/Sdk
```

`tools/build_rust.sh` accepts `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and
`ANDROID_NDK_HOME`, so a committed `local.properties` is not required in CI.

## Build

Rebuild the native library for all ABIs:

```bash
./tools/build_rust.sh
# or one ABI at a time on a memory-constrained machine:
./tools/build_rust.sh arm64-v8a
```

Build and test (run the bundle separately: AGP does not allow ABI-split APKs
and an AAB in the same invocation):

```bash
./gradlew assembleDebug assembleRelease testDebugUnitTest
./gradlew bundleRelease
```

Artifacts:

- Universal debug APK: `app/build/outputs/apk/debug/universal-debug.apk`
- Per-ABI release APKs: `app/build/outputs/apk/release/`
- App Bundle for Play: `app/build/outputs/bundle/release/app-release.aab`

Install the universal debug APK:

```bash
adb install -r app/build/outputs/apk/debug/universal-debug.apk
adb shell am start -n io.github.maharab4120.lightweightbrowser/com.lightweightbrowser.MainActivity
```

Changing the `applicationId` creates a new app identity on a device, so old
preferences and shortcuts are not migrated from the former `com.example` build.

Rust unit tests:

```bash
source "$HOME/.cargo/env"
cargo test --manifest-path rust/Cargo.toml
```

## Privacy and DNS limits

The DNS filter is opt-in and DNS-only. It:

- routes only `10.23.42.2/32` through the VPN — there is no general web tunnel;
- filters IPv4 UDP queries to system-resolver servers and returns local NXDOMAIN for blocked hosts;
- cannot see DoH, DoT, Private DNS, or TCP DNS traffic, which can bypass it.

YouTube opens in the browser by default. Background audio may pause when the
screen is off because WebView cannot keep the same background session as the
YouTube app. Ad requests are filtered, but ads stitched into the video stream by
YouTube cannot be removed here.

## Licensing

Application and JNI bridge code are licensed under MPL-2.0 (see `LICENSE`).
Bundled filter data has its own licenses and attribution; see
`THIRD_PARTY_NOTICES.md` and the comments in the filter assets. No JavaScript is
downloaded or executed from filter-list servers.
