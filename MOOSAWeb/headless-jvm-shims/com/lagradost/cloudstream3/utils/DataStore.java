package com.lagradost.cloudstream3.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Android-only DataStore facade used by Kotlin inline settings getters.
 * Returns NO Android preferences or credentials; untrusted plugins must not
 * read a user's local or cloud configuration from a headless process.
 */
public final class DataStore {
    public static final DataStore INSTANCE = new DataStore();
    private DataStore() {}
    public SharedPreferences getSharedPrefs(Context context){return null;}
    public <T> T getKey(Context context,String key,Class<T> type){return null;}
}
