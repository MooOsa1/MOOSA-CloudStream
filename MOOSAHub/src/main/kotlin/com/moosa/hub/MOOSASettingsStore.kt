package com.moosa.hub

import android.content.Context
import android.content.SharedPreferences

object MOOSASettingsStore {
    private const val PREF_FILE = "MOOSAHub"
    private const val KEY_CONCURRENCY = "provider_concurrency"
    private const val KEY_DISABLED_SOURCES = "disabled_sources"
    private const val KEY_VERIFY_LINKS = "verify_links"
    private const val KEY_ARABIC_FAST = "arabic_fast_mode"
    private const val KEY_WYZIE_KEY = "wyzie_key"
    private const val KEY_SUBDL_KEY = "subdl_key"
    private const val KEY_DISABLED_SERVERS = "disabled_servers"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(
            PREF_FILE,
            Context.MODE_PRIVATE
        )
    }

    fun providerConcurrency(): Int =
        prefs?.getInt(KEY_CONCURRENCY, 10)?.coerceIn(2, 16) ?: 10

    fun setProviderConcurrency(value: Int) {
        prefs?.edit()?.putInt(KEY_CONCURRENCY, value.coerceIn(2, 16))?.apply()
    }

    fun disabledSources(): Set<String> =
        prefs?.getStringSet(KEY_DISABLED_SOURCES, emptySet())
            ?.toSet()
            .orEmpty()

    fun setDisabledSources(values: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_DISABLED_SOURCES, values.toSet())?.apply()
    }

    fun verifyLinks(): Boolean =
        prefs?.getBoolean(KEY_VERIFY_LINKS, true) ?: true

    fun setVerifyLinks(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_VERIFY_LINKS, enabled)?.apply()
    }

    fun arabicFastMode(): Boolean =
        prefs?.getBoolean(KEY_ARABIC_FAST, true) ?: true

    fun setArabicFastMode(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ARABIC_FAST, enabled)?.apply()
    }

    fun wyzieKey(): String? =
        prefs?.getString(KEY_WYZIE_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun setWyzieKey(value: String?) {
        prefs?.edit()?.putString(KEY_WYZIE_KEY, value?.trim().orEmpty())?.apply()
    }

    fun subdlKey(): String? =
        prefs?.getString(KEY_SUBDL_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun setSubdlKey(value: String?) {
        prefs?.edit()?.putString(KEY_SUBDL_KEY, value?.trim().orEmpty())?.apply()
    }

    fun disabledServers(): Set<String> =
        prefs?.getStringSet(KEY_DISABLED_SERVERS, emptySet())
            ?.toSet()
            .orEmpty()

    fun setDisabledServers(values: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_DISABLED_SERVERS, values.toSet())?.apply()
    }
}
