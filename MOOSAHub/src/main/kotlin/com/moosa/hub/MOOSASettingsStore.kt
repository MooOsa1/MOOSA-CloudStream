package com.moosa.hub

import android.content.Context
import android.content.SharedPreferences

object MOOSASettingsStore {
    private const val PREF_FILE = "MOOSAHub"
    private const val KEY_CONCURRENCY = "provider_concurrency"
    private const val KEY_DISABLED_SOURCES = "disabled_sources"
    private const val KEY_VERIFY_LINKS = "verify_links"
    private const val KEY_ARABIC_FAST = "arabic_fast_mode"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(
            PREF_FILE,
            Context.MODE_PRIVATE
        )
    }

    fun providerConcurrency(): Int =
        prefs?.getInt(KEY_CONCURRENCY, 8)?.coerceIn(2, 16) ?: 8

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
}
