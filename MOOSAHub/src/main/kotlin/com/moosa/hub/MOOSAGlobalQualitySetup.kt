package com.moosa.hub

import com.lagradost.cloudstream3.utils.Qualities

/**
 * Applies the user's requested quality floor to the HOST APP as well.
 *
 * MOOSA Hub can filter links from ANY provider routed through Hub.loadLinks.
 * Independent plugin providers invoked directly do not call Hub.loadLinks.
 * CloudStream's own per-profile Source Priority is therefore the supported
 * app-wide route: use negative quality priorities + HideNegativeSources=true.
 *
 * Reflection avoids a compile-time dependency on CloudStream's Android app
 * source-priority package (only the public plugin/library API is on the
 * build classpath). No Media3/player interception or TLS bypass is used.
 */
internal object MOOSAGlobalQualitySetup {
    private const val VERSION = 1

    @Volatile
    var lastResult: String = "Not applied"
        private set

    @Synchronized
    fun applyIfNeeded(force: Boolean = false): Boolean {
        if (!force && MOOSASettingsStore.globalQualityPolicyVersion() >= VERSION) {
            lastResult = "Applied to CloudStream profiles"
            return true
        }

        return try {
            val helperClass = Class.forName(
                "com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper"
            )
            val helper = helperClass.getField("INSTANCE").get(null)
            val qualitySetter = helperClass.methods.first {
                it.name == "setQualityPriority" && it.parameterCount == 3
            }
            val settingsSetter = helperClass.methods.first {
                it.name == "setProfileSetting" && it.parameterCount == 3
            }

            // CloudStream keeps Wi-Fi, data and download quality profiles;
            // profiles 1..7 are defined by the host app.
            val hideClass = Class.forName(
                "com.lagradost.cloudstream3.ui.player.source_priority." +
                    "ProfileSettings\$HideNegativeSources"
            )
            val hideNegative = hideClass.getField("INSTANCE").get(null)
            val sourceSetter = helperClass.methods.firstOrNull {
                it.name == "setSourcePriority" && it.parameterCount == 3
            }
            val sourceNames = helperClass.methods.firstOrNull {
                it.name == "getAllSourcePriorityNames" && it.parameterCount == 1
            }

            for (profile in 1..7) {
                for (q in Qualities.values()) {
                    val priority = when {
                        q.value >= 2160 -> 8
                        q.value >= 1080 -> 6
                        q.value >= 720 -> 4
                        // Very negative so any normal source weight still
                        // produces a negative total -> hide in CloudStream.
                        else -> -100
                    }
                    qualitySetter.invoke(helper, profile, q, priority)
                }

                settingsSetter.invoke(helper, profile, hideNegative, true)

                // Known download-named source entries can also be excluded
                // via the host app. Newly discovered names are always
                // filtered in Hub; unknown standalone names cannot be
                // intercepted by any independent plugin.
                val configuredNames = runCatching {
                    (sourceNames?.invoke(helper, profile) as? List<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                }.getOrDefault(emptyList())

                for (name in configuredNames) {
                    if (MOOSAQualityPolicy.isDownloadName(name)) {
                        sourceSetter?.invoke(helper, profile, name, -100)
                    }
                }
            }

            MOOSASettingsStore.setGlobalQualityPolicyVersion(VERSION)
            lastResult = "Applied 4K > 1080p > 720p; SD hidden in all profiles"
            true
        } catch (error: Throwable) {
            lastResult = "Host-wide setting unavailable: " +
                error.javaClass.simpleName
            false
        }
    }
}
