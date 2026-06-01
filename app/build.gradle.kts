@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    alias(libs.plugins.androidx.room)
}

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "com.rite.pillcounting"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rite.pillcounting"
        minSdk = 29
        targetSdk = 36
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

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
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

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}

configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

configurations.all {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
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
    releaseImplementation(libs.compose.ui.tooling.preview)
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

    // OpenCV — tray color detection
    implementation("org.opencv:opencv:4.10.0")

    implementation(project(":hl7Core"))
}
