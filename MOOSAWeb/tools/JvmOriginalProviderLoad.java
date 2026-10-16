import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class JvmOriginalProviderLoad {
    static String brief(Object value,int max) {
        String s=String.valueOf(value);
        return s.substring(0,Math.min(s.length(),max)).replace('\n',' ');
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("lib jar, extension jar, runtime jars directory");
        List<URL> jars=new ArrayList<>();
        jars.add(Path.of(args[0]).toUri().toURL());
        jars.add(Path.of(args[1]).toUri().toURL());
        try(var stream=Files.list(Path.of(args[2]))) {
            stream.filter(p->p.toString().endsWith(".jar")).forEach(p->{
                try{jars.add(p.toUri().toURL());}
                catch(MalformedURLException e){throw new RuntimeException(e);}
            });
        }
        try(URLClassLoader loader=new URLClassLoader(jars.toArray(new URL[0]),ClassLoader.getPlatformClassLoader())) {
            Class<?> type=Class.forName("com.megix.CineStreamProvider",true,loader);
            Object provider=type.getDeclaredConstructor().newInstance();
            Class<?> continuationType=Class.forName("kotlin.coroutines.Continuation",false,loader);
            Object context=Class.forName("kotlin.coroutines.EmptyCoroutineContext",true,loader)
                .getField("INSTANCE").get(null);
            Class<?> kotlinEnums=Class.forName("kotlin.coroutines.intrinsics.CoroutineSingletons",true,loader);
            Object suspended=Enum.valueOf((Class)kotlinEnums.asSubclass(Enum.class),"COROUTINE_SUSPENDED");
            CountDownLatch done=new CountDownLatch(1);
            final Object[] resumed=new Object[1];
            Object continuation=java.lang.reflect.Proxy.newProxyInstance(
                loader,new Class<?>[]{continuationType},(proxy,method,values)->{
                    switch(method.getName()){
                        case "getContext": return context;
                        case "resumeWith": resumed[0]=values[0];done.countDown();return null;
                        case "toString":return "MOOSA metadata load continuation";
                        default:return null;
                    }
                });
            final String imdb="tt0816692",movieTitle="Interstellar";
            final String passData="{\"id\":\""+imdb+"\",\"type\":\"movie\"}";
            System.out.println("ORIGINAL_CINESTREAM_LOAD_INVOKED: "+imdb);
            Object result;
            try{
                Method load=type.getMethod("load",String.class,continuationType);
                result=load.invoke(provider,passData,continuation);
            }catch(InvocationTargetException e){
                Throwable root=e.getCause()!=null?e.getCause():e;
                System.out.println("ORIGINAL_LOAD_THREW: "+root.getClass().getName()+" "+brief(root.getMessage(),180));
                System.exit(21);return;
            }
            if(result==suspended){
                if(!done.await(35,TimeUnit.SECONDS)){
                    System.out.println("ORIGINAL_LOAD_TIMEOUT: metadata resolution stalled");
                    System.exit(22);return;
                }
                result=resumed[0];
            }
            try{
                Class.forName("kotlin.ResultKt",true,loader).getMethod("throwOnFailure",Object.class).invoke(null,result);
            }catch(InvocationTargetException e){
                Throwable root=e.getCause()!=null?e.getCause():e;
                System.out.println("ORIGINAL_LOAD_KOTLIN_FAILURE: "+root.getClass().getName()+" "+brief(root.getMessage(),180));
                System.exit(23);return;
            }
            if(result==null){System.out.println("ORIGINAL_LOAD_RETURNED_NULL");System.exit(24);return;}
            final String actual=String.valueOf(result.getClass().getMethod("getName").invoke(result));
            System.out.println("ORIGINAL_LOAD_RESULT_TITLE: "+brief(actual,120));
            boolean correct=actual.equalsIgnoreCase(movieTitle);
            System.out.println("ORIGINAL_LOAD_EXACT_MOVIE_MATCH: "+correct);
            if(!correct){System.exit(25);return;}
            System.out.println("ORIGINAL_CINESTREAM_LOAD_REAL_METADATA_SUCCESS");
        }
    }
}
