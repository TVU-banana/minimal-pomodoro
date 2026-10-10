# Immediate bottom navigation — 2026-10-10

## Cause and contract

The previous click path waited for the bottom highlight's 180 ms animation to finish, then posted
the Fragment transaction. This deliberately delayed every destination change, including cached
screens. System animation scaling could extend the wait.

MainActivity now selects the item and commits the destination inside the same click handler.
BottomNavigationView only draws the highlight; it has no completion callback that can gate or
schedule navigation. Repeated taps on the visible destination skip the Fragment transaction.

Visited Views remain cached. Only the visible Fragment reaches RESUMED, and hidden Fragments
remain STARTED. Theme changes still rebuild themed Views, and Room remains the source of truth.
First visits still inflate their Views synchronously; report data loads asynchronously. This
change removes the animation wait without introducing eager inflation into app startup.

## Validation

- Strengthened InteractionPerformanceUiTest to assert the destination type, selected button,
  and visible View before the click handler returns. The original implementation failed this
  assertion when selecting Habits. The fixed implementation passed.
- Full local Pixel_10_Pro instrumentation suite: 20 tests passed, including first and cached
  navigation, rapid taps, retained View identity, hidden lifecycle state, Activity recreation,
  theme changes, editor display, Room migration, habit persistence, and poster preview.
- `test lint assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=1`:
  successful; 121 unit tests passed; Android Lint reported 0 errors and 103 existing warnings.
- `git diff --check`: passed.

Sampled emulator click-handler times:

| Destination | First visit | Cached visit |
| --- | ---: | ---: |
| Timer | Already visible | 8.5 ms |
| Habits | 25.9 ms | 11.7 ms |
| Reports | 38.4 ms | 8.0 ms |
| Settings | 16.9 ms | 3.0 ms |

These measure main-thread click processing, excluding input delivery, subsequent traversal, GPU
work, and screen presentation. They are not physical-device click-to-display measurements.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The automated test suite ran only on
the local emulator. Physical-device interaction acceptance remains with the user.

## Authorized phone deployment

After the user requested deployment with historical statistics preserved, the APK was installed
on RMX3562 (`<device-serial>`) using `adb install -r`. Installation succeeded, and MainActivity
launched with `Status: ok`. No uninstall, data clear, import, restore, or record-edit operation ran.

- The installed APK SHA-256 matches the local build:
  `20bcd87c08c1162fec58146ee19531c03b7db283c94e6cfb2e0ac5489071c0e8`.
- Before and immediately after installation, SHA-256 values for the database, WAL, shared
  preferences, and app files matched exactly. SQLite's transient SHM file was excluded.
- After launching, `clock.db`, `clock.db-wal`, and `clock_settings.xml` still matched the pre-install
  values exactly. Only AndroidX ProfileInstaller's `files/profileInstalled` metadata changed.
- Only digests were captured; no historical database contents were exported.
