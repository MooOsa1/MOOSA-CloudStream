package com.moosa.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MOOSAArchivePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(
            ArchiveCatalogProvider(
                providerName = "MOOSA Foreign Archive",
                providerLang = "en",
                languageFilter = "(language:(eng) OR language:(English))"
            )
        )
        registerMainAPI(
            ArchiveCatalogProvider(
                providerName = "MOOSA Arabic Archive",
                providerLang = "ar",
                languageFilter = "(language:(ara) OR language:(Arabic))"
            )
        )
        registerMainAPI(
            ArchiveCatalogProvider(
                providerName = "MOOSA Indian Archive",
                providerLang = "hi",
                languageFilter = "(language:(hin) OR language:(Hindi) OR language:(tam) OR language:(Tamil) OR language:(tel) OR language:(Telugu) OR language:(ben) OR language:(Bengali) OR language:(mal) OR language:(Malayalam))"
            )
        )
    }
}
