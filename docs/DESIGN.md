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
  background, and stops from the notification. If the node exits, the service stops, so the
  notification never claims a node that isn't there.
- **The panel is the UI.** The WebView loads only `http://127.0.0.1:8080`. Any other link, and
  any window the panel opens, goes to the phone's browser.
- **Data** lives in the app's private files directory. Backups are off (`allowBackup="false"`):
  `node.key` is the phone's identity and should not be copied to a cloud backup.

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

- In-process node (gomobile) instead of child processes, so Android's process limits can't
  kill tor behind the app's back.
- 32-bit ARM and x86-64 builds, release signing, F-Droid metadata.
- QR scanner for pairing links, and handling `sol://pair` links from other apps.
- Saving downloads from the Files screen.
- iOS. It cannot run child processes at all, so it needs the in-process node first.
