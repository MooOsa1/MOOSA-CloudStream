#!/usr/bin/env python3
"""Live, source-only diagnostics for dubbing categories and playback mirrors.
No media files downloaded: check at most 64 bytes of MP4 headers with Range.
"""
from urllib.parse import quote,urlparse,urljoin,urlencode
from urllib.request import Request,urlopen
from urllib.error import URLError,HTTPError
from time import monotonic
from bs4 import BeautifulSoup
import re
ROOT="https://ak.sv"
HEADERS={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36","Accept-Language":"ar,en;q=0.7"}
def fetch(url, referer=None, byte_range=None):
    h=dict(HEADERS)
    if referer:h["Referer"]=referer
    if byte_range:h["Range"]="bytes=0-63"
    t=monotonic()
    try:
        with urlopen(Request(quote(url,safe=":/?&=%#@;,+"),headers=h),timeout=11) as r:
            body=r.read(64 if byte_range else 600000)
            return r.status,r.url,r.headers,body,round((monotonic()-t)*1000)
    except HTTPError as e:
        return e.code,url,e.headers,e.read(64),round((monotonic()-t)*1000)
    except (URLError,OSError) as e:
        return 0,url,{},str(type(e).__name__).encode(),round((monotonic()-t)*1000)
def scan(path):
    status,url,headers,raw,ms=fetch(ROOT+path)
    return status,url,BeautifulSoup(raw,"html.parser"),ms
print("DISCOVER")
for group in ["movies","series"]:
    for route in [f"/{group}?section=29&page=1",f"/{group}?page=1"]:
        status,url,doc,ms=scan(route)
        opts=[]
        for sel in doc.select("select"):
            options=[{"text":op.get_text(" ",strip=True),"value":op.get("value"),"data":dict(op.attrs)} for op in sel.select("option")]
            matches=[op for op in options if "مدبلج" in op["text"] or "دبلجة" in op["text"] or "عربي" in op["text"]]
            opts.append({"name":sel.get("name"),"id":sel.get("id"),"class":sel.get("class"),"count":len(options),"matching":matches[:8]})
        anchors=[(a.get_text(" ",strip=True)[:35],a.get("href","")[:110]) for a in doc.select("a[href]") if "مدبلج" in a.get_text(" ",strip=True) or "دبلجة" in a.get_text(" ",strip=True)]
        names=[a.get("title") or a.get_text(" ",strip=True) for a in doc.select("a[href*='/series/'],a[href*='/movie/']")]
        print("FILTER",group,route,"HTTP",status,"ms",ms,"selects",opts,"dubanchors",anchors[:8],"dub_titles",list(dict.fromkeys(x for x in names if "مدبلج" in x))[:6],flush=True)
for grp in ["movies","series"]:
    for path in [f"/{grp}?category=26&page=1",f"/{grp}?category=27&page=1"]:
        status,url,doc,ms=scan(path)
        print("CATEGORY_SAMPLE",grp,path,"title",doc.title.get_text(" ",strip=True)[:90] if doc.title else "","count",len(doc.select("a[href*='/movie/'],a[href*='/series/']")),flush=True)
print("SERVERS")
for label,page in [
    ("بيت الروبي",ROOT+"/movie/9031/بيت-الروبي-5"),
    ("جعفر العمدة",ROOT+"/series/4072/جعفر-العمدة-3")
]:
    status,url,_,body,ms=fetch(page)
    doc=BeautifulSoup(body,"html.parser")
    if "/series/" in page:
        ep=next((a.get("href") for a in doc.select("a[href*='/episode/']") if re.search(r"حلقة\s*1(?:\D|$)",a.get_text(" ",strip=True))),None)
        if ep:
            status,url,_,body,ms=fetch(urljoin(url,ep))
            doc=BeautifulSoup(body,"html.parser")
    print("ITEM",label,"HTTP",status,"ms",ms,"title",doc.select_one("h1").get_text(" ",strip=True)[:90] if doc.select_one("h1") else "",flush=True)
    tabs=doc.select("div.tab-content.quality")
    for tab in tabs:
        links=[urljoin(url,a.get("href","")) for a in tab.select("a[href*='/download/'],a[href*='/watch/'],a[href*='/link/']")]
        print("TAB",label,"id",tab.get("id"),"data-quality",tab.get("data-quality"),"links",len(links),"paths",[urlparse(x).path[:75] for x in links[:3]],flush=True)
    wrappers=[urljoin(url,a.get("href","")) for a in doc.select("a[href*='/download/']")]
    seen=set()
    for wrapper in wrappers[:3]:
        if wrapper in seen:continue
        seen.add(wrapper)
        status,final,_,raw,ms=fetch(wrapper,url)
        html=BeautifulSoup(raw,"html.parser")
        destinations=[urljoin(final,a.get("href","")) for a in html.select("a[href]") if (urlparse(urljoin(final,a.get("href",""))).hostname or "").endswith(".downet.net")]
        print("WRAPPER",label,"HTTP",status,"ms",ms,"targets",len(destinations),flush=True)
        if destinations:
            target=destinations[0]
            for scheme in ("https","http"):
                candidate=target.replace("https://",scheme+"://",1).replace("http://",scheme+"://",1)
                code,final,head,part,ms=fetch(candidate,wrapper,True)
                signature=len(part)>8 and part[4:8] in (b"ftyp",b"styp")
                print("MIRROR",label,"scheme",scheme,"host",urlparse(final).hostname,"HTTP",code,"ms",ms,"mp4",signature,"range",str(head.get("Content-Range",""))[:32],flush=True)
            break
print("DONE")
