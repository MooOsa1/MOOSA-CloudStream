#!/usr/bin/env python3
"""Live regression for native Akwam seasons and catalog. No video downloads."""
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen
from bs4 import BeautifulSoup
import re
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
BASE="https://ak.sv"
def fetch(path):
    u=BASE+path
    with urlopen(Request(quote(u,safe=":/?&=%#@;,+"),headers={"User-Agent":UA}),timeout=15) as r:
        return r.url,BeautifulSoup(r.read(900000),"html.parser")
cases=[
 ("جعفر العمدة",1,"/series/4072/جعفر-العمدة-3"),
 ("اللعبة",1,"/series/32/اللعبة-23"),
 ("اللعبة ج5 : الكلاسيكو",5,"/series/5557/اللعبة-ج5-الكلاسيكو")
]
for label,season,path in cases:
    final,doc=fetch(path)
    title=doc.select_one("h1.entry-title,h1")
    assert title is not None
    title=title.get_text(" ",strip=True)
    assert label.split()[0] in title,(label,title)
    cards={}
    for a in doc.select("a[href*='/episode/']"):
        href=urljoin(final,a.get("href",""))
        if a.get_text(" ",strip=True):
            cards[href]=a.get_text(" ",strip=True)
    nums=[]
    for url,name in cards.items():
        match=re.search(r"(?:حلقة|الحلقة|الحلقه)\s*(\d{1,3})",name) or re.search(r"الحلقة-(\d+)",url)
        if match: nums.append(int(match.group(1)))
    print("SEASON",title,"requested",season,"unique_episodes",len(cards),"parsed",len(nums),"span",(min(nums),max(nums)) if nums else None,flush=True)
    assert len(cards)>=10, "Missing native series episodes"
    assert len(nums)==len(cards), "Some native episode numbers could not be parsed"
    assert len(set(nums))==len(nums), "Duplicate episode numbers"
    assert min(nums)==1, "Episode numbering does not start at 1"

for kind in ("movies","series"):
    final,doc=fetch(f"/{kind}?section=29&page=1")
    names=[a.get("title") or a.get_text(" ",strip=True) for a in doc.select("a[href*='/movie/'],a[href*='/series/']")]
    names=list(dict.fromkeys(n.strip() for n in names if n.strip() not in ("","شاهد","مشاهدة")))
    dubs=[n for n in names if re.search(r"مدبلج",n)]
    print("SHELF",kind,"items",len(names),"explicit_dubs",len(dubs),flush=True)
    assert names, "Missing Arabic category cards"

for n in (1,2):
    final,doc=fetch("/search?q="+quote("اللعبة")+"&page="+str(n))
    urls={urljoin(final,a.get("href","")) for a in doc.select("a[href*='/series/'],a[href*='/movie/']")}
    print("PAGED_SEARCH",n,"unique_url_count",len(urls),flush=True)
    assert urls, "Native paged search yielded no URLs"
print("PASS: native seasons, episode numbers, Arabic categories, paged search")
