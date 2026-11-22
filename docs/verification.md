# Libretro Step 2 — GLES PSP / PS2 verification

Same Android 15 / API 35 x86_64 emulator as Step 1, 2400 × 1080 landscape. Renderer from SurfaceFlinger and the frontend's current EGL context:

```text
GLES: Google (Google Inc.), Android Emulator OpenGL ES Translator (Google SwiftShader), OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)
```

## Final checks

`cd rust && cargo test --locked`, after the last Rust edit:

```text
running 16 tests
test libretro::tests::av_changes_and_hardware_preference ... ok
test retro_hw::tests::aspect_fit_and_invalid_geometry ... ok
test retro_hw::tests::rejects_desktop_vulkan_and_invalid_es_requests ... ok
...
test result: ok. 16 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.02s

running 0 tests
test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

Coverage adds hardware-context request validation, aspect fitting (including zero/invalid geometry), preferred hardware rendering, AV/geometry changes and null hardware requests. Existing software pixel, environment, input, bounded-audio and storage/launch tests remain enabled.

`cd android && ./gradlew assembleDebug --max-workers=2`, after the same edit:

```text
    Building arm64-v8a (aarch64-linux-android)
   Compiling winport_core v0.1.0 (/home/ubuntu/repos/winport/rust)
    Finished `release` profile [optimized] target(s) in 3.99s
    Building x86_64 (x86_64-linux-android)
   Compiling winport_core v0.1.0 (/home/ubuntu/repos/winport/rust)
    Finished `release` profile [optimized] target(s) in 3.74s
     Copying libraries to /home/ubuntu/repos/winport/android/app/src/main/jniLibs
...
> Task :app:assembleDebug

BUILD SUCCESSFUL in 9s
36 actionable tasks: 5 executed, 31 up-to-date
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

```text
    10096  1981-01-01 01:01   lib/arm64-v8a/libandroidx.graphics.path.so
    10760  1981-01-01 01:01   lib/x86_64/libandroidx.graphics.path.so
  1071976  1981-01-01 01:01   lib/arm64-v8a/libwinport_core.so
  1168664  1981-01-01 01:01   lib/x86_64/libwinport_core.so
```

ZIP inspection found no `_libretro_android.so`, ELF/PBP/EXE test content or PGF fonts in the APK. Cores, generated content and asset archives remain outside Git. The installed final APK reported `Success` and cold-started successfully. A fresh-shell build had lost `ANDROID_HOME`; a machine-local, ignored `android/local.properties` now points to the installed SDK. It is not committed. The earlier compiler warning about mismatched `ANativeWindow_release` declarations was corrected; the final native build had no such warning.

## Core and asset availability

Checked both directory listings under `https://buildbot.libretro.com/nightly/android/latest/<abi>/`:

| Exact archive | arm64-v8a | x86_64 |
| --- | --- | --- |
| `ppsspp_libretro_android.so.zip` | present | present |
| `play_libretro_android.so.zip` | present | present |
| `pcsx2_libretro_android.so.zip` | present | present |
| `lrps2_libretro_android.so.zip` | absent | absent |

PCSX2 was not selected, bundled or executed. Availability does not establish Android runtime support or remove that core's own BIOS requirements. No Sony BIOS was fetched.

Selected PSP and PS2 on the same in-app core screen and pressed Download core for each. Both downloaded directly from their x86_64 nightly URLs into app-private storage. PPSSPP's user-triggered download also installed `https://buildbot.libretro.com/assets/system/PPSSPP.zip` under `files/system/PPSSPP`, including the archive's `LICENSE.TXT`, `flash0/font/ltn0.pgf` and the PPGe atlas. This is PPSSPP's open-source system-asset archive, not a Sony BIOS. The downloader bounds extraction, checks canonical destinations and stages installation. Missing/archive-error paths were not fault-injected on Android.

```text
8a292bfc546f7a0a10ce9e17a5aeefbbf86b2f07334d81aa81502c678ef41c37  ppsspp_libretro_android.so
8394325c48468217f21910aa39b5e1637aecb99480f7fbb2504ba11cafb0b378  play_libretro_android.so
ff265754cbb2e0d3b674f90a79ef5bbaa1f7f1075aec9f5e241524520e5049fa  PPSSPP/LICENSE.TXT
```

## Original homebrew and GLES execution

Generated both tests outside the repository, without SDK, BIOS or commercial content:

```text
$ python3 scripts/make-hw-demos.py /home/ubuntu/winport-verification
/home/ubuntu/winport-verification/winport-psp.elf: 185624 bytes, original CC0 MIPS ELF
/home/ubuntu/winport-verification/winport-ps2.elf: 82568 bytes, original CC0 MIPS ELF
PASS: missing argument
PASS: missing output directory
```

The last two lines are subprocess checks that the generator returns exit 1 and its documented message for absent arguments/output directory. Output hashes:

```text
37f7c06a12dd46d777cebe9b024a74433666b6404b5ba2e324fab0b0f24c653a  winport-psp.elf
5f53e302ee15bf517080e51dc26ccb08c90286d1da8ef6f6a43a208933b16679  winport-ps2.elf
```

Pushed them to `/sdcard/Download` and chose each with Open ROM / Android's document picker, selecting the matching core first. The generated ELF files were copied into app-private ROM-hash directories. The PSP test writes VRAM and imports HLE display functions; PPSSPP presents those pixels through its GLES backend. The PS2 test configures CRT output through HLE and submits original GIF/GS sprites. Both show original WINPORT / PSP or PS2 / CC0 lettering and color bars, not a frontend-provided image. These are simple, silent 2D tests, not 3D/game compatibility benchmarks.

PPSSPP runtime evidence:

```text
WinportRetro: [IO] OpenCFile(/data/user/0/io.winport.app/files/system/PPSSPP/ppge_atlas.zim): Opening content file for read (libretro vfs): ok
WinportRetro: [Loader] Module entry: 08804000 (WinportCC0 0001)
WinportGL: FBO 1: 960x272 depth=1 stencil=0
WinportGL: EGL ready: OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1) / Android Emulator OpenGL ES Translator (Google SwiftShader)
WinportRetro: [G3D] Context reset
WinportGL: Presented hardware frame 480x270 bottom_left=1 viewport=240,0 1920x1080
```

Actual core-produced hardware-frame callbacks were accepted and the frontend FBO blitted to the native window. PSP uses the core's reported 16:9 presentation geometry, with 240-pixel side margins. Home/background → wait for the launcher to become idle → reopen → Resume exercised actual surface destruction and recreation, not merely a pause overlay:

```text
11-22 04:51:01.296  8833  9025 I WinportRetro: [G3D] Context destroy
11-22 04:51:06.248  8833  9025 I WinportGL: FBO 1: 960x272 depth=1 stencil=0
11-22 04:51:06.379  8833  9025 I WinportRetro: [G3D] Context reset
11-22 04:51:10.137  8833  9025 I WinportGL: Presented hardware frame 480x270 bottom_left=1 viewport=240,0 1920x1080
```

The restored content was visually checked after rendering resumed. An earlier immediate post-Resume capture was black; the final capture waits for the resumed UI/frame processing and replaces it. EGL context-loss/BAD_SURFACE recovery branches, stencil attachments and GLES2-request compatibility were implemented but not separately induced on this emulator. ES2 requests use a compatible ES3 host context; ES2-only devices are not supported. No Vulkan was required by these cores.

### Play! Android initialization fixes and remaining Exit limitation

The initial Play! attempt aborted before an EGL context was created. Its Android `retro_init` uses `getenv("EXTERNAL_STORAGE")` as its files directory; the frontend now supplies the app-private per-core/per-ROM save path. The original C++ exception text was not captured, so its exact abort cause is not claimed as confirmed.

With private storage set, a second attempt reached Play!'s `PS2VM Thread` but crashed at a null JavaVM pointer:

```text
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 8171 (PS2VM Thread)
#00 pc 000000000046403c .../play_libretro_android.so
#01 pc 000000000037a953 .../play_libretro_android.so
```

NDK `llvm-addr2line` resolved these to `Framework::CJavaVM::AttachCurrentThread(_JNIEnv**, char const*)` and `CPS2VM::EmuThread()`. Disassembly at `0x46403c` dereferences the null VM object. The same downloaded core exports `_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM` but no `JNI_OnLoad`; disassembly of the setter shows it stores the supplied VM pointer. The Rust loader now passes the JNI-provided app VM through that optional Play!-specific hook before `retro_init`. This resolved initialization without changing or repackaging the downloaded core. It is a non-libretro, version-sensitive Android compatibility bridge.

Final Play! attempt, with the same core and generated ELF:

```text
11-22 04:47:40.285  8451  8666 I WinportRetro: Loading core /data/user/0/io.winport.app/files/cores/x86_64/play_libretro_android.so
11-22 04:47:40.489  8451  8666 I WinportRetro: Initializing core
11-22 04:47:41.176  8451  8666 I WinportRetro: Core initialized
11-22 04:47:41.198  8451  8666 I WinportGL: FBO 1: 5120x3584 depth=1 stencil=0
11-22 04:47:41.199  8451  8666 I WinportGL: EGL ready: OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1) / Android Emulator OpenGL ES Translator (Google SwiftShader)
11-22 04:47:42.028  8451  8666 I WinportGL: Presented hardware frame 640x448 bottom_left=1 viewport=480,0 1440x1080
```

The PS2 lettering and sprites were visually checked, upright and scaled to 4:3. Play! ran this ELF with its HLE, without external BIOS or a boot UI. **Exit did not complete**: the pause overlay remained, the `libretro-run` thread's `/proc/.../wchan` was `futex_wait_queue`, and `debuggerd` returned `root is required`. Whether the blocked operation was `retro_run` or teardown was not verified; no upstream-core-only cause is claimed. Android force-stop was used before the next test. Clean PS2 shutdown, PS2 surface recreation and hardware-core save/load remain unverified. A process-isolated core runner or a targeted native stack investigation is follow-up work; neither is silently claimed implemented here.

## PS1 software regression

The existing CC0 PS-X EXE still renders with PCSX ReARMed HLE and a 4:3 software SurfaceView after adding the GLES path. The in-app state replay diagnostic still passes:

```text
11-22 04:45:10.160  8217  8316 I WinportState: verify {"bytes":4456448,"frameAdvanced":true,"replayMatched":true,"restored":true}
```

## Step 2 screenshots and remaining scope

All listed captures were visually inspected on the emulator:

- `docs/psp-core-installed.png` — PPSSPP installed through the same five-core settings screen.
- `docs/psp-homebrew.png` — actual PPSSPP GLES output.
- `docs/psp-surface-recreated.png` — restored PSP output after context destroy/reset.
- `docs/ps2-core-installed.png` — Play! user-triggered download installed.
- `docs/ps2-homebrew.png` — actual Play! GLES rendering of the original PS2 ELF.
- `docs/ps1-step2-regression.png` — PS1 software video still functioning.

No physical ARM64/GPU device, audible audio, Bluetooth gamepad, complex 3D content, commercial game or BIOS was tested. Nightly binaries/assets are unpinned and not sandboxed. Frame pacing follows the core-reported FPS, not a measured performance guarantee. PPSSPP printed a nonfatal `Couldn't attach thread - g_attach not set` for its IO worker during the test; the assets/content loaded and the renderer/surface recreation worked, but unrelated PPSSPP Android integrations were not tested. Asset extraction failure injection, live audio sample-rate changes and general process-death recovery were not tested.

---

# Libretro Step 1 verification (historical)

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
