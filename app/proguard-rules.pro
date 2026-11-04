# Python bridge classes are looked up reflectively from Python.
-keep class com.edgevision.wallpaper.inference.TfliteBridge { *; }
-keep class com.chaquo.python.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Chaquopy model assets are read by name at runtime.
-keepclassmembers class * {
    native <methods>;
}
