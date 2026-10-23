import importlib.util
import sys
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / "monitor_upstream_plugins.py"
spec = importlib.util.spec_from_file_location("monitor_upstream_plugins", MODULE)
monitor = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = monitor
spec.loader.exec_module(monitor)


class UpstreamMonitorTests(unittest.TestCase):
    def setUp(self):
        self.repos = {
            "CineStream": "SaurabhKaperwan/CSX",
            "StreamPlay": "phisher98/cloudstream-extensions-phisher",
        }

    def entry(self, name, version=10):
        return {
            "internalName": name, "version": version,
            "fileSize": 20000,
            "fileHash": "sha256-" + "a" * 64,
            "url": "https://raw.githubusercontent.com/" +
                   self.repos[name] + "/builds/" + name + ".cs3",
        }

    def test_only_exact_pinned_release_is_accepted(self):
        item = self.entry("CineStream")
        got = monitor.select_release([item], "CineStream", self.repos["CineStream"])
        self.assertEqual(got["version"], 10)
        self.assertEqual(got["bytes"], 20000)
        for invalid in [
            dict(item, url="https://attacker.invalid/bad.cs3"),
            dict(item, version=-1),
            dict(item, version=True),
            dict(item, fileHash="sha256-1234"),
            dict(item, fileSize=0),
        ]:
            with self.assertRaises(ValueError):
                monitor.select_release([invalid], "CineStream", self.repos["CineStream"])
        with self.assertRaises(ValueError):
            monitor.select_release([item, item], "CineStream",
                                   self.repos["CineStream"])

    def test_source_absence_is_reported_truthfully(self):
        repos = self.repos
        def fake(url):
            if "plugins.json" in url:
                name = "CineStream" if "CSX" in url else "StreamPlay"
                return [self.entry(name)]
            if "commits/master" in url:
                return {"sha": "a" * 40}
            if "git/trees/master" in url:
                if "CSX" in url:
                    return {"tree": [{"path": "CineStream/src/main/A.kt",
                                      "type": "blob"}]}
                return {"tree": [{"path": "README.md", "type": "blob"}]}
            raise AssertionError(url)
        state = monitor.inspect(fetch=fake)
        self.assertTrue(state["providers"]["CineStream"]["source_available"])
        self.assertFalse(state["providers"]["StreamPlay"]["source_available"])
        self.assertEqual(state["providers"]["StreamPlay"]["integration"],
                         "monitor_only")
        self.assertIn("No", monitor.review_report({}, state))

    def test_change_detection_ignores_reordered_metadata(self):
        fixture = {"schema": 1, "providers": {
            "StreamPlay": {"release": {"version": 8}},
            "CineStream": {"release": {"version": 10}},
        }}
        new = {"schema": 1, "providers": dict(reversed(
            list(fixture["providers"].items())
        ))}
        self.assertEqual(monitor.changed_providers(fixture, new), [])
        new["providers"]["StreamPlay"] = {"release": {"version": 9}}
        self.assertEqual(monitor.changed_providers(fixture, new),
                         ["StreamPlay"])

    def test_missing_source_index_is_failure_not_source_guess(self):
        def fake(url):
            if "plugins.json" in url:
                name = "CineStream" if "CSX" in url else "StreamPlay"
                return [self.entry(name)]
            if "commits/master" in url:
                return {"sha": "a" * 40}
            return {"tree": [], "truncated": True}
        with self.assertRaises(ValueError):
            monitor.inspect(fetch=fake)


if __name__ == "__main__":
    unittest.main()
