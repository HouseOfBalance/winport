# Winport

A small, buildable **Winlator-style launcher scaffold**: Kotlin / Jetpack Compose on Android, with a Rust core behind a JSON JNI boundary. Landscape, immersive console-style UI, local game library, Wine prefix containers, editable performance presets and user-imported runtime components.

<img src="docs/home-two-games.png" alt="Winport console home running in landscape on an Android 15 emulator" width="960" />

**This is not yet a working Windows runtime on modern Android.** The launcher builds and packages a real Rust native library; it does not bundle or silently download Box64, Wine, DXVK or a Linux rootfs. No games are included.

## Architecture

```
android/app/.../ConsoleActivity.kt Immersive activity, home, game hub, library, routing
             ConsoleKit.kt        Original vector icons, focus motion, artwork, ambient backdrop
             ConsoleOverlays.kt   Options menu, Control Center, confirmation/error overlays
             ConsoleForms.kt      Fullscreen game form and profile controls
             ConsoleSettings.kt   Settings list and two-pane management pages
             CoreViewModel.kt     Async JNI calls and Android SAF stream copying
             NativeCore.kt        Serializable view models + JSON bridge
                            ↓ JNI: NativeCore.call(method, JSON) → JSON
rust/src/lib.rs                   Core, validated CRUD, process ownership
         storage.rs               Atomic JSON snapshot replacement
         components.rs            Staged archive import and registry
         containers.rs            Wine prefix metadata
         profiles.rs              Performance / Balanced / Compatibility
         library.rs               Game metadata
         launch.rs                Pure launch plan, spawn and stop
         jni.rs                   Mutex-protected core, exception translation
```

Domain state, validation, archive extraction, presets, launch environment/argv and process control live in Rust. Kotlin owns UI presentation and the Android-only SAF file transport. Disk-heavy work and JNI calls run off the main thread. Persistence lives in `files/core/state.json`; prefixes and components are siblings beneath that directory. Artwork URI permissions and last launch-attempt timestamps are presentation-only Android preferences. There is no network permission or backend.

## Build (Linux, JDK 17)

Prerequisites: Rust stable, a C toolchain, `unzip`, JDK 17, Android SDK command-line tools. The checked-in Gradle wrapper uses Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21. The application targets SDK 35 and supports API 26+.

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$HOME/.cargo/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# Download Android command-line tools from developer.android.com/studio#command-tools
# and extract them to $ANDROID_HOME/cmdline-tools/latest first.
sdkmanager --licenses
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018'
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked

cd rust
cargo test --locked
cd ..
bash scripts/build-rust.sh
cd android
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg libwinport_core.so
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleDebug` automatically runs `:app:buildRust`, invoking cargo-ndk for **arm64-v8a and x86_64**, API 26, release mode. Output goes to `android/app/src/main/jniLibs/<abi>/libwinport_core.so` and is ignored by Git. To build only arm64 manually:

```sh
cd rust
cargo ndk -t arm64-v8a --platform 26 -o ../android/app/src/main/jniLibs build --release --locked
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Use environment variables above (or Android Studio's `local.properties` for the SDK); no machine-specific SDK path is committed. Google's public Maven Central mirror precedes Maven Central because the latter returned HTTP 429 in the build environment. Add these SDK/NDK/JDK and Rust targets to your Devin environment blueprint to avoid reinstalling in a future session.

## Use

1. **Settings → Components:** choose a kind and import a trusted `.tar.xz`, `.tar.gz` or `.zip` through the Android file picker. The content URI is copied to a private temporary cache directory, extracted by Rust into staging, then renamed into place. Failed imports remove staging and leave the registry unchanged.
2. **Settings → Containers:** select an imported Wine version and create a named prefix. Creating a container makes the directory, **not a fully initialized Wine installation**.
3. **Home → Add game:** select the container and profile in the fullscreen form. Choose a `.exe` with SAF (extension checked after selection because providers do not agree on an EXE MIME type), or enter an existing container-relative path. An imported EXE is copied to `drive_c/imports/<uuid>/filename.exe`. Importing an EXE alone does **not** copy its adjacent DLLs/assets; place a complete game tree in the prefix separately. Optionally choose artwork through SAF; otherwise an original gradient is generated from the game name.
4. Extra arguments are one argument per line. Paths and arguments containing spaces stay individual argv entries; no shell is involved. Select the saved game, then its **Launch command** activity card to preview the environment, argv and CPU affinity. The round **…** button opens Edit game, Change container, Change profile and Remove; changes use the same fullscreen form.
5. **Settings → Performance Profiles** exposes Box64 big-block / strong-memory / safe-flags controls, DXVK HUD, FPS limit (0 = unlimited), resolution and comma-separated CPU cores (blank = OS default). Profiles are editable; presets are created only on first initialization.
6. Launch reports a PID if spawning succeeds; Stop only accepts a process owned by the current core session, kills its process group and reaps it. Running indicators are session-local and are cleared with Stop; they are not a live process monitor.

Container and component deletion is permanent and asks for confirmation. A Wine component referenced by a container, a container referenced by a game, or a profile referenced by a game cannot be removed. Removing a game only removes its library metadata, not its files.

### Console navigation

Use touch or a D-pad: focus moves between tiles, tabs and controls; **A / Enter / D-pad center** selects and **B / Back / Escape** returns. Select a game tile to move to its Play button. **Start / Menu**, the avatar or the bottom Control Center button opens the sliding bottom overlay. Swipe upward from the bottom edge to open it as well. Power → Exit app closes the launcher. Sound controls Android media volume; Network and Accessibility link to Android settings. Media intentionally starts empty.

The UI uses Android's system sans font, original Canvas-drawn icons and generated artwork. No Sony logos, controller-button artwork, fonts, sounds or images are included. This independent project is not affiliated with Sony Interactive Entertainment.

## Screenshots

Captured on an Android 15 / API 35 x86_64 emulator, **2400 × 1080, landscape, 320 dpi**, with system bars hidden:

| State | Screenshot |
| --- | --- |
| Fresh library / empty home | [docs/home-empty.png](docs/home-empty.png) |
| Home with two runtime-added games | [docs/home-two-games.png](docs/home-two-games.png) |
| Focused game hub and options menu | [docs/game-options.png](docs/game-options.png) |
| Control Center | [docs/control-center.png](docs/control-center.png) |
| Settings | [docs/settings.png](docs/settings.png) |
| Two-pane performance profile detail | [docs/settings-profiles.png](docs/settings-profiles.png) |

OpenTTD 14.1 and Luanti 5.11.0 were downloaded externally and added **only to the emulator's private runtime state using ADB and the real JNI CRUD API**, with their actual Windows EXEs and game directories copied into a container. Wine 10.0 was imported from a flattened upstream archive to create that container. No games, artwork, runtime binaries or seeded game records are bundled or committed. The first screenshot was taken before these imports. These screenshots verify the launcher, not game execution; Box64, DXVK, rootfs and the Android execution backend remain unprovisioned.

### Archive layout and selection

The archive **basename** is its version, e.g. `wine-9.0.tar.xz` → `components/wine/wine-9.0/`. Only ASCII letters, numbers, `.`, `_`, `-` are allowed in basenames. Duplicate kind/version directories are rejected rather than overwritten. The archive must contain the following paths directly (no extra top-level directory):

| Kind | Expected content |
| --- | --- |
| Box64 | `bin/box64` (ARM64 Linux build, not bundled) |
| Wine | `bin/wine` and its matching Wine installation |
| Dxvk | `x64/*.dll` |
| Rootfs | `lib/x86_64-linux-gnu/`, `usr/lib/x86_64-linux-gnu/`, loader and required system files |

Containers pin a Wine component ID. Other kinds use the **first installed** component of that kind. Import is a registry operation, not binary compatibility validation. Archives with symlinks, hardlinks or special files are deliberately rejected, and traversal/absolute paths are rejected. Most distribution rootfs archives contain links: prepare a flattened, regular-file-only archive first. ZIP executable modes are preserved when present; use tar for reliable Unix permissions. Imports are not size-quota limited: only import trusted archives and check free space. Runtime binaries are untrusted native code and are not sandboxed beyond Android's app sandbox.

## JSON JNI API

`object NativeCore` loads `winport_core`; `external fun call(method: String, payload: String): String` is the only native entry point. Call `init` with `{"data_dir":"/absolute/private/path"}` once. JNI failures throw `IllegalStateException` with a readable Rust error; calls must not assume an `{"error":...}` return. A mutex serializes operations and all mutations persist before returning success.

| Method | Payload / result |
| --- | --- |
| `snapshot` | `{}` → components, containers, profiles, games |
| `listGames`, `listProfiles`, `listContainers`, `listComponents` | `{}` → JSON array |
| `addGame`, `updateGame` | Full Game object; add ignores supplied id and creates UUID |
| `addProfile`, `updateProfile` | Full Profile object; add creates UUID |
| `addContainer`, `updateContainer` | `name`, `wine_component`; update also requires `id`; Rust owns dir |
| `installComponent` | `archive_path`, `kind` (`Box64`, `Wine`, `Dxvk`, `Rootfs`) → Component |
| `updateComponent` | Full Component object; only display version is mutable, not id/path/kind |
| `removeGame`, `removeProfile`, `removeContainer`, `removeComponent` | `id` |
| `buildLaunchPlan` | `id` (saved game) → `env`, `argv`, `cwd`, `cpu_cores` |
| `launch` | `id` → `{"pid":1234}` |
| `stop` | `pid` → `{}` |

Model fields are defined in `rust/src/{library,profiles,containers,components}.rs`. Missing components fail clearly, even during preview. The plan function is pure and intentionally does not require binaries to exist on the machine doing the preview.

## Honest limits / next steps

- **Android 10+ W^X / SELinux prevents direct exec of binaries from writable app data** for modern-target apps. Current `spawn` is a diagnostic direct-execution backend; it is not a workaround. Real launch needs a packaged, policy-compliant **proot / ld-linux loader approach like Winlator**, native ABI handling, rootfs mappings and careful lifecycle management. Rootfs metadata and library search paths alone do not implement that backend.
- No X11/Wayland display server, Vulkan/Turnip/Mesa integration, audio server, controller mapping or Wine prefix bootstrap is included. DXVK paths and DLL override hints are generated, but **DXVK DLL deployment and Wine configuration still need implementation**. On-device rendering will not work merely by importing components.
- Box64 runs x86-64 Linux code on ARM64; building this UI for an x86_64 emulator verifies the launcher/JNI, not ARM emulation or Windows game compatibility. 32-bit x86/WoW64 is not implemented.
- There is no durable Android foreground service, process resurrection, live exit observer or runtime log viewer. Stop is scoped to this app process; Android can terminate it.
- No component signature verification, updater, migration framework, launch sandbox or import disk quota yet. Back up data before experimenting. JSON corruption is reported instead of being silently replaced.
- **AAA games are unlikely to run well**, even after a complete backend: translation overhead, GPU drivers, memory pressure, anti-cheat/DRM and thermal throttling remain major constraints. No performance or compatibility claims are made.

## Verification

Rust tests exercise launch argv/environment, empty arguments, all four missing-component errors, invalid settings and traversal paths, persistence round-trip and corrupt state, staged ZIP import, unsafe ZIP rejection, container directories/deletion guards and PID ownership. `docs/verification.md` records build and emulator verification. See the screenshots above for the redesigned launcher; none verifies Windows execution.
