"""Exercise publication guards with isolated Git histories and nested archives."""

import io
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SCANNER = Path(__file__).resolve().parents[1] / "check-repository.py"


class RepositoryChecksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Synthetic Test")
        self.git("config", "user.email", "1+test@users.noreply.github.com")
        (self.root / "README.md").write_text("Synthetic fixture.\n")
        self.commit("initial")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.DEVNULL)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "--quiet", "-m", message)

    def scan(self, *args):
        return subprocess.run(
            ["python3", str(SCANNER), *args], cwd=self.root,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )

    def test_clean_history_passes(self):
        self.assertEqual(self.scan("--history").returncode, 0)

    def test_deleted_key_remains_blocked_in_history(self):
        key = self.root / "signing.key"
        key.write_text("Synthetic non-secret key fixture.\n")
        self.commit("add fixture")
        key.unlink()
        self.commit("remove fixture")
        self.assertEqual(self.scan().returncode, 0)
        self.assertEqual(self.scan("--history").returncode, 1)

    def test_nested_zip_path_is_blocked_without_printing_value(self):
        private_path = "/" + "/".join(("Users", "synthetic-user", "module"))
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("render.cjs", private_path)
        with zipfile.ZipFile(self.root / "assets.zip", "w") as archive:
            archive.writestr("nested.zip", nested.getvalue())
        result = self.scan()
        self.assertEqual(result.returncode, 1)
        self.assertIn("assets.zip!/nested.zip!/render.cjs", result.stderr)
        self.assertNotIn(private_path, result.stderr)

    def test_unreadable_archive_fails_closed(self):
        (self.root / "broken.zip").write_bytes(b"not a zip")
        self.assertEqual(self.scan().returncode, 1)

    def test_nesting_budget_fails_closed(self):
        content = b"synthetic payload"
        for unused in range(5):
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr("nested.zip", content)
            content = buffer.getvalue()
        (self.root / "deep.zip").write_bytes(content)
        self.assertEqual(self.scan().returncode, 1)

    def test_non_noreply_commit_is_blocked(self):
        self.git("config", "user.email", "fixture@example.invalid")
        (self.root / "README.md").write_text("Updated synthetic fixture.\n")
        self.commit("email fixture")
        self.assertEqual(self.scan("--history").returncode, 1)


if __name__ == "__main__":
    unittest.main()
