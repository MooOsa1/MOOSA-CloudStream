#!/usr/bin/env python3
"""Inspect first page and episode player structure of independently verified ArabSeed host.
No media is downloaded and no signed stream URLs are printed.
"""
import re,json,html
from bs4 import BeautifulSoup
from urllib.parse import quote,urljoin,urlparse
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
BASE="https://arabseeds.watch"
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
def req(url,referer=None):
    head={"User-Agent":UA,"Accept-Language":"ar,en;q=0.8"}
    if referer:head["Referer"]=referer
    try:
        with urlopen(Request(quote(url,safe=":/?&=%#@;,+"),headers=head),timeout=12) as r:
            return (r.status,r.url,r.read(500000).decode("utf8","replace"))
    except HTTPError as e:return (e.code,url,e.read(5000).decode("utf8","replace"))
    except (URLError,OSError) as e:return (0,url,str(e))
def links(soup,base):
    found=[]
    for a in soup.select("a[href]"):
        title=(a.get("title") or a.get_text(" ",strip=True)).strip()
        href=urljoin(base,html.unescape(a.get("href","")))
        if title and href not in [x[0] for x in found]:found.append((href,title,a))
    return found
for q in ["اللعبة","الفيل الأزرق","الكينج"]:
    status,url,body=req(BASE+"/?s="+quote(q))
    soup=BeautifulSoup(body,"html.parser")
    candidates=[(u,t,a) for u,t,a in links(soup,url) if q.split()[0] in t and u.startswith(BASE) and u !=url]
    print("SEARCH",q,"status",status,"html_len",len(body),"candidates",len(candidates),"first_titles",[t[:92] for _,t,_ in candidates[:7]],flush=True)
    if q!="اللعبة" or not candidates:continue
    chosen=next(((u,t) for u,t,a in candidates if "الحلقة 1" in t and "ج5" in t),None)
    if not chosen:chosen=(candidates[0][0],candidates[0][1])
    for item_url, item_title in [chosen]:
        st,final,pbody=req(item_url)
        doc=BeautifulSoup(pbody,"html.parser")
        print("EPISODE","title",item_title[:95],"status",st,
              "host",urlparse(final).hostname,"body_len",len(pbody),
              "h1",[x.get_text(" ",strip=True)[:100] for x in doc.select("h1")[:3]],
              "iframe",len(doc.select("iframe")),"forms",len(doc.select("form")),
              "script_count",len(doc.select("script")),
              "server_words",sum(word in pbody.lower() for word in ("سيرفر","server","iframe","m3u8","mp4")),
              flush=True)
        for sel in ["iframe[src]","[data-server]","[data-src]","[data-embed]","[data-url]","[data-link]","video","source",".servers-list",".servers",".server",".WatchServers","a[href*='watch']","a[href*='view']","a[href*='play']"]:
            elems=doc.select(sel)
            print("SELECTOR",sel,"n",len(elems),"samples",[
                {k:(urlparse(v).hostname if k in ("src","href") else v[:65])
                for k,v in e.attrs.items() if k in ("src","href","data-server","data-src","data-embed","data-url","data-link","class") and isinstance(v,str)}
                for e in elems[:3]],flush=True)
        for t in ("Server", "السيرفرات", "سيرفرات المشاهدة","1080","720","embed","iframe","watch", "download"):
            places=[m.start() for m in list(re.finditer(re.escape(t),pbody,re.I))[:3]]
            print("MARKER",t,len(places),[
                re.sub(r"<[^>]+>"," ",pbody[max(0,i-90):i+170]).replace("\n"," ")[:180]
                for i in places[:2]],flush=True)
        print("EXTERNAL_IFRAMES",[urlparse(urljoin(final,e.get("src",""))).hostname for e in doc.select("iframe[src]")[:12]],flush=True)
        print("INTERNAL_LINKS",[(urlparse(u).path[:110],title[:75]) for u,title,a in links(doc,final) if title and any(w in title.lower() for w in ["مشاهدة","شاهد","server","سيرفر","الحلقة"])][:17],flush=True)

        # Inspect watch/embed subpages separately. Landing is NOT the player.
        embed=re.search(r'["\x27]embedUrl["\x27]\s*:\s*["\x27]([^"\x27]+)',pbody,re.I)
        candidate_urls=[]
        if embed:candidate_urls.append(("JSONLD_EMBED",embed.group(1).replace("\\/","/")))
        for link,title,anchor in links(doc,final):
            if any(hint in title for hint in ("الذهاب لصفحة المشاهدة","شاهد الآن","مشاهدة الآن")):
                candidate_urls.append(("WATCH_LINK",link))
        if not candidate_urls:
            candidate_urls.append(("WATCH_DEFAULT",item_url.rstrip("/")+"/watch/"))
        for kind,url in list(dict((u,(kind,u)) for kind,u in candidate_urls).values())[:3]:
            code,landing,htmlbody=req(url,referer=final)
            panel=BeautifulSoup(htmlbody,"html.parser")
            frames=[urlparse(urljoin(landing,e.get("src",""))).hostname for e in panel.select("iframe[src]")]
            print("SUBPAGE",kind,"status",code,"hostname",urlparse(landing).hostname,
                  "path",urlparse(landing).path[:95],"html_len",len(htmlbody),
                  "frames",frames[:8],"server_count",len(panel.select("[data-server],[data-url],[data-src],li.server")),
                  "players",len(panel.select("video,source")),
                  "quality_mentions",[str(q) for q in [2160,1080,720] if str(q) in htmlbody],
                  "scripts",len(panel.select("script[src]")),
                  "title",panel.title.get_text(" ",strip=True)[:75] if panel.title else "",flush=True)
            print("SUBPAGE_ELEMENTS",kind,[
                (e.name,e.get("class"),str(e.get("data-server") or e.get("data-url") or e.get("data-src") or "")[:45])
                for e in panel.select("[data-server],[data-url],[data-src]")[:8]
            ],flush=True)
            print("SUBPAGE_LINKS",kind,[
                (urlparse(l).hostname,urlparse(l).path[:75],name[:45])
                for l,name,a in links(panel,landing) if "سيرفر" in name or "1080" in name or "720" in name
            ][:8],flush=True)


            print("SUBPAGE_SERVERS",kind,[
                {"attrs":{k:(urlparse(v).hostname if k in ("data-url","data-src","data-link") else v[:50])
                    for k,v in li.attrs.items() if isinstance(v,str) and k in ("data-url","data-src","data-link","class")},
                 "label":li.get_text(" ",strip=True)[:65]}
                for li in panel.select(".server-item,li[data-server]")[:7]
            ],flush=True)
            if kind in ("JSONLD_EMBED","WATCH_LINK"):
                for iframe in panel.select("iframe[src]")[:2]:
                    inner=urljoin(landing,iframe.get("src",""))
                    if not (urlparse(inner).hostname or "").endswith("upbom.cam"): continue
                    c,player,htmlplayer=req(inner,referer=landing)
                    ds=BeautifulSoup(htmlplayer,"html.parser")
                    print("VIDEO_HOST",kind,"status",c,
                          "host",urlparse(player).hostname,"bytes",len(htmlplayer),
                          "title",ds.title.get_text(" ",strip=True)[:60] if ds.title else "",
                          "iframes",[urlparse(x.get("src","")).hostname for x in ds.select("iframe[src]")[:6]],
                          "tags",len(ds.select("video,source")),
                          "has_mp4",bool(re.search(r"\.mp4",htmlplayer,re.I)),
                          "has_m3u8",bool(re.search(r"\.m3u8",htmlplayer,re.I)),
                          "has_jwplayer","jwplayer" in htmlplayer.lower(),
                          "has_packed","eval(function(p,a,c,k,e" in htmlplayer,
                          "scripts",[urlparse(x.get("src","")).hostname for x in ds.select("script[src]")[:8]],
                          flush=True)

                    for source in ds.select("video[src],source[src]")[:3]:
                        raw=source.get("src","")
                        media=urljoin(player,raw)
                        hs={"User-Agent":UA,"Referer":player,"Range":"bytes=0-63"}
                        try:
                            with urlopen(Request(media,headers=hs),timeout=10) as response:
                                prefix=response.read(64)
                                print("MEDIA_RANGED","host",urlparse(response.url).hostname,
                                      "status",response.status,"type",response.headers.get("Content-Type","")[:60],
                                      "range",response.headers.get("Content-Range","")[:40],
                                      "mp4_signature",len(prefix)>=8 and prefix[4:8]==b"ftyp",
                                      "hls_signature",prefix.startswith(b"#EXTM3U"),
                                      "declared_type",source.get("type",""),
                                      "data_quality",source.get("data-quality",""),
                                      flush=True)
                        except (HTTPError,URLError,OSError) as error:
                            print("MEDIA_RANGED","error",type(error).__name__,
                                  "host",urlparse(media).hostname,flush=True)
