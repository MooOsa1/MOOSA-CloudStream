package com.megix.settings;

import java.util.List;
import java.util.Locale;

/**
 * Experiment-only JVM configuration adapter for an original CineStream .cs3.
 * Intentionally shadows the Android Settings UI/storage object so a *single*
 * openly documented HTTP provider can be exercised on a licensed test film.
 *
 * Not a general-purpose implementation of CloudStream settings.
 * Never stores authentication, passwords, third-party cookies, DRM keys
 * or anti-bot sessions. No parallel provider fanout is permitted.
 */
public final class Settings {
    public static final Settings INSTANCE = new Settings();
    private Settings() {}

    // Only the one named original provider is enabled. No torrents or bypasses.
    public List<String> getActiveProviderOrder() {
        return List.of("p_vaplayer");
    }
    public int getConcurrency() { return 1; }
    public boolean getAllowDownloadLinks() { return false; }
    public boolean cloudflareBypassEnabled() { return false; }
    public boolean hasCloudflareBypassForUrl(String url) { return false; }
    public String getCookieForDomain(String url) { return null; }
    public String getShowboxToken() { return null; }
    public String getWyzieSubsKey() { return null; }
    public String getGramCinemaToken() { return null; }
    public List<StremioAddon> getStremioAddons() { return List.of(); }
    public String stremioAddonKey(String name) {
        return "stremio_" + name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    public enum AddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }
    public static final class StremioAddon {
        private final String name, url;
        private final AddonType type;
        public StremioAddon(String name,String url,AddonType type){
            this.name=name;this.url=url;this.type=type;
        }
        public String getName(){return name;}
        public String getUrl(){return url;}
        public AddonType getType(){return type;}
    }
}
