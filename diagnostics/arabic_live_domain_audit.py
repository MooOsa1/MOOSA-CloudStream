#!/usr/bin/env python3
"""Public provider identity/search diagnostics; no video downloaded.
Only consider actual HTML catalogue URLs, never parked / advertising pages.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import quote, urljoin, urlparse
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError
from bs4 import BeautifulSoup
import time,re
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
SITES=[
    ("MySeed-listed-in-plugin","https://m.myseed.tv"),
    ("MySeed-old-domain","https://m.myseed.pics"),
    ("ArabSeed-gateway","https://arbsd.net"),
    ("ArabSeed-watch","https://arabseeds.watch"),
    ("ArabSeed-co","https://arabseeds.co")
]
TESTS=["اللعبة","بيت الروبي"]
def get(url):
    start=time.monotonic()
    try:
        with urlopen(Request(url, headers={"User-Agent":UA,"Accept-Language":"ar,en-US;q=0.8"}),timeout=12) as r:
            return r.url,r.status,r.read(700000).decode("utf-8","replace"),round(time.monotonic()-start,2)
    except HTTPError as e:
        return url,e.code,e.read(4000).decode("utf-8","replace"),round(time.monotonic()-start,2)
    except (URLError,TimeoutError,OSError) as e:
        return url,0,type(e).__name__,round(time.monotonic()-start,2)
def probe(item):
    name,base=item;out=[]
    for query in TESTS:
        url=base+"/?s="+quote(query)
        final,status,body,seconds=get(url)
        soup=BeautifulSoup(body,"html.parser")
        cards=[]
        for a in soup.select("a[href]"):
            label=a.get("title") or a.get_text(" ",strip=True)
            if not label or query.split()[0] not in label:continue
            href=urljoin(final,a.get("href",""))
            cards.append((urlparse(href).hostname,urlparse(href).path[:65],label[:65]))
        links=len(soup.select("a[href]"))
        js=[urlparse(a.get("src","")).hostname for a in soup.select("script[src]")[:5]]
        out.append({
            "name":name,"query":query,"HTTP":status,
            "host":urlparse(final).hostname,"secs":seconds,"html_len":len(body),
            "links":links,"matches":len(cards),"examples":cards[:2],
            "scripts":js,"title":soup.title.get_text(" ",strip=True)[:75] if soup.title else ""
        })
    return out
with ThreadPoolExecutor(max_workers=5) as pool:
    for future in as_completed([pool.submit(probe,x) for x in SITES]):
        for result in future.result():
            print("SOURCE_AUDIT",result,flush=True)
