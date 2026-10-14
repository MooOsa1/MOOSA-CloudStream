#!/usr/bin/env python3
"""Test current TopCinema Arabic episode watch route and server hosts.
Only metadata/HTML is fetched, no movie streams downloaded.
"""
import json,re,html
from urllib.parse import quote,urljoin,urlparse
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
from bs4 import BeautifulSoup
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
base="https://topcima.online"
def request(url,ref=None):
    h={"User-Agent":UA,"Accept":"text/html,application/json,*/*"}
    if ref:h["Referer"]=ref
    try:
        with urlopen(Request(quote(url,safe=":/?&=%#@;,+"),headers=h),timeout=12) as r:
            return r.status,r.url,r.read(450000).decode("utf8","replace")
    except HTTPError as e:return e.code,url,e.read(5000).decode("utf8","replace")
    except (URLError,OSError) as e:return 0,url,type(e).__name__+":"+str(e)[:90]
search=base+"/wp-json/wp/v2/search?search="+quote("اللعبة")+"&per_page=10"
s,u,body=request(search)
items=json.loads(body) if body.lstrip().startswith("[") else []
print("SEARCH",s,len(items),[(str(i.get("title",""))[:80],i.get("id")) for i in items[:6]],flush=True)
candidate=next((i for i in items if "مسلسل اللعبة الموسم الخامس الحلقة" in i.get("title","")),None)
if candidate:
    item_url=candidate.get("url","")
    for tag,url,ref in [("EPISODE",item_url,None),("WATCH",item_url.rstrip("/")+"/watch/",item_url)]:
        st,landing,raw=request(url,ref)
        soup=BeautifulSoup(raw,"html.parser")
        print("PAGE",tag,"status",st,"host",urlparse(landing).hostname,"len",len(raw),
          "title",soup.title.get_text(" ",strip=True)[:85] if soup.title else "",
          "h1",[n.get_text(" ",strip=True)[:95] for n in soup.select("h1")[:4]],
          "episode_links",len(soup.select(".allepcont a[href],.EpisodesList a[href],.seasonslist li")),
          "iframes",[urlparse(urljoin(landing,f.get("src",""))).hostname for f in soup.select("iframe[src]")[:8]],
          "quality_numbers",[i for i in (2160,1080,720) if str(i) in raw],
          flush=True)
        for sel in ["ul#watch > li",".servers-list li","[data-watch]","[data-src]","iframe[src]",".WatchIframe",".player-embed","video[src]"]:
            found=soup.select(sel)
            print("ELEMENT",tag,sel,"count",len(found),"samples",[
                {"label":el.get_text(" ",strip=True)[:35],
                 "data_host":urlparse(el.get("data-watch","")).hostname,
                 "iframe_host":urlparse(urljoin(landing,el.select_one("iframe[src]").get("src"))) .hostname if el.select_one("iframe[src]") else None}
                for el in found[:4]
            ],flush=True)
        marker=next((m for m in ["reviewrate.net","gamehub.cam","filemoon","vidmoly","vidshar","upbom.cam"] if m in raw),None)
        print("PLAYER_HOST_MARKER",tag,marker,flush=True)
