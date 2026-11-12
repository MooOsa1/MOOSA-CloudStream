import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/**
 * Isolated CI-only smoke: instantiate the ORIGINAL signed .cs3 CineStreamProvider
 * converted to JVM bytecode and call non-networking provider metadata getters.
 * Does not open any movie links or download videos.
 */
public final class ProviderInstanceProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("libraryJar pluginJar required");
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()},
            ClassLoader.getSystemClassLoader())) {
            final String name = "com.megix.CineStreamProvider";
            try {
                Class<?> type = Class.forName(name, true, loader);
                Constructor<?> constructor = type.getDeclaredConstructor();
                Object provider = constructor.newInstance();
                System.out.println("JVM_PROVIDER_INSTANTIATION status=success type=" + name);
                for(String getter : new String[]{"getName","getMainUrl","getSupportedTypes"}) {
                    try{
                        Object value=type.getMethod(getter).invoke(provider);
                        System.out.println("JVM_PROVIDER_GETTER status=success method="+getter+" result="+
                            String.valueOf(value).replaceAll("[\r\n\\t]"," ").substring(0,
                            Math.min(160,String.valueOf(value).length())));
                    }catch(Throwable e){
                        Throwable c=e instanceof InvocationTargetException ite?ite.getTargetException():e;
                        System.out.println("JVM_PROVIDER_GETTER status=error method="+getter+
                            " exception="+c.getClass().getName()+" cause="+safe(c));
                    }
                }
            } catch (Throwable e) {
                Throwable c=e instanceof InvocationTargetException ite?ite.getTargetException():e;
                System.out.println("JVM_PROVIDER_INSTANTIATION status=error type="+name+
                    " exception="+c.getClass().getName()+" cause="+safe(c));
            }
        }
    }
    private static String safe(Throwable t){
        String s=String.valueOf(t.getMessage());
        return s.replaceAll("[^\u0020-\u007E]","_").substring(0,Math.min(160,s.length()));
    }
}
