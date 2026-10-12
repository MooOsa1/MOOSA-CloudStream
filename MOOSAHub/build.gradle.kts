version = 11

cloudstream {
    language = "ar"
    description = "واجهة MOOSA تبحث في كل المصادر المثبتة داخل CloudStream"
    authors = listOf("MOOSA")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=%size%"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
