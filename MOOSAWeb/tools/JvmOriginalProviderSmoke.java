import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Real original CineStreamProvider class + official CloudStream JVM library.
 * Executes only its no-argument constructor and safe metadata getters.
 * No Android app, media resolution, browser, DRM, or source claiming.
 * This experiment runs on an isolated GitHub runner without secrets.
 */
public final class JvmOriginalProviderSmoke {
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
                        System.out.println("ORIGINAL_PROVIDER_" + getter + ": " + String.valueOf(m.invoke(instance)).substring(0,100));
                    } catch (NoSuchMethodException e) {
                        System.out.println("ORIGINAL_PROVIDER_MISSING_GETTER: " + getter);
                    }
                }
                System.out.println("ORIGINAL_CINESTREAM_PROVIDER_METADATA_EXECUTED");
            } catch (Throwable failure) {
                Throwable root=failure;
                while(root instanceof InvocationTargetException && root.getCause()!=null)root=root.getCause();
                System.out.println("ORIGINAL_PROVIDER_CONSTRUCTOR_BLOCKED: " + root.getClass().getName() + ": " + String.valueOf(root.getMessage()).substring(0,200));
            }
        }
    }
}
