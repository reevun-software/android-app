# The screens call the app through this object's @JavascriptInterface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
