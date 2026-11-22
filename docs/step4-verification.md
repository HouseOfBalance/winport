# Step 4 verification

## Scope and important incomplete requirement

**Lossless PS2 deadline exit/reload is NOT demonstrated and must not be claimed.**
The pinned Play! core blocks in `retro_run` before queued checkpoints/teardown can
execute. Automatically killing it at that point would violate the save prerequisite.
The automatic two-second deadline now starts only after a successful durable
checkpoint. The watchdog instead offers an explicit, warned force-exit when a core
call is stuck. That explicit force-exit, Android SIGKILL and device power loss before
a checkpoint completes cannot be made lossless by this frontend.

## Commands

`cargo test --locked` in `rust/`:

```text
test result: ok. 28 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.09s

   Doc-tests winport_core
running 0 tests
test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
```

`./gradlew assembleDebug --max-workers=2` in `android/` (JDK 17):

```text
> Task :app:assembleDebug

BUILD SUCCESSFUL in 51s
36 actionable tasks: 4 executed, 32 up-to-date
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
Rust tests cover valid/empty/tampered/missing core files, durable atomic replacement
including empty/error cases, nonblocking audio with the callback mutex deliberately
held, canonical document-key deduplication, different sizes, invalid identities and
PS4 rejection. The preexisting persistence fixture was changed from PS4 to PS3 to
keep testing ordinary persistence under the new explicit PS4-exclusion policy.

## Emulator evidence

Android 15/API 35 x86_64, GLES3 SwiftShader, `adb root` supported (`userdebug`).
Only original generated homebrew and synthetic metadata were used; no Sony BIOS.

- **Background checkpoint / reload:** enable auto-save, pause PS1 and background
  the activity. Logs report `checkpoint {"durable":true,"sramBytes":131072,"stateBytes":4456448}`.
  On a subsequent launch, `load:0 {"loaded":true}` succeeds. Exit reports the same
  durable byte counts. `step4-save-files.txt` records the actual 131072-byte SRAM
  and 4456448-byte auto-state. `step4-save-reload.png` shows auto-save enabled and
  the auto-slot load control. This proves the exercised PS1 path, **not PS2 safety**.
  The final APK also completed normal PS1 shutdown in the required save-first order:

  ```text
  06:42:30.089 WinportCheckpoint: exit {"durable":true,"sramBytes":131072,"stateBytes":4456448}
  06:42:30.125 WinportRetro: Teardown: retro_unload_game
  06:42:30.125 WinportRetro: Teardown: retro_deinit
  06:42:30.139 WinportRetro: Teardown: EGL released
  06:42:30.282 WinportExit: Native close completed pid=14974
  ```
- **Overlapping SAF roots:** selected `Download/step4-outer` and then its `inner`
  child through the real picker. Both scans recognize three games; final persisted
  data contains **2 roots, 3 games, 3 unique document keys**, with systems PSP,
  PS2, PS1. See `step4-scan.json` and `step4-library.png`. The fixture also contains
  a PS4 SFO plus `eboot.bin`: neither the folder nor that executable leaks into the
  library. Existing PS4 entries were hidden on the initial upgraded-app load.
- **Diagnostics:** temporarily hide PCSX ReARMed: `step4-missing-core.png` explains
  where to download it. Corrupt one ELF byte: `step4-hash-error.png` rejects launch
  with a SHA-256 explanation. The original core was restored after both tests.
- **Watchdog:** Play! renders the generated PS2 image, then stops native progress.
  Logs show `Core bị treo phase=run age=5172`, then 6174, 7175, 8176 ms.
  `step4-core-stalled.png` shows the warning, wait and force-exit controls.
  Force-exit returned to the live launcher; no automatic pre-checkpoint deadline
  or save-success claim was used for that stalled session.

All five PNGs were visually inspected. Full local logs are `step4-cargo.log`,
`step4-gradle.log`, `step4-save.log`, `step4-play.log` and `step4-final.log`; they
are intentionally gitignored. Raw backtraces and the compact evidence above are
committed. UI automation sometimes returned a transient null accessibility root,
especially while Gradle was running; stopping its daemon and capturing the actual
rendered states avoided treating those failed automation attempts as app evidence.

## Play! blocking site, not a speculative deinit diagnosis

The installed ELF SHA-256 is
`8394325c48468217f21910aa39b5e1637aecb99480f7fbb2504ba11cafb0b378`, identical
to the manifest. `step4-play-running.txt` and `step4-play-before.txt` capture the old
frontend, and `step4-play-after.txt` captures the changed APK. The latter records
PID 14699 at 06:38:52.980 UTC:

```text
libretro-run sysTid=14720
pthread_cond_wait
play +0x521e61  std::condition_variable::wait
play +0x36b95a  CMailBox::WaitForCall()
play +0x303380  CGSHandler::ProcessSingleFrame()
frontend +0x8f5c6  winport_core::libretro::Session::run_frame
Java_io_winport_app_RetroNative_command
```

The Play! symbols were resolved against the pulled, hash-matching binary with
`llvm-nm -D -C`; frontend addresses with `llvm-addr2line -Cfpe`. The watchdog then
records the unchanged `run` phase for over five seconds. Its timestamp is reset on
every native run entry/return; this discriminates a persistent call from merely
sampling a normal short mailbox wait. No teardown log or successful checkpoint
appears for that session. Thus the verified immediate blocker is **an outstanding
retro_run/GS mailbox wait**, not an established `retro_deinit` deadlock. Reordering
teardown cannot unblock a call that has not returned to the teardown dispatch.

`step4-play-memory.txt` disassembles this exact binary's `retro_get_memory_size`:
it returns nonzero only for memory id 2, not libretro SAVE_RAM id 0. Therefore this
core exposes no SRAM buffer for the frontend to persist. Do not fabricate an empty
SRAM file as proof. Play!'s own memory-card files and its internal producer/JIT
behavior require separate core-side investigation. Source inspection suggested
VM/GS synchronization, but **why the producer does not satisfy this wait remains
unverified**; the stack alone does not establish that deeper cause.

Frontend shutdown now explicitly makes EGL current for context_destroy before
unload/deinit, then releases EGL, with logs at each boundary. The Play! retest still
stops earlier in retro_run, so clean Play! native shutdown and PS2 state restoration
remain unverified. Calling serializer/unload concurrently on another thread would
break the libretro contract and is not used as a workaround.

## Core pins, CI and remaining limits

The update script successfully fetched/hashes five cores for each of arm64-v8a and
x86_64. Hashes cover the extracted ELF, not the ZIP. Stable 1.21.0's Android index
listed RetroArch APKs rather than individual versioned cores. These exact probes
returned 404:

```text
stable/1.21.0/android/x86_64/play_libretro_android.so.zip
stable/1.22.2/android/x86_64/play_libretro_android.so.zip
nightly/android/2026-11-22/x86_64/play_libretro_android.so.zip
```

No usable immutable individual Android core URL was established; pins therefore
fail closed against mutable nightly sources. Updating them is a deliberate
maintainer action (`scripts/update-core-hashes.sh` + manifest review + rebuild), not
an automatic acceptance of new upstream code. PPSSPP assets remain a separate
runtime download; this change pins core ELFs, not the asset archive.

CI config covers push/PR, Rust tests, JDK 17, Android SDK/NDK 27.2.12479018, both Rust
Android targets, cargo-ndk, assembleDebug and APK artifact upload. It has not run on
GitHub because nothing was pushed. Build outputs, JNI libraries, local.properties
and docs logs are ignored. No LICENSE file was added; app name/Home layout and PS3
external-app support remain unchanged.

Missing-BIOS and unsupported-format messages are implemented but were not exercised
end-to-end in this pass. Physical ARM64, audible output, Bluetooth hardware, PS3
external compatibility, CUE/M3U alias save-directory migration and interrupted
power-loss fsync guarantees are not verified. Checkpoints cannot promise saves
after arbitrary Android process death or while a core never returns control.
