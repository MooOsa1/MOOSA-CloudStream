#!/usr/bin/env python3
"""Inspect live native Akwam HTML for v2; no media streams downloaded."""
from urllib.request import Request,urlopen
from urllib.parse import quote,urljoin,urlparse
from collections import Counter
from bs4 import BeautifulSoup
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
BASE="https://ak.sv"
def get(path):
    url=BASE+path
    with urlopen(Request(quote(url,safe=":/?&=%#@;,+"),headers={"User-Agent":UA}),timeout=13) as resp:
        return resp.url,BeautifulSoup(resp.read(800000),"html.parser")
series=[
  ("Jaafar S1","/series/4072/%D8%AC%D8%B9%D9%81%D8%B1-%D8%A7%D9%84%D8%B9%D9%85%D8%AF%D8%A9-3"),
  ("El Ataawla S2","/series/5242/%D8%A7%D9%84%D8%B9%D8%AA%D8%A7%D9%88%D9%84%D8%A9-2"),
  ("Game S1","/series/32/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-23"),
  ("Game S5","/series/5557/%D8%A7%D9%84%D9%84%D8%B9%D8%A8%D8%A9-%D8%AC5-%D8%A7%D9%84%D9%83%D9%84%D8%A7%D8%B3%D9%8A%D9%83%D9%88")
]
for label,path in series:
    try:
        final,doc=get(path)
        title=doc.select_one("h1.entry-title,h1")
        anchors=doc.select("a[href*='/episode/']")
        urls=[urljoin(final,a.get("href","")) for a in anchors]
        print("SERIES",label,"title",title.get_text(" ",strip=True)[:80] if title else None,
              "raw_links",len(anchors),"unique",len(set(urls)),
              "parents",Counter(" ".join(a.parent.get("class",[])) for a in anchors).most_common(5),flush=True)
        for a in anchors[:8]:
            print("  EP",a.get_text(" ",strip=True)[:65],urlparse(a.get("href","")).path[-100:],"parent",a.parent.name,a.parent.get("class"),flush=True)
        siblings=doc.select("a[href*='/series/']")
        print(" RELATED",[(a.get_text(" ",strip=True)[:30],urlparse(a.get("href","")).path[:60]) for a in siblings[:10]],flush=True)
    except Exception as exc:
        print("SERIES_ERROR",label,type(exc).__name__,str(exc)[:130],flush=True)
for q in ("جعفر العمدة","العتاولة","اللعبة"):
    for page in (1,2):
        try:
            final,doc=get("/search?q="+quote(q)+"&page="+str(page))
            links={urljoin(final,a.get("href","")):a.get_text(" ",strip=True)[:60] for a in doc.select("a[href*='/series/'],a[href*='/movie/']")}
            print("SEARCH_PAGE",q,page,"found",len(links),"examples",list(links.values())[:6],flush=True)
        except Exception as exc:
            print("SEARCH_ERROR",q,page,type(exc).__name__,str(exc)[:110],flush=True)
for path in ("/movies?section=29&page=1","/series?section=29&page=1","/series?page=1"):
    try:
        _,doc=get(path)
        print("SECTION",path,"title",doc.title.get_text(" ",strip=True) if doc.title else None,
              "filters",[(a.get_text(" ",strip=True)[:40],a.get("href","")[:100]) for a in doc.select("a[href*='section='],a[href*='/countries'],a[href*='country=']")[:25]],flush=True)
    except Exception as exc: print("SECTION_ERROR",path,str(exc)[:90],flush=True)
