# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/az/Android/Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep line numbers for crash reports / GitHubCrashReporter stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep generic signatures (Retrofit needs these for Call<T>, Kotlin reflection
# needs them, Room uses them for relations).
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# Room generates implementations from @Database / @Dao via KSP. The generated
# classes reference your entity types reflectively at runtime when loading
# columns. Keep entities, the DAO, and Room internals.
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
# Keep our entity + projection types so column getters survive minification.
-keep class com.hereliesaz.cleanunderwear.data.Target { *; }
-keep class com.hereliesaz.cleanunderwear.data.TargetLite { *; }
-keep class com.hereliesaz.cleanunderwear.data.TargetWorkInfo { *; }
-keep class com.hereliesaz.cleanunderwear.data.TargetSourceInfo { *; }
-keep enum com.hereliesaz.cleanunderwear.data.TargetStatus { *; }
-keep enum com.hereliesaz.cleanunderwear.data.MonitorabilityState { *; }

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
# Hilt generates _HiltModules and _HiltComponents that reflectively wire
# @Inject constructors. Without these keeps, R8 strips generated code and DI
# fails at runtime with NoSuchMethodError on app start.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_GeneratedInjector { *; }
-keep class hilt_aggregated_deps.** { *; }
# Keep @Inject constructors.
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# ---------------------------------------------------------------------------
# WorkManager + HiltWorker
# ---------------------------------------------------------------------------
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep @androidx.hilt.work.HiltWorker class * { *; }

# ---------------------------------------------------------------------------
# Retrofit + Gson
# ---------------------------------------------------------------------------
# Retrofit ships consumer rules but they don't always cover every edge.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
# Gson reflectively constructs DTOs using their no-arg constructors and
# field names. If we add response models in the future, annotate them with
# @Keep or list them here.
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------------
# OkHttp ships its own consumer rules; these just suppress warnings about
# its optional dependencies (BouncyCastle, Conscrypt, OpenJSSE).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Coroutines
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.debug.**

# ---------------------------------------------------------------------------
# TensorFlow Lite (LiteRT)
# ---------------------------------------------------------------------------
# OnDeviceResearchAgent loads research_agent.tflite at runtime. The interpreter
# resolves native ops by class-name reflection.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**

# ---------------------------------------------------------------------------
# Jsoup
# ---------------------------------------------------------------------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---------------------------------------------------------------------------
# WebView with JS interfaces (BrowserScreen.HtmlDumpInterface, etc.)
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# App-specific JS interface classes (called from injected page scripts).
# ---------------------------------------------------------------------------
-keep class com.hereliesaz.cleanunderwear.ui.HtmlDumpInterface { *; }

# ---------------------------------------------------------------------------
# Crash reporter — payload uses reflection on Throwable; keep stack frames.
# ---------------------------------------------------------------------------
-keep class com.hereliesaz.cleanunderwear.util.GitHubCrashReporter { *; }

# ---------------------------------------------------------------------------
# Compose / Material — both ship comprehensive consumer rules; nothing to add.
# Paging — also ships consumer rules.
# ---------------------------------------------------------------------------
