#!/usr/bin/env python3
"""Regression: the native dubbed catalog is NOT a text filter of section=29.
Check source site routes only; never download movies or videos.
"""
from urllib.request import Request,urlopen
from urllib.parse import quote,urljoin
from bs4 import BeautifulSoup
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
ROOT="https://ak.sv"
def fetch(path):
    with urlopen(Request(quote(ROOT+path,safe=":/?&=%#@;,+"),headers={"User-Agent":UA}),timeout=16) as r:
        return r.status,r.url,BeautifulSoup(r.read(900000),"html.parser")
for kind in ("movies","series"):
    normal,normal_url,normal_doc=fetch(f"/{kind}?section=29&page=1")
    dubbed,dubbed_url,dubbed_doc=fetch(f"/{kind}?category=71&page=1")
    page2,url2,doc2=fetch(f"/{kind}?category=71&page=2")
    def entries(doc,base):
        return {urljoin(base,a.get("href","")):
                (a.get("title") or a.get_text(" ",strip=True))
                for a in doc.select("a[href*='/movie/'],a[href*='/series/']")
                if (a.get("title") or a.get_text(" ",strip=True)).strip() not in ("","مشاهدة","شاهد")}
    arab=entries(normal_doc,normal_url)
    dubs=entries(dubbed_doc,dubbed_url)
    next_page=entries(doc2,url2)
    title=dubbed_doc.title.get_text(" ",strip=True) if dubbed_doc.title else ""
    print("DUBBED_NATIVE",kind,"title",title,"HTTP",dubbed,
          "page1",len(dubs),"page2",len(next_page),
          "samples",list(dubs.values())[:8],flush=True)
    assert normal==200 and dubbed==200 and page2==200
    assert len(dubs)>=15, "Dubbed source page unexpectedly empty"
    assert len(next_page)>=5, "Dubbed source page 2 unexpectedly empty"
    assert len(dubs)>len([v for v in arab.values() if "مدبلج" in v]), (
        "Regression: old Arabic-section text filtering still equals dubbed catalog")
    assert "مدبلج" in title, "Source did not confirm dubbed-category routing"
print("PASS: genuine dubbed films and shows are sourced from category=71 across pages")
