#!/usr/bin/env python3
"""Audit public MySeed 720/1080 watch tab behavior; metadata-only."""
from urllib.parse import quote,urljoin,urlparse
from urllib.request import Request,build_opener,HTTPCookieProcessor
from urllib.error import HTTPError,URLError
from http.cookiejar import CookieJar
from bs4 import BeautifulSoup
import re,json

UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132 Safari/537.36"
http=build_opener(HTTPCookieProcessor(CookieJar()))
base="https://m.myseed.tv"

def fetch(url,ref=None,max_len=1_800_000,post=None):
    heads={"User-Agent":UA,"Accept-Language":"ar,en;q=0.8"}
    if ref:heads["Referer"]=ref
    if post is not None:
        heads["Content-Type"]="application/x-www-form-urlencoded"
        heads["X-Requested-With"]="XMLHttpRequest"
        from urllib.parse import urlencode
        data=urlencode(post).encode()
    else:data=None
    try:
        with http.open(Request(url,headers=heads,data=data),timeout=13) as resp:
            return resp.status,resp.url,resp.read(max_len).decode("utf8","replace")
    except HTTPError as e:
        return e.code,url,e.read(6000).decode("utf8","replace")
    except (URLError,TimeoutError,OSError) as e:
        return 0,url,repr(e)

st,url,body=fetch(base+"/?s="+quote("اللعبة"))
soup=BeautifulSoup(body,"html.parser")
items=[(a.get("title",""),urljoin(url,a.get("href",""))) for a in soup.select("a[title][href]")]
samples=[(title,link) for title,link in items if "مسلسل اللعبة" in title and "الموسم الخامس" in title and "الحلقة 30" in title]
print("SEARCH",st,"HTML_SIZE",len(body),"cards",len(items),"target_candidates",len(samples),flush=True)
if samples:
    title,ep=samples[0]
    st,final,body=fetch(ep)
    doc=BeautifulSoup(body,"html.parser")
    watch=doc.select_one("a.watch__btn[href]")
    watchUrl=urljoin(final,watch.get("href","")) if watch else final.rstrip("/")+"/watch/"
    print("ITEM",title[:100],"http",st,"watch_url_path",urlparse(watchUrl).path[:100],flush=True)
    st,watchFinal,watchBody=fetch(watchUrl,final)
    wd=BeautifulSoup(watchBody,"html.parser")
    print("WATCH",st,"bytes",len(watchBody),"host",urlparse(watchFinal).hostname,flush=True)
    for a in wd.select("[data-quality]"):
        print("QUALITY_ELEMENT",a.name,{k:v[:60] for k,v in a.attrs.items() if isinstance(v,str) and k.startswith("data-")},"text",a.get_text(" ",strip=True)[:36],flush=True)
    for a in wd.select("[data-server], [data-src], [data-link]")[:12]:
        attrs={k:(urlparse(v).hostname if v.startswith("http") else v[:50]) for k,v in a.attrs.items() if isinstance(v,str) and k.startswith("data-")}
        print("SERVER_ELEMENT",a.name,attrs,"text",a.get_text(" ",strip=True)[:35],flush=True)
    scripts=[urljoin(watchFinal,a.get("src","")) for a in wd.select("script[src]")]
    print("SCRIPT_URL_PATHS",[(urlparse(u).hostname,urlparse(u).path[:105]) for u in scripts[-25:]],flush=True)
    snippets=[]
    for script in wd.select("script:not([src])"):
        blob=script.get_text(" ",strip=True)
        for pat in ["csrf","quality","get__watch","get__quality","ajax","post_id","server"]:
            loc=blob.lower().find(pat)
            if loc>=0:
                snippets.append((pat,blob[max(0,loc-70):loc+120].replace("\n"," ")[:180]))
    print("INLINE_PATTERNS",snippets[:14],flush=True)
    for js in scripts[-12:]:
        if urlparse(js).hostname not in ["m.myseed.tv","cdn.jsdelivr.net","code.jquery.com"]:continue
        status,_,jsbody=fetch(js,watchFinal,max_len=700000)
        keywords=[pat for pat in ["get__watch__server","get__quality__servers","csrf__token","get_quality","quality_servers","data-quality","data-server"] if pat in jsbody]
        print("JS",urlparse(js).path[:90],status,"bytes",len(jsbody),"markers",keywords,flush=True)
        for pat in keywords[:2]:
            loc=jsbody.find(pat)
            print(" JS_SNIPPET",pat,jsbody[max(0,loc-70):loc+170].replace("\n"," ")[:230],flush=True)
    print("QUALITY_SERVER_HTTP")
    inline_text="\n".join(script.get_text() for script in wd.select("script:not([src])"))
    token_match=re.search(
        r"""csrf__token['"]?\s*:\s*['"]([^'"]{6,})['"]""",
        inline_text
    )
    csrf=token_match.group(1) if token_match else ""
    print(" TOKEN_PRESENT",bool(csrf))
    for js in scripts:
        if "autoptimize" not in js: continue
        status,_,jsbody=fetch(js,watchFinal,max_len=900000)
        for keyword in ["get__quality__servers","get__watch__server"]:
            at=jsbody.find(keyword)
            if at>=0:
                snippet=jsbody[at:at+850]
                snippet=re.sub(r"""csrf__token\s*[:=]\s*['"][^'"]+['"]""","csrf__token:<redacted>",snippet)
                print(" AJAX_CODE",keyword,snippet[:750].replace("\n"," "),flush=True)
        break
    li=wd.select_one("li[data-server][data-post]")
    postid=li.get("data-post","") if li else ""
    if postid:
        for endpoint in ["/get__quality__servers/","/get__watch__server/"]:
            data={"post_id":postid,"quality":"720","server":"0"}
            for field in ["csrf_token","csrf__token","token","_token"]:
                if not csrf:break
                payload={**data,field:csrf}
                status,fin,res=fetch(base+endpoint,watchFinal,post=payload)
                try:
                    obj=json.loads(res)
                    print("  REQUEST",endpoint,"csrf_field",field,status,
                          "type",obj.get("type"),"json_keys",list(obj.keys()),
                          "html_bytes",len(obj.get("html","")),"server_host",urlparse(obj.get("server","")).hostname,flush=True)
                except Exception:
                    print("  REQUEST",endpoint,field,status,"response_start",res[:65],flush=True)
                if '"type":"success"' in res or '"html"' in res:break

        # Inspect only the publicly advertised 720p and 1080p choices.
        # Neither the AJAX token nor actual signed video URLs are logged.
        if csrf:
            for level in (720,1080):
                st,_,payload=fetch(base+"/get__quality__servers/",watchFinal,post={
                    "post_id":postid,"quality":str(level),"csrf_token":csrf
                })
                try:
                    info=json.loads(payload)
                    listed=BeautifulSoup(info.get("html",""),"html.parser")
                    servers=listed.select("li[data-server], li[data-src], li[data-link]")
                    sample=[{
                        "id":e.get("data-server"),
                        "quality":e.get("data-qu"),
                        "label":e.get("data-server-name","")[:55],
                        "host":urlparse(e.get("data-link","")).hostname,
                        "direct":e.get("data-direct-arabseed")
                    } for e in servers[:6]]
                    print("HD_SERVERS",level,st,info.get("type"),"count",len(servers),
                          "samples",sample,flush=True)
                    for e in servers[:2]:
                        sid=e.get("data-server","")
                        post=e.get("data-post","") or postid
                        q=e.get("data-qu","") or str(level)
                        st2,_,player=fetch(base+"/get__watch__server/",watchFinal,post={
                            "post_id":post,"quality":q,"server":sid,
                            "csrf_token":csrf
                        })
                        p=json.loads(player)
                        target=p.get("server","")
                        print("HD_PLAYER",level,"server",sid,"HTTP",st2,
                              "type",p.get("type"),"host",urlparse(target).hostname,
                              "path_kind","vids.php" if "vids.php" in target else "other",flush=True)
                        if p.get("type")!="success" or not target:continue
                        wrapped=urljoin(watchFinal,target)
                        st3,u3,embed=fetch(wrapped,watchFinal,max_len=180000)
                        snippet=BeautifulSoup(embed,"html.parser")
                        iframes=snippet.select("iframe[src]")
                        print("HD_WRAPPER",level,"status",st3,"bytes",len(embed),
                              "iframe_hosts",[urlparse(urljoin(u3,e.get("src",""))).hostname for e in iframes[:2]],flush=True)
                except Exception as e:
                    print("HD_ERROR",level,type(e).__name__,str(e)[:110],flush=True)
