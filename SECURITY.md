# Security

Sol for Android is the [Sol](https://github.com/apv022/sol) node in an Android shell. Its
security properties, and what it does not protect, are in the main project's
[threat model](https://github.com/apv022/sol/blob/main/docs/THREAT_MODEL.md).

**Neither project has had an independent security review.** Treat this as alpha software.

## Reporting a vulnerability

Please report privately, not in a public issue:
[open a security advisory](https://github.com/apv022/sol-mobile/security/advisories/new) on this
repository (for the Android shell) or on [apv022/sol](https://github.com/apv022/sol/security/advisories/new)
(for the node, protocol or panel).

## Phone-specific notes

- **Identity.** `node.key` is in the app's private storage. Android cloud backup is disabled for
  the app, so the key is not copied off the phone. A lost phone should be unpaired from your
  other machines; that closes its connections at once.
- **Other apps on the phone** can connect to any tunnel you have open (`127.0.0.1:<port>`), as on
  a desktop. They cannot reach the panel's API without its CSRF token, and they cannot read the
  app's files.
- **The WebView** loads only the panel on `127.0.0.1:8080`, with JavaScript on and file access
  off. Cleartext HTTP is allowed to loopback only.
- **Tor** is the Tor Project's Android build. `build.sh` refuses it unless its checksum list is
  signed by the Tor Browser Developers key
  (`EF6E 286D DA85 EA2A 4BA7 DE68 4E2C 6E87 9329 8290`).
- **Release APKs are signed with a debug key** for now. Verify a download against the SHA-256
  printed in its release notes.
