# Keep JNI native classes and methods
-keep class io.github.romanvht.byedpi.core.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNA interfaces and classes
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class com.multibypass.app.core.tgproxy.** { *; }
-keep interface com.multibypass.app.core.tgproxy.** { *; }
-keepclassmembers class * extends com.sun.jna.Library {
    public <methods>;
}

# Keep data models for Gson serialization
-keep class com.multibypass.app.data.model.** { *; }
-keepclassmembers class com.multibypass.app.data.model.** { *; }
