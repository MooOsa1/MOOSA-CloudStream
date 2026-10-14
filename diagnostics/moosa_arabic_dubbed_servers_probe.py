#!/usr/bin/env python3
"""Akwam-native dubbed category -> item -> episode -> CDN range smoke test.

Checks tiny MP4 signatures only (64 bytes); never downloads video files.
These checks do NOT prove sustained playback, codec, or encoded 1080p quality.
"""
from urllib.parse import quote, urljoin, urlparse
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError
from time import monotonic
from bs4 import BeautifulSoup
import re

ROOT = "https://ak.sv"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

def fetch(url, referer=None, range_probe=False):
    headers = {"User-Agent": UA, "Accept-Language": "ar,en;q=0.7"}
    if referer:
        headers["Referer"] = referer
    if range_probe:
        headers["Range"] = "bytes=0-63"
    start = monotonic()
    try:
        req = Request(quote(url, safe=":/?&=%#@;,+"), headers=headers)
        with urlopen(req, timeout=11) as response:
            body = response.read(64 if range_probe else 600000)
            return response.status, response.url, response.headers, body, round((monotonic() - start) * 1000)
    except HTTPError as error:
        return error.code, url, error.headers, error.read(64), round((monotonic() - start) * 1000)
    except (URLError, OSError, ValueError) as error:
        print("FETCH_FAILED", type(error).__name__, urlparse(url).hostname, flush=True)
        return 0, url, {}, b"", round((monotonic() - start) * 1000)

def html_page(url, referer=None):
    status, final, headers, body, ms = fetch(url, referer)
    return status, final, BeautifulSoup(body, "html.parser"), ms

def eligible_items(kind):
    status, final, doc, ms = html_page(f"{ROOT}/{kind}?category=71&page=1")
    title = doc.title.get_text(" ", strip=True) if doc.title else ""
    assert status == 200 and "مدبلج" in title, (kind, status, title)
    found = {}
    for a in doc.select("a[href]"):
        absolute = urljoin(final, a.get("href", ""))
        path = urlparse(absolute).path
        if re.match(rf"^/{'movie' if kind == 'movies' else 'series'}/\d+/", path):
            found.setdefault(absolute, a.get("title", "") or a.get_text(" ", strip=True))
    assert len(found) >= 5, (kind, "dubbed catalogue unexpectedly sparse", len(found))
    print("DUBBED_CATALOG", kind, "items", len(found), "ms", ms, flush=True)
    return list(found.items())

def media_signature(url, referer):
    status, final, headers, body, ms = fetch(url, referer, True)
    range_header = headers.get("Content-Range", "")
    is_mp4 = len(body) >= 8 and body[4:8] in (b"ftyp", b"styp")
    passed = status == 206 and is_mp4 and range_header.startswith("bytes 0-63/")
    print("CDN_RANGE", urlparse(final).hostname, "HTTP", status,
          "mp4", is_mp4, "content-range", range_header[:38], "ms", ms, flush=True)
    return passed

def probe_item(kind, item_url, item_title):
    status, final, doc, ms = html_page(item_url)
    if status != 200:
        print("SKIP_ITEM", kind, item_title[:40], "HTTP", status, flush=True)
        return False
    if kind == "series":
        episodes = [urljoin(final, a.get("href", "")) for a in doc.select("a[href*='/episode/']")]
        episodes = list(dict.fromkeys(episodes))
        if not episodes:
            print("SKIP_SERIES_NO_EPISODES", item_title[:55], flush=True)
            return False
        # Every episode link is taken from this exact series, never a title search.
        status, final, doc, ms = html_page(episodes[0], final)
        if status != 200:
            print("SKIP_EPISODE", item_title[:40], "HTTP", status, flush=True)
            return False
    tabs = doc.select("div.tab-content.quality")
    tabs.sort(key=lambda e: 0 if e.get("id") == "tab-5" else
                       1 if e.get("id") == "tab-4" else 2)
    for tab in tabs:
        if tab.get("id") not in ("tab-5", "tab-4"):
            continue
        wrappers = [urljoin(final, a.get("href", "")) for a in tab.select("a[href*='/download/']")]
        for wrapper in list(dict.fromkeys(wrappers))[:2]:
            code, wrapper_url, wrapper_doc, ms = html_page(wrapper, final)
            if code != 200:
                continue
            links = []
            for a in wrapper_doc.select("a[href]"):
                candidate = urljoin(wrapper_url, a.get("href", ""))
                host = urlparse(candidate).hostname or ""
                if host == "downet.net" or host.endswith(".downet.net"):
                    links.append(candidate)
            for candidate in list(dict.fromkeys(links))[:2]:
                # HTTPS first; HTTP is a separately identified, unencrypted fallback.
                parsed = urlparse(candidate)
                https = parsed._replace(scheme="https").geturl()
                if media_signature(https, wrapper):
                    print("PASS_DUBBED_STREAM", kind, "source_quality_tab", tab.get("id"),
                          "transport", "HTTPS", "item", item_title[:60], flush=True)
                    return True
                http = parsed._replace(scheme="http").geturl()
                if media_signature(http, wrapper):
                    print("PASS_DUBBED_STREAM", kind, "source_quality_tab", tab.get("id"),
                          "transport", "HTTP_UNENCRYPTED", "item", item_title[:60], flush=True)
                    return True
    print("NO_CONFIRMED_CDN", kind, item_title[:60], flush=True)
    return False

for kind in ("movies", "series"):
    # Newly listed titles can have absent episode/server uploads; try several
    # exact items from Akwam's own dubbed category, with a bounded request count.
    samples = eligible_items(kind)[:8]
    result = any(probe_item(kind, url, title) for url, title in samples)
    assert result, f"No working dubbed {kind} MP4 CDN range confirmed"
print("PASS: both native dubbed categories produced a verified MP4 206 range")
