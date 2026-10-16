package com.lagradost.cloudstream3;

import android.content.Context;

/**
 * JVM-only compatibility for CloudStreamApp Android preferences.
 * Always return null context and null secrets/config. No UI, no cookies,
 * no filesystem access, no attempts to emulate an Android device.
 */
public final class CloudStreamApp {
    public static final Companion Companion = new Companion();
    private CloudStreamApp() {}
    public static final class Companion {
        public Context getContext() { return null; }
        public <T> T getKeyClass(String path,Class<T> type) { return null; }
        public <T> void setKeyClass(String path,T value) {
            throw new UnsupportedOperationException("Read-only headless preferences");
        }
    }
}
