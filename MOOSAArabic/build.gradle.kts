import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = 1

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

cloudstream {
    language = "ar"
    description = "أفلام ومسلسلات عربية من أقسام أكوام الأصلية، بدون TMDB أو مطابقة خارجية"
    authors = listOf("MOOSA")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=akwam.ss&sz=%size%"
    isCrossPlatform = false
}
dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
