version = 16

cloudstream {
    language = "en"
    description = "English catalog, Arabic synopsis, and multi-source playback"
    authors = listOf("MOOSA")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=%size%"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
