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
base="https://www.topcinema.vip"
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
        if tag=="EPISODE":
            for a in soup.select(".allepcont a[href],.EpisodesList a[href]")[:5]:
                print("EPISODE_ENTRY","text",a.get_text(" ",strip=True)[:80],
                      "path",urlparse(urljoin(landing,a.get("href",""))).path[:125],flush=True)
        if tag=="WATCH":
            tested=set()
            for iframe in soup.select("ul#watch iframe[src], .WatchIframe iframe[src]"):
                player=urljoin(landing,iframe.get("src",""))
                host=urlparse(player).hostname
                if not host or host in tested:continue
                tested.add(host)
                if len(tested)>5:break
                code,redir,phtml=request(player,ref=landing)
                doc2=BeautifulSoup(phtml,"html.parser")
                print("HOSTER",host,"status",code,"final",urlparse(redir).hostname,
                      "bytes",len(phtml),"title",doc2.title.get_text(" ",strip=True)[:70] if doc2.title else "",
                      "has_mp4",".mp4" in phtml.lower(),
                      "has_m3u8",".m3u8" in phtml.lower(),
                      "has_1080","1080" in phtml,"has_720","720" in phtml,
                      "video_elems",len(doc2.select("video,source")),
                      "script_elems",len(doc2.select("script")),
                      "captcha",any(x in phtml.lower() for x in ("just a moment", "captcha","cloudflare challenge")),
                      flush=True)


                if host=="vidara.to":
                    code=urlparse(player).path.rstrip("/").rsplit("/",1)[-1]
                    body=json.dumps({"filecode":code,"device":"web"}).encode()
                    headers={"User-Agent":UA,"Content-Type":"application/json"}
                    try:
                        with urlopen(Request("https://vidara.to/api/stream",headers=headers,data=body),timeout=12) as api:
                            rawinfo=api.read(150000).decode("utf8","replace")
                            info=json.loads(rawinfo)
                            playlist=info.get("streaming_url","")
                            print("VIDARA_API",api.status,"title",str(info.get("title",""))[:100],
                                  "playlist",bool(playlist),"host",urlparse(playlist).hostname,flush=True)
                            if playlist:
                                ps,purl,manifest=request(playlist,ref=player)
                                qualities=re.findall(r"RESOLUTION=\d+x(\d+)",manifest,re.I)
                                print("VIDARA_PLAYLIST","status",ps,"host",urlparse(purl).hostname,
                                      "size",len(manifest),"hls",manifest.lstrip().startswith("#EXTM3U"),
                                      "resolutions",qualities[:18],"segments",manifest.count("#EXTINF:"),
                                      flush=True)

                                lines=manifest.splitlines()
                                variants=[]
                                for idx,line in enumerate(lines):
                                    match=re.search(r"RESOLUTION=\d+x(\d+)",line,re.I)
                                    if not match:continue
                                    later=next((l.strip() for l in lines[idx+1:] if l.strip() and not l.startswith("#")),None)
                                    if later:variants.append((int(match.group(1)),urljoin(purl,later)))
                                for height,url2 in variants:
                                    if height not in (1080,720):continue
                                    vstatus,vland,vbody=request(url2,ref=purl)
                                    vlines=vbody.splitlines()
                                    fragments=[q.strip() for q in vlines if q.strip() and not q.startswith("#")]
                                    print("VIDARA_VARIANT",height,"status",vstatus,
                                          "host",urlparse(vland).hostname,
                                          "has_extm3u",vbody.lstrip().startswith("#EXTM3U"),
                                          "fragments",len(fragments),flush=True)
                                    if not fragments:continue
                                    segment=urljoin(vland,fragments[0])
                                    try:
                                        with urlopen(Request(segment,headers={"User-Agent":UA,"Range":"bytes=0-63","Referer":vland}),timeout=10) as seg:
                                            first=seg.read(64)
                                            print("VIDARA_SEGMENT",height,"status",seg.status,
                                                  "bytes",len(first),
                                                  "mime",seg.headers.get("Content-Type","")[:50],
                                                  "host",urlparse(seg.url).hostname,flush=True)
                                    except (HTTPError,URLError,OSError) as err:
                                        print("VIDARA_SEGMENT_ERR",height,type(err).__name__,
                                              str(getattr(err,"reason",err))[:95],flush=True)

                    except (HTTPError,URLError,OSError,ValueError) as e:
                        print("VIDARA_API_FAIL",type(e).__name__,
                              "reason",str(getattr(e,"reason",e))[:110],flush=True)


for query in ["سيكو سيكو","الحريفة","ولاد رزق","الهوى سلطان","أحمد وأحمد"]:
    url=base+"/wp-json/wp/v2/search?search="+quote(query)+"&per_page=10"
    status,final,raw=request(url)
    arr=json.loads(raw) if raw.lstrip().startswith("[") else []
    print("FILM_SEARCH",query,"status",status,"count",len(arr),
          "titles",[str(x.get("title",""))[:85] for x in arr[:4]],flush=True)
