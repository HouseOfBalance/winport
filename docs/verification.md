# Libretro Step 1 verification

Ubuntu, OpenJDK 17, Rust 1.99.1, cargo-ndk 4.1.2, SDK 35, NDK 27.2.12479018. Gradle 8.9 / AGP 8.7.3; minSdk/native API 26, targetSdk 35. Runtime check: Android 15 / API 35 Google APIs x86_64 emulator, 2400 × 1080 landscape, 320 dpi, SwiftShader.

## Unit tests

Command: `cd rust && cargo test --locked` after the final Rust edit.

```text
running 13 tests
test libretro::tests::environment_directories_formats_and_legacy_defaults ... ok
test libretro::tests::formats_and_padded_pitch ... ok
test libretro::tests::modern_defaults_and_bounded_audio ... ok
test libretro::tests::rejects_invalid_frames ... ok
test libretro::tests::joypad_bitmask_analog_and_invalid_input ... ok
...
test result: ok. 13 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.05s

   Doc-tests winport_core
running 0 tests
test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

Coverage includes all three pixel formats and padded pitch, zero/truncated/unsupported frames, system/save directory pointers, legacy/default core options, v2 defaults, missing variables, unknown/null environment commands, bounded audio, joypad bitmasks and analog/invalid input. The eight existing storage/launch/component regression tests still pass; their Windows functionality is no longer exposed by the active UI.

## Native / Android build

Command: `cd android && ./gradlew assembleDebug --max-workers=2`, with the SDK/NDK/JDK environment documented in README loaded. One invocation after starting a fresh shell failed with `SDK location not found`; SDK files were present, but ANDROID_HOME was not exported. Loading the documented environment resolved it without source/configuration changes.

```text
    Building arm64-v8a (aarch64-linux-android)
   Compiling winport_core v0.1.0 (/home/ubuntu/repos/winport/rust)
> Task :app:buildRust
    Finished `release` profile [optimized] target(s) in 14.36s
    Building x86_64 (x86_64-linux-android)
   Compiling winport_core v0.1.0 (/home/ubuntu/repos/winport/rust)
    Finished `release` profile [optimized] target(s) in 12.53s
     Copying libraries to /home/ubuntu/repos/winport/android/app/src/main/jniLibs
...
> Task :app:assembleDebug

BUILD SUCCESSFUL in 31s
36 actionable tasks: 5 executed, 31 up-to-date
```

APK: `/home/ubuntu/repos/winport/android/app/build/outputs/apk/debug/app-debug.apk`.

`unzip -l android/app/build/outputs/apk/debug/app-debug.apk | rg '\.so'`:

```text
    10096  1981-01-01 01:01   lib/arm64-v8a/libandroidx.graphics.path.so
    10760  1981-01-01 01:01   lib/x86_64/libandroidx.graphics.path.so
  1052400  1981-01-01 01:01   lib/arm64-v8a/libwinport_core.so
  1147640  1981-01-01 01:01   lib/x86_64/libwinport_core.so
```

These are only the frontend and Compose graphics helper. APK inspection found no `_libretro_android.so`, generated demo or BIOS. The APK's `DebugProbesKt.bin` is a Kotlin coroutine resource, not a ROM. No core/BIOS/ROM binary is tracked in Git.

Final APK installation and cold start:

```text
Performing Streamed Install
Success
Starting: Intent { cmp=io.winport.app/.MainActivity }
Status: ok
LaunchState: COLD
Activity: io.winport.app/.MainActivity
TotalTime: 6082
WaitTime: 6125
Complete
```

## In-app runtime download and PS1 execution

Buildbot directory listings contained `swanstation_libretro_android.so.zip`, `mednafen_psx_libretro_android.so.zip`, `mednafen_psx_hw_libretro_android.so.zip` and `pcsx_rearmed_libretro_android.so.zip` for **both arm64-v8a and x86_64**. The app offers the software-capable SwanStation, Mednafen PSX and PCSX ReARMed choices. SwanStation is preferred when a legal BIOS is available; PCSX ReARMed was used for the HLE-only check.

Using the app UI, selected PCSX ReARMed · HLE and pressed Download core. The core came from:

`https://buildbot.libretro.com/nightly/android/latest/x86_64/pcsx_rearmed_libretro_android.so.zip`

It was installed at `/data/user/0/io.winport.app/files/cores/x86_64/pcsx_rearmed_libretro_android.so`; SHA-256:

```text
2f4ee471b6f692aac66ecf4aa92f2359eb11d6add7efa362a3ee3cd50f5731ee
```

Generated an original CC0 PS-X EXE with `python3 scripts/make-ps1-demo.py /home/ubuntu/winport-verification/winport-demo.exe`:

```text
/home/ubuntu/winport-verification/winport-demo.exe: 10240 bytes, original CC0 PS-X EXE
```

Pushed it to `/sdcard/Download/`, then selected it with the app's Open ROM / Android document picker. ROM SHA-256: `f40fff5e2c58b8e31eb39105a7387efee0b929a0f191623a11e46d888207b85d`.

Relevant logcat excerpts (tag `WinportRetro`):

```text
Starting PCSX-ReARMed r26l-137-g7b0e8f8 ... 64bit pic ssse3 lightrec gpu=BUILTIN_GPU
No BIOS files found.
Using PIXEL_FORMAT 2
plugin: /builtin_gpu
plugin: /builtin_spu
HLE BIOS initialized.
note: running with HLE BIOS, expect compatibility problems
port: 1  device: dualshock
```

`files/system` was absent before first launch and remained empty afterward. No Sony BIOS or commercial ROM was downloaded. The logs, app-private downloaded core and rendered homebrew demonstrate successful `dlopen` from writable app-private data on API 35 / targetSdk 35, not execution of an imported standalone program. The framebuffer shows the original WINPORT / PS1 / CC0 lettering, color bars and animated square. A later capture showed a different square position.

## State and SRAM round trips

Used Pause → Save state, Load state and Verify state round trip. Final app-process logcat:

```text
11-22 04:14:53.898  5897  6105 I WinportState: save {"savedBytes":4456448}
11-22 04:14:57.657  5897  6105 I WinportState: load {"loaded":true}
11-22 04:15:01.251  5897  6105 I WinportState: verify {"bytes":4456448,"frameAdvanced":true,"replayMatched":true,"restored":true}
```

The verifier writes the state file, runs five frames, reloads the file, runs the same five frames again, compares the complete software framebuffer and restores the file once more. The animated demo advanced and replayed identically, rather than merely accepting an unserialize call. An initial diagnostic compared serialized blobs and found they were not byte-identical even though unserialize succeeded; byte-identical reserialization is not assumed by the final verifier. The cause of the blob difference was not investigated. Pausing before the first video callback correctly reported `No software video frame to verify`; after resuming until video appeared, the final check above passed.

The clean Exit wrote a 131,072-byte `sram.bin` next to the 4,456,448-byte `state.bin`, under `files/saves/2f4ee471b6f692aac66ecf4a/<ROM hash>/`. A subsequent fresh session loaded with that file present and rendered again without a size/load error. This checks persistence of the core's initial memory-card contents, not an in-game memory-card write. PCSX also created/loaded its own 131,072-byte `pcsx-card2.mcd`.

AudioFlinger showed the app's 44,100 Hz stereo PCM AudioTrack and consumed frames. The homebrew is silent; audible correctness was not checked, and the software emulator reported underruns. No fatal Java/native crash appeared in the filtered runtime logs.

## Screenshot evidence

- `docs/ps1-core-installed.png` — user-triggered runtime download confirmation.
- `docs/ps1-homebrew.png` — actual PS1 software video, HLE, original virtual pad.
- `docs/ps1-state-roundtrip.png` — pause menu and successful state replay result.

These were visually inspected; no Compose preview or fabricated framebuffer was used. Older screenshots document the historical Windows-launcher UI only.

## Unverified / deferred

No physical ARM64 device, Bluetooth gamepad or audible test. KeyEvent/MotionEvent dispatch is implemented; Rust input mapping has unit coverage, but the silent noninteractive demo does not establish hardware controller behavior. SwanStation and Beetle availability was checked, but their execution/BIOS setup was not tested. No GPU rendering, multi-file disc import, full PlayStation library/BIOS UI, physical-device performance, suspend/process-death recovery or game compatibility matrix is claimed. Core downloads are HTTPS nightlies, not signed/pinned artifacts or sandboxed code. Nothing was pushed.
