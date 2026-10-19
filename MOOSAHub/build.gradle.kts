import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 116
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
    description = "Global movies, series and anime with native anime episodes and Arabic subtitles; Arabic originals in MOOSA Arabic"
    authors = listOf("MOOSA")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=%size%"
    isCrossPlatform = false
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
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

// v80: restore subtitle discovery, validate more real CDN alternatives in parallel
// with video search, and keep Arabic/English subtitles even on slower links.

// v81: explicit ar/en tracks only at playback; probe more alternate subtitle
// files and pass native provider headers during content validation.

// v82: progressive subtitle delivery, minimal verification I/O, and bounded
// subtitle finalization even while streams finish resolving.

// v83: preserve original subtitle release type/FPS and show each verified
// Arabic/English version separately for manual WEB-DL/WEBRip matching.

// v84: reuse recently verified subtitle content, use the legacy RPC only
// as last resort, and provide on-demand native SubSource guidance.

// v85: validate player-compatible subtitle timestamps; signal correct WEBVTT
// MIME for extensionless SubDL files and refuse unreadable source formats.

// v86: official SubDL lang-field compatibility and on-device subtitle-source
// diagnostics; no extra network requests or automatic ZIP downloads.

// v87: release each provider's subtitle candidates independently; inspect
// further releases only if an initial bounded text check finds too few tracks.

// v88: provide fair source-specific subtitle probe slots within global four,
// count SubDL index-to-candidate exclusions and separate queued/started checks.

// v89: classify SubDL responses that contain no subtitle timestamps without
// recording raw text; reserve one of four subtitle probes for native tracks.

// v90: use the documented normal-GET contract for SubDL raw files and
// diagnose JSON response error categories (rate/auth/missing/unknown) safely.

// v91: avoid exhausting SubDL daily downloads while probing subtitle lists;
// stop on quota/rate errors and keep other subtitle sources available.

// v92: emit subtitle files from installed CloudStream repositories during
// matched provider load, with bounded checks and distinct source attribution.

// v93: group subtitle selector entries by language + actual subtitle source.
// Individual subtitle URLs remain distinct selectable tracks, no A/B/C labels.

// v94: query documented OpenSubtitles legacy backup for sparse Arabic results
// without waiting on other subtitle hosts or changing the grouped UI.

// v95: Show More real TMDB server-side genre/type/sort/origin filters with
// cancellable pagination, TV remote focus controls and original-shelf reset.

// v96: fix Android CloudStream runtime crash when changing Show More filters
// by avoiding coroutines Job.cancel default-argument synthetic method.

// v97: lightweight Android TV browse motion, deterministic DPAD focus,
// stable grid updates and bounded source-keyed page caching.

// v98: compact top drop-down filters with one applied-chip row, neutral
// CloudStream theme, draft-and-apply TMDB queries and stable Android TV focus.

// v99: contain TV remote focus inside modal top filter drawer, rename category
// from applied TMDB filters and replace Close label with compact × action.

// v100: reduce home scrolling work: no per-title image fetches on home,
// bound the Show More layout observer to idle, visible home rows.

// v101: opt-in HTTPS CloudStream Web Bridge supporting installed playback providers.
