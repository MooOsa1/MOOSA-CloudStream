package com.moosa.plex

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PlexFreePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PlexFreeProvider())
    }
}
