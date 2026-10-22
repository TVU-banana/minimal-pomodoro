# Security policy

Clock keeps user activity on the device and has no Internet permission. Backups intentionally
contain personal records, so treat them as private data. See [privacy](docs/privacy.md).

## Supported versions

Security fixes target the current `main` branch. There is no promised maintenance window for older
snapshots. Use the latest verified source and preserve the signing identity when upgrading.

## Report a vulnerability

Use GitHub's private **Report a vulnerability** control in this repository's Security tab when
available. If it is unavailable, open an issue asking for a private reporting channel without
including exploit details, credentials, backups, screenshots, or personal data.

Include the affected version/commit, impact, reproduction steps using synthetic data, and Android
version. Do not publish sensitive evidence in an ordinary issue or pull request.

## Maintainer checks

- Run Gitleaks and `scripts/check-repository.py --history` before publishing any Git ref.
- Keep signing keys and credentials outside Git; provide signing values through the environment.
- Check packaged permissions, dependency changes, backup validation and database migrations.
- Review image/archive attachments manually. Automated scanning cannot establish the absence of
  every kind of sensitive information.
- Removing data from the current tree does not remove it from history, existing clones, forks,
  cached objects, CI artifacts, or prior releases. Revoke exposed credentials immediately; history
  cleanup and GitHub Support requests may also be needed.
