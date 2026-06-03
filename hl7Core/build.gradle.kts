plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.rite.hl7"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Pin Kotlin + Java to JVM 17 to match the :app module (jvmToolchain(17)) and the
// project's enforced JDK 17. Previously Java was set to 21 while Kotlin defaulted to
// 17, which fails the Kotlin/Java JVM-target consistency check.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
