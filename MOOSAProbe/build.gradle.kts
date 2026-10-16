version = 1

cloudstream {
    language = "en"
    description = "Disposable CloudStream runtime search/load/loadLinks test. NOT for publication."
    authors = listOf("MOOSA test suite")
    status = 0
    tvTypes = listOf("Movie")
    isCrossPlatform = false
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
