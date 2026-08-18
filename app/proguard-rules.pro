# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Xposed API
-keep class de.robv.android.xposed.** { *; }
-keepclasseswithmembers class * {
    public <init>(de.robv.android.xposed.XposedBridge);
}

# Keep our hook classes
-keep class com.radiasync.downloadprovidermod.** { *; }
