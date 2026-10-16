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
 * Read-only CI probe for original StreamPlay Lite loadLinks on CC-licensed Sintel.
 * Sources remain metadata-only, no video downloaded or secret URLs printed.
 * No auth headers, Cloudflare bypass, Android WebView, torrents or DRM proxies.
 */
public final class StreamPlayLiteProbe {
    private static final String DATA="{\"id\":45745,\"imdbId\":\"tt1727587\","
      +"\"title\":\"Sintel\",\"year\":2010,\"type\":\"movie\","
      +"\"isAnime\":false,\"isAsian\":false,\"isBollywood\":false}";
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("libraryJar convertedStreamPlayJar");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
            Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()
        },ClassLoader.getSystemClassLoader())){
            Class<?> klass;
            Object provider;
            try{
                // Recent published .cs3 does not expose StreamPlayLite even though
                // it exists in a source branch. Fall back to the verified original
                // StreamPlay class, not an invented provider.
                try{
                    klass=Class.forName("com.phisher98.StreamPlayLite",true,loader);
                }catch(ClassNotFoundException missingLite){
                    klass=Class.forName("com.phisher98.StreamPlay",true,loader);
                }
                provider=klass.getDeclaredConstructor().newInstance();
                System.out.println("JVM_STREAMPLAY_LITE stage=instantiate status=success provider="+klass.getName());
            }catch(Throwable error){
                Throwable e=error instanceof InvocationTargetException ite?ite.getTargetException():error;
                System.out.println("JVM_STREAMPLAY_LITE stage=instantiate status=error type="+
                    e.getClass().getSimpleName());
                return;
            }
            AtomicInteger videos=new AtomicInteger(),hd=new AtomicInteger(),subtitles=new AtomicInteger();
            Function1<Object,Unit> onVideo=link->{
                videos.incrementAndGet();
                try{
                    Object quality=link.getClass().getMethod("getQuality").invoke(link);
                    if(quality instanceof Number n && n.intValue()>=720)hd.incrementAndGet();
                }catch(Throwable ignored){}
                return Unit.INSTANCE;
            };
            Function1<Object,Unit> onSub=x->{subtitles.incrementAndGet();return Unit.INSTANCE;};
            CountDownLatch latch=new CountDownLatch(1);
            AtomicReference<Object> value=new AtomicReference<>();
            Continuation<Object> finish=new Continuation<>() {
                public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
                public void resumeWith(Object next){value.set(next);latch.countDown();}
            };
            long start=System.nanoTime();
            try{
                Object result=klass.getMethod("loadLinks",String.class,boolean.class,
                    Function1.class,Function1.class,Continuation.class)
                    .invoke(provider,DATA,false,onSub,onVideo,finish);
                if(result==IntrinsicsKt.getCOROUTINE_SUSPENDED()){
                    if(!latch.await(35,TimeUnit.SECONDS)){
                        System.out.println("JVM_STREAMPLAY_LITE stage=loadLinks status=timeout");
                        return;
                    }
                    result=value.get();
                }
                kotlin.ResultKt.throwOnFailure(result);
                System.out.println("JVM_STREAMPLAY_LITE stage=loadLinks status=returned "+
                    "result="+result+" sources="+videos.get()+" hd720plus="+hd.get()+
                    " subtitles="+subtitles.get()+" durationMs="+
                    ((System.nanoTime()-start)/1_000_000));
            }catch(Throwable error){
                Throwable e=error instanceof InvocationTargetException ite?ite.getTargetException():error;
                System.out.println("JVM_STREAMPLAY_LITE stage=loadLinks status=error "+
                    "cause="+e.getClass().getSimpleName()+" sources="+videos.get()+
                    " subtitles="+subtitles.get());
            }
        }finally{System.exit(0);}
    }
}
