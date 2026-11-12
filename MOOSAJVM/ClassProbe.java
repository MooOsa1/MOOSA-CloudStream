import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Passive compatibility probe for verified but untrusted CloudStream extension bytecode.
 *
 * Never instantiate plugin classes and never trigger a static initializer.
 * Linking/reflection catches missing Android/CloudStream/JVM runtime types.
 * The goal is reliable, falsifiable evidence — not pretending DEX conversion
 * means an extension is operational.
 */
public final class ClassProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: java ClassProbe <jvm-library.jar> <converted-plugin.jar> <provider> <entrypoint>");
            System.exit(2);
        }
        final String[] labels = {"official-cloudstream-library", "provider", "extension-entrypoint"};
        final String[] classes = {"com.lagradost.cloudstream3.MainAPI", args[2], args[3]};
        try (URLClassLoader loader = new URLClassLoader(new URL[] {
            Path.of(args[0]).toUri().toURL(),
            Path.of(args[1]).toUri().toURL()
        }, ClassLoader.getSystemClassLoader())) {
            for (int i = 0; i < classes.length; i++) {
                String name = classes[i];
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    System.out.println("JVM_CLASS_LINK status=linked label=" + labels[i] + " class=" + name);
                    try {
                        System.out.println("JVM_METHOD_SIGNATURES status=linked label=" + labels[i] +
                            " methods=" + type.getDeclaredMethods().length);
                    } catch (Throwable e) {
                        System.out.println("JVM_METHOD_SIGNATURES status=unresolved label=" + labels[i] +
                            " exception=" + e.getClass().getSimpleName() +
                            " dependency=" + safeMessage(e));
                    }
                } catch (Throwable e) {
                    System.out.println("JVM_CLASS_LINK status=unresolved label=" + labels[i] +
                        " class=" + name + " exception=" + e.getClass().getSimpleName() +
                        " dependency=" + safeMessage(e));
                }
            }
        }
    }
    private static String safeMessage(Throwable t) {
        String s = t.getMessage() == null ? "(unspecified)" : t.getMessage();
        return s.replaceAll("[^a-zA-Z0-9 ._/$:-]", "_").substring(0, Math.min(s.length(), 130));
    }
}
