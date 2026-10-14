package com.moosa.arabic

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MOOSAArabicPlugin : Plugin() {
    override fun load(context: Context) {
        MOOSASettingsStore.attach(context)
        openSettings = { ctx -> MOOSASettingsDialog.show(ctx) }
        // Internal Arabic sources do not appear as separate providers.
        registerMainAPI(MOOSAArabicProvider())
    }
}
