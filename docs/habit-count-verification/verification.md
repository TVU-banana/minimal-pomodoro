# Daily repetition verification — 2026-10-05

- Application unit suite: 110 tests, zero failures/errors.
- Emulator application instrumentation suite: 16 tests, zero failures/errors, Android 17.
- Android Lint: zero errors, 110 warnings (includes existing project warnings).
- Release and Preview optimized APKs built successfully. Final formatting-only cleanup was
  followed by another unit/Lint/Release build. The same production source is used by Preview.
- Instrumentation verifies migration 7/8/9 to 10 with Room's generated schema validator, old
  completed/undone conversion, note/history preservation, partial counts after disk reopening,
  backup restoration, and 32 concurrent repetitions across two repositories without lost updates.
- The UI test verifies both themes, 12dp name-number separation, natural short-name width,
  smaller count type, readable contrast, 2 → 1 → checked → 1, and progress after tab replacement.
- Unit coverage includes stale feedback undo, target edits preserving counts/history, invalid
  counts/targets, independent backup merges, and actual version-3 archives lacking new fields.
- The root connected test command also invoked the separate benchmark module. Baseline generation
  was stopped after stalling; startup benchmarking rejects an emulator/debuggable target. No
  performance result is claimed. The complete application instrumentation suite passed separately.

## Phone installation

RMX3562 was updated with `adb install -r`, retaining the installed application and its data.
The first Release installation was rejected because its certificate differed from the existing
application. The installed certificate was verified against the local debug certificate before
installing the optimized Preview APK with that matching certificate. No uninstall or data clear
was performed. Launch and the existing habit list were inspected after installation: five visible
habits and two completed entries remained available.

Installed artifact: `app/build/outputs/apk/preview/app-preview.apk`.
SHA-256: `d34cd8fb4e730e5527b53e0d017a6bdb04e9ca7b2fbb6ead4cf36b53c51e43dc`.
Phone verification establishes successful update, launch, and legacy history display. The new
multi-count interaction, light/dark contrast, and persistence were verified on the emulator; no
user-owned phone habit was changed for testing. Existing unrelated worktree edits were preserved.

## Rendered screenshots

A temporary emulator task named `Exercise 2030` was created through the editor with target 2.
Manual taps reproduced remaining 2, remaining 1, completed with no number, then remaining 1 again.
The trailing number is visibly smaller and spaced away even when the task name ends with digits.
Both theme screenshots were inspected. The phone's own habits were not changed.

- Light initial（本地验收附件，不公开分发）
- Light partial（本地验收附件，不公开分发）
- Light complete（本地验收附件，不公开分发）
- Dark partial（本地验收附件，不公开分发）

A 360dp-wide emulator viewport with 1.5x font scaling was inspected in dark mode; the
remaining number stayed separated and readable. The original font scale and viewport were restored.

- Narrow viewport with enlarged text（本地验收附件，不公开分发）
