package com.moosa.movies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MOOSAMoviesProvider())
    }
}
