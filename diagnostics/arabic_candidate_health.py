#!/usr/bin/env python3
"""Site-level health audit for candidate independent Arabic content providers.
Only fetches homepage/search metadata, no video or copyrighted media bytes.
"""
import re,html
from concurrent.futures import ThreadPoolExecutor,as_completed
from urllib.parse import quote,urlparse
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError

SITES=[
    ("FaselHD","https://web31312x.faselhdx.bid","/main"),
    ("MyCima","https://mycima.living","/"),
    ("Shahid4u","https://shahed4u.im","/"),
    ("Shahidwbas","https://w30.shahidwbas.tv","/"),
    ("TopCinema","https://web8.topcinema.cam","/"),
    ("TukTukcima","https://tuktukhd.com","/"),
    ("CimaLight","https://r.cimalight.co","/"),
    ("EgyDead","https://tv10.egydead.live","/"),
]
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

def check(info):
    name,base,path=info
    req=Request(base+path,headers={"User-Agent":UA,"Accept-Language":"ar,en;q=0.7"})
    try:
        with urlopen(req,timeout=13) as r:
            status=r.status
            url=r.url
            body=r.read(250000).decode("utf-8","replace")
    except HTTPError as e:
        status=e.code;url=base+path;body=e.read(10000).decode("utf-8","replace")
    except (URLError,TimeoutError,OSError) as e:
        return (name,"error",base,"",0,0,type(e).__name__+":"+str(getattr(e,"reason",e))[:60])

    titleMatch=re.search(r"<title[^>]*>(.*?)</title>",body,re.I|re.S)
    title=html.unescape(re.sub(r"<[^>]*>"," ",titleMatch.group(1))).strip()[:66] if titleMatch else ""
    challenge="cf-challenge" in body.lower() or "just a moment" in body.lower() or "challenge-platform" in body.lower()
    count=len(re.findall(r"(?:movie-item|post-item|series-item|GridItem|Small--Box|BlocksHolder)",body,re.I))
    return (name,status,urlparse(url).hostname,title,len(body),count, "cloudflare" if challenge else "")

with ThreadPoolExecutor(max_workers=6) as executor:
    for result in as_completed([executor.submit(check,s) for s in SITES]):
        print("SOURCE_HEALTH",*result.result(),sep=" | ",flush=True)
