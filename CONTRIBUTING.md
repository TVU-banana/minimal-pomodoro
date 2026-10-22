# Contributing to Clock

Clock is a local-first Android application written in Kotlin. Contributions must preserve its
offline behavior, small package size, low background resource use, and open data formats.

## Development checks

Run the complete local gate before opening a pull request:

```shell
./gradlew test lint assembleDebug assemblePreview assembleRelease assembleDebugAndroidTest :benchmark:assembleDebug
python3 scripts/check-repository.py
python3 scripts/check-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Release builds are unsigned unless all four `CLOCK_` signing variables are supplied; see
[release instructions](docs/release.md). Use JDK 21 for Gradle and SDK Platform 37.0.
Run `./gradlew :app:connectedDebugAndroidTest` on a dedicated emulator before merging.
Instrumentation tests create and delete application data; never run them on a personal installation.

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

## Repository privacy

Use GitHub's noreply address for both author and committer identities. Configure it per repository:

```sh
git config user.email "<your GitHub noreply email>"
```

Get the exact address from GitHub Settings → Emails. Before publishing, run
`python3 scripts/check-repository.py --history` and `gitleaks git --redact --log-opts=--all .`.
The scanners print finding locations rather than secret values; manual attachment review remains
necessary. Do not commit keystores, credentials, user backups, device identifiers, local logs, or
screenshots containing personal records. Use synthetic data in public examples.

For local preview, connect a development device, select it with `ANDROID_SERIAL` when necessary,
and run `./scripts/run-preview.sh`. It installs the debug-signed Preview variant in place; use
only a compatible development installation.
