package com.phisher98

import com.lagradost.cloudstream3.metaproviders.TmdbProvider

class StreamPlay : TmdbProvider() {
    override var name = "StreamPlay"
    override var lang = "en"

    override val useMetaLoadResponse = true
    override val apiName = "TMDB"
    override val disableSeasonZero = true
    override val includeAdult = false
}
