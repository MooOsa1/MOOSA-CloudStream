package android.content;
/** Read-only linkage interface; the headless host has no Android preferences. */
public interface SharedPreferences {
    String getString(String key,String defaultValue);
}
