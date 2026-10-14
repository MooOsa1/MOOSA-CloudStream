package com.moosa.hub

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.moosa.hub.bundled.akwam.AkwamProvider
import com.moosa.hub.bundled.arabseed.ArabSeedProvider
import com.moosa.hub.bundled.cimaclub.CimaClubProvider
import com.moosa.hub.bundled.topcinema.TopCinemaProvider
import com.moosa.hub.bundled.wecima.WeCimaProvider
import com.moosa.hub.bundled.shahidwbas.ShahidWBasProvider

@CloudstreamPlugin
class MOOSAHubPlugin : Plugin() {
    override fun load(context: Context) {
        MOOSASettingsStore.attach(context)
        forceTvLayoutOnce(context)
        MOOSAProgress.setArabicSubtitleDefault()
        openSettings = { ctx ->
            MOOSASettingsDialog.show(ctx)
        }
        registerMainAPI(MOOSAHubProvider())

        // v62 inadvertently hid all playable sources on some devices.
        // Recover the host-wide profile settings once, asynchronously.
        // Never automatically enforce global priority/hiding again.
        Thread(
            { MOOSAGlobalQualitySetup.restoreV62PrioritiesIfNeeded() },
            "MOOSA-v62-Playback-Recovery"
        ).start()
    }

    private fun currentActivity(): Activity? {
        return try {
            val common = Class.forName(
                "com.lagradost.cloudstream3.CommonActivity"
            )
            val instance = common.getField("INSTANCE").get(null)
            common.methods
                .firstOrNull {
                    it.name == "getActivity" && it.parameterCount == 0
                }
                ?.invoke(instance) as? Activity
        } catch (_: Throwable) {
            null
        }
    }

    private fun forceTvLayoutOnce(context: Context) {
        val activity = currentActivity() ?: return
        val prefs = activity.getSharedPreferences(
            "${activity.packageName}_preferences",
            Context.MODE_PRIVATE
        )
        val recoveryKey = "moosa_tv_layout_recovery_v18"

        if (prefs.getBoolean(recoveryKey, false)) return

        prefs.edit()
            .putInt("app_layout_key", 1)
            .putBoolean(recoveryKey, true)
            .apply()

        updateCloudStreamLayout(context)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                activity.recreate()
            } catch (_: Throwable) {
            }
        }, 450)
    }

    private fun updateCloudStreamLayout(context: Context) {
        try {
            val globals = Class.forName(
                "com.lagradost.cloudstream3.ui.settings.Globals"
            )
            val instance = globals.getField("INSTANCE").get(null)
            globals.methods
                .firstOrNull {
                    it.name == "updateTv" &&
                        it.parameterCount == 1
                }
                ?.invoke(instance, context)
        } catch (_: Throwable) {
            // Saved preference will still apply after restarting CloudStream.
        }
    }
}
