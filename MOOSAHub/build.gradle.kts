import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 68


android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

cloudstream {
    language = "en"
    description = "English catalog, Arabic synopsis, and multi-source playback"
    authors = listOf("MOOSA")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=%size%"
    isCrossPlatform = false
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.android.material:material:1.14.0")
}

// v66: real TMDB original names, correct MySeed Arabic episode/season indexing, HD Ajax discovery and verified fallback, local Downet CDN speed samples

// v67: request each ArabSeed HD server through its real get__watch__server AJAX endpoint rather than stale SD data-link

// v68: familiar catalog titles in English, genuine Arabic-script metadata,
// exact Arabic season/episode search, Akwam episode-number repair, real
// TopCinema pagination, bounded bridge search, parallel Arabic fallback.
