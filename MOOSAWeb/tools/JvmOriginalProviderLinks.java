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
        if(args.length!=3)throw new IllegalArgumentException("official library jar, original CineStream jar, runtime deps dir");
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
            final String id="tt1727587";
            System.out.println("ORIGINAL_LINKS_EXACT_MOVIE: Sintel ("+id+")");
            Object details=invokeSuspend(provider,load,new Object[]{"{\"id\":\""+id+"\",\"type\":\"movie\"}"},loader,18);
            if(details==null)throw new IllegalStateException("Original load() returned null");
            String title=String.valueOf(details.getClass().getMethod("getName").invoke(details));
            String originalData=String.valueOf(details.getClass().getMethod("getDataUrl").invoke(details));
            if(!"Sintel".equalsIgnoreCase(title)||!originalData.contains("\"id\":\""+id+"\""))
                throw new IllegalStateException("Rejected title or IMDb mismatch");
            System.out.println("ORIGINAL_LINKS_EXACT_IDENTITY_VERIFIED: true");
            System.out.println("ORIGINAL_LINKS_ORIGINAL_DATA_BYTES: "+originalData.length());
            // Upstream CineStreamProvider.load() currently clears a real TMDB
            // ID on the IMDb movie path. For this one independently licensed,
            // exact IMDb-verified film restore only its fixed, verified ID.
            // Never rewrite title, IMDb ID, year or arbitrary movie metadata.
            final String repairedData=originalData.replace(
                "\"tmdbId\":null", "\"tmdbId\":45745");
            System.out.println("ORIGINAL_CINESTREAM_TMDB_RESTORED: "+
                !repairedData.equals(originalData));
            Class<?> fn1=Class.forName("kotlin.jvm.functions.Function1",false,loader);
            Object unit=Class.forName("kotlin.Unit",true,loader).getField("INSTANCE").get(null);
            AtomicInteger links=new AtomicInteger(0),subs=new AtomicInteger(0),hdLinks=new AtomicInteger(0);
            Set<String> videoHosts=ConcurrentHashMap.newKeySet();
            Set<String> candidateUrls=ConcurrentHashMap.newKeySet();
            ConcurrentHashMap<String,Set<String>> publicSourceGroups=new ConcurrentHashMap<>();
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
                            try {
                                Object label=link.getClass().getMethod("getSource").invoke(link);
                                String safe=String.valueOf(label).replaceAll("[^a-zA-Z0-9 _.-]","");
                                safe=safe.substring(0,Math.min(36,safe.length()));
                                publicSourceGroups.computeIfAbsent(host.toLowerCase(),
                                    key->ConcurrentHashMap.newKeySet()).add(safe);
                            }catch(Throwable ignored){}
                            if(uri.getPort()==-1 && uri.getUserInfo()==null && candidateUrls.size()<24)
                                candidateUrls.add(uri.toString());
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
                    new Object[]{repairedData,false,subtitleCallback,linkCallback},loader,35);
            }catch(Throwable e){
                report("EXECUTION_BLOCKED",e);
                System.out.println("ORIGINAL_LINKS_CALLBACK_COUNTS: links="+links+" hd="+hdLinks+" subtitles="+subs);
                System.exit(14);return;
            }
            System.out.println("ORIGINAL_LINKS_FUNCTION_RETURN: "+status);
            System.out.println("ORIGINAL_LINKS_CALLBACK_COUNTS: links="+links+" hd="+hdLinks+" subtitles="+subs);
            System.out.println("ORIGINAL_LINKS_RETURNED_PUBLIC_HOST_COUNT: "+videoHosts.size());
            // Provider name and public CDN hostname ONLY, no stream paths or
            // signed query parameters. Helps identify incorrect-film callbacks.
            for(String host:new TreeSet<>(publicSourceGroups.keySet())){
                System.out.println("ORIGINAL_CINESTREAM_PROVIDER_HOST: "+
                    host+" names="+new TreeSet<>(publicSourceGroups.get(host)));
            }
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
            for(String url:new TreeSet<>(candidateUrls)){
                if(encoded.size()>=24)break;
                encoded.add(java.util.Base64.getEncoder().encodeToString(
                    url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            Files.write(candidateFile,encoded,java.nio.charset.StandardCharsets.UTF_8);
            try{
                Files.setPosixFilePermissions(candidateFile,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                     java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            }catch(UnsupportedOperationException ignored){}
            System.out.println("ORIGINAL_LINKS_CANDIDATES_NOT_BROWSER_VERIFIED");
            System.out.println("ORIGINAL_LINKS_PRIVATE_PROBE_INPUT_COUNT: "+encoded.size());
            // HTTP client/coroutines create non-daemon threads. End this
            // isolated JVM only after truthful callback results are recorded.
            System.exit(0);
        }
    }
}
