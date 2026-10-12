package com.moosa.hub

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAHubPlugin : BasePlugin() {
    override fun load() {
        forceTvLayoutOnce()
        MOOSAProgress.setArabicSubtitleDefault()
        registerMainAPI(MOOSAHubProvider())
    }

    private fun forceTvLayoutOnce() {
        val appContext = context ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val recoveryKey = "moosa_tv_layout_recovery_v17"

        if (prefs.getBoolean(recoveryKey, false)) return

        prefs.edit()
            .putInt("app_layout_key", 1)
            .putBoolean(recoveryKey, true)
            .apply()

        updateCloudStreamLayout(appContext)

        Handler(Looper.getMainLooper()).postDelayed({
            recreateCurrentActivity()
        }, 450)
    }

    private fun updateCloudStreamLayout(appContext: Context) {
        try {
            val globals = Class.forName(
                "com.lagradost.cloudstream3.ui.settings.Globals"
            )
            val instance = globals.getField("INSTANCE").get(null)
            globals.methods
                .firstOrNull {
                    it.name == "updateTv" &&
                        it.parameterCount == 1 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                ?.invoke(instance, appContext)
        } catch (_: Throwable) {
            // The saved preference will still take effect on the next app launch.
        }
    }

    private fun recreateCurrentActivity() {
        try {
            val common = Class.forName(
                "com.lagradost.cloudstream3.CommonActivity"
            )
            val instance = common.getField("INSTANCE").get(null)
            val activity = common.methods
                .firstOrNull {
                    it.name == "getActivity" && it.parameterCount == 0
                }
                ?.invoke(instance) as? android.app.Activity

            activity?.recreate()
        } catch (_: Throwable) {
            // If recreation is unavailable, restarting CloudStream applies TV layout.
        }
    }
}
