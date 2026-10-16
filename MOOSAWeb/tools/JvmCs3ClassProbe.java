import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * JVM dependency gate for a real converted CloudStream class.
 * It NEVER instantiates, initializes, or invokes downloaded plugin code.
 * Successful class linkage is NOT proof of playable source execution.
 */
public final class JvmCs3ClassProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: JvmCs3ClassProbe <label> <converted.jar> <plugin.class>");
            System.exit(2);
        }
        String label = args[0], klass = args[2];
        URL[] urls = {Path.of(args[1]).toAbsolutePath().toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, JvmCs3ClassProbe.class.getClassLoader())) {
            try {
                Class<?> found = Class.forName(klass, false, loader);
                System.out.println(label + " JVM_CLASS_LINKED: " + found.getName());
                System.out.println(label + " WARNING: no extension code executed; Android/CloudStream dependencies may fail later");
            } catch (NoClassDefFoundError missing) {
                System.out.println(label + " JVM_CLASS_BLOCKED_BY_MISSING_DEPENDENCY: " + missing.getMessage());
            } catch (ClassNotFoundException missing) {
                System.out.println(label + " JVM_ENTRY_CLASS_NOT_FOUND: " + missing.getMessage());
            } catch (UnsupportedClassVersionError | ClassFormatError invalid) {
                System.out.println(label + " JVM_CLASS_CONVERSION_INVALID: " + invalid.getMessage());
            } catch (LinkageError error) {
                System.out.println(label + " JVM_LINKAGE_ERROR: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
    }
}
