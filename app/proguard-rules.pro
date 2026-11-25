# Keep JNI native classes and methods
-keep class io.github.romanvht.byedpi.core.** { *; }
-keep class hev.htproxy.** { *; }
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
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Keep data models and generic signatures for Gson serialization
-keepattributes Signature, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.multibypass.app.data.model.** { *; }
-keepclassmembers class com.multibypass.app.data.model.** { *; }
