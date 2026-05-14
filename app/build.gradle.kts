plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.rite.pillcounting"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rite.pillcounting"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Inject version & server key as build fields
        resValue("string", "app_version_name", versionName ?: "")
        buildConfigField(
            "String",
            "BASE_URL",
            "\"https://pill.ccrlindia.com/\""
        )

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }



    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            enableUnitTestCoverage = true
//            isMinifyEnabled = true
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }

        jniLibs {
            useLegacyPackaging = false
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)  // Locks both Java and Kotlin to JVM 21 — no mismatch possible
}

ksp {
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}

configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

configurations.all {
    resolutionStrategy {
        force("com.google.mlkit:barcode-scanning:17.3.0")
    }
}

dependencies {
    // --- Compose BOM ---
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    // --- Core & Compose UI ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.graphics.path)
    implementation(libs.bundles.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- Lifecycle & ViewModel ---
    implementation(libs.bundles.lifecycle)

    // --- Coroutines ---
    // NOTE: Remove libs.kotlin.stdlib — Kotlin 2.x adds it automatically
    implementation(libs.bundles.coroutines)

    // --- Hilt DI ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // --- Room Database ---
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // --- Retrofit & Networking ---
    implementation(libs.bundles.networking)
    debugImplementation(libs.chucker.library)
    releaseImplementation(libs.chucker.library.no.op)

    // --- Firebase ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)

    // --- CameraX ---
    implementation(libs.bundles.camerax)

    // --- ML Kit ---
    implementation(libs.mlkit.barcode.scanning)

    // --- TensorFlow Lite ---
    implementation(libs.bundles.tensorflow)

    // --- Location ---
    implementation(libs.play.services.location)

    // --- Utilities ---
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.serialization.converter)
    implementation(libs.okhttp.profiler)
    implementation(libs.security.crypto)
    implementation(libs.calendar.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.json)

    // --- TLS / Local Server ---
    implementation(libs.bundles.bouncycastle)
    implementation(libs.nanohttpd)

    // --- Testing ---
    testImplementation(libs.bundles.test.unit)
    androidTestImplementation(libs.bundles.test.android)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
