#!/usr/bin/env python3
"""Inspect live Akwam title/season search and episode URLs, without downloading media."""
import re
import html
from urllib.request import Request, urlopen
from urllib.parse import quote, urljoin, urlparse
from urllib.error import URLError

BASE = "https://akwam.ss"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
PAGES = [
    (1, "https://akwam.ss/series/32/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-23"),
    (2, "https://akwam.ss/series/1404/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AB%D8%A7%D9%86%D9%8A-18"),
    (3, "https://akwam.ss/series/2793/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AB%D8%A7%D9%84%D8%AB-%D8%A7%D9%84%D9%84%D8%B9%D8%A8-%D9%85%D8%B9-%D8%A7%D9%84%D9%83%D8%A8%D8%A7%D8%B1-41"),
    (4, "https://akwam.ss/series/4234/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%B1%D8%A7%D8%A8%D8%B9-%D8%AF%D9%88%D8%B1%D9%8A-%D8%A7%D9%84%D8%A3%D8%A8%D8%B7%D8%A7%D9%84-4"),
    (5, "https://akwam.ss/series/5557/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-%D8%AC5-%D8%A7%D9%84%D9%83%D9%84%D8%A7%D8%B3%D9%8A%D9%83%D9%88")
]

def fetch(url):
    try:
        with urlopen(Request(url, headers={"User-Agent": UA, "Accept-Language": "ar,en;q=0.7"}), timeout=15) as r:
            return r.url, r.read(500_000).decode("utf-8", "replace")
    except (URLError, TimeoutError) as e:
        print("FETCH FAILED", type(e).__name__, str(e)[:130])
        return url, ""

def anchors(text, base):
    for m in re.finditer(r'<a\b([^>]*?)>(.*?)</a\s*>', text, re.I | re.S):
        attrs, inner = m.groups()
        href = re.search(r'\bhref\s*=\s*["\x27]([^"\x27]+)["\x27]', attrs, re.I)
        if not href:
            continue
        label = html.unescape(re.sub(r'<[^>]+>', ' ', inner))
        label = re.sub(r'\s+', ' ', label).strip()
        yield urljoin(base, html.unescape(href.group(1))), label, attrs[:160]

for season, url in PAGES:
    final, raw = fetch(url)
    series = [(href, label) for href,label,_ in anchors(raw,final) if "/series/" in urlparse(href).path]
    episodes = [(href, label) for href,label,_ in anchors(raw,final) if "/episode/" in urlparse(href).path]
    title = re.search(r'<title[^>]*>(.*?)</title>', raw, re.S | re.I)
    print(f"SERIES S{season} final_host={urlparse(final).hostname} title={html.unescape(title.group(1)).strip()[:90] if title else 'n/a'}")
    print(f"  bytes={len(raw)}, series_links={len(series)}, episode_links={len(episodes)}")
    for href,label in episodes[:3]:
        print(f"  EP {urlparse(href).path[:160]} LABEL {label[:100]!r}")
    for href,label in series[:3]:
        print(f"  RELATED {urlparse(href).path[:120]} {label[:55]!r}")
    print()

queries = [
    "اللعبة",
    "اللعبة الموسم الثاني",
    "اللعبة الموسم الثالث",
    "اللعبة الموسم الرابع",
    "اللعبة ج5"
]
for query in queries:
    final, raw = fetch(f"https://ak.sv/search?q={quote(query)}")
    results = [(href,label) for href,label,_ in anchors(raw,final) if "/series/" in urlparse(href).path]
    unique = list(dict(((href,label),(href,label)) for href,label in results).values())
    print(f"SEARCH {query!r}: host={urlparse(final).hostname} html={len(raw)} results={len(unique)}")
    for href,label in unique[:8]:
        print(f"  {urlparse(href).path[:135]} TITLE {label[:90]!r}")
