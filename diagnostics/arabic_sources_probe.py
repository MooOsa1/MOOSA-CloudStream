#!/usr/bin/env python3
"""Check current Arabic source HTTP endpoints, search parsers and listing structure.
No media is downloaded; only HTML/search metadata is inspected.
"""
import base64
import html
import json
import re
import ssl
from collections import Counter
from bs4 import BeautifulSoup
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from urllib.parse import urlencode, quote, urlparse, urljoin
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener

UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

class Anchors(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[]
        self.active=None
    def handle_starttag(self,tag,attrs):
        if tag=="a":
            props=dict(attrs)
            self.active={"href": props.get("href",""),"title":props.get("title",""),"text":""}
    def handle_data(self,data):
        if self.active is not None:
            self.active["text"]+=data
    def handle_endtag(self,tag):
        if tag=="a" and self.active is not None:
            self.links.append(self.active)
            self.active=None

def client():
    return build_opener(HTTPCookieProcessor(CookieJar()))

def fetch(opener,url,post=None,referer=None):
    heads={"User-Agent":UA,"Accept-Language":"ar,en;q=0.7","Accept":"text/html,application/json,*/*"}
    if referer:heads["Referer"]=referer
    if post is not None:
        heads["Content-Type"]="application/x-www-form-urlencoded; charset=UTF-8"
        heads["X-Requested-With"]="XMLHttpRequest"
    req=Request(url, headers=heads, data=urlencode(post).encode() if post is not None else None)
    try:
        with opener.open(req,timeout=16) as resp:
            body=resp.read(800_000).decode("utf-8","replace")
            return {"status":resp.status,"url":resp.url,"type":resp.headers.get("Content-Type",""),"body":body}
    except HTTPError as e:
        return {"status":e.code,"url":url,"type":e.headers.get("Content-Type",""),"body":e.read(8000).decode("utf-8","replace"),"error":"http"}
    except (URLError,TimeoutError, OSError) as e:
        return {"status":0,"url":url,"type":"","body":"","error":type(e).__name__+":"+str(getattr(e,"reason",e))[:90]}

def show(provider,step,r):
    body=r["body"];status=r["status"]
    title=re.search(r"<title[^>]*>(.*?)</title>",body,re.I|re.S)
    flags=[n for n,flag in [
        ("captcha","captcha" in body.lower()),("cloudflare","challenge-platform" in body or "Just a moment" in body),
        ("login","<form" in body and "password" in body.lower())
    ] if flag]
    print(f"{provider} {step}: code={status} host={urlparse(r['url']).hostname} bytes={len(body)} type={r['type'][:45]!r} title={html.unescape(re.sub('<[^>]+>',' ',title.group(1))).strip()[:75] if title else ''!r} flags={flags} error={r.get('error','')}",flush=True)

def links(result,selector):
    p=Anchors();p.feed(result["body"])
    found=[]
    for a in p.links:
        u=urljoin(result["url"],html.unescape(a["href"]))
        if selector(u,a):
            found.append((u,html.unescape(a["title"] or a["text"]).strip()))
    return list(dict((u,(u,t)) for u,t in found).values())

def page_structure(name,body,base):
    soup=BeautifulSoup(body,"html.parser")
    print(name,"forms:",[
        {
            "action":f.get("action","")[:80],
            "method":f.get("method","GET"),
            "fields":[i.get("name","") for i in f.select("input")][:12]
        } for f in soup.select("form")[:4]
    ],flush=True)
    allclasses=Counter(c for e in soup.select("[class]") for c in e.get("class",[]))
    print(name,"classes:",allclasses.most_common(18),flush=True)
    candidates=[]
    for a in soup.select("a[href]"):
        label=a.get("title") or a.get_text(" ",strip=True)
        href=urljoin(base,a.get("href",""))
        if "لعب" in label or "لعب" in href:
            candidates.append((urlparse(href).path[:85],label[:85]))
    print(name,"game-related:",len(candidates),"samples:",candidates[:8],flush=True)
    print(name,"search-like attributes:",[
        (e.name,e.get("id"),e.get("name"),e.get("action"),e.get("placeholder"))
        for e in soup.select("[id*='search'],[class*='search'],input[placeholder]")[:14]
    ],flush=True)
    return soup

def arabseed():
    name="ARABSEED";o=client();base="https://m.myseed.pics"
    main=fetch(o,base+"/main/");show(name,"main",main)
    htmltext=main["body"]
    page_structure(name,htmltext,main["url"])
    for pattern in ["find__posts","csrf__token","/search/","?s=","search?q","search_type","ajax","search-form"]:
        print(name,"contains",pattern,pattern.lower() in htmltext.lower())
    for query_url in [
        base+"/?s="+quote("اللعبة"),
        "https://m.myseed.tv/?s="+quote("اللعبة"),
        "https://m.myseed.tv/search/?q="+quote("اللعبة")
    ]:
        alt=fetch(o,query_url)
        show(name,"GET search "+urlparse(query_url).path+"?"+urlparse(query_url).query[:15],alt)
        page_structure(name+" alternative",alt["body"],alt["url"])
    csrf_patterns=[
        r"""["']?csrf__token["']?\s*[:=]\s*["']([^"']{6,})["']""",
        r"""var\s+csrf_token\s*=\s*["']([^"']{6,})["']"""
    ]
    csrf=next((m.group(1) for p in csrf_patterns if (m:=re.search(p,htmltext))),None)
    print(name,"csrf:",bool(csrf),"quality elements:",len(re.findall(r"data-quality",htmltext)))
    if csrf:
        for query in ["اللعبة","اللعبة الموسم الخامس"]:
            r=fetch(o,base+"/find__posts/",{"search":query,"search_type":"","csrf_token":csrf},referer=base+"/")
            show(name,f"search {query!r}",r)
            try:
                obj=json.loads(r["body"]);inner=str(obj.get("html",""))
                els=Anchors();els.feed(inner)
                matches=[(a["href"][:110],a["title"][:70]) for a in els.links if a["href"] and "اللعب" in (a["title"]+a["text"]+a["href"])]
                print(name,"response_keys:",list(obj)[:10],"matches:",len(matches),"sample:",matches[:4])
            except (json.JSONDecodeError,ValueError) as e:
                print(name,"search JSON invalid:",type(e).__name__)

def arabseed_episode_deep_probe():
    o=client()
    base="https://m.myseed.tv"
    for query in ["اللعبة الموسم الخامس الحلقة 1","اللعبة الموسم الخامس","اللعبة", "اللعبة الموسم الثاني"]:
        u=base+"/?s="+quote(query)
        r=fetch(o,u);show("ARABSEED-DEEP",f"search {query!r}",r)
        soup=BeautifulSoup(r["body"],"html.parser")
        cards=soup.select(".movie__block a[href],a[title][href]")
        hits=[]
        for a in cards:
            href=urljoin(r["url"],a.get("href",""))
            title=a.get("title") or a.get_text(" ",strip=True)
            if "لعب" in title or "لعب" in href:
                hits.append((href,title))
        hits=list(dict((u,(u,t)) for u,t in hits).values())
        print("ARABSEED-DEEP",query,"matches",len(hits),"sample",[(urlparse(u).path[:115],t[:65]) for u,t in hits[:6]],flush=True)
        pager=[(a.get_text(" ",strip=True),urljoin(r["url"],a["href"])) for a in soup.select("a[href]") if "page/" in a["href"] or "paged=" in a["href"]]
        print("ARABSEED-DEEP pager:",[(t,urlparse(u).path[:70],urlparse(u).query[:25]) for t,u in pager[:4]],flush=True)
        if query != "اللعبة":continue
        if not hits:continue
        href,title=hits[0]
        targets=[href]
        if re.search(r"eps\d+",href,re.I):
            targets.append(re.sub(r"eps\d+","eps1",href,flags=re.I))
        for episode_url in targets:
            ep=fetch(o,episode_url);show("ARABSEED-DEEP","episode",ep)
            doc=BeautifulSoup(ep["body"],"html.parser")
            h1=doc.select_one("h1")
            print("ARABSEED-DEEP episodeTitle",(h1.get_text(" ",strip=True) if h1 else "missing")[:105])
            for selector in ["a.watch__btn[href]","a[href*='download']",".episodes__list a[href]","[data-post-id]","li[data-server]","li[data-src]","li[data-quality]","[data-link]"]:
                nodes=doc.select(selector)
                print("ARABSEED-DEEP",selector,len(nodes),[(n.get_text(" ",strip=True)[:36],list(n.attrs.keys())) for n in nodes[:3]])
            download_buttons=[(a.get_text(" ",strip=True)[:50],urljoin(ep["url"],a.get("href",""))) for a in doc.select("a[href*='download']")]
            print("ARABSEED-DEEP download candidates",[(label,urlparse(u).path[:100]) for label,u in download_buttons[:6]])
            for label,u in download_buttons[:2]:
                dl=fetch(o,u,referer=ep["url"]);show("ARABSEED-DEEP","download "+label,dl)
                dd=BeautifulSoup(dl["body"],"html.parser")
                print("ARABSEED-DEEP DOWNLOAD anchors",[(a.get_text(" ",strip=True)[:45],urlparse(urljoin(dl["url"],a.get("href",""))).hostname)
                     for a in dd.select("a[href]") if re.search("1080|720|480|server|download|تحميل",a.get_text(" ",strip=True),re.I)][:12])
            watch=doc.select_one("a.watch__btn[href]")
            watch_urls=[urljoin(ep["url"],watch.get("href",""))] if watch else [episode_url.rstrip("/")+"/watch/"]
            for watchurl in watch_urls[:1]:
                wr=fetch(o,watchurl,referer=ep["url"]);show("ARABSEED-DEEP","watch",wr)
                wdoc=BeautifulSoup(wr["body"],"html.parser")
                for selector in ["[data-quality]","[data-post]","[data-post-id]","li[data-server]","li[data-src]","[data-link]","iframe[src]","video[src]","source[src]"]:
                    nodes=wdoc.select(selector)
                    sample=[]
                    for n in nodes[:3]:
                        sample.append({key:value[:45] for key,value in n.attrs.items()
                                       if key in ("data-quality","data-qu","data-server","data-post","data-post-id","class") and isinstance(value,str)})
                    print("ARABSEED-DEEP WATCH",selector,len(nodes),"sample",sample)
                for pattern in ["get__quality__servers","get__watch__server","csrf__token","m3u8","mp4","wp-admin/admin-ajax.php"]:
                    print("ARABSEED-DEEP WATCH has",pattern, pattern.lower() in wr["body"].lower())
                for sel in ["li[data-server]","li[data-quality]","iframe[src]","a[href*='download']"]:
                    matches=wdoc.select(sel)
                    print("ARABSEED-DEEP attributes",sel,[
                        {k:(urlparse(v).hostname if "link" in k or k=="src" else v[:75]) for k,v in n.attrs.items() if isinstance(v,str) and k.startswith("data-") or k=="src"}
                        for n in matches[:7]
                    ])
                scripts=[n.get("src","") for n in wdoc.select("script[src]")]
                print("ARABSEED-DEEP scripts count",len(scripts),"sample",scripts[-15:])
                post_el=wdoc.select_one("li[data-post][data-server]")
                if post_el:
                    post=post_el.get("data-post","")
                    for endpoint in ["/get__quality__servers/","/get__watch__server/"]:
                        for quality in ([1080,720] if "quality" in endpoint else [480]):
                            data={"post_id":post,"quality":str(quality)}
                            if "watch" in endpoint: data["server"]="0"
                            aj=fetch(o,base+endpoint,data,referer=watchurl)
                            show("ARABSEED-DEEP",f"ajax {endpoint} q{quality}",aj)
                            print("ARABSEED-DEEP ajax-json",aj["body"][:200].replace("\n"," "))


    for page in range(2,9):
        r=fetch(o,base+"/page/"+str(page)+"/?s="+quote("اللعبة"))
        doc=BeautifulSoup(r["body"],"html.parser")
        titles=[a.get("title","") for a in doc.select("a[title][href]")
                if "لعب" in a.get("title","")]
        print("ARABSEED-DEEP PAGE",page,"status",r["status"],"hits",len(titles),
              "first",titles[:2],"last",titles[-2:],
              "S5E1",any("الموسم الخامس الحلقة 1 " in t for t in titles),flush=True)
    for suffix in [
        "/find/?word="+quote("اللعبة")+"&type=",
        "/?s="+quote("اللعبة")+"&paged=2",
        "/page/2/?s="+quote("اللعبة"),
        "/?s="+quote("اللعبة")+"&page=2"
    ]:
        r=fetch(o,base+suffix)
        doc=BeautifulSoup(r["body"],"html.parser")
        hits=[a.get("title") for a in doc.select("a[title][href]")
              if "لعب" in (a.get("title") or "")]
        show("ARABSEED-DEEP","pagination/alt-search "+urlparse(suffix).path,r)
        print("ARABSEED-DEEP alternate hits count",len(hits),"first",hits[:2],"last",hits[-2:])

def wecima():
    name="WECIMA";o=client();base="https://wecima.ac"
    main=fetch(o,base);show(name,"main",main)
    actual=main["url"].rstrip("/")
    for query in ["اللعبة","اللعبة الموسم الخامس"]:
        r=fetch(o,actual+"/search",{"q":query},referer=actual+"/")
        show(name,f"search {query!r}",r)
        try:
            data=json.loads(r["body"])
            results=data.get("results",[]) if isinstance(data,dict) else []
            sample=[{k:record.get(k) for k in ("title","slug","istv")} for record in results[:4] if isinstance(record,dict)]
            print(name,"result_count:",len(results),"sample:",sample)
        except (json.JSONDecodeError,ValueError) as e:
            print(name,"search JSON invalid:",type(e).__name__)
    # One regular listing as selector baseline; no streaming involved.
    r=fetch(o,actual+"/category/arabic-series");show(name,"arabic-series",r)
    matches=links(r,lambda u,a:"/series/" in urlparse(u).path)
    print(name,"series_links:",len(matches),"sample:",[(urlparse(u).path[:80],t[:45]) for u,t in matches[:3]])

def cimaclub():
    name="CIMACLUB";o=client();base="https://cimacub.com"
    main=fetch(o,base);show(name,"main",main)
    for query in ["اللعبة","اللعبة الموسم الثاني","Oppenheimer","الحشاشين"]:
        url=base+"/?s="+quote(query)
        r=fetch(o,url);show(name,f"search {query!r}",r)
        soup=page_structure(name,r["body"],r["url"])
        print(name,"CARD STRUCTURE",{
            "BlocksHolder":len(soup.select(".BlocksHolder")),
            "Small--Box":len(soup.select(".Small--Box")),
            "posts":len(soup.select(".post,.Post,.post-item,.PostItem")),
            "article":len(soup.select("article"))
        })
        print(name,"PAGE SEARCH BODY MARKERS",
              [x for x in ["no-results","لا توجد","لم يتم العثور","search-results","نتائج البحث"] if x in r["body"]])
        matches=links(r,lambda u,a:"/series/" in urlparse(u).path or "مسلسل" in a["title"] or "اللعبة" in a["title"])
        print(name,"relevant_links:",len(matches),"sample:",[(urlparse(u).path[:85],t[:60]) for u,t in matches[:4]])

for method in (arabseed,arabseed_episode_deep_probe,wecima,cimaclub):
    print("---",method.__name__,"---",flush=True)
    try:method()
    except Exception as exc:
        print("PROBE ERROR",method.__name__,type(exc).__name__,str(exc)[:200],flush=True)
