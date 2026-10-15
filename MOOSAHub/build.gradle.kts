import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 79


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
    description = "Global movies and series with Arabic subtitles; Arabic originals in separate MOOSA Arabic plugin"
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

// v71: preserve the hidden SHOW MORE list and selected item until the user
// returns from a film; suppress lifecycle-driven popup on screensaver;
// direct DPAD_DOWN into visible row posters and remove title-count clutter.

// v70: close SHOW MORE before playback instead of restoring it on TV resume;
 // coalesce layout observer updates, remove duplicate DPAD_CENTER handling,
 // use a neutral white focus bar and black browse dialog; smaller paging.

// v68: familiar catalog titles in English, genuine Arabic-script metadata,
// exact Arabic season/episode search, Akwam episode-number repair, real
// TopCinema pagination, bounded bridge search, parallel Arabic fallback.

// v72: stable directional focus on SHOW MORE, correct More Info navigation,
// validated non-empty subtitles, official Arabic overviews, broader Indian catalog.

// v73: stable standalone SHOW MORE button unaffected by CloudStream
// rebinds, and vertical focus restoration for offscreen previous rows.

// v74: keep CloudStream's native title focus anchor and delegate remote OK
// to the separate SHOW MORE action. Fixes v73 unclickable TV focus.

// v75: a compact arrow-only SHOW MORE control that reveals the label on
// remote focus; retains v74 button activation and directional focus.

// v76: 190ms SHOW MORE focus-expand and 150ms blur-collapse animations;
// preserves v75 arrow, v74 click activation and fixed D-pad routing.

// v77: restore meaningful Indian top-rated film filters (200+ TMDB votes,
// rating >= 7), three sorted pages; Arabic plots from TMDB first, external
// translation of synopsis alone when not available. Native titles unchanged.

// v78: Trending Indian Movies directly above Top Indian Movies, ranked by
// current TMDB popularity among quality-screened releases from the past
// 24 months. Existing Indian Top ranking and other sections remain separate.

// v79: expand recent Indian trending coverage and improve browse pagination.
