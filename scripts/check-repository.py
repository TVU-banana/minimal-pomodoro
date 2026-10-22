#!/usr/bin/env python3
"""Reject private artifacts and identifying data without printing matched values."""

import argparse
from pathlib import Path
import re
import subprocess
import sys


PATTERNS = {
    "personal home directory": re.compile(rb"/(?:Users|home)/[A-Za-z0-9._-]+/"),
    "personal email": re.compile(
        rb"[\w.+-]+@(?:gmail|outlook|hotmail|icloud|qq|163|126|protonmail)\.com\b",
        re.IGNORECASE,
    ),
    "literal signing password": re.compile(
        rb'''(?:storePassword|keyPassword|KEYSTORE_PASSWORD|KEY_PASSWORD)\s*=\s*["'][^"'$\n]+["']'''
    ),
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".db",
                    ".sqlite", ".sqlite3", ".clockbackup", ".apk", ".aab", ".hprof", ".log"}


def git(*args):
    return subprocess.check_output(["git", *args])


def check(path, data):
    problems = []
    name = Path(path)
    if (name.suffix in PRIVATE_SUFFIXES or name.name == "local.properties"
            or name.name.startswith(".env") or ".kotlin" in name.parts
            or ".local" in name.parts):
        problems.append("private/generated artifact")
    if b"\x00" not in data[:8000]:
        problems.extend(label for label, pattern in PATTERNS.items() if pattern.search(data))
    return {(path, label) for label in problems}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true", help="Scan every reachable Git object")
    options = parser.parse_args()
    problems = set()
    count = 0
    if options.history:
        objects = git("rev-list", "--objects", "--all").decode().splitlines()
        for item in objects:
            oid, _, path = item.partition(" ")
            kind = git("cat-file", "-t", oid).strip()
            if kind == b"blob":
                problems.update(check(path, git("cat-file", "blob", oid)))
                count += 1
            elif kind in (b"commit", b"tag"):
                data = git("cat-file", kind.decode(), oid)
                problems.update(check("commit " + oid[:12], data))
                for line in data.splitlines():
                    if line.startswith((b"author ", b"committer ", b"tagger ")):
                        email = re.search(rb"<([^>]+)>", line)
                        if email and not email[1].endswith(b"@users.noreply.github.com"):
                            problems.add(("commit " + oid[:12], "non-noreply author email"))
        # One blob can appear under several names; inspect every historical path as well.
        for commit in git("rev-list", "--all").decode().splitlines():
            for path in git("ls-tree", "-r", "--name-only", "-z", commit).split(b"\0"):
                if path:
                    problems.update(check(path.decode(), b""))
    else:
        paths = git("ls-files", "-z", "--cached", "--others", "--exclude-standard")
        for item in sorted(set(paths.split(b"\0")) - {b""}):
            path = item.decode()
            if Path(path).is_file():
                problems.update(check(path, Path(path).read_bytes()))
                count += 1
    for path, label in sorted(problems):
        print(f"{path}: {label}", file=sys.stderr)
    print(f"Checked {count} file versions; {len(problems)} privacy findings.")
    return bool(problems)


if __name__ == "__main__":
    sys.exit(main())
