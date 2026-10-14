#!/usr/bin/env python3
"""Audits several different Arabic movies and shows against Akwam.
No complete video downloads. Prints no signed media query parameters.
This is a diagnostic of indexing + item HTML + final CDN range bytes.
"""
import re, html, socket
from collections import defaultdict
from html.parser import HTMLParser
from urllib.request import Request, urlopen
from urllib.parse import quote, urlparse, urljoin
from urllib.error import URLError, HTTPError

BASE="https://akwam.ss"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
CASES=[
    ("MOVIE", "بيت الروبي", 2023),
    ("MOVIE", "الحريفة", 2024),
    ("MOVIE", "السرب", 2024),
    ("MOVIE", "ولاد رزق 3", 2024),
    ("MOVIE", "الفيل الأزرق 2", 2019),
    ("SHOW", "جعفر العمدة", 2023),
    ("SHOW", "العتاولة", 2024),
    ("SHOW", "الحشاشين", 2024)
]
class Parser(HTMLParser):
    def __init__(self):
        super().__init__();self.anchors=[];self.curr=None
    def handle_starttag(self,tag,attrs):
        if tag=="a":
            self.curr={"attrs":dict(attrs),"text":""}
    def handle_data(self,data):
        if self.curr is not None:self.curr["text"]+=data
    def handle_endtag(self,tag):
        if tag=="a" and self.curr is not None:
            self.anchors.append(self.curr);self.curr=None

def retrieve(url, ref=None, range_header=None, limit=450000):
    heads={"User-Agent":UA,"Accept-Language":"ar,en-US;q=0.8,en;q=0.7"}
    if ref:heads["Referer"]=ref
    if range_header:heads["Range"]=range_header
    try:
        with urlopen(Request(quote(url,safe=":/?&=%#@;,+"),headers=heads), timeout=12) as resp:
            return resp.url,resp.status,resp.headers,resp.read(limit)
    except HTTPError as e:
        return url,e.code,e.headers,e.read(2048)
    except (URLError,TimeoutError,OSError) as e:
        return url,0,{},repr(e).encode()[:300]

def anchors(raw,base):
    p=Parser();p.feed(raw.decode("utf-8","replace"))
    results=[]
    for a in p.anchors:
        rawhref=a["attrs"].get("href","")
        href=urljoin(base,html.unescape(rawhref))
        title=html.unescape(a["attrs"].get("title") or a["text"]).strip()
        results.append((href,title))
    return results

def page_h1(body):
    m=re.search(r"<h1[^>]*>(.*?)</h1>",body.decode("utf-8","replace"),re.S|re.I)
    return html.unescape(re.sub("<[^>]+>","",m.group(1))).strip()[:100] if m else ""

def direct_probe(url, referer):
    target=url.replace("https://","http://",1) if urlparse(url).hostname and urlparse(url).hostname.endswith(".downet.net") else url
    final,status,headers,chunk=retrieve(target,referer,"bytes=0-63",64)
    signature=len(chunk)>8 and chunk[4:8] in (b"ftyp",b"styp")
    return f"status={status} host={urlparse(final).hostname} mp4={signature} range={headers.get('Content-Range','')[:28]}"

def inspect(kind,query,year):
    searchurl=BASE+"/search?q="+quote(query)
    landing,status,_,body=retrieve(searchurl)
    matches=[]
    seen=set()
    for url,name in anchors(body,landing):
        path=urlparse(url).path
        correct_type=(kind=="MOVIE" and "/movie/" in path) or (kind=="SHOW" and "/series/" in path)
        if not correct_type or url in seen:continue
        seen.add(url);matches.append((url,name))
    print(f"CASE {kind} {query!r} ({year}) SEARCH status={status} count={len(matches)}",flush=True)
    for url,title in matches[:6]:
        print(f"    RESULT title={title[:100]!r} path={urlparse(url).path[:100]}",flush=True)

    # Find a linked item that matches the base words and optionally the year.
    base_words=[w for w in re.sub(r"^\s*(فيلم|مسلسل)\s+","",query).split() if len(w)>2]
    ranked=sorted(matches,key=lambda x:(
        all(w in x[1] for w in base_words),
        str(year) in x[1]),reverse=True)
    if not ranked:return
    url,_=ranked[0]
    landing,status,_,body=retrieve(url)
    title=page_h1(body)
    eps=[(e,t) for e,t in anchors(body,landing) if "/episode/" in urlparse(e).path]
    print(f"    LOAD status={status} title={title!r} episodes={len(eps)}",flush=True)

    if kind=="SHOW":
        if not eps:return
        episode=next((e for e,t in eps if re.search(r"(?:الحلقه|الحلقة|حلقة)[ -]*1(?:$|\D)",t) or re.search(r"/الحلقة-1(?:$|/)",e)),eps[0][0])
        landing,status,_,body=retrieve(episode)
        print(f"    EPISODE status={status} h1={page_h1(body)!r}",flush=True)

    # Film or episode page -> download page -> downet direct media.
    downloads=[(u,t) for u,t in anchors(body,landing) if "/download/" in urlparse(u).path]
    if not downloads:
        print("    CDN no download page",flush=True);return
    dlurl=downloads[0][0]
    final,status,_,dlbody=retrieve(dlurl,landing)
    direct=[(u,t) for u,t in anchors(dlbody,final) if (urlparse(u).hostname or "").endswith(".downet.net")]
    print(f"    DOWNLOAD status={status} direct_targets={len(direct)}",flush=True)
    if direct:
        print("    MEDIA "+direct_probe(direct[0][0],final),flush=True)

for case in CASES:
    try:inspect(*case)
    except Exception as e:print("ERROR",case[1],type(e).__name__,str(e)[:150],flush=True)
