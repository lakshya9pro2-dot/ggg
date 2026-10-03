# Proguard / R8 rules for LiteWebView

# Keep NanoHTTPD classes and interfaces
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep JavaScript interfaces for WebView if needed
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX Core
-dontwarn androidx.**
