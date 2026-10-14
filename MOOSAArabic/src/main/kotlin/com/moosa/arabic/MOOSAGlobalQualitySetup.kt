package com.moosa.arabic

import com.lagradost.cloudstream3.utils.Qualities

/**
 * Recovery for a v62 regression.
 *
 * v62 changed CloudStream-wide source priorities and enabled the
 * "hide negative sources" option without backing up prior values. Those
 * settings survive plugin updates, so simply reverting the Hub cannot fix
 * hidden sources on devices where the policy was applied.
 *
 * This recovery NEVER enables that policy. Once, it restores the host app's
 * default quality priorities on profiles touched by v62, clears negative
 * Download-name priorities set by v62 and disables HideNegativeSources.
 *
 * It cannot recover personalized values that v62 overwrote, because v62
 * did not save a snapshot. The user can customize profiles again.
 */
internal object MOOSAGlobalQualitySetup {
    private const val REPAIRED_VERSION = -1

    @Volatile
    var lastResult: String = "Recovery pending"
        private set

    @Synchronized
    fun restoreV62PrioritiesIfNeeded(): Boolean {
        if (MOOSASettingsStore.globalQualityPolicyVersion() == REPAIRED_VERSION) {
            lastResult = "Priorities restored"
            return true
        }

        return try {
            val klass = Class.forName(
                "com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper"
            )
            val helper = klass.getField("INSTANCE").get(null)
            val getQuality = klass.methods.first {
                it.name == "getQualityPriority" && it.parameterCount == 2
            }
            val setQuality = klass.methods.first {
                it.name == "setQualityPriority" && it.parameterCount == 3
            }
            val setProfile = klass.methods.first {
                it.name == "setProfileSetting" && it.parameterCount == 3
            }
            val namesMethod = klass.methods.firstOrNull {
                it.name == "getAllSourcePriorityNames" && it.parameterCount == 1
            }
            val getSource = klass.methods.firstOrNull {
                it.name == "getSourcePriority" && it.parameterCount == 2
            }
            val setSource = klass.methods.firstOrNull {
                it.name == "setSourcePriority" && it.parameterCount == 3
            }
            val hideSettings = Class.forName(
                "com.lagradost.cloudstream3.ui.player.source_priority." +
                    "ProfileSettings\$HideNegativeSources"
            ).getField("INSTANCE").get(null)

            val v62Applied = MOOSASettingsStore.globalQualityPolicyVersion() >= 1
            val modifiedProfiles = mutableSetOf<Int>()
            var restored = 0

            for (profile in 1..7) {
                for (q in Qualities.values()) {
                    val current = (getQuality.invoke(helper, profile, q) as? Number)
                        ?.toInt()
                    val changedByV62 =
                        v62Applied ||
                            (q.value < 720 && current == -100)
                    if (changedByV62) {
                        // If v62 only partly ran, restore each suspicious
                        // -100 priority without overriding other preferences.
                        setQuality.invoke(helper, profile, q, q.defaultPriority)
                        modifiedProfiles += profile
                        restored++
                    }
                }
            }

            // v62 explicitly enabled "Hide negative sources" across all 7
            // profiles. Clear it only on profiles actually touched by v62.
            for (profile in modifiedProfiles) {
                setProfile.invoke(helper, profile, hideSettings, false)
            }

            if (v62Applied && getSource != null &&
                setSource != null && namesMethod != null
            ) {
                for (profile in 1..7) {
                    val names = (namesMethod.invoke(helper, profile) as? List<*>)
                        ?.filterIsInstance<String>().orEmpty()
                    for (name in names) {
                        if (!MOOSAQualityPolicy.isDownloadName(name)) continue
                        val priority = (getSource.invoke(helper, profile, name)
                            as? Number)?.toInt()
                        if (priority == -100) {
                            // Native default source weight. No backup existed.
                            setSource.invoke(helper, profile, name, 1)
                        }
                    }
                }
            }

            MOOSASettingsStore.setGlobalQualityPolicyVersion(REPAIRED_VERSION)
            lastResult = "V62 host quality overrides reset ($restored values)"
            true
        } catch (error: Throwable) {
            lastResult = "Automatic recovery unavailable: " +
                error.javaClass.simpleName
            false
        }
    }
}
