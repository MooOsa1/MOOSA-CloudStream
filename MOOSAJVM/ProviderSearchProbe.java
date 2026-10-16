import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Executes original CineStreamProvider.search on a JVM using the published,
 * checksum verified .cs3 DEX conversion and official CloudStream JVM API.
 * Probe only requests public Cinemeta/Kitsu catalog metadata for a CC movie.
 * No video URL or streaming endpoint is opened.
 */
public final class ProviderSearchProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("usage: ProviderSearchProbe libraryJar pluginJar");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
            Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()
        },ClassLoader.getSystemClassLoader())) {
            Class<?> type=Class.forName("com.megix.CineStreamProvider",true,loader);
            Object provider=type.getDeclaredConstructor().newInstance();
            CountDownLatch done=new CountDownLatch(1);
            AtomicReference<Object> value=new AtomicReference<>();
            Continuation<Object> completion=new Continuation<>() {
                public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
                public void resumeWith(Object x){value.set(x);done.countDown();}
            };
            long started=System.nanoTime();
            Object result=null;
            try{
                result=type.getMethod("search",String.class,Continuation.class)
                    .invoke(provider,"Big Buck Bunny",completion);
                if(result==IntrinsicsKt.getCOROUTINE_SUSPENDED()){
                    if(!done.await(35,TimeUnit.SECONDS)){
                        System.out.println("JVM_REAL_SEARCH status=timeout query=licensed_test durationMs="+elapsed(started));
                        return;
                    }
                    result=value.get();
                }
                kotlin.ResultKt.throwOnFailure(result);
                if(result instanceof List<?> list){
                    long exact=list.stream().filter(o->{
                        try{
                            String n=String.valueOf(o.getClass().getMethod("getName").invoke(o));
                            return n.equalsIgnoreCase("Big Buck Bunny");
                        }catch(Throwable ignore){return false;}
                    }).count();
                    System.out.println("JVM_REAL_SEARCH status=completed query=licensed_test count="+list.size()+
                        " exactMatches="+exact+" durationMs="+elapsed(started));
                }else{
                    System.out.println("JVM_REAL_SEARCH status=unexpected_result type="+
                        (result==null?"null":result.getClass().getName())+
                        " durationMs="+elapsed(started));
                }
            }catch(Throwable ex){
                Throwable cause=ex instanceof java.lang.reflect.InvocationTargetException ite?
                    ite.getTargetException():ex;
                System.out.println("JVM_REAL_SEARCH status=error exception="+cause.getClass().getName()+
                    " reason="+String.valueOf(cause.getMessage()).replaceAll("[^a-zA-Z0-9 ./_:-]","_").substring(0,
                    Math.min(150,String.valueOf(cause.getMessage()).length()))+
                    " durationMs="+elapsed(started));
            }
        }
    }
    private static long elapsed(long started){return (System.nanoTime()-started)/1_000_000;}
}
