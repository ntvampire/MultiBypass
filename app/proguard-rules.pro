# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNA interfaces
-keepclassmembers class * extends com.sun.jna.Library {
    public <methods>;
}
-keep class com.sun.jna.** { *; }

# Keep data models for Gson
-keepclassmembers class com.multibypass.app.data.model.** { <fields>; }
