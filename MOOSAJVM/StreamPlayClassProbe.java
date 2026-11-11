import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/** Passive original StreamPlay .cs3 JVM loading and constructor test.
 *  No search, network calls, video extraction, tokens, Android runtime, or UI.
 */
public final class StreamPlayClassProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("library.jar streamplay.jar");
        String typeName="com.phisher98.StreamPlay";
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
            Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()
        },ClassLoader.getSystemClassLoader())){
            try{
                Class<?> type=Class.forName(typeName,false,loader);
                System.out.println("JVM_STREAMPLAY_CLASS status=linked superclass="+
                    type.getSuperclass().getName());
                Object obj;
                try{
                    Class.forName(typeName,true,loader);
                    Constructor<?> ctor;
                    try{
                        ctor=type.getDeclaredConstructor();
                        obj=ctor.newInstance();
                    }catch(NoSuchMethodException noZero){
                        ctor=type.getDeclaredConstructor(
                            Class.forName("android.content.SharedPreferences",false,
                                ClassLoader.getSystemClassLoader()));
                        obj=ctor.newInstance(new Object[]{null});
                    }
                    System.out.println("JVM_STREAMPLAY_INSTANTIATION status=success");
                }catch(Throwable error){
                    Throwable cause=error instanceof InvocationTargetException ite?
                        ite.getTargetException():error;
                    System.out.println("JVM_STREAMPLAY_INSTANTIATION status=blocked cause="+
                        safe(cause));
                    return;
                }
                for(String key:new String[]{"getName","getSupportedTypes"}){
                    try{
                        Object value=type.getMethod(key).invoke(obj);
                        System.out.println("JVM_STREAMPLAY_GETTER method="+key+" status=success value="+
                            String.valueOf(value).replaceAll("[\\r\\n\\t]"," ").substring(
                                0,Math.min(120,String.valueOf(value).length())));
                    }catch(Throwable e){
                        System.out.println("JVM_STREAMPLAY_GETTER method="+key+" status=blocked reason="+safe(e));
                    }
                }
            }catch(Throwable e){
                System.out.println("JVM_STREAMPLAY_CLASS status=blocked cause="+safe(e));
            }
        }finally{System.exit(0);}
    }
    static String safe(Throwable e){
        Throwable c=e instanceof InvocationTargetException ite?ite.getTargetException():e;
        String s=String.valueOf(c.getMessage()).replaceAll("[^a-zA-Z0-9 ._/$:-]","_");
        return c.getClass().getSimpleName()+":"+s.substring(0,Math.min(145,s.length()));
    }
}
