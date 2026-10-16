import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real original CineStreamProvider class + official CloudStream JVM library.
 * Executes constructor, metadata getters, and public Cinemeta/Kitsu search.
 * No Android app, media resolution, browser, DRM, or source claiming.
 * This experiment runs on an isolated GitHub runner without secrets.
 */
public final class JvmOriginalProviderSmoke {
    private static String shortText(Object value,int limit){
        String text=String.valueOf(value);
        return text.substring(0,Math.min(limit,text.length()));
    }

    /**
     * Invoke CineStreamProvider.search("Sintel") from the actual .cs3 class.
     * Search delegates to official public Cinemeta/Kitsu metadata endpoints.
     * We neither call any media host nor load video or DRM-protected content.
     */
    private static void tryOriginalSearch(Class<?> provider, Object instance, ClassLoader loader) throws Exception {
        Class<?> continuationInterface=Class.forName("kotlin.coroutines.Continuation",false,loader);
        Class<?> contextClass=Class.forName("kotlin.coroutines.EmptyCoroutineContext",true,loader);
        Object context=contextClass.getField("INSTANCE").get(null);
        Object suspended=Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt",true,loader)
            .getMethod("getCOROUTINE_SUSPENDED").invoke(null);
        CountDownLatch completed=new CountDownLatch(1);
        final Object[] delivered=new Object[1];
        Object continuation=java.lang.reflect.Proxy.newProxyInstance(
            loader,new Class<?>[]{continuationInterface},(proxy,method,args)->{
                if(method.getName().equals("getContext"))return context;
                if(method.getName().equals("resumeWith")){
                    delivered[0]=args[0];
                    completed.countDown();
                    return null;
                }
                if(method.getName().equals("toString"))return "MOOSA original provider search continuation";
                return null;
            });
        Method search=provider.getMethod("search",String.class,continuationInterface);
        System.out.println("ORIGINAL_CINESTREAM_SEARCH_INVOKED: Sintel");
        Object immediate=search.invoke(instance,"Sintel",continuation);
        final Object result;
        if(immediate==suspended){
            if(!completed.await(25,TimeUnit.SECONDS))
                throw new TimeoutException("Original CineStream search did not complete within 25 seconds");
            result=delivered[0];
        }else result=immediate;
        Class.forName("kotlin.ResultKt",true,loader)
            .getMethod("throwOnFailure",Object.class).invoke(null,result);
        if(!(result instanceof List<?>))throw new IllegalStateException("Search result was not List");
        List<?> matches=(List<?>)result;
        System.out.println("ORIGINAL_CINESTREAM_SEARCH_RESULT_COUNT: "+matches.size());
        for(Object item:matches.subList(0,Math.min(matches.size(),3))){
            try {
                System.out.println("ORIGINAL_SEARCH_TITLE: "+shortText(
                    item.getClass().getMethod("getName").invoke(item),100));
            }catch(ReflectiveOperationException error){
                System.out.println("ORIGINAL_SEARCH_RECORD_CLASS: "+item.getClass().getName());
            }
        }
        if(matches.isEmpty())
            throw new IllegalStateException("Original CineStream search returned zero results");
        System.out.println("ORIGINAL_CINESTREAM_SEARCH_REAL_METADATA_SUCCESS");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("lib jar, provider jar, dep dir");
        List<URL> urls = new ArrayList<>();
        urls.add(Path.of(args[0]).toUri().toURL());
        urls.add(Path.of(args[1]).toUri().toURL());
        try (var deps = Files.list(Path.of(args[2]))) {
            deps.filter(f -> f.toString().endsWith(".jar")).forEach(f -> {
                try { urls.add(f.toUri().toURL()); } catch (MalformedURLException e) { throw new RuntimeException(e); }
            });
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> providerClass;
            try {
                providerClass=Class.forName("com.megix.CineStreamProvider", false, loader);
                System.out.println("ORIGINAL_PROVIDER_CLASS_LINKED: " + providerClass.getName());
            } catch (Throwable failure) {
                System.out.println("ORIGINAL_PROVIDER_CLASS_BLOCKED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                return;
            }
            Class<?> mainApi;
            try {
                mainApi=Class.forName("com.lagradost.cloudstream3.MainAPI", false, loader);
            } catch (Throwable failure) {
                System.out.println("OFFICIAL_JVM_MAIN_API_BLOCKED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                return;
            }
            System.out.println("REAL_MAIN_API_PARENT: " + mainApi.isAssignableFrom(providerClass));
            try {
                Object instance=providerClass.getDeclaredConstructor().newInstance();
                for (String getter : List.of("getName", "getMainUrl")) {
                    try {
                        Method m=providerClass.getMethod(getter);
                        System.out.println("ORIGINAL_PROVIDER_" + getter + ": " + shortText(m.invoke(instance),100));
                    } catch (NoSuchMethodException e) {
                        System.out.println("ORIGINAL_PROVIDER_MISSING_GETTER: " + getter);
                    }
                }
                System.out.println("ORIGINAL_CINESTREAM_PROVIDER_METADATA_EXECUTED");
                // Run a real metadata request via the original Kotlin provider.
                // The corresponding GitHub job has no credentials or secrets.
                try {
                    tryOriginalSearch(providerClass,instance,loader);
                }catch(Throwable failure){
                    Throwable root=failure;
                    while(root instanceof InvocationTargetException && root.getCause()!=null)
                        root=root.getCause();
                    System.out.println("ORIGINAL_CINESTREAM_SEARCH_FAILED: "+
                        root.getClass().getName()+": "+shortText(root.getMessage(),200));
                    System.exit(4);
                }
            } catch (Throwable failure) {
                Throwable root=failure;
                while(root instanceof InvocationTargetException && root.getCause()!=null)root=root.getCause();
                System.out.println("ORIGINAL_PROVIDER_CONSTRUCTOR_BLOCKED: " + root.getClass().getName() + ": " + shortText(root.getMessage(),200));
                System.exit(3);
            }
        }
    }
}
