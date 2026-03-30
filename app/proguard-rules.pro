# ═══════════════════════════════════════════════════════════════
# PROGUARD RULES - Video App
# ═══════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════
# OBFUSCATION SETTINGS
# ═══════════════════════════════════════════════════════════════

# Use dictionary for obfuscation (uncomment if you created the file)
# -obfuscationdictionary proguard-dict.txt
# -classobfuscationdictionary proguard-dict.txt
# -packageobfuscationdictionary proguard-dict.txt

# ═══════════════════════════════════════════════════════════════
# REMOVE LOGS IN RELEASE
# ═══════════════════════════════════════════════════════════════

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ═══════════════════════════════════════════════════════════════
# KEEP APP CLASSES
# ═══════════════════════════════════════════════════════════════

# Keep Security Helper
-keep class com.copyrightfree.video.security.** { *; }
-keep class com.copyrightfree.video.model.** { *; }
-keep class com.copyrightfree.video.helper.** { *; }
-keep class com.copyrightfree.video.viewModel.** { *; }
-keep class com.copyrightfree.video.processor.** { *; }
-keep class com.copyrightfree.video.repository.** { *; }
-keep class com.copyrightfree.video.network.** { *; }
-keep class com.copyrightfree.video.views.** { *; }

# ═══════════════════════════════════════════════════════════════
# RETROFIT & NETWORK
# ═══════════════════════════════════════════════════════════════

-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# Retrofit
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ═══════════════════════════════════════════════════════════════
# FFMPEG
# ═══════════════════════════════════════════════════════════════

-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**

# ═══════════════════════════════════════════════════════════════
# ANDROIDX & MATERIAL
# ═══════════════════════════════════════════════════════════════

-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ═══════════════════════════════════════════════════════════════
# LIFECYCLE & VIEWMODEL
# ═══════════════════════════════════════════════════════════════

-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ═══════════════════════════════════════════════════════════════
# PARCELABLE & SERIALIZABLE
# ═══════════════════════════════════════════════════════════════

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ═══════════════════════════════════════════════════════════════
# GENERAL ANDROID
# ═══════════════════════════════════════════════════════════════

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ═══════════════════════════════════════════════════════════════
# DEBUG INFO
# ═══════════════════════════════════════════════════════════════

-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ★ Obfuscate sensitive strings
-keepclassmembers class com.example.uniquecreator.security.SecurityConfig {
    private static final java.lang.String DEBUG_SIGNATURE_HASH;
    private static final java.lang.String RELEASE_SIGNATURE_HASH;
}

# ★ Remove debug logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ★ Keep only error logs
-assumenosideeffects class android.util.Log {
    public static *** w(...);
    public static *** e(...);
}

# ★ Aggressive optimization
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# ★ Hide class names
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ═══════════════════════════════════════════════════════════════
# ★ DEXTER PERMISSIONS LIBRARY
# ═══════════════════════════════════════════════════════════════

-keep class com.karumi.dexter.** { *; }
-dontwarn com.karumi.dexter.**

# (বাকি তোমার existing rules same থাকবে)