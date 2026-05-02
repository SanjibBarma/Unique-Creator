# ═══════════════════════════════════════════════════════════════
# ULTIMATE STABILITY RULES (Fixes Blank Screen)
# ═══════════════════════════════════════════════════════════════

# ১. আপনার প্রোজেক্টের সব ক্লাস রক্ষা করা (শুধুমাত্র মেথড বডি অবফাসকেট হবে)
-keep class com.example.uniquecreator.** { *; }
-dontwarn com.example.uniquecreator.**

# ২. অ্যান্ড্রয়েড এবং ভিউ এলিমেন্ট রক্ষা করা
-keep class android.** { *; }
-keep class androidx.** { *; }
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# ৩. রিসোর্স আইডি রক্ষা করা
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ৪. ViewModel এবং Lifecycle
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep interface androidx.lifecycle.** { *; }

# ৫. Native Methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# ৬. FFmpeg এবং Media3 লাইব্রেরি রক্ষা করা
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ৭. Glide এবং নেটওয়ার্কিং
-keep class com.bumptech.glide.** { *; }
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }

# ৮. অবফাসকেশনDictionary রিমুভ করছি (যদি এটি কোনো কনফ্লিক্ট তৈরি করে)
# -obfuscationdictionary proguard-dict.txt
