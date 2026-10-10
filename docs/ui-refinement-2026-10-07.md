# UI refinement verification — 2026-10-07

## Changes

- Increased countdown digit size and removed visible remaining-time and stage captions.
- Moved remaining repetitions into the check-in circle; zero remaining renders a check mark.
- Added an explicit history date-picker entry and immediate selected-day refresh.
- Replaced day/week bars with viewport-width line charts; report and poster share the dataset.
- Removed the bar renderer, layout, and unused hourly query/result type.
- Poster opens directly with a live preview and section checkboxes; exports remain full resolution.
- Duration labels use h/min with minutes alone below one hour.
- Settings shows light/dark only, removes redundant headings/descriptions, and gives reading a card button.

## Verification

- 116 unit tests passed.
- Android Lint: zero errors, 110 warnings.
- 18 device tests passed on RMX3562 / Android 14, including history/recreation,
  circle countdown/completion/undo in both themes, and live poster pixel changes.
- Final Debug APK installed with adb install -r; installation returned Success.
- Settings screenshot inspected on the final APK.

## Data incident and approved partial recovery

The connected Gradle test flow uninstalled the target application after the run.
The final installation consequently started with an empty database; the application UID changed
from u0_a349 during testing to u0_a352 after final installation. The report page had shown
1 h 38 min before the completed test run and was empty afterward. No pre-test current-data backup
was captured. This was an execution error, not a requested reset.

No Clock archive was found in the phone's Documents, Download, or Android/media directories.
Older local snapshots exist under /tmp/clock-habit-review. The newest inspected candidate is
/tmp/clock-habit-review/restored-after-progress/clock.db, dated 2026-10-04, with 7 sessions,
13 presets, 2 categories, and 3 habits. It cannot establish recovery of later changes.
The user explicitly chose the 2026-10-04 snapshot. The current empty installation was backed up to
/tmp/clock-recovery-20261007/before-restore.tar, then the snapshot was restored and migrated by the
application from Room version 8 to 10. Verified: 7 sessions, 13 presets, 2 categories, 3 habits,
and 2 check-in rows. Original session/preset/category/habit/check-in fields match the snapshot;
SQLite integrity_check returned ok and foreign_key_check returned no violations.
Later data remains unrecovered. A recovery copy is retained at
/tmp/clock-recovery-20261007/clock-20261004.sqlite.

Future device checks must back up current app data first and avoid automatic target uninstallation,
or use an isolated application ID/emulator. Do not run connected tests directly on user data again.
