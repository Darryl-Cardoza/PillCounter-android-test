plugins {

    // Android Gradle Plugin
    id("com.android.application") version "8.11.0" apply false

    // Kotlin
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false

    // Hilt
    id("com.google.dagger.hilt.android") version "2.58" apply false

    // Google Services
    id("com.google.gms.google-services") version "4.4.4" apply false

    // Room
    id("androidx.room") version "2.7.2" apply false

    // KSP
    id("com.google.devtools.ksp") version "2.3.8" apply false

    // Dependency Analysis (dead-code gate) -- applied for real here (NOT
    // `apply false`), not injected by the pipeline, because both `app` and
    // `hl7Core` apply AGP. DAGP coordinates root + every analyzed module
    // through one classloader, so once any module needs it declared
    // directly, root must have a real application too, and the pipeline's
    // init script backs off for this whole repo. See ops-rite-android-
    // pipeline README's "Dead code on Android modules" for why. Keep this
    // version in sync with DAGP_VERSION in that pipeline's push.yml.
    id("com.autonomousapps.dependency-analysis") version "1.33.0"
}