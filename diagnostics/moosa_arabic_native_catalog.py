#!/usr/bin/env python3
"""Live smoke test of the native Akwam-backed MOOSA Arabic catalog.
Only HTML metadata is requested. Never downloads video or prints streaming tokens.
Fail the job on empty/misrouted Arabic catalog pages.
"""
from urllib.request import urlopen, Request
from urllib.parse import quote, urljoin, urlparse
from urllib.error import HTTPError, URLError
from bs4 import BeautifulSoup
import re

BASE = "https://ak.sv"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
def fetch(url):
    try:
        with urlopen(Request(url, headers={"User-Agent": UA, "Accept-Language": "ar,en;q=0.5"}), timeout=16) as resp:
            return resp.status, resp.url, resp.read(1_000_000).decode("utf-8", "replace")
    except HTTPError as e:
        return e.code, url, e.read(3000).decode("utf-8","replace")
    except (URLError, OSError) as e:
        return 0, url, type(e).__name__ + ": " + str(e)[:100]

def catalog_items(body, page):
    soup=BeautifulSoup(body, "html.parser")
    grouped={}
    for a in soup.select("a[href*='/series/'],a[href*='/movie/']"):
        href=urljoin(page,a.get("href",""))
        tag=a.select_one("h1,h2,h3,.entry-title,.title")
        heading=tag.get_text(" ",strip=True) if tag else ""
        img=a.select_one("img[alt]")
        title=heading or a.get("title","").strip() or (img.get("alt","").strip() if img else "")
        if not title:
            for p in list(a.parents)[:3]:
                tag=p.select_one("h1,h2,h3,.entry-title,.title")
                if tag:
                    title=tag.get_text(" ",strip=True)
                    if title:break
        if title and title.strip() not in ("مشاهدة","شاهد","-->"):
            if len(title)>len(grouped.get(href,"")):grouped[href]=title.strip()
    return grouped,soup

checked={}
for category, path, must_contain in [
    ("أفلام عربية","/movies?section=29&page=1","/movie/"),
    ("مسلسلات عربية","/series?section=29&page=1","/series/"),
]:
    status,final,body=fetch(BASE+path)
    found,soup=catalog_items(body,final)
    own={url:title for url,title in found.items() if must_contain in urlparse(url).path}
    page_title=soup.title.get_text(" ",strip=True)[:90] if soup.title else ""
    print("CATALOG",category,"HTTP",status,"site",urlparse(final).hostname,
          "html",len(body),"title",page_title,
          "entries",len(own),"samples",list(own.values())[:7],flush=True)
    assert status==200 and own, f"{category} returned no native Akwam cards"
    checked[category]=own

for name in ("جعفر العمدة","بيت الروبي"):
    status,final,body=fetch(BASE+"/search?q="+quote(name))
    found,_=catalog_items(body,final)
    direct=[(title,urlparse(url).path) for url,title in found.items() if name in title]
    print("SEARCH",name,"HTTP",status,"native_matches",len(direct),
          "samples",direct[:4],flush=True)
    if status!=200:
        print("WARNING: Akwam search not reachable for",name,flush=True)
    # Search may legitimately omit titles, so no misleading pass claim.

serial=checked["مسلسلات عربية"]
first_url=next(iter(serial))
status,final,body=fetch(first_url)
soup=BeautifulSoup(body,"html.parser")
eps=[urljoin(final,a["href"]) for a in soup.select("a[href*='/episode/']")]
title=soup.select_one("h1.entry-title,h1")
print("SERIES_LOAD","HTTP",status,"path",urlparse(final).path[:110],
      "title",title.get_text(" ",strip=True)[:80] if title else "",
      "episode_links",len(set(eps)),flush=True)
assert status==200, "Akwam Arabic series detail page failed"
print("PASS: Arabic catalog pages and native series item can be requested")
