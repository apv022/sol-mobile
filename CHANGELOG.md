# Changelog

## [Unreleased]

- A node that exits on SIGTERM (status 0) is restarted: the service only treats an exit as "stopped" when it asked for it itself. After 15 early deaths in a row it gives up and the app offers to start it.
- If Android 12+ refuses a background restart of the service, it logs that, reports "stopped" and does not retry; opening the app starts it.
- Dark mode: the activity is recreated on a light/dark switch (the window background and bar icons were left in the old mode, which showed as a light strip under the tab bar), and the 3-button navigation scrim is off.
- The "should the app start the service" decision is plain Java with unit tests.
- Opening the app (or relaunching it while it is open) starts the node again if the service had stopped; before, a node that exited cleanly left the panel saying it would reconnect by itself, for ever.
- `ABIS="arm64-v8a x86_64" ./build.sh` builds an x86_64 APK for emulators (arm64 stays the default).

- The node is supervised: if it dies it is started again with a growing delay; only Stop leaves it off.
- Reconnects when the phone changes network (Wi-Fi, mobile data, back from Doze), if the node supports `network-changed`.
- The panel moves to a free port when 8080 is taken.
- The node's log is capped (two files of 1 MiB) and the notification says what is really happening.
- A stale control socket left by a kill no longer blocks the next start.
- Unit tests for the service logic: `./build.sh test`.
- A native loading screen shows what the node is doing while it starts, retries until the panel answers, and offers "Restart node" and "View log" (with Share) if it never does.
- The panel is loaded from the node's real port, and re-pointed if the port changes.
- A crashed WebView renderer is rebuilt; panel history survives rotation and process death; Back goes through panel history first.
- Edge-to-edge window with light and dark system bars, and the keyboard resizes the panel.
- Downloads from the panel are saved through the system file picker, with names cleaned.
- `sol://pair?...` links open the panel's join screen with the link filled in (never pairs by itself). The WebView user agent ends in `SolAndroid/<version>`.
- First run asks for the notification permission (Android 13+) and offers the battery exemption once, with an explanation.
- Hardening: no mixed content, no Safe Browsing lookups of loopback URLs, other links open in the browser.

## [0.1.0-alpha] - 2026-11-11

First build.

- The Sol node and Tor run in a foreground service, with a notification and a Stop button.
- Sol's panel is the whole interface; a peer's app opens in the phone's browser.
- Sending files from the phone through the system file picker.
- arm64, Android 8.0 and newer. No VPN permission (docs/DESIGN.md).
