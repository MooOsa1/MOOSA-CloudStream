package com.moosa.movies

import com.lagradost.cloudstream3.metaproviders.TmdbProvider

class MOOSAMoviesProvider : TmdbProvider() {
    override var name = "MOOSA Movies"
    override var lang = "en"

    override val useMetaLoadResponse = true
    override val apiName = "TMDB"
    override val disableSeasonZero = true
    override val includeAdult = false
}
