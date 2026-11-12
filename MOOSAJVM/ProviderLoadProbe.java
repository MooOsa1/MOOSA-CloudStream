import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Real original CineStreamProvider.load() invoked on plain JVM for CC-licensed
 * Sintel metadata (IMDb tt1727587). This does NOT fetch video or extracts.
 */
public final class ProviderLoadProbe {
    private static final String MOVIE_ID="tt1727587";
    private static final String LOAD_DATA="{\"id\":\""+MOVIE_ID+"\",\"type\":\"movie\"}";
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("library jar and CineStream plugin jar required");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
            Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()
        },ClassLoader.getSystemClassLoader())){
            Class<?> type=Class.forName("com.megix.CineStreamProvider",true,loader);
            Object provider=type.getDeclaredConstructor().newInstance();
            CountDownLatch latch=new CountDownLatch(1);
            AtomicReference<Object> resumed=new AtomicReference<>();
            Continuation<Object> continuation=new Continuation<>() {
                @Override public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
                @Override public void resumeWith(Object x){resumed.set(x);latch.countDown();}
            };
            long start=System.nanoTime();
            try{
                Object result=type.getMethod("load",String.class,Continuation.class)
                    .invoke(provider,LOAD_DATA,continuation);
                if(result==IntrinsicsKt.getCOROUTINE_SUSPENDED()){
                    if(!latch.await(20,TimeUnit.SECONDS)){
                        System.out.println("JVM_REAL_LOAD status=timeout imdb="+MOVIE_ID);
                        return;
                    }
                    result=resumed.get();
                }
                kotlin.ResultKt.throwOnFailure(result);
                if(result==null){
                    System.out.println("JVM_REAL_LOAD status=null imdb="+MOVIE_ID+
                        " durationMs="+((System.nanoTime()-start)/1_000_000));
                }else{
                    String title=String.valueOf(result.getClass().getMethod("getName").invoke(result));
                    String safeTitle=title.replaceAll("[^\\p{L}\\p{N} :()'.,-]","_");
                    System.out.println("JVM_REAL_LOAD status=returned imdb="+MOVIE_ID+
                        " correctTitle="+("Sintel".equalsIgnoreCase(title))+
                        " title="+safeTitle.substring(0,Math.min(100,safeTitle.length()))+
                        " kind="+result.getClass().getSimpleName()+
                        " durationMs="+((System.nanoTime()-start)/1_000_000));
                }
            }catch(Throwable e){
                Throwable c=e instanceof java.lang.reflect.InvocationTargetException ite?ite.getTargetException():e;
                System.out.println("JVM_REAL_LOAD status=error imdb="+MOVIE_ID+
                    " exception="+c.getClass().getSimpleName()+
                    " reason="+String.valueOf(c.getMessage()).replaceAll("[^a-zA-Z0-9 _./:$-]","_").substring(
                        0,Math.min(110,String.valueOf(c.getMessage()).length())));
            }
        }finally{
            // The upstream HTTP dispatcher has non-daemon threads. Probe is isolated
            // and cannot keep GitHub runner alive beyond its result.
            System.exit(0);
        }
    }
}
