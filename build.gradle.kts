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
    // pipeline README's "Dead code on Android modules" for why.
    //
    // Version is intentionally NOT synced to the pipeline's DAGP_VERSION
    // (1.33.0): that pin exists only for the pipeline's init-script
    // injection path (needs 1.x's autoapply escape hatch), which doesn't
    // apply here since this repo declares DAGP directly. 1.33.0's bundled
    // kotlin-metadata-jvm reader tops out at Kotlin metadata 2.1.0 and can't
    // parse this project's Kotlin 2.3.20 output ("Provided Metadata instance
    // has version 2.3.0, while maximum supported version is 2.1.0"). 3.19.1
    // requires AGP >= 8.10.0 (we're on 8.11.0) and reads newer Kotlin
    // metadata. Safe to bump independently of the pipeline's pin going
    // forward -- just check the changelog for breaking changes to the
    // `dependencyAnalysis { issues { ... } }` DSL the pipeline's init script
    // configures via DAGP_SEVERITY.
    id("com.autonomousapps.dependency-analysis") version "3.19.1"
}