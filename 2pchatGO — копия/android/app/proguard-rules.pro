# ProGuard Rules for 2PChat

# Keep SQLCipher database classes
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.sqlite.** { *; }

# Keep Native Go Core JNI interfaces
-keep class com.example.twopchat.NativeBridge { *; }
-keep class com.example.twopchat.bridge.** { *; }

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

# Keep NetCipher & Tor Binary native classes
-keep class info.guardianproject.** { *; }
-keep class org.torproject.android.binary.** { *; }
-dontwarn info.guardianproject.**
-dontwarn org.torproject.android.binary.**

# Strip raw android.util.Log in release builds
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
