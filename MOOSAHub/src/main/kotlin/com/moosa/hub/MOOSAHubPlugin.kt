package com.moosa.hub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAHubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MOOSAHubProvider())
    }
}
