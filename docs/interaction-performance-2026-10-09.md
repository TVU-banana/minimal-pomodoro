# Interaction performance — 2026-10-09

Navigation timing below is historical. The 180 ms animation gate was removed on 2026-10-10;
see [immediate navigation validation](navigation-responsiveness-2026-10-10.md).

## Diagnosis and changes

- Preset editing previously awaited a fresh tag query, preset query, and tag-link query before
  creating the dialog. It now uses the latest Room-backed screen snapshot, opens synchronously,
  guards duplicate dialogs, and removes the window entrance animation. Writes still use the
  repository. Tag choices no longer perform a repeated linear ID lookup.
- Day/night changes previously recreated the Activity and its window. MainActivity now handles
  uiMode, rebuilds themed Views in the same window, and retains Fragment state. Hidden cached
  screens are detached on theme change and reinflated on their next visit. Radio selection state
  cannot restore an obsolete theme choice.
- Navigation previously replaced/destroyed each screen during the 180 ms highlight animation.
  Visited screens now retain their Views. The highlight completes before screen work is posted;
  rapid taps cancel pending destinations. Only the visible screen reaches RESUMED. Timer ticks,
  habit date receivers, and screen observers pause while hidden. Reports refresh on return and
  cancel loading when hidden.
- Timer cards skip rebuilding unchanged data. Daily progress aggregation runs on Dispatchers.Default.
  Toolbar backdrop capture uses quarter-size dimensions (1/16 of the original pixels), with
  correctly scaled blur and no capture while hidden.

## Physical-device baseline

RMX3562, existing debug installation, 1080 x 2412. Repeated navigation used 12 taps across all four
screens; theme sampling used six toggles; editor sampling used three open/cancel cycles. Samples
used adb dumpsys gfxinfo reset/framestats. These are frame statistics, not click-to-display latency.

| Interaction | Frames | Missed-deadline frames | P95 frame duration |
| --- | ---: | ---: | ---: |
| Navigation | 131 | 23 (17.56%) | 105 ms |
| Theme toggle | 231 | 8 (3.46%) | 44 ms |
| Open/cancel editor | 140 | 19 (13.57%) | 150 ms |

Navigation recorded 22 slow-UI-thread events. Existing process PSS was approximately 117 MiB.
The measurements and identified work support a software/main-thread bottleneck. They do not
establish that this phone lacks capacity, nor completely exclude system load or thermal effects.

## Validation

- `test lint assembleDebug assembleDebugAndroidTest`: successful; 121 unit tests passed,
  Android Lint 0 errors / 103 warnings.
- Full app instrumentation suite on the local Pixel_10_Pro emulator: 20 tests passed, including
  cached View identity, hidden lifecycle state, rapid navigation, Activity recreation, same-window
  theme changes from both screens, and synchronous editor display.
- Emulator handler timings: timer theme 24.6 ms; editor 51.5 ms. These exclude input delivery,
  traversal, GPU work, and display presentation; they are not physical-device latency results.
- Emulator PSS at the sampled point was 99,312 KB; lowMemory was false. This is an emulator sample.
- Updated the existing poster-preview test to assert reduced image height when its chart is hidden;
  its old fixed pixel coordinate was outside the compact image bounds.
- `git diff --check`: passed.

## Delivery and remaining acceptance

The debug APK was installed with `adb install -r`, without clearing app data. The user requested
manual physical-device acceptance, so no post-fix phone interaction benchmark is claimed. The
phone test-component installation was declined and did not succeed. Tests ran only on the local
emulator afterward.

Check editor open/cancel, both theme controls, repeated and rapid four-tab selection, and timer
continuity when switching tabs. Also check that selected dates/report ranges survive a theme
change and that returning to Reports displays newly recorded sessions.

An attempted host backup of the app sandbox was rejected by automatic approval review. No app
sandbox archive was exported. No backup/restore, record edits, or data-clear command was used on
the physical phone. The baseline UI hierarchy capture created `/sdcard/clock-perf.xml` before the
user restricted subsequent phone operations; it was left untouched afterward.
