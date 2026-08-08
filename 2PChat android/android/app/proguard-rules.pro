# ProGuard Rules for 2PChat

# Keep SQLCipher database classes
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.sqlite.** { *; }

# Keep Chaquopy Python runtime interfaces
-keep class com.chaquo.python.** { *; }
-keep class **.PythonBridge** { *; }
-dontwarn com.chaquo.python.**

# Keep Yggdrasil Go JNI bindings
-keep class mobile.Mobile { *; }
-keep class **YggdrasilInterface** { *; }

# Keep Jetpack Compose models and state holders
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep P2P Models
-keep class com.example.twopchat.data.** { *; }
-keep class com.example.twopchat.group.ui.** { *; }

# Strip raw android.util.Log in release builds
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
