#!/usr/bin/env python3
"""Public Akwam episode/CDN smoke probe. Never prints signed media URLs."""
import html
import socket
import ssl
from html.parser import HTMLParser
from urllib.request import Request, urlopen
from urllib.parse import urljoin, urlparse, quote
from urllib.error import HTTPError, URLError

slug = quote("اللعبة-ج5-الكلاسيكو")
episode = quote("الحلقة-1")
page_url = f"https://akwam.ss/download/177413/99188/{slug}/{episode}"
ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

class Anchors(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
    def handle_starttag(self, tag, attrs):
        if tag != "a":
            return
        href = dict(attrs).get("href")
        if href:
            self.links.append(html.unescape(href).strip())

def request(url, referer=None, byterange=None, insecure=False):
    headers = {"User-Agent": ua, "Accept": "*/*"}
    if referer:
        headers["Referer"] = referer
    if byterange:
        headers["Range"] = byterange
    req = Request(url, headers=headers, method="GET")
    try:
        context = ssl._create_unverified_context() if insecure else None
        with urlopen(req, timeout=14, context=context) as response:
            return {
                "status": response.status,
                "url": response.url,
                "content_type": response.headers.get("Content-Type", ""),
                "content_range": response.headers.get("Content-Range", ""),
                "body": response.read(64 if byterange else 600000),
            }
    except HTTPError as error:
        return {"status": error.code, "error": f"HTTP {error.code}"}
    except (URLError, TimeoutError, OSError) as error:
        detail = str(getattr(error, "reason", error)).replace("\\n", " ")[:150]
        return {"status": 0, "error": f"{type(error).__name__}: {detail}"}

def show(label, result):
    print(
        f"{label}: status={result.get('status')}, "
        f"host={urlparse(result.get('url','')).hostname or 'none'}, "
        f"type={result.get('content_type','')[:65]}, "
        f"range={result.get('content_range','')[:55]}, "
        f"bytes={len(result.get('body',b''))}, "
        f"error={result.get('error','')}"
    )

page = request(page_url)
show("Akwam download page", page)
body = page.get("body", b"").decode("utf-8", "replace")
parser = Anchors()
parser.feed(body)
links = [
    urljoin(page_url, href)
    for href in parser.links
    if urlparse(urljoin(page_url, href)).hostname and
       (urlparse(urljoin(page_url, href)).hostname or "").endswith(".downet.net")
]
print(f"Downet download targets found: {len(links)}")
if links:
    target = links[0]
    media = urlparse(target)
    print(f"Download URL scheme: {media.scheme}; host: {media.hostname}")
    try:
        addresses = socket.getaddrinfo(media.hostname, 443)
        print(f"DNS resolved: {len(addresses)} address(es)")
    except OSError as error:
        print(f"DNS failed: {type(error).__name__}: {str(error)[:120]}")
    print(f"First media basename: {media.path.rsplit('/',1)[-1]}")
    begin = request(target, page_url, "bytes=0-63")
    show("MP4 beginning", begin)
    prefix = begin.get("body", b"")
    print(f"First bytes signature: MP4={len(prefix)>=8 and prefix[4:8]==b'ftyp'}")
    middle = request(target, page_url, "bytes=1048576-1048639")
    show("MP4 seek", middle)
    seek_ok = (
        begin.get("status")==206 and
        middle.get("status")==206 and
        middle.get("content_range","").startswith("bytes 1048576-") and
        len(middle.get("body", b""))>0
    )
    print(f"Progressive playback seek test: {'PASS' if seek_ok else 'FAIL'}")
    # Diagnostics only: determine whether TLS is the sole blocker.
    # This result is NEVER passed to the app or used to enable unsafe playback.
    if begin.get("status") == 0 and "CERTIFICATE_VERIFY_FAILED" in begin.get("error", ""):
        print("Retesting *for diagnosis only* with certificate verification disabled")
        unsafe_begin = request(target, page_url, "bytes=0-63", insecure=True)
        show("Unverified-cert MP4 start", unsafe_begin)
        print(
            "Unverified-cert MP4 signature: " +
            str(unsafe_begin.get("body", b"")[4:8] == b"ftyp")
        )
        unsafe_seek = request(target, page_url, "bytes=1048576-1048639", insecure=True)
        show("Unverified-cert MP4 seek", unsafe_seek)
        # Check whether the CDN offers a real HTTP endpoint or upgrades to TLS.
        http_url = target.replace("https://", "http://", 1)
        plain = request(http_url, page_url, "bytes=0-63")
        show("HTTP transport (diagnosis only)", plain)
        print(
            "HTTP final scheme: " +
            (urlparse(plain.get("url", "")).scheme or "none")
        )
else:
    print("Cannot test CDN from this runner: page did not expose direct target.")
