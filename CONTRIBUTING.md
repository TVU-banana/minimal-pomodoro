# Contributing to Clock

Clock is a local-first Android application written in Kotlin. Contributions must preserve its
offline behavior, small package size, low background resource use, and open data formats.

## Development checks

Run the complete local gate before opening a pull request:

```shell
./gradlew test lint assemblePreview assembleRelease
```

Use Android Studio's **Reformat Code** action with the repository `.editorconfig` before committing.

## Code conventions

- Follow the Google Android Kotlin Style Guide and the repository `.editorconfig`.
- Write identifiers in idiomatic English and all KDoc and comments in English.
- Explain contracts and non-obvious design decisions; do not comment obvious syntax.
- Add or update tests whenever behavior changes.
- Keep user-visible text in `res/values/strings.xml`.
- Add an ADR under `docs/decisions/` when changing a documented product or architecture contract.

## Pull requests

Keep each pull request focused. Describe the behavior change, compatibility impact, tests run, and
any effect on APK size, permissions, battery use, or exported data.
