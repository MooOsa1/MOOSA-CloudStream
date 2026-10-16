#!/usr/bin/env python3
"""Inspect local CloudStream CS3 packages without installing or executing code."""
import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
MAX_FILES = 256
MAX_TOTAL_EXTRACTED = 32 * 1024 * 1024
MAX_MEMBER_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_BYTES = 128 * 1024
DEX_PATTERN = re.compile(r"(?:^|/)classes\d*\.dex$", re.IGNORECASE)
JS_PATTERN = re.compile(r"(?:^|/)(?:index|main|plugin)\.(?:mjs|cjs|js)$", re.IGNORECASE)

def inspect_cs3(filename):
    path = Path(filename)
    size = path.stat().st_size
    if size < 8 or size > MAX_ARCHIVE_BYTES:
        raise ValueError("archive_size_out_of_bounds")
    checksum = hashlib.sha256(path.read_bytes()).hexdigest()
    if not zipfile.is_zipfile(path):
        raise ValueError("not_zip_archive")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_FILES:
            raise ValueError("too_many_entries")
        total = 0
        for entry in entries:
            name = entry.filename
            if name.startswith("/") or "\\" in name or any(part in (".", "..") for part in name.split("/")):
                raise ValueError("unsafe_archive_path")
            if entry.file_size > MAX_MEMBER_BYTES:
                raise ValueError("oversized_entry")
            if entry.file_size and entry.file_size > max(entry.compress_size, 1) * 400:
                raise ValueError("suspicious_compression_ratio")
            total += entry.file_size
            if total > MAX_TOTAL_EXTRACTED:
                raise ValueError("archive_expanded_too_large")
        names = [entry.filename for entry in entries if not entry.is_dir()]
        dex = sorted(name for name in names if DEX_PATTERN.search(name))
        embedded_js = sorted(name for name in names if JS_PATTERN.search(name))
        for name in dex:
            with archive.open(name) as content:
                head = content.read(8)
                if len(head) < 8 or not head.startswith(b"dex\n") or head[7] != 0:
                    raise ValueError("invalid_dex_magic")
        if "manifest.json" not in names:
            raise ValueError("missing_cloudstream_manifest")
        if archive.getinfo("manifest.json").file_size > MAX_MANIFEST_BYTES:
            raise ValueError("manifest_too_large")
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("invalid_manifest")
        classname = str(manifest.get("pluginClassName") or "")[:160]
        version = manifest.get("version")
    return {
        "file": path.name,
        "size_bytes": size,
        "sha256": checksum,
        "plugin_class": classname,
        "version": version if type(version) is int else None,
        "dex_files": dex,
        "script_entries": embedded_js,
        "format": "android_dex_archive" if dex else "archive_without_dex",
        "direct_node_execution": False,
        "requires_separate_port_or_android_runtime": bool(dex),
        "note": "Static ZIP inspection only; no plugin code executed."
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", nargs="+", help="Local CS3 files only, not URLs")
    args = parser.parse_args()
    results = []
    failed = False
    for filename in args.archives:
        try:
            results.append(inspect_cs3(filename))
        except (OSError, zipfile.BadZipFile, ValueError, UnicodeError) as err:
            failed = True
            results.append({"file": Path(filename).name, "error": str(err)[:140]})
    print(json.dumps({"results": results}, ensure_ascii=False, indent=2))
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
