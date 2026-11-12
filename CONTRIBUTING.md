# Contributing

Thanks for looking. Most of Sol lives in the [main repository](https://github.com/apv022/sol):
the node, the protocol and the panel. Changes there reach Android by rebuilding. This repository
is only the Android shell, so the most useful contributions here are:

- **Test reports from real phones.** Model, Android version, what you did, what happened, and
  the last lines of the log (shown on screen if the node doesn't start).
- **Android lifecycle fixes**: battery savers, process limits, notifications.
- **Packaging**: more CPU architectures, reproducible builds, F-Droid metadata.

## Building

`./build.sh` builds `build/sol.apk`; the README lists what it needs. Install it with
`adb install -r build/sol.apk`, and read the node's log with
`adb shell run-as io.solarae.sol cat files/sol/sol.log`.

## Ground rules

- Keep the shell thin. Logic belongs in Sol, where every platform gets it.
- No new permission without a paragraph in `docs/DESIGN.md` saying why.
- No analytics, no crash reporting, no network calls of the app's own.
- Don't commit anything from `.toolchain/` (it holds the signing key).
