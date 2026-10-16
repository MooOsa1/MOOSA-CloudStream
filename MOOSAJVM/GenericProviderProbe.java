import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Generic, CI-only headless execution proof for one explicitly approved, verified
 * original CloudStream plugin provider. Does NOT load raw repository code itself.
 *
 * Runs only within an isolated, disposable GitHub Actions process. This is NOT
 * a security boundary or production multi-tenant plugin engine.
 *
 * Inputs: official CloudStream JVM JAR, converted .cs3 JAR, discovered provider
 * class name, either "inspect" or "load-licensed-sintel".
 * Output: bounded, sanitized metadata only. No stream URLs or secrets.
 */
public final class GenericProviderProbe {
    private static final String SINTEL_IMDB="tt1727587";
    private static final String TEST_PAYLOAD="{\"id\":\"tt1727587\",\"type\":\"movie\"}";
    static String safe(String raw) {
        if(raw==null)return "null";
        String s=raw.replaceAll("[^\\p{L}\\p{N} ._:/(),-]","_");
        return s.substring(0,Math.min(s.length(),110));
    }
    static String failure(Throwable t) {
        Throwable e=t instanceof InvocationTargetException i?i.getTargetException():t;
        return safe(e.getClass().getSimpleName()+":"+e.getMessage());
    }
    interface Invocation { Object invoke(Continuation<Object> result) throws Throwable; }
    static Object suspendCall(Invocation call) throws Throwable {
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<Object> resumed=new AtomicReference<>();
        Continuation<Object> cont=new Continuation<>() {
            @Override public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
            @Override public void resumeWith(Object x){resumed.set(x);latch.countDown();}
        };
        Object out;
        try {out=call.invoke(cont);}
        catch(InvocationTargetException e){throw e.getTargetException();}
        if(out==IntrinsicsKt.getCOROUTINE_SUSPENDED()){
            if(!latch.await(18,TimeUnit.SECONDS))throw new java.util.concurrent.TimeoutException("call");
            out=resumed.get();
        }
        kotlin.ResultKt.throwOnFailure(out);
        return out;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException(
            "usage: GenericProviderProbe <official.jar> <plugin.jar> <discovered.class> <inspect|load-licensed-sintel>");
        String providerName=args[2],action=args[3];
        if(providerName.length()>230||!providerName.matches(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+") ||
            (!action.equals("inspect")&&!action.equals("load-licensed-sintel")))
            throw new IllegalArgumentException("invalid_provider_or_action");
        int exitCode=0;
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
            Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()
        },ClassLoader.getSystemClassLoader())){
            try {
                Class<?> api=Class.forName("com.lagradost.cloudstream3.MainAPI",false,loader);
                Class<?> cls=Class.forName(providerName,false,loader);
                if(!api.isAssignableFrom(cls)||Modifier.isAbstract(cls.getModifiers())||
                    !Modifier.isPublic(cls.getModifiers()))
                    throw new IllegalArgumentException("provider_not_concrete_mainapi");
                Class.forName(providerName,true,loader);
                Object provider=null;
                try{
                    Constructor<?> c=cls.getDeclaredConstructor();
                    provider=c.newInstance();
                }catch(NoSuchMethodException noArg){
                    // StreamPlay has a nullable android.content.SharedPreferences constructor;
                    // null is deliberately used, never real Android context or credentials.
                    Class<?> pref=Class.forName("android.content.SharedPreferences",false,loader);
                    provider=cls.getDeclaredConstructor(pref).newInstance(new Object[]{null});
                }
                String displayName=String.valueOf(cls.getMethod("getName").invoke(provider));
                String types=String.valueOf(cls.getMethod("getSupportedTypes").invoke(provider));
                String marker="MOOSA_GENERIC_PROVIDER";
                System.out.println(marker+" phase=instantiate result=success class="+safe(providerName)+
                    " displayName="+safe(displayName)+" types="+safe(types));
                if(action.equals("load-licensed-sintel")){
                    final Object p=provider;
                    Object result=suspendCall(cont->cls.getMethod("load",String.class,Continuation.class)
                        .invoke(p,TEST_PAYLOAD,cont));
                    if(result==null){
                        System.out.println(marker+" phase=load result=null expectedImdb="+SINTEL_IMDB);
                    }else{
                        String name=String.valueOf(result.getClass().getMethod("getName").invoke(result));
                        boolean exact="Sintel".equalsIgnoreCase(name);
                        System.out.println(marker+" phase=load result="+(exact?"exact":"mismatch")+
                            " title="+safe(name)+" kind="+safe(result.getClass().getSimpleName())+
                            " expectedImdb="+SINTEL_IMDB);
                        if(!exact)exitCode=1;
                    }
                }
            }catch(Throwable err){
                System.out.println("MOOSA_GENERIC_PROVIDER phase="+safe(action)+
                    " result=blocked reason="+failure(err));
                exitCode=2;
            }
        }finally {
            // NiceHTTP / coroutine dispatcher threads may outlive single-shot load.
            System.exit(exitCode);
        }
    }
}
