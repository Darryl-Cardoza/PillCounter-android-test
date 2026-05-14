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


############################################
# KOTLIN
############################################
# Kotlin stdlib does NOT need keep rules
# Metadata is enough for reflection / Room / Moshi


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

-dontwarn com.google.firebase.**


############################################
# ML KIT
############################################

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
# YOUR APP (KEEP ONLY WHAT USES REFLECTION)
############################################

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