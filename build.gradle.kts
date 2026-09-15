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

    // Dependency Analysis (dead-code gate) -- declared directly here, not
    // injected, because the `app` module applies AGP. See ops-rite-android-
    // pipeline README's "Dead code on Android modules" for why. Keep this
    // version in sync with DAGP_VERSION in that pipeline's push.yml.
    id("com.autonomousapps.dependency-analysis") version "1.33.0" apply false
}