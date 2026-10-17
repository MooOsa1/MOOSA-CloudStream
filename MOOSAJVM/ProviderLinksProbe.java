import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;

/**
 * Isolated one-shot original CineStreamProvider.loadLinks test on freely licensed Sintel.
 * Runs the original checksum-verified .cs3 bytecode translated to Java classes and
 * the official CloudStream JVM library. Does not download video, stream or disclose
 * third party links, headers, tokens, cookies or subtitles; counters only.
 */
public final class ProviderLinksProbe {
    private static final String SINTEL_DATA="{\"id\":\"tt1727587\",\"type\":\"movie\"}";
    interface Invocation { Object call(Continuation<Object> continuation) throws Throwable; }
    static Object runSuspend(Invocation fn,int seconds) throws Throwable {
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<Object> resume=new AtomicReference<>();
        Continuation<Object> completion=new Continuation<>() {
            @Override public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
            @Override public void resumeWith(Object v){resume.set(v);latch.countDown();}
        };
        Object result;
        try{result=fn.call(completion);}
        catch(InvocationTargetException e){throw e.getTargetException();}
        if(result==IntrinsicsKt.getCOROUTINE_SUSPENDED()){
            if(!latch.await(seconds,TimeUnit.SECONDS))throw new java.util.concurrent.TimeoutException("suspended_method");
            result=resume.get();
        }
        kotlin.ResultKt.throwOnFailure(result);
        return result;
    }
    static String clean(Throwable t){
        Throwable c=t instanceof InvocationTargetException i?i.getTargetException():t;
        // Plugin exceptions may contain signed media URLs or bearer tokens.
        return c.getClass().getSimpleName();
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("cloudstreamLibrary.jar cineStreamPlugin.jar");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
            Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()
        },ClassLoader.getSystemClassLoader())){
            Class<?> type=Class.forName("com.megix.CineStreamProvider",true,loader);
            Object provider=type.getDeclaredConstructor().newInstance();
            Object load;
            try{
                load=runSuspend(c->type.getMethod("load",String.class,Continuation.class)
                    .invoke(provider,SINTEL_DATA,c),20);
                if(load==null){System.out.println("JVM_REAL_LINKS stage=load status=null");return;}
                String title=String.valueOf(load.getClass().getMethod("getName").invoke(load));
                if(!title.equalsIgnoreCase("Sintel")){
                    System.out.println("JVM_REAL_LINKS stage=identity status=rejected_mismatch");return;
                }
            }catch(Throwable e){System.out.println("JVM_REAL_LINKS stage=load status=error reason="+clean(e));return;}
            String payload;
            try{
                payload=String.valueOf(load.getClass().getMethod("getDataUrl").invoke(load));
                if(payload.length()<10||payload.length()>13000||!payload.contains("tt1727587")){
                    System.out.println("JVM_REAL_LINKS stage=payload status=missing_or_untrusted");return;
                }
            }catch(Throwable e){System.out.println("JVM_REAL_LINKS stage=payload status=error reason="+clean(e));return;}
            String chosen=System.getenv("MOOSA_JVM_SINGLE_PROVIDER");
            String providerKey=java.util.Set.of("p_vaplayer","p_vidzee","p_videasy",
                "p_vidcore","p_vidfastpro","p_primesrc","p_cinejoy").contains(chosen==null?"":chosen)
                ?chosen:"p_vaplayer";
            // Original provider metadata can erase its TMDB ID on IMDb movie
            // loads. Restore it only from matching trusted Cinemeta metadata.
            int before=CinemetaIdentity.tmdbId(payload);
            final String approvedPayload=CinemetaIdentity.enrichLicensedSintel(payload);
            int after=CinemetaIdentity.tmdbId(approvedPayload);
            System.out.println("JVM_METADATA_IDENTITY provider="+providerKey+
                " imdbVerified=true tmdbBefore="+(before>0)+
                " tmdbAfter="+(after>0)+" restored="+(before==0&&after>0));
            AtomicInteger total=new AtomicInteger(), hd=new AtomicInteger(), captions=new AtomicInteger();
            Map<String,AtomicInteger> sourceGroups=new ConcurrentHashMap<>();
            List<String> approvedHttpsHls=java.util.Collections.synchronizedList(new ArrayList<>());
            AtomicInteger needsReferer=new AtomicInteger(), linksWithHeaders=new AtomicInteger();
            AtomicInteger declaredHls=new AtomicInteger(), declaredMp4=new AtomicInteger(), https=new AtomicInteger();
            Function1<Object,Unit> onSub=subtitle->{captions.incrementAndGet();return Unit.INSTANCE;};
            Function1<Object,Unit> onVideo=link->{
                total.incrementAndGet();
                try{
                    Object q=link.getClass().getMethod("getQuality").invoke(link);
                    if(q instanceof Number && ((Number)q).intValue()>=720)hd.incrementAndGet();
                }catch(Throwable ignored){}
                try{
                    Object ref=link.getClass().getMethod("getReferer").invoke(link);
                    if(ref instanceof String text && !text.isBlank())needsReferer.incrementAndGet();
                }catch(Throwable ignored){}
                try{
                    Object headers=link.getClass().getMethod("getHeaders").invoke(link);
                    if(headers instanceof Map<?,?> map && !map.isEmpty())linksWithHeaders.incrementAndGet();
                }catch(Throwable ignored){}
                try{
                    Object val=link.getClass().getMethod("getUrl").invoke(link);
                    URI u=new URI(String.valueOf(val));
                    if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null)return Unit.INSTANCE;
                    https.incrementAndGet();
                    String ext=String.valueOf(u.getPath()).toLowerCase();
                    String kind=ext.endsWith(".m3u8")?"hls":
                        ext.endsWith(".mp4")?"mp4":
                        ext.endsWith(".webm")?"webm":"other";
                    if(kind.equals("hls"))declaredHls.incrementAndGet();
                    if(kind.equals("hls") && u.getHost().equalsIgnoreCase("visionaryfounderslab.site")
                        && approvedHttpsHls.size()<3 && !approvedHttpsHls.contains(String.valueOf(val)))
                        approvedHttpsHls.add(String.valueOf(val));
                    if(kind.equals("mp4"))declaredMp4.incrementAndGet();
                    String host=u.getHost().toLowerCase();
                    if(host.length()>100)host="unclassified";
                    sourceGroups.computeIfAbsent(host+"("+kind+")",k->new AtomicInteger()).incrementAndGet();
                }catch(Throwable ignored){}
                return Unit.INSTANCE;
            };
            long begin=System.nanoTime();
            try{
                Object returned=runSuspend(c->type.getMethod(
                    "loadLinks",String.class,boolean.class,Function1.class,Function1.class,Continuation.class
                ).invoke(provider,approvedPayload,false,onSub,onVideo,c),55);
                System.out.println("JVM_REAL_LINKS stage=loadLinks status=returned result="+String.valueOf(returned)+
                    " sources="+total.get()+" hd720plus="+hd.get()+" subtitles="+captions.get()+
                    " durationMs="+(System.nanoTime()-begin)/1_000_000);
                System.out.println("JVM_SOURCE_CLASSIFICATION https="+https.get()+
                    " hls="+declaredHls.get()+" mp4="+declaredMp4.get()+
                    " needsReferer="+needsReferer.get()+" extraHeaders="+linksWithHeaders.get()+
                    " hosts="+new TreeMap<>(sourceGroups).entrySet().stream()
                        .limit(20).map(e->e.getKey()+":"+e.getValue().get())
                        .reduce((a,b)->a+";"+b).orElse("none"));
                HlsSourceProbe.probe(approvedHttpsHls);
            }catch(Throwable e){
                System.out.println("JVM_REAL_LINKS stage=loadLinks status=error reason="+clean(e)+
                    " sources="+total.get()+" hd720plus="+hd.get()+" subtitles="+captions.get());
            }
        }finally{
            System.exit(0); // one-shot CI process; no lingering NiceHTTP/coroutine threads
        }
    }
}
