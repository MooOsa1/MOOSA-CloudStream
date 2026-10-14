#!/usr/bin/env python3
"""Trace the exact Arabic titles reported as broken, using provider metadata only.
Never download media, interpret source labels as actual HD, or claim playback.
"""
import json
from urllib.parse import quote, urlencode, urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
TITLES = ("هوجان", "فرصة ثانية", "جعفر العمدة", "بيت الروبي")
BASES = {"TOPCINEMA": "https://www.topcinema.vip", "AKWAM": "https://ak.sv",
         "ARABSEED": "https://m.myseed.tv"}
def fetch(url):
    try:
        with urlopen(Request(url,headers={"User-Agent":UA,"Accept-Language":"ar,en-US;q=0.8"}),timeout=11) as r:
            return r.status, r.url, r.read(500000).decode("utf-8","replace")
    except HTTPError as e:
        return e.code, url, e.read(2000).decode("utf-8","replace")
    except (URLError,TimeoutError,OSError) as e:
        return 0,url,str(e)[:100]
for title in TITLES:
    print("TITLE",title,flush=True)
    for provider,base in BASES.items():
        if provider=="TOPCINEMA":
            for page in (1,2):
                u=base+"/wp-json/wp/v2/search?"+urlencode({"search":title,"per_page":"30","page":str(page)})
                status,final,body=fetch(u)
                try: objects=json.loads(body)
                except (ValueError,TypeError): objects=[]
                if not isinstance(objects,list):objects=[]
                results=[(str(x.get("title",""))[:90],urlparse(x.get("url","")).path[:85]) for x in objects[:8] if isinstance(x,dict)]
                print(provider,title,"page",page,"HTTP",status,"count",len(objects),"titles",results,flush=True)
                if len(objects)<30:break
        elif provider=="AKWAM":
            status,final,body=fetch(base+"/search?q="+quote(title))
            doc=BeautifulSoup(body,"html.parser")
            anchors=doc.select("a[href*='/series/'],a[href*='/movie/']")
            rows=[]
            for a in anchors:
                name=a.select_one("h1,h2,h3,.entry-title,.title")
                label=(name.get_text(" ",strip=True) if name else a.get("title") or
                       (a.select_one("img[alt]").get("alt") if a.select_one("img[alt]") else a.get_text(" ",strip=True)))
                if label and label not in ("مشاهدة","شاهد"):
                    rows.append((label[:90],urlparse(a.get("href","")).path[:80]))
            print(provider,title,"HTTP",status,"all",len(anchors),"titles",list(dict.fromkeys(rows))[:10],flush=True)
        else:
            status,final,body=fetch(base+"/?s="+quote(title))
            doc=BeautifulSoup(body,"html.parser")
            rows=[(a.get("title","")[:90],urlparse(a.get("href","")).path[:75])
                  for a in doc.select("a[title][href]") if title.split()[0] in a.get("title","")]
            print(provider,title,"HTTP",status,"html",len(body),"matches",len(rows),"titles",rows[:10],flush=True)
