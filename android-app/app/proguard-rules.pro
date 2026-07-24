# VoiceVerify App ProGuard Rules

# Retain Vonage SDK classes
-keep class com.vonage.** { *; }
-keep interface com.vonage.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class retrofit2.** { *; }
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.Platform$*

# Gson
-keep class com.google.gson.** { *; }
-keep class com.example.voiceverify.api.** { *; }

# Coroutines
-keepattributes SourceFile, LineNumberTable
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
