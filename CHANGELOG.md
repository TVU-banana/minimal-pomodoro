# Changelog

All notable changes to Clock are documented in this file.

## Unreleased (source version 1.0.1)

- Added independent daily habits, repetition targets, notes, and version 4 backups.
- Added report navigation, paged heatmaps and customizable poster sections.
- Added an optional band-control protocol and isolated pilot build; hardware acceptance is pending.
- Improved navigation responsiveness and corrected the timer ring sweep angle.
- Removed embedded signing credentials; unsigned builds work without a private keystore.
- Added repository privacy checks, pinned CI dependencies and packaged APK verification.
- Updated the APK budget to 12 MiB to account for the bundled Chinese poster font.

## 1.0.0 - 2026-07-18

- Added configurable warmup and focus countdown phases.
- Added digital, ring, and hourglass timer styles.
- Added local history, reports, CSV/JSON export, and `.clockbackup` import/export.
- Added custom background image support through Android system pickers.
- Added exact-alarm recovery without a persistent foreground service.
- Refined the UI with a restrained warm orange visual system.
- Split timer persistence, rendering, metadata loading, and backup validation into focused classes.
- Added English KDoc and repository rules based on the Google Android Kotlin Style Guide.

