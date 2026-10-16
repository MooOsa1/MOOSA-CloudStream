package com.lagradost.cloudstream3.network;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * Headless compatibility ONLY for the Android CloudflareKiller class.
 *
 * This implementation cannot solve Cloudflare challenges or use WebView,
 * CookieManager, Android Context, or device identities. It merely allows
 * ordinary HTTP requests to be passed through by the genuine CineStream
 * Kotlin code. Failures such as HTTP 403 remain failures.
 *
 * No credentials, cookies, headers or bypass tokens are manufactured.
 */
public final class CloudflareKiller implements Interceptor {
    private final Map<String, Map<String,String>> savedCookies = new ConcurrentHashMap<>();

    public CloudflareKiller() {}

    public Map<String, Map<String,String>> getSavedCookies() {
        return savedCookies;
    }

    public Headers getCookieHeaders(String url) {
        return new Headers.Builder().build();
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        // No retry, bypass, WebView or credential forwarding.
        return chain.proceed(chain.request());
    }
}
