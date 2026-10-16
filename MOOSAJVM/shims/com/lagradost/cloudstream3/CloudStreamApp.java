package com.lagradost.cloudstream3;

import android.content.Context;

/**
 * Standalone JVM test replacement for Android Application preference access.
 * Returns NULL / defaults only: no Android Context, filesystem preferences,
 * cookies, accounts, secrets, or protected-service credentials.
 *
 * This is an ABI diagnostic shim, not a CloudStream app implementation.
 */
public final class CloudStreamApp {
    public static final Companion Companion=new Companion();
    public static final class Companion {
        public Context getContext(){return null;}
        public <T> T getKeyClass(String key,Class<T> type){return null;}
        public <T> void setKeyClass(String key,T value){
            throw new UnsupportedOperationException("Android preferences disabled");
        }
        public <T> void setKey(String key,T value){
            throw new UnsupportedOperationException("Android preferences disabled");
        }
        public <T> void setKey(String folder,String key,T value){
            throw new UnsupportedOperationException("Android preferences disabled");
        }
        public void removeKey(String key){
            throw new UnsupportedOperationException("Android preferences disabled");
        }
    }
}
