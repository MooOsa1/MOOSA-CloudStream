import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        String reason=String.valueOf(c.getMessage()).replaceAll("[^a-zA-Z0-9 _./:-]","_");
        return c.getClass().getSimpleName()+"/"+reason.substring(0,Math.min(95,reason.length()));
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
            AtomicInteger total=new AtomicInteger(), hd=new AtomicInteger(), captions=new AtomicInteger();
            Function1<Object,Unit> onSub=subtitle->{captions.incrementAndGet();return Unit.INSTANCE;};
            Function1<Object,Unit> onVideo=link->{
                total.incrementAndGet();
                try{
                    Object q=link.getClass().getMethod("getQuality").invoke(link);
                    if(q instanceof Number && ((Number)q).intValue()>=720)hd.incrementAndGet();
                }catch(Throwable ignored){}
                return Unit.INSTANCE;
            };
            long begin=System.nanoTime();
            try{
                Object returned=runSuspend(c->type.getMethod(
                    "loadLinks",String.class,boolean.class,Function1.class,Function1.class,Continuation.class
                ).invoke(provider,payload,false,onSub,onVideo,c),55);
                System.out.println("JVM_REAL_LINKS stage=loadLinks status=returned result="+String.valueOf(returned)+
                    " sources="+total.get()+" hd720plus="+hd.get()+" subtitles="+captions.get()+
                    " durationMs="+(System.nanoTime()-begin)/1_000_000);
            }catch(Throwable e){
                System.out.println("JVM_REAL_LINKS stage=loadLinks status=error reason="+clean(e)+
                    " sources="+total.get()+" hd720plus="+hd.get()+" subtitles="+captions.get());
            }
        }finally{
            System.exit(0); // one-shot CI process; no lingering NiceHTTP/coroutine threads
        }
    }
}
