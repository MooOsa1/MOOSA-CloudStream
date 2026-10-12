package com.moosa.hub

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAHubPlugin : BasePlugin() {
    override fun load() {
        forceTvLayoutOnce()
        MOOSAProgress.setArabicSubtitleDefault()
        registerMainAPI(MOOSAHubProvider())
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

    private fun forceTvLayoutOnce() {
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

        updateCloudStreamLayout(activity)

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
