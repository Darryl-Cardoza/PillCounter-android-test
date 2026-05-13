// settings.gradle.kts or root build.gradle.kts

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false

    // Hilt 2.51 → 2.56 required for Kotlin 2.1.0 compatibility
    id("com.google.dagger.hilt.android") version "2.56" apply false

    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false

    // ADD — required for Compose with Kotlin 2.0+
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false

    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}