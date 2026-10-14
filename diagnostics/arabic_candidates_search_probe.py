#!/usr/bin/env python3
"""Compare current source catalogue searches. No media downloaded."""
import json
from urllib.parse import quote,urlparse
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
from bs4 import BeautifulSoup
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
sites=[
  ("TOPCINEMA_LIVE","https://web.topcinema.io"),
  ("TOPCINEMA_OLD","https://topcima.online"),
  ("MYCIMA","https://mycima.mom"),
  ("EGYDEAD","https://tv10.egydead.live")]
tests=["اللعبة","الفيل الأزرق","جعفر العمدة"]
def get(url):
    try:
        with urlopen(Request(url,headers={"User-Agent":UA}),timeout=12) as r:
            return r.status,r.url,r.headers.get("Content-Type",""),r.read(300000).decode("utf8","replace")
    except HTTPError as e:return e.code,url,e.headers.get("Content-Type",""),e.read(2000).decode("utf8","replace")
    except (URLError,OSError) as e:return 0,url,"",str(e)
for name,base in sites:
    for q in tests:
        links=[
          ("wordpress",base+"/wp-json/wp/v2/posts?search="+quote(q)+"&per_page=10"),
          ("wordpress2",base+"/wp-json/wp/v2/search?search="+quote(q)+"&per_page=10"),
          ("query",base+"/?s="+quote(q))
        ]
        for tag,url in links:
            status,landing,ctype,body=get(url)
            if "json" in ctype or body.lstrip().startswith("["):
                try:
                    items=json.loads(body)
                    titles=[str(x.get("title") if isinstance(x.get("title"),str) else x.get("title",{}).get("rendered",""))[:70] for x in items[:4]] if isinstance(items,list) else []
                    total=len(items) if isinstance(items,list) else 0
                except (ValueError,AttributeError):total=0;titles=[]
                print("SEARCH_AUDIT",name,q,tag,"status",status,
                     "type",ctype[:25],"host",urlparse(landing).hostname,
                     "count",total,"titles",titles,flush=True)
            else:
                soup=BeautifulSoup(body,"html.parser")
                candidate=[
                    (a.get("title") or a.get_text(" ",strip=True))[:75]
                    for a in soup.select("a[href]") if q.split()[0] in (a.get("title") or a.get_text(" ",strip=True))
                ]
                print("SEARCH_AUDIT",name,q,tag,"status",status,
                     "host",urlparse(landing).hostname,
                     "html_size",len(body),"anchors",len(soup.select("a[href]")),
                     "matches",len(candidate),"titles",candidate[:3],flush=True)
