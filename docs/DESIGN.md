# Design notes

## One codebase

The Android app adds nothing to Sol's protocol or logic. It runs the `sol` program from the main
repository and shows its panel. A fix or feature in Sol reaches the phone by rebuilding.

```
MainActivity (WebView) ──http──> 127.0.0.1:8080  panel
NodeService (foreground)
   └─ libsol.so run --data <app files>/sol          the Sol node
        └─ libtor.so                                 Tor (Tor Project build)
```

- **Programs named like libraries.** Android lets an app execute only files in its
  native-library directory, and only files named `lib*.so` are unpacked there. So `sol` ships as
  `libsol.so` and `tor` as `libtor.so`. Sol finds tor through `SOL_BIN_TOR`.
- **A foreground service** owns the node process. It starts with the app, keeps running in the
  background, and stops from the notification. It also supervises the node: see "Staying
  connected" below.
- **The panel is the UI.** The WebView loads only `http://127.0.0.1:8080`. Any other link, and
  any window the panel opens, goes to the phone's browser.
- **Data** lives in the app's private files directory. Backups are off (`allowBackup="false"`):
  `node.key` is the phone's identity and should not be copied to a cloud backup.

## Staying connected

The phone is a hostile place for a long-running network program. What the service does about it
(`NodeService`, with the Android-free logic in `core/`, unit-tested by `./build.sh test`):

- **Supervision.** The node runs with `SOL_SUPERVISED=1`, so "Restart" in the panel makes it
  exit 75 instead of spawning a replacement the service cannot see. Exit 75 restarts at once; a
  crash or a kill (Android's low-memory killer, 137) restarts after 1 s, doubling to 60 s
  (`Backoff`); the node exiting 0 is restarted too (SIGTERM and `sol stop` both give 0).
  The service stops the node only itself (`stopping`: user Stop, teardown), and `ExitPolicy` decides
  from that, not from the exit code. After three quick failures the notification says "keeps
  failing to start"; after 15 in a row (about ten minutes of backoff) the service gives up and
  reports stopped, and opening the app starts it again (`NodeStart`). `START_STICKY` brings the service back
  after Android kills the app; the user's Stop returns `START_NOT_STICKY`.
- **Stale lock.** Before every start the service looks at `control.sock`. If something answers, it
  is a node from a killed service and is asked to `sol stop`; if nothing answers, the file is a
  stale lock and is deleted. A socket that answers is never deleted.
- **Port.** The panel is started with `--panel 127.0.0.1:<port>`: 8080 if free, else the next
  free one, else any (`PortPicker`). The choice is saved (SharedPreferences `sol`/`panel_port`) so
  it stays put when possible, because the WebView's storage is per port.
- **Network changes.** A default-network callback feeds `NetDebounce` (3 s to settle, 30 s between
  reconnects). On a real change, or when the phone leaves Doze, the service runs
  `libsol.so network-changed`, so Tor rebuilds circuits instead of waiting for timeouts. An older
  node without that command just logs a failure. IPv6 addresses are ignored when deciding that
  the network changed, because privacy addresses rotate.
- **Truthful status.** The service polls the node's `/api/status` every 5 s and turns it into a
  `Phase` (starting, connecting, connected, reconnecting, no network, restarting, failing). The
  notification and the `io.solarae.sol.STATUS` broadcast report it.
- **Refused foreground start (Android 12+).** A sticky restart happens in the background, where
  `startForeground` throws `ForegroundServiceStartNotAllowedException`. The service logs it, sets
  the phase to stopped, calls `stopSelf()` and returns `START_NOT_STICKY`, so Android does not retry
  and there is no crash loop. The next time the app opens, `ensureNode` (`NodeStart.shouldStart`)
  sees stopped and starts the service from the foreground, which is allowed.
- **Log.** The node's output and the service's own `[app]` lines go to `files/sol/sol.log`, at
  most 1 MiB, then `sol.log.1`. Pairing links and keys are never logged by the service; the node
  does not log them either.
- **No wake lock.** The node is network-bound, and a held wake lock would cost battery all day.
  Doze can still cut the network while the screen is off; the cure is the battery-optimisation
  exemption, which the activity offers with an explanation
  (`NodeService.batteryExemptionIntent`). Leaving Doze triggers a reconnect.
- **Notifications.** The service type is `specialUse` (Android 14). Without the notification
  permission (Android 13+) the service still runs, but silently; `notificationsAllowed()` lets the
  activity say so.

## Why no VPN permission

An Android app can become a VPN (`VpnService`) and see every packet the phone sends. Tailscale
works this way. Sol deliberately does not.

**What the VPN permission would give us**

- A peer's services could have stable virtual addresses that every app reaches with no setup.
- Other apps would not need to be pointed at a local port.

**What it would cost**

- **One VPN at a time.** Android has a single VPN slot. Turning Sol on would turn Tailscale,
  WireGuard, Proton VPN or a work VPN off, and the other way round. People who self-host tend to
  already run one of those.
- **A frightening prompt.** The system dialog says the app can monitor all network traffic. For
  a privacy tool from an unknown developer, that is the wrong first impression, and it is true
  of the permission whether or not the app uses it.
- **A much larger attack surface.** A bug in a packet-level tunnel can leak or misroute all of
  the phone's traffic. Today a bug in Sol affects only connections to Sol peers.
- **More code only this platform uses**, which is what "one codebase" is meant to avoid.
- **Store review.** Google Play requires a declaration and review for VPN apps.

**What we do instead**

Sol is a layer-4 tool on every platform: a peer's service is forwarded to `127.0.0.1:<port>` on
this device (see "Tunnels" in the main repo's `docs/PEERS.md`). On Android that port is reachable
by the browser and by every other app, so the Jellyfin or Home Assistant app can simply be given
`http://127.0.0.1:9000`.

**What that costs**

- The user has to tell an app the local address once. Bookmarks keep working: a tunnel keeps its
  port across restarts.
- Any app on the phone can connect to an open tunnel. Close tunnels you don't use.
- Apps that insist on HTTPS with a public certificate won't accept a loopback address.

**When to revisit**

If people ask for "every app just works" more than they ask for coexisting with their VPN, a
VPN mode could be added as an opt-in, never as the default.

## What is not done yet

- Orphaned `libtor.so` processes after a hard kill of the node are not hunted down.
- No wake lock or `WorkManager` watchdog: if Android refuses a sticky restart from the background
  (Android 12+), the node stays off, reported as stopped, until the user opens the app. The refusal
  path has not been seen on a real device.
- Landscape and dark-mode bars were reviewed in code only: rotation is handled in the activity (insets
  are re-requested), a light/dark switch recreates it. A report of the WebView at portrait width after
  rotating had no cause in the code; check on a phone.
- Starting at boot.
- Reconnecting on network change was run on an emulator (Wi-Fi off/on and airplane mode both
  reach `network-changed` and restart Tor, which came back online), not yet on a real phone.
- In-process node (gomobile) instead of child processes, so Android's process limits can't
  kill tor behind the app's back.
- 32-bit ARM, release signing, F-Droid metadata. (An opt-in x86_64 build exists for the emulator only.)
- QR scanner for pairing links (`sol://pair` links from other apps are handled).
- Downloads always ask where to save (system file picker); no silent save to Downloads.
- The native screens, downloads and `sol://pair` flow have only been compiled, not run on a device.
- iOS. It cannot run child processes at all, so it needs the in-process node first.

## Testing on the emulator

`ABIS="arm64-v8a x86_64" ./build.sh`, then an API 34 x86_64 AVD (`ANDROID_AVD_HOME=$PWD/.toolchain/avd`,
`emulator -avd sol -no-window -no-audio -gpu swiftshader_indirect`).

- The image advertises arm64 through `libndk_translation`, but the Go node segfaults under it, so
  the arm64 APK does not run there. Use the x86_64 build.
- Software rendering is slow: SystemUI ANR dialogs ("Wait") are normal; Tor was online in about
  3 minutes from a cold install, the panel shows in about 12 s.
- `adb shell` can read `files/sol/sol.log` on a userdebug image. `kill` of the node works only
  sometimes from the shell user; a clean SIGTERM makes the node exit 0, which the service now restarts after about 1 s, like a SIGKILL.
