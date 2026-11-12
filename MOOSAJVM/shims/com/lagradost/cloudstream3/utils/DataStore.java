package com.lagradost.cloudstream3.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/**
 * JVM test-only settings ABI: without an actual Android Context there is
 * no persistent preference store. No privileged account/host/auth state is
 * synthesized, loaded or saved. Call sites may use defaults only.
 */
public final class DataStore {
    public static final DataStore INSTANCE=new DataStore();
    private DataStore(){}
    public SharedPreferences getSharedPrefs(Context context){
        throw new UnsupportedOperationException("Android SharedPreferences unavailable");
    }
    public <T> T getKey(Context context,String path,Class<T> valueType){return null;}
    public <T> T getKey(Context context,String path,T defaultValue){return defaultValue;}
    public <T> T getKey(Context context,String folder,String path,T defaultValue){return defaultValue;}
    public <T> void setKey(Context context,String path,T value){
        throw new UnsupportedOperationException("Android settings writes disabled");
    }
    public List<String> getKeys(Context context,String folder){return List.of();}
}
