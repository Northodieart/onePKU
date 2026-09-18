"""Installer transaction tests; no network or real account configuration."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import install


class InstallationTests(unittest.TestCase):
    def test_changed_config_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "subtitles-provider.json"
            path.write_bytes(b"newer configuration")
            with self.assertRaisesRegex(RuntimeError, "其他操作"):
                install.publish(path, {"model": "new"}, b"old")
            self.assertEqual(path.read_bytes(), b"newer configuration")
            self.assertEqual(len(list(Path(directory).iterdir())), 1)

    def test_replacement_keeps_backup_and_private_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "subtitles-provider.json"
            original = b'{"model":"old"}'
            path.write_bytes(original)
            install.publish(path, {"model": "new"}, original)
            self.assertEqual(json.loads(path.read_text())["model"], "new")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            backup, = Path(directory).glob("*.backup-*.json")
            self.assertEqual(backup.read_bytes(), original)

    def test_failed_existing_check_does_not_start_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "subtitles-provider.json"
            original = b'{"python":"/missing/python"}'
            path.write_bytes(original)
            args = install.argparse.Namespace(data_dir=root, check=False, replace=False)
            with patch.object(install.platform, "system", return_value="Darwin"), \
                 patch.object(install.platform, "machine", return_value="arm64"), \
                 patch.object(install, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "python 不可用"):
                    install.install(args)
                run.assert_not_called()
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(len(list(root.iterdir())), 1)


if __name__ == "__main__":
    unittest.main()
