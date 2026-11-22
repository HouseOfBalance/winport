# Step 3 — Vietnamese library and isolated emulation sessions

These results supersede the earlier temporary UI and launcher-return limitation. They do **not** establish that Play! can deinitialize safely in-process.

## Final checks

`cd rust && cargo test --locked`:

```text
test result: ok. 24 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.12s

   Doc-tests winport_core

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

Coverage includes existing launch/persistence/frontend tests plus SFO bounds/title/serial, ELF signatures, empty/malformed input, ISO BOOT2 and PSP SFO requests, fuzzy matching, batch persistence, CSO raw/deflated blocks and CHD v5/CSO SYSTEM.CNF extraction. A new empty-image assertion initially exposed extension-only recognition of an empty ISO; empty input now returns no detection, and the final suite above passes.

`cd android && ./gradlew assembleDebug --max-workers=2`:

```text
> Task :app:assembleDebug

BUILD SUCCESSFUL in 1m
36 actionable tasks: 4 executed, 32 up-to-date
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Both native ABIs are built by the existing Gradle cargo-ndk task. APK inspection listed only the frontend and AndroidX graphics helper (timestamps omitted):

```text
    10096  lib/arm64-v8a/libandroidx.graphics.path.so
    10760  lib/x86_64/libandroidx.graphics.path.so
  1442384  lib/arm64-v8a/libwinport_core.so
  1585520  lib/x86_64/libwinport_core.so
No libretro core, test executable or named PS1 BIOS bundled
```

`git diff --check` returned exit 0. Runtime cores/assets remained from the earlier user-triggered in-app downloads; no core, BIOS or ROM was added to Git. JDK 17 and the existing SDK/NDK were used. The inherited `JDK_JAVA_OPTIONS` notice was diagnosed and removed from the build shell. Transient UiAutomator `null root node` errors occurred during automation; build daemons were stopped to release host memory and UI calls serialized. Final images were captured and visually inspected, not inferred from automation text.

## SAF scan and art

Ran `python3 scripts/make-step3-fixture.py /home/ubuntu/winport-verification/step3-scan`. It generated the original CC0 PS-X EXE, PSP ELF, PS2 ELF, a synthetic PS4 PARAM.SFO (`CUSA00000`) and an original gradient ICON0.PNG. These files stayed outside the repository. Pushed the folder to `/sdcard/Download/step3-scan`, selected it in Android's SAF picker and accepted the folder-access dialog. After APK updates/cold starts the persisted folder could be rescanned through settings:

```text
Đã nhận diện 4 mục.
```

Home showed `winport-demo` (PS1), `winport-psp` (PSP), `winport-ps2` (PS2) and `Thư viện PS4 · kiểm tra` (PS4 / CUSA00000), with system badges. The PS4 ICON0 was cached and used as tile/hero art. The Rust library has no default games; the requested PS4 fixture is metadata-only, not a runnable PS4 title.

A final scanner review fixed empty relative-root handling when selecting a PS3/PS4 game folder itself, and PSP root-relative EBOOT/art paths. The fixture now includes a clearly non-executable `eboot.bin` marker. Selecting `PS4-library-test` itself through SAF reported **1** game, not a spurious PS1 entry for that marker. The temporary overlapping tree/library alias was removed after the check. Overlapping SAF tree URIs can create duplicate references to the same content; avoid scanning overlapping roots or remove the extra entry.

## Save slots, auto-save and controls

Used PS1 Pause to save two slots, load slot 1, delete slot 2 and enable auto-save for one Exit. Relevant final-run logs:

```text
11-22 05:34:34.174 11330 11369 I WinportState: save:2 {"savedBytes":4456448}
11-22 05:34:37.449 11330 11369 I WinportState: load:1 {"loaded":true}
11-22 05:35:15.322 11330 11369 I WinportState: delete:2 {}
11-22 05:35:18.448 11330 11369 I WinportState: save:0 {"savedBytes":4456448}
```

The screenshot shows two real framebuffer thumbnails/timestamps, four manual slots and an auto-save toggle. Visual checking found black inherited text and clipped delete actions in the first draft; an explicit white-content surface and compact action rows fixed both, and the screenshot was replaced. The auto-save setting persisted into the next emulation process, then was disabled for the PSP/PS2 exit checks.

On the controls screen, selected △ and injected `KEYCODE_BUTTON_X` using ADB. The UI confirmed the binding and `shared_prefs/controls.xml` contained `<int name="key_99" value="1" />`. This exercises key capture/persistence, not Bluetooth radio or physical analog hardware. Defaults were restored after the screenshot.

## Exit verification — same launcher process

During the Exit verification the launcher PID was **11165** before/after all three sessions. PS1 and PSP native close returned. Play! rendered the generated ELF through GLES but required the private-process deadline. The subsequent final scanner-only fix does not change this activity/frontend lifecycle code:

```text
11-22 05:35:18.376 11330 11330 I WinportExit: Returning home; nativeClosed=false pid=11330
11-22 05:35:19.035 11330 11330 I WinportExit: Native close completed pid=11330
11-22 05:36:10.877 11649 11649 I WinportExit: Returning home; nativeClosed=false pid=11649
11-22 05:36:11.359 11649 11649 I WinportExit: Native close completed pid=11649
11-22 05:36:40.525 11839 11873 I WinportGL: Presented hardware frame 640x448 bottom_left=1 viewport=480,0 1440x1080
11-22 05:36:53.888 11839 11839 I WinportExit: Returning home; nativeClosed=false pid=11839
11-22 05:36:55.889 11839 11839 I WinportExit: Exit deadline; ending isolated process pid=11839
```

After PS2 Exit, `adb shell pidof io.winport.app io.winport.app:emu` printed only `11165`; the library was visible and remained interactive. The screenshot captures that returned home state. Play!'s exact native blocking call remains unverified; returning home is intentional process termination, not a successful `retro_deinit` claim. Pending SRAM/state can be lost. No UI-thread join or Android force-stop was used for these Exits.

## Visually inspected evidence

- `docs/step3-library.png` — scanned real homebrews plus the requested PS4 metadata fixture, all with badges.
- `docs/step3-save-slots.png` — PS1 thumbnails/timestamps and all four save/load/delete rows.
- `docs/step3-bios.png` — Vietnamese BIOS guide, expected PS1 filenames, missing/HLE status.
- `docs/step3-controls.png` — remap confirmation, deadzone, opacity, size and hide-on-controller.
- `docs/step3-ps2-exit.png` — responsive home after the Play! process deadline.
- `docs/step3-ps4-notice.png` — exact Vietnamese unsupported-PS4 notice and parsed serial/art.

## Limits not established by this check

No physical ARM64/GPU, Bluetooth controller, audible test, commercial game or Sony BIOS was used. Actual BIOS imports with proprietary files, live GitHub thumbnail lookup/custom-image picking, CUE/M3U gameplay, and real PS3/PS4 external-emulator compatibility were not exercised end-to-end. The corresponding UI/code is implemented; no installed external emulator was available to establish compatibility. Unknown firmware hashes remain explicitly unverified. CSO scanning supports CISO v1 and CHD scanning supports v5 with bounded metadata; unsupported/ambiguous variants require manual system selection. PS3/PS4 are external library entries, not in-app emulation. Save-state support and orderly teardown depend on each core. Nothing was pushed.
