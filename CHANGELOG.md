# Changelog

## [Unreleased]

Run on an API 34 x86_64 emulator; not yet verified on a real phone (see docs/DESIGN.md).

### Added

- Tagged releases: pushing `vX.Y.Z[-alpha.N]` builds, signs with the release key and publishes the APK, `SHA256SUMS` and notes. `build.sh` takes `SOL_VERSION` (derives versionCode) and `SOL_KEYSTORE`/`SOL_KEYSTORE_PASS`/`SOL_KEY_ALIAS`; the first release-signed build needs a one-time uninstall of the debug-signed alpha.
- The node is supervised: if it dies it is started again with a growing delay; only Stop leaves it off.
- Reconnects when the phone changes network (Wi-Fi, mobile data, airplane mode, back from Doze) by running `sol network-changed`, if the node supports it.
- The panel moves to a free port when 8080 is taken.
- The node's log is capped (two files of 1 MiB) and the notification says what is really happening.
- Native loading screen showing what the node is doing while it starts; if the panel never answers, an error screen with "Restart node" and "View log" (with Share).
- Downloads from the panel are saved through the system file picker, with names cleaned; uploads use the file chooser.
- `sol://pair?...` links open the panel's join screen with the link filled in (never pairs by itself). The WebView user agent ends in `SolAndroid/<version>`.
- First run asks for the notification permission (Android 13+) and offers the battery exemption once, with an explanation.
- Edge-to-edge window with light and dark system bars, and the keyboard resizes the panel.
- `ABIS="arm64-v8a x86_64" ./build.sh` builds an x86_64 APK for emulators (arm64 stays the default; the x86_64 build is for the emulator only).
- Unit tests for the service logic: `./build.sh test`. The "should the app start the service" decision is plain Java with unit tests.

### Changed

- A node that exits on SIGTERM (status 0) is restarted: the service only treats an exit as "stopped" when it asked for it itself. After 15 early deaths in a row it gives up and the app offers to start it.
- Opening the app (or relaunching it while it is open) starts the node again if the service had stopped.
- The panel is loaded from the node's real port, and re-pointed if the port changes.
- A crashed WebView renderer is rebuilt; panel history survives rotation and process death; Back goes through panel history first.
- Hardening: no mixed content, no Safe Browsing lookups of loopback URLs, other links open in the browser.

### Fixed

- If Android 12+ refuses a background restart of the service, it logs that, reports "stopped" and does not retry; opening the app starts it.
- Dark mode: the activity is recreated on a light/dark switch (the window background and bar icons were left in the old mode, which showed as a light strip under the tab bar), and the 3-button navigation scrim is off.
- A stale control socket left by a kill no longer blocks the next start.
- A node that exited cleanly used to leave the panel saying it would reconnect by itself, for ever.

## [0.1.0-alpha] - 2026-11-11

First build.

- The Sol node and Tor run in a foreground service, with a notification and a Stop button.
- Sol's panel is the whole interface; a peer's app opens in the phone's browser.
- Sending files from the phone through the system file picker.
- arm64, Android 8.0 and newer. No VPN permission (docs/DESIGN.md).
