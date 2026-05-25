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
# Kotlin stdlib does NOT need keep rules
# Metadata is enough for reflection / Room / Moshi

# Firebase ComponentRuntime.discoverComponents() has a hardcoded string comparison
# "kotlinx.coroutines.CoroutineDispatcher" for dedup. R8 renames this to a short
# name (e.g. jk1), breaking the check and causing DependencyCycleException on launch.
-keep class kotlinx.coroutines.** { *; }
-keepnames class kotlinx.coroutines.**


############################################
# JETPACK COMPOSE
############################################
# Compose already provides consumer ProGuard rules
# DO NOT add -keep rules here


############################################
# HILT / DAGGER
############################################

# Keep generated Hilt components only
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }

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

# --- Retrofit (required for suspend + annotations reflection) ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Keep Retrofit service interfaces (so method generic signatures remain intact)
-keep interface com.rite.pillcounting.** { *; }
-keep class retrofit2.** { *; }
-keep class kotlin.coroutines.** { *; }


-dontwarn retrofit2.**


############################################
# MOSHI
############################################

# Keep only classes annotated with @JsonClass
-keep @com.squareup.moshi.JsonClass class * { *; }
-dontwarn com.squareup.moshi.**


############################################
# GSON
############################################

# Keep only fields using @SerializedName
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
# FIREBASE
############################################

-keep class com.google.firebase.** { *; }
-keepclassmembers class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepclassmembers class com.google.android.gms.** { *; }

# Keep all ComponentRegistrar implementations and the interface itself
-keep interface com.google.firebase.components.ComponentRegistrar
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.firebase.components.** { *; }
-keep class com.google.firebase.installations.** { *; }

# Prevent R8 from inlining Firebase's ServiceLoader calls.
# R8's ServiceLoader optimization can corrupt Firebase's component graph.
-keep class java.util.ServiceLoader { *; }
-keepclassmembers class java.util.ServiceLoader {
    public static java.util.ServiceLoader load(java.lang.Class, java.lang.ClassLoader);
    public static java.util.ServiceLoader load(java.lang.Class);
}

-dontwarn com.google.firebase.**


############################################
# ML KIT
############################################

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**


############################################
# PLAY SERVICES (required by Firebase + ML Kit)
############################################

-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.android.gms.**


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
# YOUR APP (KEEP ONLY WHAT USES REFLECTION)
############################################

############################################
# SECURITY — JNI NATIVE METHODS
############################################
# R8 must NOT rename these — libsecurity.so calls them by exact name
-keepclasseswithmembernames class com.rite.pillcounting.core.security.SecurityUtils {
    private native boolean nativeIsRooted();
    private native boolean nativeIsDebuggerAttached();
}

############################################
# SECURITY — KEYSTORE / CRYPTO CLASSES
############################################
# These use reflection internally via Keystore — keep their structure
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

# Example: JSON / DB / Serialization models
# Adjust package as needed
############################################
# API MODELS (Retrofit JSON)
############################################
-keep class com.rite.pillcounting.feature.**.model.** { *; }

# BlockHound (JVM-only)
-dontwarn reactor.blockhound.**

# Jetty NPN / ALPN (JVM-only)
-dontwarn org.eclipse.jetty.npn.**

# JVM management APIs (not on Android)
-dontwarn java.lang.management.**

# Optional logging frameworks (not bundled)
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

############################################
# NANOHTTPD
############################################
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**


############################################
# STRIP ALL LOG CALLS IN RELEASE
############################################
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int e(...);
}

# Also strip AppLogger (which delegates to android.util.Log)
-assumenosideeffects class com.rite.pillcounting.core.utils.logger.AppLogger {
    public void d(...);
    public void i(...);
    public void w(...);
    public void e(...);
}

-assumenosideeffects class kotlinx.coroutines.debug.** { *; }

-dontoptimize
-printconfiguration build/outputs/r8-full-config.txt

-keep class **$$ExternalSyntheticLambda* { *; }
-keep class **$$InternalSyntheticLambda* { *; }