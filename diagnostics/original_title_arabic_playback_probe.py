#!/usr/bin/env python3
"""Arabic matching diagnostic: Akwam CDN range speed and MySeed item pages.
Only reads metadata and at most 256 KiB of media per checked CDN. It never
downloads complete films or episodes. Prints no streaming tokens.
"""
import re, html, time, json
from urllib.parse import quote, urlparse, urljoin
from urllib.error import URLError,HTTPError
from urllib.request import Request, urlopen
from bs4 import BeautifulSoup

UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132 Safari/537.36"
def get(url,ref=None,maxbytes=450000,range_spec=None):
    heads={"User-Agent":UA,"Accept-Language":"ar,en-US;q=0.8,en;q=0.7"}
    if ref: heads["Referer"]=ref
    if range_spec:heads["Range"]=range_spec
    url=quote(url,safe=":/?&=%#@;+,-")
    began=time.monotonic()
    try:
        with urlopen(Request(url,headers=heads),timeout=13) as resp:
            blob=resp.read(maxbytes)
            return resp.status,resp.url,resp.headers,blob,time.monotonic()-began
    except HTTPError as e:
        return e.code,url,e.headers,e.read(1200),time.monotonic()-began
    except (URLError,TimeoutError,OSError) as e:
        return 0,url,{},str(e).encode()[:200],time.monotonic()-began

def page_text(doc):
    h=doc.select_one("h1,.entry-title,.as-hero-title")
    return h.get_text(" ",strip=True)[:120] if h else ""

def myseed():
    base="https://m.myseed.tv"
    samples=[
        ("مسلسل","جعفر العمدة"),
        ("مسلسل","اللعبة"),
        ("مسلسل","العتاولة"),
        ("فيلم","بيت الروبي"),
        ("فيلم","الحريفة"),
        ("فيلم","Inception")
    ]
    for kind,query in samples:
        status,url,headers,raw,_=get(base+"/?s="+quote(query))
        doc=BeautifulSoup(raw,"html.parser")
        cards=[]
        seen=set()
        for a in doc.select("a[title][href]"):
            title=(a.get("title") or "").strip()
            href=urljoin(url,a.get("href",""))
            if query.lower() not in title.lower() or href in seen:
                continue
            seen.add(href)
            cards.append((title,href))
        print("MYSEED_SEARCH",kind,query,"status",status,"results",len(cards), flush=True)
        for title,href in cards[:2]:
            print(" MYSEED_TITLE",title[:115],"path",urlparse(href).path[:100],flush=True)
        if not cards:continue
        # Check a single content post from each result, including episode
        # pages, to determine if the item is actually a series landing page.
        wanted=next(((t,h) for t,h in cards if ("الحلقة 1" in t or "الحلقة الاولى" in t)),cards[0])
        title,href=wanted
        st,final,heads,body,_=get(href)
        d=BeautifulSoup(body,"html.parser")
        epanchors=d.select(".episodes__list a[href],.episodes-list a[href],a[href*='الحلقة']")
        watch=d.select_one("a.watch__btn[href],a[href*='/watch/']")
        watchlink=urljoin(final,watch["href"]) if watch else final.rstrip("/")+"/watch/"
        ws,wurl,_,watchbody,_=get(watchlink,final)
        wd=BeautifulSoup(watchbody,"html.parser")
        qtabs=[a.get("data-quality") for a in wd.select("li[data-quality]")][:8]
        svr=[(s.get("data-server"),s.get("data-qu"),s.get("data-src","")[:26]) for s in wd.select("li[data-server],li[data-src]")[:7]]
        print(" MYSEED_ITEM","status",st,"h1",page_text(d),"epanchors",len(epanchors),"watch",ws,"qualities",qtabs,"servers",svr,"csrf",bool(re.search("csrf__token",watchbody.decode("utf-8","replace"))),flush=True)

def akwam():
    base="https://akwam.ss"
    samples=[("MOVIE","بيت الروبي"),("MOVIE","الحريفة"),("SHOW","جعفر العمدة"),("SHOW","العتاولة")]
    for kind,query in samples:
        st,url,_,body,_=get(base+"/search?q="+quote(query))
        doc=BeautifulSoup(body,"html.parser")
        selector="a[href*='/movie/']" if kind=="MOVIE" else "a[href*='/series/']"
        items=list(dict.fromkeys(urljoin(url,a["href"]) for a in doc.select(selector)))
        print("AKWAM_SEARCH",kind,query,"status",st,"results",len(items),flush=True)
        if not items:continue
        target=next((u for u in items if query.replace(" ","-") in u),items[0])
        st,url,_,body,_=get(target)
        doc=BeautifulSoup(body,"html.parser")
        if kind=="SHOW":
            episodes=list(dict.fromkeys(urljoin(url,a["href"]) for a in doc.select("a[href*='/episode/']")))
            if not episodes:continue
            ep=next((e for e in episodes if re.search(r"/الحلقة-1(?:/|$)",e)),episodes[0])
            st,url,_,body,_=get(ep)
            doc=BeautifulSoup(body,"html.parser")
        downloads=list(dict.fromkeys(urljoin(url,a["href"]) for a in doc.select("a[href*='/download/']")))
        if not downloads:continue
        st,durl,_,dbody,_=get(downloads[0],url)
        dd=BeautifulSoup(dbody,"html.parser")
        medias=list(dict.fromkeys(urljoin(durl,a["href"]) for a in dd.select("a[href]") if ".downet.net" in a.get("href","")))
        for media in medias[:2]:
            link=media.replace("https://","http://",1)
            st,final,headers,prefix,elapsed=get(link,durl,maxbytes=256*1024,range_spec="bytes=0-262143")
            signature=len(prefix)>8 and prefix[4:8]==b"ftyp"
            rate=round(len(prefix)*8/max(elapsed,.001)/1e6,2)
            print(" AKWAM_RANGE",query,"host",urlparse(final).hostname,"status",st,"mp4",signature,"size",len(prefix),"elapsed_s",round(elapsed,2),"Mbps_sample",rate,"range",headers.get("Content-Range","")[:38],flush=True)
            break

def tmdb():
    import os
    key="e6333b32409e02a4a6eba6fb7ff866bb"
    api="https://api.themoviedb.org/3"
    for typ,title in [("movie","Parasite"),("movie","بيت الروبي"),("tv","العتاولة")]:
        uri=api+"/search/"+typ+"?api_key="+key+"&query="+quote(title)+"&language=en-US"
        st,_,_,raw,_=get(uri)
        try:
            items=json.loads(raw).get("results",[])
            if items:
                entry=items[0]
                print("TMDB_ORIGINAL",typ,title,"searched_en",entry.get("title") or entry.get("name"),"original",entry.get("original_title") or entry.get("original_name"),"lang",entry.get("original_language"),flush=True)
        except Exception:print("TMDB_ERROR",typ,title,st,flush=True)

for name,fn in [("myseed",myseed),("akwam",akwam),("tmdb",tmdb)]:
    try: fn()
    except Exception as e:print("TEST_EXCEPTION",name,type(e).__name__,str(e)[:110],flush=True)
