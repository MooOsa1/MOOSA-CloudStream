package com.lagradost.cloudstream3.network;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * JVM test shim ONLY. Deliberately no Android WebView, session cookies,
 * Cloudflare circumvention, custom Referer or hidden authentication.
 * Existing ordinarily permitted HTTP requests pass unchanged. If upstream
 * returns 403 or 503 this interceptor does NOT attempt to get around it.
 * This is a limited symbol-compatibility substitute, not the original
 * Android CloudflareKiller implementation.
 */
public final class CloudflareKiller implements Interceptor {
    private final Map<String,Map<String,String>> savedCookies=new ConcurrentHashMap<>();

    @Override public Response intercept(Interceptor.Chain chain) throws IOException {
        return chain.proceed(chain.request());
    }

    public Map<String,Map<String,String>> getSavedCookies(){return savedCookies;}

    public Headers getCookieHeaders(String url){
        return new Headers.Builder().build();
    }
}
