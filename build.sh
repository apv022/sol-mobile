#!/usr/bin/env bash
# Builds the Sol Android app (build/sol.apk) with no Gradle and no Android
# Studio: the Go node is cross-compiled, the Java is compiled with javac and
# d8, and the APK is packed and signed with the Android build tools.
#
#   ./build.sh            build a debug APK for arm64 phones
#   ABIS="arm64-v8a x86_64" ./build.sh   also include x86_64 (for the emulator)
#   ./build.sh setup      only download the toolchain into .toolchain/
#   ./build.sh test       compile and run the plain-Java unit tests (app/src/.../core, test/)
#   ./build.sh java       only compile the app's Java against android.jar (no Go, no APK)
#
# Needs: bash, curl, tar, unzip, zip, gpg, and Go 1.24+. Everything else (a
# JDK, the Android SDK build tools, Tor's Android build) is downloaded into
# .toolchain/ on first use, about 1 GB. Nothing is installed system-wide.
#
# Environment:
#   ABIS      space-separated ABIs to package: arm64-v8a (default), x86_64.
#             The Android x86_64 emulator image's arm64 translation layer
#             crashes the Go node, so emulator testing needs x86_64. Release
#             builds use the default (arm64 only).
#   SOL_SRC   the Sol source tree to build the node from (default: ../Sol if
#             it exists, else a clone of github.com/apv022/sol at SOL_REF)
#   SOL_REF   git ref to clone when SOL_SRC is not set (default: main)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
TC=$ROOT/.toolchain
OUT=$ROOT/build

# Pinned versions. Bump them together with a test on a real phone.
TOR_BROWSER=16.0.2          # dist.torproject.org/torbrowser/<this>/ holds tor-expert-bundle-android-*
BUILD_TOOLS=35.0.0
PLATFORM=android-35
MIN_SDK=26                  # Tor's Android build needs 26
TARGET_SDK=34
VERSION_NAME=0.1.0-alpha
VERSION_CODE=1
ABIS=${ABIS:-arm64-v8a}
# Per-ABI names: Go's GOARCH, Tor's bundle name, and where the bundle is unpacked.
# (arm64 keeps the original .toolchain/tor path so existing toolchains are reused.)
abi_goarch() { case $1 in arm64-v8a) echo arm64;; x86_64) echo amd64;; *) echo "unsupported ABI: $1" >&2; exit 1;; esac; }
# Go's android/amd64 needs cgo and the NDK, which this build does not use, so
# the x86_64 node is a static linux/amd64 binary: Android's kernel runs it, and
# it is only meant for the emulator. arm64 phones get the real android build.
abi_goos() { case $1 in x86_64) echo linux;; *) echo android;; esac; }
abi_tor() { case $1 in arm64-v8a) echo aarch64;; x86_64) echo x86_64;; *) echo "unsupported ABI: $1" >&2; exit 1;; esac; }
abi_tordir() { case $1 in arm64-v8a) echo "$TC/tor";; *) echo "$TC/tor-$1";; esac; }
# The Tor Browser Developers signing key (https://support.torproject.org/tbb/how-to-verify-signature/).
TOR_KEY=EF6E286DDA85EA2A4BA7DE684E2C6E8793298290

say() { printf '\n== %s\n' "$*"; }

setup() {
  mkdir -p "$TC/dl"
  if [ ! -x "$TC/jdk/bin/javac" ]; then
    say "JDK 17 (Eclipse Temurin)"
    curl -fsSL -o "$TC/dl/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "$TC/jdk" && tar -xzf "$TC/dl/jdk.tar.gz" -C "$TC/jdk" --strip-components=1 && rm "$TC/dl/jdk.tar.gz"
  fi
  export JAVA_HOME=$TC/jdk PATH=$TC/jdk/bin:$PATH
  if [ ! -x "$TC/sdk/build-tools/$BUILD_TOOLS/aapt2" ] || [ ! -f "$TC/sdk/platforms/$PLATFORM/android.jar" ]; then
    say "Android SDK build tools $BUILD_TOOLS and $PLATFORM"
    if [ ! -x "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
      curl -fsSL -o "$TC/dl/cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
      mkdir -p "$TC/sdk/cmdline-tools" && (cd "$TC/sdk/cmdline-tools" && unzip -q -o "$TC/dl/cmdline-tools.zip" && rm -rf latest && mv cmdline-tools latest)
      rm "$TC/dl/cmdline-tools.zip"
    fi
    echo "This accepts the Android SDK licence terms (https://developer.android.com/studio/terms)."
    yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TC/sdk" --licenses >/dev/null
    "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TC/sdk" "build-tools;$BUILD_TOOLS" "platforms;$PLATFORM" >/dev/null
  fi
  local abi
  for abi in $ABIS; do
    local tdir; tdir=$(abi_tordir "$abi")
    [ -f "$tdir/tor/libTor.so" ] && continue
    say "Tor $TOR_BROWSER for Android ($abi), signature checked"
    local base="https://dist.torproject.org/torbrowser/$TOR_BROWSER" f="tor-expert-bundle-android-$(abi_tor "$abi")-$TOR_BROWSER.tar.gz"
    (cd "$TC/dl" && curl -fsSL -O "$base/$f" -O "$base/sha256sums-signed-build.txt" -O "$base/sha256sums-signed-build.txt.asc")
    export GNUPGHOME=$TC/gnupg; mkdir -p -m 700 "$GNUPGHOME"
    gpg --batch --quiet --auto-key-locate nodefault,wkd --locate-keys torbrowser@torproject.org >/dev/null 2>&1 || true
    # The checksum list must be signed by the pinned key, and the bundle must match the list.
    gpg --batch --status-fd 1 --verify "$TC/dl/sha256sums-signed-build.txt.asc" "$TC/dl/sha256sums-signed-build.txt" 2>/dev/null \
      | grep -q "^\[GNUPG:\] VALIDSIG .* $TOR_KEY\$" || { echo "Tor checksum list: signature is not from $TOR_KEY" >&2; exit 1; }
    (cd "$TC/dl" && grep " $f\$" sha256sums-signed-build.txt | sha256sum -c --quiet -)
    rm -rf "$tdir" && mkdir -p "$tdir" && tar -xzf "$TC/dl/$f" -C "$tdir"
  done
  if [ ! -f "$TC/debug.keystore" ]; then
    say "Debug signing key (stays in .toolchain/, never committed)"
    keytool -genkeypair -keystore "$TC/debug.keystore" -storepass android -keypass android -alias sol-debug \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Sol debug build" >/dev/null 2>&1
  fi
}

build() {
  local bt=$TC/sdk/build-tools/$BUILD_TOOLS jar=$TC/sdk/platforms/$PLATFORM/android.jar abi
  rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

  say "Sol node for android ($ABIS)"
  local src=${SOL_SRC:-}
  if [ -z "$src" ] && [ -f "$ROOT/../Sol/go.mod" ]; then src=$ROOT/../Sol; fi
  if [ -z "$src" ]; then
    src=$TC/sol-src
    rm -rf "$src" && git clone --quiet --depth 1 --branch "${SOL_REF:-main}" https://github.com/apv022/sol "$src"
  fi
  local rev; rev=$(git -C "$src" rev-parse --short HEAD 2>/dev/null || echo unknown)
  echo "from $src ($rev)"
  # Programs must be named lib*.so to be unpacked where Android lets an app run them.
  local abi
  for abi in $ABIS; do
    mkdir -p "$OUT/lib/$abi"
    (cd "$src" && CGO_ENABLED=0 GOOS=$(abi_goos "$abi") GOARCH=$(abi_goarch "$abi") go build -trimpath \
       -ldflags "-s -w -X main.version=$VERSION_NAME-android+$rev" -o "$OUT/lib/$abi/libsol.so" ./cmd/sol)
    cp "$(abi_tordir "$abi")/tor/libTor.so" "$OUT/lib/$abi/libtor.so"
  done

  say "Resources and Java"
  "$bt/aapt2" compile --dir app/res -o "$OUT/res.zip"
  "$bt/aapt2" link -o "$OUT/unsigned.apk" -I "$jar" --manifest app/AndroidManifest.xml --java "$OUT/gen" \
     --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
     --version-name "$VERSION_NAME" --version-code $VERSION_CODE "$OUT/res.zip"
  javac -Xlint:-options -source 11 -target 11 -classpath "$jar" -d "$OUT/classes" \
     $(find app/src "$OUT/gen" -name '*.java')
  "$bt/d8" --lib "$jar" --min-api $MIN_SDK --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

  say "Package and sign"
  (cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
  (cd "$OUT" && zip -q "$OUT/unsigned.apk" $(for abi in $ABIS; do echo lib/$abi/libsol.so lib/$abi/libtor.so; done))
  "$bt/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
  "$bt/apksigner" sign --ks "$TC/debug.keystore" --ks-pass pass:android --key-pass pass:android \
     --out "$OUT/sol.apk" "$OUT/aligned.apk"
  "$bt/apksigner" verify "$OUT/sol.apk"
  rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/res.zip" "$OUT/sol.apk.idsig"
  say "Built $OUT/sol.apk ($(du -h "$OUT/sol.apk" | cut -f1))"
  sha256sum "$OUT/sol.apk"
}

# The Android-free classes in core/ are tested on a plain JVM: JUnit is not in
# the toolchain, so test/.../CoreTests.java is a main() that exits non-zero on failure.
unit_tests() {
  export JAVA_HOME=$TC/jdk PATH=$TC/jdk/bin:$PATH
  local o=$OUT/test-classes
  rm -rf "$o" && mkdir -p "$o"
  say "Unit tests"
  javac -Xlint:-options -source 11 -target 11 -d "$o" $(find app/src/io/solarae/sol/core test -name '*.java')
  java -cp "$o" io.solarae.sol.core.CoreTests
}

# Compiles all the app's Java (with the generated R) to check it, without building the node.
java_only() {
  export JAVA_HOME=$TC/jdk PATH=$TC/jdk/bin:$PATH
  local bt=$TC/sdk/build-tools/$BUILD_TOOLS jar=$TC/sdk/platforms/$PLATFORM/android.jar o=$OUT/java-check
  rm -rf "$o" && mkdir -p "$o/gen" "$o/classes"
  "$bt/aapt2" compile --dir app/res -o "$o/res.zip"
  "$bt/aapt2" link -o "$o/x.apk" -I "$jar" --manifest app/AndroidManifest.xml --java "$o/gen" \
     --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK "$o/res.zip"
  javac -Xlint:-options -source 11 -target 11 -classpath "$jar" -d "$o/classes" $(find app/src "$o/gen" -name '*.java')
  say "Java compiles"
}

case "${1:-}" in
  setup) setup ;;
  test) setup; unit_tests ;;
  java) setup; java_only ;;
  *) setup; unit_tests; build ;;
esac
