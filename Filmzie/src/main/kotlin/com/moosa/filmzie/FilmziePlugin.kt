package com.moosa.filmzie

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmziePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmzieProvider())
    }
}
