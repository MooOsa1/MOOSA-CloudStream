package com.moosa.hub

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object MOOSASettingsStore {
    private const val PREF_FILE = "MOOSAHub"
    private const val KEY_CONCURRENCY = "provider_concurrency"
    private const val KEY_DISABLED_SOURCES = "disabled_sources"
    private const val KEY_VERIFY_LINKS = "verify_links"
    private const val KEY_ARABIC_FAST = "arabic_fast_mode"
    private const val KEY_WYZIE_KEY = "wyzie_key"
    private const val KEY_SUBDL_KEY = "subdl_key"
    private const val KEY_DISABLED_SERVERS = "disabled_servers"
    private const val KEY_SOURCE_PROFILES = "source_profiles"
    private const val KEY_SERVER_PROFILES = "server_profiles"

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

    fun saveSourceProfile(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return

        val root = JSONObject(
            prefs?.getString(KEY_SOURCE_PROFILES, "{}") ?: "{}"
        )
        val array = JSONArray()
        disabledSources().sorted().forEach { array.put(it) }
        root.put(clean, array)
        prefs?.edit()?.putString(KEY_SOURCE_PROFILES, root.toString())?.apply()
    }

    fun sourceProfiles(): Map<String, Set<String>> {
        val raw = prefs?.getString(KEY_SOURCE_PROFILES, "{}") ?: "{}"
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }

        val result = linkedMapOf<String, Set<String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val array = root.optJSONArray(name) ?: continue
            val values = buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            result[name] = values
        }
        return result
    }

    fun loadSourceProfile(name: String): Boolean {
        val profile = sourceProfiles()[name] ?: return false
        setDisabledSources(profile)
        return true
    }

    fun deleteSourceProfile(name: String): Boolean {
        val raw = prefs?.getString(KEY_SOURCE_PROFILES, "{}") ?: "{}"
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
        if (!root.has(name)) return false
        root.remove(name)
        prefs?.edit()?.putString(KEY_SOURCE_PROFILES, root.toString())?.apply()
        return true
    }

    fun saveServerProfile(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return

        val root = JSONObject(
            prefs?.getString(KEY_SERVER_PROFILES, "{}") ?: "{}"
        )
        val array = JSONArray()
        disabledServers().sorted().forEach { array.put(it) }
        root.put(clean, array)
        prefs?.edit()?.putString(KEY_SERVER_PROFILES, root.toString())?.apply()
    }

    fun serverProfiles(): Map<String, Set<String>> {
        val raw = prefs?.getString(KEY_SERVER_PROFILES, "{}") ?: "{}"
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }

        val result = linkedMapOf<String, Set<String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val array = root.optJSONArray(name) ?: continue
            val values = buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            result[name] = values
        }
        return result
    }

    fun loadServerProfile(name: String): Boolean {
        val profile = serverProfiles()[name] ?: return false
        setDisabledServers(profile)
        return true
    }

    fun deleteServerProfile(name: String): Boolean {
        val raw = prefs?.getString(KEY_SERVER_PROFILES, "{}") ?: "{}"
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
        if (!root.has(name)) return false
        root.remove(name)
        prefs?.edit()?.putString(KEY_SERVER_PROFILES, root.toString())?.apply()
        return true
    }

}
