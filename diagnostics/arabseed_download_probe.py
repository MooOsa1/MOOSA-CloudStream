#!/usr/bin/env python3
"""Inspect ArabSeed's current download alternatives and confirm real media bytes.
No full videos are downloaded. Output excludes signed URLs/query tokens.
"""
import html
import re
from bs4 import BeautifulSoup
from http.cookiejar import CookieJar
from urllib.parse import quote,urljoin,urlparse
from urllib.request import Request,build_opener,HTTPCookieProcessor
from urllib.error import URLError,HTTPError

UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
opener=build_opener(HTTPCookieProcessor(CookieJar()))

def load(url,ref=None,limit=800000,byte_range=None):
    h={"User-Agent":UA,"Accept-Language":"ar,en-US;q=0.8,en;q=0.6"}
    if ref: h["Referer"]=ref
    if byte_range: h["Range"]=byte_range
    try:
        with opener.open(Request(url,headers=h),timeout=15) as resp:
            return {"status":resp.status,"url":resp.url,"type":resp.headers.get("Content-Type",""),
                    "range":resp.headers.get("Content-Range",""),
                    "body":resp.read(limit)}
    except HTTPError as e:
        return {"status":e.code,"url":url,"type":e.headers.get("Content-Type",""),
                "range":"","body":e.read(min(1500,limit)),"error":"HTTP"}
    except (URLError,TimeoutError,OSError) as e:
        return {"status":0,"url":url,"type":"","range":"","body":b"","error":type(e).__name__+":"+str(getattr(e,"reason",e))[:80]}

base="https://m.myseed.tv"
search=load(base+"/?s="+quote("اللعبة"))
soup=BeautifulSoup(search["body"],"html.parser")
episode=next((
    urljoin(search["url"],a["href"]) for a in soup.select("a[title][href]")
    if "مسلسل اللعبة الموسم الخامس الحلقة 30" in a.get("title","")
),None)
print("EPISODE_FOUND",bool(episode))
if episode:
    page=load(episode);doc=BeautifulSoup(page["body"],"html.parser")
    downloads=[
        urljoin(page["url"],a.get("href","")) for a in doc.select("a[href]")
        if "/download/" in a.get("href","")
    ]
    print("DOWNLOAD_PAGES",len(set(downloads)))
    if downloads:
        download=load(downloads[0],ref=page["url"])
        print("DOWNLOAD_PAGE",download["status"],urlparse(download["url"]).hostname,len(download["body"]))
        ddoc=BeautifulSoup(download["body"],"html.parser")
        links=[]
        for a in ddoc.select("a[href]"):
            text=a.get_text(" ",strip=True)
            if not ("تحميل" in text or "Download" in text or "سيرفر" in text):continue
            u=urljoin(download["url"],a.get("href",""))
            if not ("1080" in text or "720" in text or "480" in text):continue
            parent=a.parent
            klass=" ".join(parent.get("class",[])) if parent else ""
            grand=" ".join(parent.parent.get("class",[])) if parent and parent.parent else ""
            links.append((u,text[:110],klass[:75],grand[:75]))
        print("DOWNLOAD_CANDIDATES",len(links))
        for url,title,parent,grand in links[:12]:
            print("ANCHOR",title,"host",urlparse(url).hostname,"path",urlparse(url).path[:90],"parent",parent,"grand",grand)
        tested=set()
        for url,title,_,_ in links:
            quality=re.search(r"(1080|720|480)p",title)
            q=quality.group(1) if quality else "?"
            if q not in ("1080","720") or q in tested:continue
            tested.add(q)
            res=load(url,ref=download["url"],limit=128,byte_range="bytes=0-127")
            prefix=res["body"]
            is_mp4=len(prefix)>=8 and prefix[4:8] in (b"ftyp",b"styp")
            is_hls=prefix.startswith(b"#EXTM3U")
            is_html=b"<html" in prefix[:100].lower() or b"<!doctype" in prefix[:100].lower()
            print("MEDIA_CHECK",q,"status",res["status"],"host",urlparse(res["url"]).hostname,
                  "path",urlparse(res["url"]).path[:90],"content_type",res["type"][:45],
                  "content_range",res["range"][:50],"mp4",is_mp4,"hls",is_hls,"html",is_html,"error",res.get("error",""))
        print("DOWNLOAD_SELECTORS",[selector+": "+str(len(ddoc.select(selector))) for selector in (
            "ul.downloads__links__list a[href]","a.download__item[href]","a[href*='.mp4']",
            ".download__links a[href]",".download__list a[href]","ul a[href]"
        )])
