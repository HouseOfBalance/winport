# Build verification

Built on Ubuntu with OpenJDK 17, Rust 1.99.1, cargo-ndk 4.1.2, SDK 35, build tools 35.0.0 (AGP also installed 34.0.0), and NDK 27.2.12479018. Gradle wrapper 8.9. Native libraries were built for API 26.

## Rust tests

Command: `cd rust && cargo test`

```text
running 8 tests
test tests::each_missing_component_has_clear_error ... ok
test tests::corrupt_storage_is_not_overwritten ... ok
test tests::empty_args_and_affinity_are_valid ... ok
test tests::invalid_paths_and_profiles_are_rejected ... ok
test tests::launch_plan_preserves_arguments_and_settings ... ok
test tests::persistence_round_trip_and_empty_defaults ... ok
test tests::unsafe_zip_leaves_no_component ... ok
test tests::import_zip_and_container_crud ... ok

test result: ok. 8 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s

   Doc-tests winport_core

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

## Android native libraries

Command: `bash scripts/build-rust.sh` (cargo-ndk arm64-v8a and x86_64, `--platform 26`, `build --release --locked`). Output summary:

```text
    Building arm64-v8a (aarch64-linux-android)
    Finished `release` profile [optimized] target(s) in 1m 00s
    Building x86_64 (x86_64-linux-android)
    Finished `release` profile [optimized] target(s) in 34.78s
     Copying libraries to /home/ubuntu/repos/winport/android/app/src/main/jniLibs
```

## APK

Command: `cd android && ./gradlew assembleDebug --max-workers=2`

```text
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 8s
36 actionable tasks: 7 executed, 29 up-to-date
```

APK: `/home/ubuntu/repos/winport/android/app/build/outputs/apk/debug/app-debug.apk`

Command: `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | rg libwinport_core.so`

```text
   989744  1981-01-01 01:01   lib/arm64-v8a/libwinport_core.so
  1076472  1981-01-01 01:01   lib/x86_64/libwinport_core.so
```

The first assemble attempt hit HTTP 429 from Maven Central. The build now uses Google's public Maven Central mirror before Central. Initial compiler/manifest warnings were fixed, and the final assemble above succeeded. This verifies packaging and compilation, not execution of imported Windows games.

## Emulator

Installed on an Android 15 / API 35 x86_64 Google APIs emulator. The first emulator run crashed using `swiftshader_indirect`; restarting with `-gpu swiftshader -feature -Vulkan`, 2 cores and 1536 MB RAM booted successfully. Installation waited for `sys.boot_completed` (ADB connection alone is not package-manager readiness).

```text
Performing Streamed Install
Success
Starting: Intent { cmp=io.winport.app/.MainActivity }
Status: ok
LaunchState: COLD
Activity: io.winport.app/.MainActivity
TotalTime: 6064
WaitTime: 6159
Complete
```

Library screen visually inspected: `docs/screenshot.png` (1080 × 2400). The Rust JNI initialization created `files/core/state.json` in the app sandbox. Other screens and SAF workflows were compiled but were not manually exercised; Windows game execution was not tested and is not expected to work with the current direct-spawn backend on Android 10+.
