# Add project specific ProGuard rules here.
# By default, the build system uses R8 for code optimization.
# For more information, see https://developer.android.com/studio/build/shrink-code.

# ----- Kotlin Coroutines (often covered by library or optimize.txt, but safe to keep) -----
# These rules are generally good. `proguard-android-optimize.txt` and `kotlinx-coroutines-core`
# often provide consumer rules that cover most of this, but keeping them explicitly here doesn't hurt.
-keepnames class kotlin.coroutines.CombinedContext
-keepnames class kotlin.coroutines.EmptyCoroutineContext
-keepclassmembers class kotlin.coroutines.Continuation {
    public kotlin.coroutines.CoroutineContext getContext();
    public void resumeWith(java.lang.Object);
}
# The BaseContinuationImpl warning is often benign or handled by other rules.
# Only uncomment if you encounter specific issues related to coroutine internals.
# -keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
#     public final kotlin.coroutines.Continuation getCompletion();
#     public final java.lang.Object getResult();
#     protected void releaseIntercepted();
#     protected java.lang.Object invokeSuspend(java.lang.Object);
# }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class kotlin.Metadata { *; }

# ----- Gson and Data Classes (CRITICAL for JSON parsing) -----
# Your data classes are nested within CurrencyFetchWorker.
# We need to keep their names, fields, and constructor/methods for Gson to work via reflection.
# Also, keep the Signature, InnerClasses, and EnclosingMethod attributes for generics and nested classes.
# The '*' wildcard after 'CurrencyFetchWorker$' will match all nested classes.
-keep class com.example.ratesve.CurrencyFetchWorker$* { *; }
-keepclassmembers class com.example.ratesve.CurrencyFetchWorker$* {
    <fields>;
    <methods>; # Keep constructors and data class generated methods (copy, componentN, etc.)
}
# Keep attributes required for reflection and generic type information (essential for Gson)
-keepattributes Signature, InnerClasses, EnclosingMethod
# If you used custom Gson TypeAdapters or JsonSerializers/Deserializers, you'd add rules for them too.


# ----- Keep any Custom Views referenced in XML Layouts (if not already covered) -----
# `proguard-android-optimize.txt` generally handles Activities, Services, BroadcastReceivers, ContentProviders.
# AppWidgetProvider is a BroadcastReceiver subclass, so it should be covered.
# However, explicit rules for components accessed via Android Manifest are always safer.
-keep public class * extends android.appwidget.AppWidgetProvider {
   <init>(); # Keep default constructor
}
# Only needed if you have custom AppWidgetHostView implementations.
-keep public class * extends android.appwidget.AppWidgetHostView {
   <init>(android.content.Context);
   <init>(android.content.Context, android.util.AttributeSet);
   <init>(android.content.Context, android.util.AttributeSet, int);
}

# ----- Keep any classes accessed via JNI or reflection that R8 can't find -----
# e.g. -keep class com.example.ratesve.utils.MyReflectionHelper { *; }

# ----- OkHttp and Jsoup -----
# These libraries usually include their own consumer ProGuard rules or are handled
# well by R8's default optimizations. Only add rules if you encounter specific issues.
# -dontwarn okhttp3.**
# -dontwarn okio.**
# -dontwarn org.jsoup.**
# It's good practice to add -dontwarn if you get specific warnings you've confirmed are benign,
# but not for general issues, as it can hide actual problems.

# ----- WorkManager -----
# WorkManager often has its own rules but it's good to ensure.
# These are typically covered by `proguard-android-optimize.txt`
-keep class androidx.work.** { *; }
-keep class * implements androidx.work.ListenableWorker { *; }
# Keep your specific worker class. `proguard-android-optimize.txt` should get this, but being explicit is fine.
-keep class com.example.ratesve.CurrencyFetchWorker { *; }

# ----- Rules for androidx.security:security-crypto (EncryptedSharedPreferences) -----
# This library uses reflection heavily for KeyStore interactions.
# While it *should* provide its own consumer rules, sometimes explicit rules are needed,
# especially if other optimizations interfere.

# Keep all classes and members within the androidx.security package.
# This is generally safe and robust for libraries that rely on reflection.
-keep class androidx.security.** { *; }
-keep interface androidx.security.** { *; }

# Specifically keeping MasterKeys and EncryptedSharedPreferences classes
# (Though covered by the broad rule above, being explicit doesn't hurt)
-keep class androidx.security.crypto.MasterKeys { *; }
-keep class androidx.security.crypto.EncryptedSharedPreferences { *; }

# Important attributes for reflection and inner classes if any are used internally
-keepattributes Signature, InnerClasses, EnclosingMethod

# Suppress warnings for potential issues in these packages (often related to reflection)
# Only add if you see specific warnings, but can be helpful for initial debugging.
-dontwarn androidx.security.**
-dontwarn java.security.**
-dontwarn javax.crypto.**

# Reminder: `proguard-android-optimize.txt` handles many common cases like
# keeping Activities, Services, Parcelables, R classes, etc.
