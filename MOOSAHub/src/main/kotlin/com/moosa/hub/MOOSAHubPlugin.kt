package com.moosa.hub

import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAHubPlugin : BasePlugin() {
    override fun load() {
        // Arabic is the default subtitle language for MOOSA Hub playback.
        setKey("subs_auto_select", "ar")
        registerMainAPI(MOOSAHubProvider())
    }
}
