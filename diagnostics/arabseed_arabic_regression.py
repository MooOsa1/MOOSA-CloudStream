#!/usr/bin/env python3
"""Focused live ArabSeed Arabic search/episode audit; no media download.
Checks title labels, per-episode page structure and watch metadata.
"""
import re,html,json
from bs4 import BeautifulSoup
from urllib.parse import quote,urlparse,urljoin
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
from http.cookiejar import CookieJar
from urllib.request import build_opener,HTTPCookieProcessor
UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
base="https://m.myseed.tv"
client=build_opener(HTTPCookieProcessor(CookieJar()))
def fetch(url,ref=None):
    h={"User-Agent":UA,"Accept-Language":"ar,en-US;q=0.8,en;q=0.6"}
    if ref:h["Referer"]=ref
    try:
        with client.open(Request(url,headers=h),timeout=13) as r:
            return r.status,r.url,r.read(550000).decode("utf-8","replace")
    except HTTPError as e:
        return e.code,url,e.read(3000).decode("utf-8","replace")
    except (URLError,OSError) as e:
        return 0,url,str(e)
def norm(s):
    return re.sub(r"[\u064b-\u065f]", "",s).replace("أ","ا").replace("إ","ا").replace("آ","ا").replace("ة","ه").strip()
cases=[("MOVIE","بيت الروبي",None,None),("MOVIE","الفيل الأزرق 2",None,None),("SERIES","جعفر العمدة",1,1),("SERIES","اللعبة",5,1),("SERIES","العتاولة",1,1)]
for typ,title,season,episode in cases:
    print("CASE",typ,title,season,episode,flush=True)
    found=[]
    for page in (1,2,3,4):
        url=base+"/?s="+quote(title) if page==1 else base+f"/page/{page}/?s="+quote(title)
        status,final,body=fetch(url)
        soup=BeautifulSoup(body,"html.parser")
        if page==1:
            pageTitle=soup.title.get_text(" ",strip=True)[:95] if soup.title else ""
            cardTitles=[a.get("title","")[:75] for a in soup.select("a[title][href]")[:12]]
            print(" DOCUMENT", "url_host",urlparse(final).hostname,"body_len",len(body),
                  "title",pageTitle,"a_with_title",len(soup.select("a[title][href]")),
                  "a_all",len(soup.select("a[href]")),
                  "sample_titles",cardTitles,flush=True)
            print(" SEARCH_CARD_SELECTORS",{
                ".movie__block":len(soup.select(".movie__block")),
                ".content-box":len(soup.select(".content-box")),
                ".as-card":len(soup.select(".as-card")),
                "article":len(soup.select("article"))
            },flush=True)
        links=[]
        for a in soup.select("a[title][href]"):
            label=a.get("title","").strip()
            href=urljoin(final,a.get("href",""))
            if not label or title.split()[0] not in label: continue
            if href not in [x[0] for x in links]: links.append((href,label))
        print(" PAGE",page,"status",status,"count",len(links),"first_titles",[x[1][:75] for x in links[:5]],flush=True)
        found += links
        if not links:break
        if len(found)>=30:break
    # Analyze up to two distinct candidate pages rather than guessing episode.
    for u,label in found[:2]:
        st,final,body=fetch(u);doc=BeautifulSoup(body,"html.parser")
        meta=doc.select_one("meta[property='og:type']")
        head=doc.select_one(".as-hero-title,h1,.entry-title")
        episodes=doc.select(".episodes__list a[href],.episodes-list a[href],a[href*='الحلقة'],a[href*='episode']")
        watched=doc.select_one("a.watch__btn[href]")
        print(" ITEM",label[:95],"status",st,"path",urlparse(final).path[:130],
              "h1",head.get_text(" ",strip=True)[:95] if head else "",
              "og",meta.get("content") if meta else "",
              "episode_links",len(episodes),
              "watch_button",bool(watched),flush=True)
        if watched:
            watch=urljoin(final,watched.get("href",""))
            st2,final2,body2=fetch(watch,final);wd=BeautifulSoup(body2,"html.parser")
            print(" WATCH","status",st2,"post_id",len(wd.select("[data-post],[data-post-id]")),
                  "qualities",[q.get("data-quality") for q in wd.select("[data-quality]")[:6]],
                  "servers",len(wd.select("li[data-server],li[data-src],iframe[src]")),
                  "csrf",bool(re.search("csrf__token|csrf_token",body2)),
                  "path",urlparse(final2).path[:100],flush=True)
