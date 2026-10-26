# Keep Xposed entry point and all classes in xposed package
-keep class org.traccar.client.xposed.** { *; }
-keep interface org.traccar.client.xposed.** { *; }

# Don't warn about compileOnly Xposed API classes
-dontwarn de.robv.android.xposed.**

# Keep KeepAlive components
-keep class org.traccar.client.keepalive.** { *; }
