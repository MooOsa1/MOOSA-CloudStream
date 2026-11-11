package android.content;

/**
 * Compatibility-only Android Context ABI. This is NOT an Android emulator,
 * contains no state, credentials, network bypass or UI support, and must
 * never be treated as a functioning Context.
 *
 * The test uses this class only to reveal the next real dependency boundary.
 * Any actual access to an Android service fails explicitly.
 */
public abstract class Context {
    public static final int MODE_PRIVATE=0;
    public static final int MODE_APPEND=32768;
    public SharedPreferences getSharedPreferences(String name,int mode){
        throw new UnsupportedOperationException("Android Context unavailable on JVM");
    }
    public Context getApplicationContext(){
        throw new UnsupportedOperationException("Android Context unavailable on JVM");
    }
    public String getPackageName(){
        throw new UnsupportedOperationException("Android Context unavailable on JVM");
    }
}
