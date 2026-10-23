#!/usr/bin/env python3
"""Review-only monitor: never download, execute, or bundle third-party CS3 files."""
import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / ".github" / "upstreams.lock.json"
SOURCES = (
    ("CineStream", "SaurabhKaperwan/CSX"),
    ("StreamPlay", "phisher98/cloudstream-extensions-phisher"),
)
SHA256 = re.compile(r"sha256-[0-9a-f]{64}\Z")
GIT_SHA = re.compile(r"[0-9a-f]{40}\Z")


def fetch_json(url):
    if not (url.startswith("https://api.github.com/repos/") or
            url.startswith("https://raw.githubusercontent.com/")):
        raise ValueError("Untrusted GitHub API endpoint")
    headers = {"User-Agent": "MOOSA-Upstream-Monitor",
               "Accept": "application/vnd.github+json"}
    token = os.getenv("GITHUB_TOKEN", "")
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def select_release(manifest, name, repo):
    if not isinstance(manifest, list):
        raise ValueError("Invalid plugin manifest for " + name)
    matches = [item for item in manifest if isinstance(item, dict)
               and item.get("internalName") == name]
    if len(matches) != 1:
        raise ValueError("Missing or duplicate " + name)
    item = matches[0]
    expected = ("https://raw.githubusercontent.com/" + repo +
                "/builds/" + name + ".cs3")
    if item.get("url") != expected:
        raise ValueError("Untrusted release origin: " + name)
    version, checksum, size = (item.get("version"), item.get("fileHash"),
                               item.get("fileSize"))
    if type(version) is not int or not 1 <= version < 1000000:
        raise ValueError("Invalid version: " + name)
    if type(size) is not int or not 1000 < size < 50000000:
        raise ValueError("Invalid artifact size: " + name)
    if not isinstance(checksum, str) or not SHA256.fullmatch(checksum):
        raise ValueError("Missing/invalid digest: " + name)
    return {"version": version, "sha256": checksum, "bytes": size,
            "url": expected}


def inspect(fetch=fetch_json):
    providers = {}
    for name, repo in SOURCES:
        plugins = fetch("https://raw.githubusercontent.com/" +
                        repo + "/builds/plugins.json")
        release = select_release(plugins, name, repo)
        commit = fetch("https://api.github.com/repos/" + repo +
                       "/commits/master")
        sha = commit.get("sha") if isinstance(commit, dict) else None
        if not isinstance(sha, str) or not GIT_SHA.fullmatch(sha):
            raise ValueError("Missing source commit: " + name)
        # Source availability is explicitly measured, not inferred from a CS3.
        tree = fetch("https://api.github.com/repos/" + repo +
                     "/git/trees/master?recursive=1")
        if not isinstance(tree, dict) or tree.get("truncated"):
            raise ValueError("Incomplete source tree: " + name)
        files = tree.get("tree")
        if not isinstance(files, list):
            raise ValueError("Missing source tree: " + name)
        prefix = "CineStream/" if name == "CineStream" else "StreamPlay/"
        source_available = any(
            isinstance(item, dict) and item.get("type") == "blob"
            and item.get("path", "").startswith(prefix)
            and item.get("path", "").endswith((".kt", ".java"))
            for item in files
        )
        providers[name] = {
            "repository": repo,
            "source_commit": sha,
            "source_available": source_available,
            "integration": "monitor_only",
            "release": release,
        }
    return {"schema": 1, "providers": providers}


def changed_providers(old, new):
    previous = old.get("providers", {})
    return [name for name, item in new["providers"].items()
            if previous.get(name) != item]


def review_report(old, new):
    changed = changed_providers(old, new)
    lines = [
        "# MOOSA upstream review",
        "",
        "Metadata check only. Does not import, run, or publish third-party code.",
        "Never auto-merge a source-code change without tests and review.",
        "",
        "| Source | Version | Rebuildable source | Changed |",
        "|---|---:|---|---|",
    ]
    for name, item in new["providers"].items():
        lines.append("| " + name + " | " + str(item["release"]["version"]) +
                     " | " + ("Yes" if item["source_available"] else "No") +
                     " | " + ("Yes" if name in changed else "No") + " |")
    lines += ["", "Before any import: check GPLv3 obligations, API secrets, "
              "non-torrent policy, title/episode ID matching, 720p minimum, "
              "Kotlin tests, and real Fire TV playback.",
              "A published CS3 without upstream source is not an editable module.",
              ""]
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--write", action="store_true")
    p.add_argument("--report", type=Path)
    args = p.parse_args()
    before = json.loads(LOCK.read_text(encoding="utf8")) if LOCK.exists() else {}
    if before and before.get("schema") != 1:
        raise ValueError("Unexpected lockfile schema")
    after = inspect()
    changed = changed_providers(before, after)
    for name, data in after["providers"].items():
        previous = before.get("providers", {}).get(name)
        if previous and data["release"]["version"] < previous["release"]["version"]:
            raise ValueError("Upstream version rollback: " + name)
    if args.report:
        args.report.write_text(review_report(before, after), encoding="utf8")
    if args.write and changed:
        LOCK.parent.mkdir(parents=True, exist_ok=True)
        LOCK.write_text(json.dumps(after, indent=2, sort_keys=True) + "\n",
                        encoding="utf8")
    print("Changed upstreams: " + (", ".join(changed) if changed else "none"))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Upstream monitor failed safely: " + str(error), file=sys.stderr)
        sys.exit(1)
