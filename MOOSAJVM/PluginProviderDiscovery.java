import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Android-free, read-only provider inventory of a SHA256-verified original .cs3
 * converted to Java class files. No provider instantiation, no static initializer,
 * no video traffic, no cookies and no plugin code execution.
 *
 * IMPORTANT: class metadata discovery is not a security sandbox. Untrusted
 * provider execution must happen out-of-process under OS isolation and
 * outbound-network controls; NEVER load arbitrary extensions into Render Node.
 *
 * Each plugin archive has its own ClassLoader. A supported plugin may contain
 * multiple MainAPI providers, unlike the one-provider earlier smoke probes.
 */
public final class PluginProviderDiscovery {
    private static final int MAX_ARCHIVE_ENTRIES=12_000;
    private static final int MAX_PROVIDER_CLASSES=400;
    private static final int MAX_NAME_LENGTH=230;

    private static boolean safeClassName(String value){
        if(value==null||value.length()>MAX_NAME_LENGTH||value.contains(".."))return false;
        return value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }
    private static List<String> classNames(Path jar) throws IOException {
        List<String> names=new ArrayList<>();
        try(ZipFile zip=new ZipFile(jar.toFile())){
            if(zip.size()>MAX_ARCHIVE_ENTRIES)throw new IOException("archive_entry_limit");
            zip.stream().filter(e->!e.isDirectory()&&e.getName().endsWith(".class"))
                .forEach(e->{
                    String name=e.getName().substring(0,e.getName().length()-6)
                        .replace('/','.');
                    if(!name.contains("$")&&safeClassName(name))names.add(name);
                });
        }
        Collections.sort(names);
        return names;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException(
            "usage: PluginProviderDiscovery cloudstreamJVM.jar convertedPlugin.jar label");
        Path official=Path.of(args[0]),plugin=Path.of(args[1]);
        String label=args[2];
        if(!label.matches("[A-Za-z0-9_-]{1,40}"))throw new IllegalArgumentException("invalid_label");
        List<String> entries=classNames(plugin);
        try(URLClassLoader loader=new URLClassLoader(new URL[]{
                official.toUri().toURL(),plugin.toUri().toURL()
            },ClassLoader.getSystemClassLoader())){
            Class<?> mainApi=Class.forName("com.lagradost.cloudstream3.MainAPI",false,loader);
            int unresolved=0,providers=0,notConcrete=0;
            List<String> matches=new ArrayList<>();
            for(String className:entries){
                try{
                    Class<?> cls=Class.forName(className,false,loader);
                    if(!mainApi.isAssignableFrom(cls)||cls==mainApi)continue;
                    if(cls.isInterface()||Modifier.isAbstract(cls.getModifiers())){
                        notConcrete++;
                        continue;
                    }
                    if(matches.size()<MAX_PROVIDER_CLASSES)matches.add(cls.getName());
                    providers++;
                }catch(LinkageError|ClassNotFoundException|SecurityException e){
                    unresolved++;
                }
            }
            matches.sort(Comparator.naturalOrder());
            System.out.println("CS3_JVM_DISCOVERY label="+label+
                " scanned="+entries.size()+" concreteProviders="+providers+
                " abstractProviders="+notConcrete+" unresolved="+unresolved);
            for(String name:matches){
                System.out.println("CS3_JVM_PROVIDER label="+label+" name="+name);
            }
            if(providers>MAX_PROVIDER_CLASSES)throw new IllegalStateException("provider_limit");
        }
    }
}
