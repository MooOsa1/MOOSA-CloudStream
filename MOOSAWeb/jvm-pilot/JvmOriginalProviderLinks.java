import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Real CineStreamProvider.loadLinks() on the official CloudStream JVM API.
 * Input comes from the original load() of an exact CC-BY film (Sintel).
 * No Android, no fake links, no signed URLs in logs or stored artifacts.
 * This is diagnostics only, not evidence of browser-decodable video.
 */
public final class JvmOriginalProviderLinks {
    private static String brief(Object value,int max){
        String s=String.valueOf(value);
        return s.substring(0,Math.min(s.length(),max)).replaceAll("[\r\n]"," ");
    }
    private static Throwable root(Throwable t){
        while(t instanceof InvocationTargetException && t.getCause()!=null)t=t.getCause();
        return t;
    }
    private static void report(String stage,Throwable t){
        Throwable cause=root(t);
        System.out.println("ORIGINAL_LINKS_"+stage+": "+cause.getClass().getName()+": "+brief(cause.getMessage(),190));
    }
    private static Object invokeSuspend(Object target,Method method,Object[] inputs,ClassLoader loader,int timeoutSec) throws Exception {
        Class<?> cont=Class.forName("kotlin.coroutines.Continuation",false,loader);
        Object context=Class.forName("kotlin.coroutines.EmptyCoroutineContext",true,loader).getField("INSTANCE").get(null);
        Class<?> sentinels=Class.forName("kotlin.coroutines.intrinsics.CoroutineSingletons",true,loader);
        Object suspended=Enum.valueOf((Class)sentinels.asSubclass(Enum.class),"COROUTINE_SUSPENDED");
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<Object> resumed=new AtomicReference<>();
        Object continuation=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{cont},(p,m,a)->{
            switch(m.getName()){
                case "getContext":return context;
                case "resumeWith":resumed.set(a[0]);latch.countDown();return null;
                case "toString":return "MOOSA original Kotlin continuation";
                case "hashCode":return System.identityHashCode(p);
                case "equals":return p==a[0];
                default:return null;
            }
        });
        Object[] call=Arrays.copyOf(inputs,inputs.length+1);
        call[inputs.length]=continuation;
        Object result;
        try{result=method.invoke(target,call);}
        catch(InvocationTargetException e){
            Throwable cause=root(e);
            System.out.println("ORIGINAL_LINKS_SUSPEND_THREW: "+cause.getClass().getName()+" "+brief(cause.getMessage(),160));
            throw e;
        }
        if(result==suspended){
            System.out.println("ORIGINAL_LINKS_COROUTINE_SUSPENDED: "+method.getName());
            if(!latch.await(timeoutSec,TimeUnit.SECONDS))throw new TimeoutException(method.getName()+" took over "+timeoutSec+"s");
            result=resumed.get();
        }
        Class.forName("kotlin.ResultKt",true,loader).getMethod("throwOnFailure",Object.class).invoke(null,result);
        return result;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3 && args.length!=5)throw new IllegalArgumentException("official library jar, original CineStream jar, runtime deps dir, optional IMDB id and expected original title");
        List<URL> jars=new ArrayList<>();
        jars.add(Path.of(args[0]).toUri().toURL());
        jars.add(Path.of(args[1]).toUri().toURL());
        try(var stream=Files.list(Path.of(args[2]))){
            stream.filter(p->p.toString().endsWith(".jar")).forEach(p->{
                try{jars.add(p.toUri().toURL());}catch(MalformedURLException e){throw new RuntimeException(e);}
            });
        }
        try(URLClassLoader loader=new URLClassLoader(jars.toArray(new URL[0]),ClassLoader.getPlatformClassLoader())){
            Class<?> cls=Class.forName("com.megix.CineStreamProvider",true,loader);
            Object provider=cls.getDeclaredConstructor().newInstance();
            Class<?> cont=Class.forName("kotlin.coroutines.Continuation",false,loader);
            Method load=cls.getMethod("load",String.class,cont);
            final String id=args.length==5?args[3]:"tt1727587";
            final String expectedTitle=args.length==5?args[4]:"Sintel";
            if(!id.matches("tt[0-9]{5,12}")||expectedTitle.isBlank()||expectedTitle.length()>90)
                throw new IllegalArgumentException("Invalid title identification");
            System.out.println("ORIGINAL_LINKS_EXACT_MOVIE: "+expectedTitle+" ("+id+")");
            Object details=invokeSuspend(provider,load,new Object[]{"{\"id\":\""+id+"\",\"type\":\"movie\"}"},loader,55);
            if(details==null)throw new IllegalStateException("Original load() returned null");
            String title=String.valueOf(details.getClass().getMethod("getName").invoke(details));
            String originalData=String.valueOf(details.getClass().getMethod("getDataUrl").invoke(details));
            if(!expectedTitle.equalsIgnoreCase(title)||!originalData.contains("\"id\":\""+id+"\""))
                throw new IllegalStateException("Rejected title or IMDb mismatch");
            System.out.println("ORIGINAL_LINKS_EXACT_IDENTITY_VERIFIED: true");
            System.out.println("ORIGINAL_LINKS_ORIGINAL_DATA_BYTES: "+originalData.length());
            Class<?> fn1=Class.forName("kotlin.jvm.functions.Function1",false,loader);
            Object unit=Class.forName("kotlin.Unit",true,loader).getField("INSTANCE").get(null);
            AtomicInteger links=new AtomicInteger(0),subs=new AtomicInteger(0),hdLinks=new AtomicInteger(0);
            Set<String> videoHosts=ConcurrentHashMap.newKeySet();
            // URL/metadata stay local to the container; no signed stream URL is logged.
            ConcurrentHashMap<String,String> candidateRecords=new ConcurrentHashMap<>();
            AtomicInteger headersRequired=new AtomicInteger(0);
            Object linkCallback=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{fn1},(proxy,method,a)->{
                if("invoke".equals(method.getName())&&a!=null&&a.length==1&&a[0]!=null){
                    links.incrementAndGet();
                    Object link=a[0];
                    try {
                        Object raw=link.getClass().getMethod("getUrl").invoke(link);
                        URI uri=URI.create(String.valueOf(raw));
                        String host=uri.getHost();
                        if(host!=null&&uri.getScheme().equalsIgnoreCase("https")){
                            videoHosts.add(host.toLowerCase());
                            if(uri.getPort()==-1 && uri.getUserInfo()==null && candidateRecords.size()<24){
                                boolean needsHeaders=true;
                                String source="Unclassified";
                                try{
                                    Object providerName=link.getClass().getMethod("getSource").invoke(link);
                                    source=String.valueOf(providerName);
                                    Object referer=link.getClass().getMethod("getReferer").invoke(link);
                                    Object headers=link.getClass().getMethod("getHeaders").invoke(link);
                                    needsHeaders=(referer!=null&&!String.valueOf(referer).isBlank()) ||
                                        (headers instanceof Map&&!((Map<?,?>)headers).isEmpty());
                                }catch(Throwable ignore){
                                    // An extractor with unknown metadata must never be
                                    // incorrectly described as browser-direct.
                                    needsHeaders=true;
                                }
                                if(needsHeaders)headersRequired.incrementAndGet();
                                String url64=Base64.getEncoder().encodeToString(
                                    uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                String source64=Base64.getEncoder().encodeToString(
                                    source.substring(0,Math.min(source.length(),70)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                String record=url64+"\t"+source64+"\t"+(needsHeaders?"1":"0");
                                // If any callback for a shared URL needs headers, keep
                                // the conservative (header-required) classification.
                                candidateRecords.merge(uri.toString(),record,
                                    (oldRec,newRec)->oldRec.endsWith("\t1")?oldRec:
                                        newRec.endsWith("\t1")?newRec:oldRec);
                            }
                        }
                    }catch(Throwable ignored){}
                    try{
                        Object q=link.getClass().getMethod("getQuality").invoke(link);
                        if(q instanceof Number&&((Number)q).intValue()>=720)hdLinks.incrementAndGet();
                    }catch(Throwable ignored){}
                }
                return unit;
            });
            Object subtitleCallback=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{fn1},(proxy,method,a)->{
                if("invoke".equals(method.getName())&&a!=null&&a.length==1&&a[0]!=null)subs.incrementAndGet();
                return unit;
            });
            Method method=cls.getMethod("loadLinks",String.class,boolean.class,fn1,fn1,cont);
            System.out.println("ORIGINAL_CINESTREAM_LOADLINKS_CALLED");
            final Object status;
            try {
                status=invokeSuspend(provider,method,
                    new Object[]{originalData,false,subtitleCallback,linkCallback},loader,110);
            }catch(Throwable e){
                report("EXECUTION_BLOCKED",e);
                System.out.println("ORIGINAL_LINKS_CALLBACK_COUNTS: links="+links+" hd="+hdLinks+" subtitles="+subs);
                System.exit(14);return;
            }
            System.out.println("ORIGINAL_LINKS_FUNCTION_RETURN: "+status);
            System.out.println("ORIGINAL_LINKS_CALLBACK_COUNTS: links="+links+" hd="+hdLinks+" subtitles="+subs);
            System.out.println("ORIGINAL_LINKS_RETURNED_PUBLIC_HOST_COUNT: "+videoHosts.size());
            // Do not equate callback URLs with media playback. We deliberately do
            // not print, store or proxy the signed candidate URLs here.
            if(links.get()==0){System.out.println("ORIGINAL_LINKS_NO_CANDIDATES");System.exit(15);return;}
            // Keep URL tokens strictly within this one ephemeral CI runner.
            // Never print, publish as an artifact, or deploy these raw links.
            Path candidateFile=Path.of("/tmp/moosa-jvm/cinestream-candidates.b64");
            List<String> encoded=new ArrayList<>();
            // Deterministic reporting across concurrent invoker callbacks.
            // Previous 12-link cap could arbitrarily omit the only working
            // cross-CDN candidate among the original 13 links.
            for(String url:new TreeSet<>(candidateRecords.keySet())){
                if(encoded.size()>=24)break;
                encoded.add(candidateRecords.get(url));
            }
            Files.write(candidateFile,encoded,java.nio.charset.StandardCharsets.UTF_8);
            try{
                Files.setPosixFilePermissions(candidateFile,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                     java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            }catch(UnsupportedOperationException ignored){}
            System.out.println("ORIGINAL_LINKS_CANDIDATES_NOT_BROWSER_VERIFIED");
            System.out.println("ORIGINAL_LINKS_PRIVATE_PROBE_INPUT_COUNT: "+encoded.size());
            System.out.println("ORIGINAL_LINKS_HEADER_REQUIRED_CALLBACKS: "+headersRequired.get());
            // HTTP client/coroutines create non-daemon threads. End this
            // isolated JVM only after truthful callback results are recorded.
            System.exit(0);
        }
    }
}
