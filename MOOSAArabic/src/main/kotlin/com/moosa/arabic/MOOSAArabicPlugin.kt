package com.moosa.arabic

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAArabicPlugin : BasePlugin() {
    override fun load() {
        // Exactly one native Arabic provider. Search, metadata and playback
        // all come from Akwam's own pages and API, not the MOOSA Hub resolver.
        registerMainAPI(MOOSAArabicProvider())
    }
}
