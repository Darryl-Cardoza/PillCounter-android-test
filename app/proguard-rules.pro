############################################
# GENERAL
############################################

-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn javax.**

-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes KotlinMetadata
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


############################################
# KOTLIN
############################################

# Fix Firebase DependencyCycleException
# Firebase checks CoroutineDispatcher class names internally
-keepnames class kotlinx.coroutines.**


############################################
# JETPACK COMPOSE
############################################

# Compose already ships consumer rules


############################################
# HILT / DAGGER — COMPLETE RULES
############################################

# Keep @HiltAndroidApp application class
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# Keep @AndroidEntryPoint annotated classes
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Keep @HiltViewModel annotated classes
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Keep all Hilt-generated components and entry points
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }
-keep class * implements dagger.hilt.android.internal.managers.ApplicationComponentManager.ComponentSupplier { *; }

# Keep generated _HiltComponents classes
-keep class **_HiltComponents { *; }
-keep class **_HiltComponents$* { *; }

# Keep generated Hilt entry point interfaces
-keep @dagger.hilt.EntryPoint interface * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep all generated Hilt module and binding classes
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }

# Keep Hilt's internal component tree wiring
-keep class dagger.hilt.internal.** { *; }
-keep class dagger.hilt.android.internal.** { *; }

# Keep aggregated deps (critical — these feed the component graph)
-keep @dagger.hilt.codegen.OriginatingElement class * { *; }
-keep @dagger.hilt.internal.aggregatedroot.AggregatedRoot class * { *; }
-keep @dagger.hilt.internal.definecomponent.DefineComponent class * { *; }
-keep @dagger.hilt.android.EarlyEntryPoint class * { *; }

# Keep generated component interfaces
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManagerHolder { *; }

# Keep Hilt ViewModel factory
-keep class androidx.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

-dontwarn dagger.**
-dontwarn javax.inject.**


############################################
# ROOM
############################################

-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}

-dontwarn androidx.room.**


############################################
# RETROFIT
############################################

-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Keep ONLY Retrofit API interfaces
-keep interface com.rite.pillcounting.data.remote.api.** { *; }

-dontwarn retrofit2.**


############################################
# MOSHI
############################################

-keep @com.squareup.moshi.JsonClass class * { *; }

-dontwarn com.squareup.moshi.**


############################################
# GSON
############################################

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-dontwarn com.google.gson.**


############################################
# KOTLINX SERIALIZATION
############################################

-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

-dontwarn kotlinx.serialization.**


############################################
# FIREBASE — COMPLETE COMPONENT RUNTIME FIX
############################################

# Keep ALL Firebase internals — component discovery uses reflection
-keep class com.google.firebase.** { *; }
-keep interface com.google.firebase.** { *; }

# Keep Google Play Services internals Firebase depends on
-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }

# Keep component registrars discovered via manifest meta-data
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
    public java.util.List getComponents();
}

# Prevent R8 from optimizing Firebase's component graph resolution.
# ComponentRuntime.discoverComponents() loads registrars by class name
# from manifest meta-data — R8 cannot see this call chain statically.
-keep class com.google.firebase.components.ComponentDiscovery { *; }
-keep class com.google.firebase.components.ComponentDiscoveryService { *; }
-keepclassmembers class com.google.firebase.components.ComponentRuntime {
    <init>(...);
    public void discoverComponents(...);
}
-keep class com.google.firebase.components.ComponentRuntime { *; }
-keep class com.google.firebase.components.Component { *; }
-keep class com.google.firebase.components.Dependency { *; }

# Keep registrars for each Firebase SDK in this project
-keep class com.google.firebase.messaging.FirebaseMessagingRegistrar { *; }
-keep class com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar { *; }
-keep class com.google.firebase.installations.FirebaseInstallationsRegistrar { *; }
-keep class com.google.firebase.datatransport.TransportRegistrar { *; }
-keep class com.google.android.datatransport.runtime.TransportRuntimeModule { *; }


############################################
# ML KIT
############################################

-keep class com.google.mlkit.** { *; }

-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }

-dontwarn com.google.mlkit.**


############################################
# TENSORFLOW LITE
############################################

-dontwarn org.tensorflow.**
-dontwarn org.tensorflow.lite.**


############################################
# CAMERAX
############################################

-dontwarn androidx.camera.**


############################################
# COIL
############################################

-dontwarn coil.**


############################################
# ANDROID SECURITY CRYPTO
############################################

-dontwarn androidx.security.**


############################################
# ANDROIDX ANNOTATIONS
############################################

-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

-dontwarn androidx.annotation.**


############################################
# SECURITY — JNI NATIVE METHODS
############################################

# JNI methods MUST keep exact names
-keepclasseswithmembernames class com.rite.pillcounting.core.security.SecurityUtils {
    private native boolean nativeIsRooted();
    private native boolean nativeIsDebuggerAttached();
}


############################################
# SECURITY — KEYSTORE / CRYPTO
############################################

-keep class com.rite.pillcounting.core.security.RuntimeUnit { *; }

-keep class com.rite.pillcounting.core.security.DatabaseKeyProvider { *; }

-keep class com.rite.pillcounting.core.security.SecurityAuditLogger { *; }

-keep class com.rite.pillcounting.core.security.AuditEvent { *; }

-keep class com.rite.pillcounting.core.security.SecurityViolationPolicy { *; }

-keep class com.rite.pillcounting.core.utils.preference.SecurePreferences { *; }


############################################
# SQLCIPHER
############################################

-keep class net.sqlcipher.** { *; }

-keep class net.sqlcipher.database.** { *; }

-dontwarn net.sqlcipher.**


############################################
# API MODELS
############################################

-keep class com.rite.pillcounting.feature.**.model.** { *; }


############################################
# OPTIONAL JVM LIBRARIES
############################################

-dontwarn reactor.blockhound.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn java.lang.management.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**


############################################
# NANOHTTPD
############################################

-keep class fi.iki.elonen.** { *; }

-dontwarn fi.iki.elonen.**


############################################
# REMOVE LOGS IN RELEASE
############################################

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int e(...);
}

-assumenosideeffects class com.rite.pillcounting.core.utils.logger.AppLogger {
    public void d(...);
    public void i(...);
    public void w(...);
    public void e(...);
}

# NOTE: kotlinx.coroutines.debug is intentionally excluded from assumenosideeffects.
# Adding it caused R8 to strip coroutine infrastructure that Firebase's
# ComponentRuntime depends on, producing a false DependencyCycleException: [].


############################################
# SYNTHETIC CLASSES
############################################

-keep class **$$ExternalSyntheticLambda* { *; }

-keep class **$$InternalSyntheticLambda* { *; }


############################################
# DEBUGGING OUTPUT
############################################

-printconfiguration build/outputs/r8-full-config.txt