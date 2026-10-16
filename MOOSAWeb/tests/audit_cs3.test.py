"""Offline synthetic CloudStream package tests, no execution of extension code."""
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "audit_cs3.py"
spec = importlib.util.spec_from_file_location("audit_cs3", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def fixture(path, manifest=None, dex=True, bad_path=False, dex_header=b"dex\n035\0"):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest or {
            "pluginClassName": "com.example.Plugin", "version": 4
        }))
        if dex:
            archive.writestr("classes.dex", dex_header + b"X" * 200)
        if bad_path:
            archive.writestr("../escape.txt", "unsafe")

class Cs3AuditTests(unittest.TestCase):
    def test_android_dex_is_not_a_node_js_module(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "StreamPlay.cs3"
            fixture(path)
            found = module.inspect_cs3(path)
            self.assertEqual(found["format"], "android_dex_archive")
            self.assertEqual(found["dex_files"], ["classes.dex"])
            self.assertEqual(found["plugin_class"], "com.example.Plugin")
            self.assertFalse(found["direct_node_execution"])
            self.assertTrue(found["requires_separate_port_or_android_runtime"])

    def test_requires_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "invalid.cs3"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"dex\n035\0" + b"X" * 80)
            with self.assertRaisesRegex(ValueError, "missing_cloudstream_manifest"):
                module.inspect_cs3(path)

    def test_rejects_archive_traversal_and_corrupt_dex(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "unsafe.cs3"
            fixture(path, bad_path=True)
            with self.assertRaisesRegex(ValueError, "unsafe_archive_path"):
                module.inspect_cs3(path)
            fixture(path, dex_header=b"notdex!!")
            with self.assertRaisesRegex(ValueError, "invalid_dex_magic"):
                module.inspect_cs3(path)

    def test_manifest_without_dex_does_not_become_executable(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "metadata.cs3"
            fixture(path, dex=False)
            found = module.inspect_cs3(path)
            self.assertEqual(found["format"], "archive_without_dex")
            self.assertFalse(found["direct_node_execution"])

if __name__ == "__main__":
    unittest.main()
