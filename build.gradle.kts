plugins {
    // Android & Kotlin base plugins
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false

    // Dagger Hilt for dependency injection
    id("com.google.dagger.hilt.android") version "2.51" apply false

    // Kotlin Serialization (for Retrofit or Ktor)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.10" apply false

    // Google Services for Firebase
    id("com.google.gms.google-services") version "4.4.2" apply false
}