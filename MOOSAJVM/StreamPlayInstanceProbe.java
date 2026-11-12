import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Constructor;

/**
 * Evidence only: instantiate StreamPlay provider class from verified original
 * Android DEX converted to JVM. No repository code is trusted for production;
 * no third-party videos are requested or played.
 */
public final class StreamPlayInstanceProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("library and StreamPlay.jar");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {
            Path.of(args[0]).toUri().toURL(), Path.of(args[1]).toUri().toURL()
        }, ClassLoader.getSystemClassLoader())) {
            final String name = "com.phisher98.StreamPlay";
            try {
                Class<?> type=Class.forName(name,true,loader);
                System.out.println("STREAMPLAY_JVM_CLASS status=linked type="+name);
                Constructor<?> constructor=null;
                for (var c:type.getDeclaredConstructors()) {
                    if(c.getParameterCount()==0) {constructor=c;break;}
                }
                if(constructor==null){
                    for(var c:type.getDeclaredConstructors()){
                        if(c.getParameterCount()==1) {constructor=c;break;}
                    }
                }
                if(constructor==null)throw new NoSuchMethodException("zero_or_one_arg_constructor");
                Object instance=constructor.getParameterCount()==0?
                    constructor.newInstance():constructor.newInstance(new Object[]{null});
                System.out.println("STREAMPLAY_JVM_INSTANCE status=success");
                for (String method:new String[]{"getName","getSupportedTypes"}) {
                    try{
                        Object value=type.getMethod(method).invoke(instance);
                        System.out.println("STREAMPLAY_JVM_GETTER status=success method="+method+
                            " value="+String.valueOf(value).replaceAll("[\\r\\n]"," ").substring(
                            0,Math.min(String.valueOf(value).length(),180)));
                    }catch(Throwable e){
                        Throwable t=e instanceof java.lang.reflect.InvocationTargetException ite?
                            ite.getTargetException():e;
                        System.out.println("STREAMPLAY_JVM_GETTER status=error method="+method+
                            " exception="+t.getClass().getSimpleName()+
                            " dependency="+String.valueOf(t.getMessage()).replaceAll("[^a-zA-Z0-9_./ -]","_").substring(0,
                            Math.min(String.valueOf(t.getMessage()).length(),150)));
                    }
                }
            }catch(Throwable e){
                Throwable t=e instanceof java.lang.reflect.InvocationTargetException ite?
                    ite.getTargetException():e;
                System.out.println("STREAMPLAY_JVM_INSTANCE status=error exception="+t.getClass().getName()+
                    " dependency="+String.valueOf(t.getMessage()).replaceAll("[^a-zA-Z0-9_./$ -]","_").substring(
                        0,Math.min(String.valueOf(t.getMessage()).length(),180)));
            }
        }
    }
}
