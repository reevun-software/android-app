package app.reevun.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.util.Locale
import org.json.JSONObject

// The app's own screens (reevun-software/app-core, one self-contained page
// built into assets/screens/index.html), the same as in every Reevun app.
// They talk to the app through ReevunAndroid.post(json); answers and events
// go back through window.reevunNative.receive. The site's web view has
// neither.
object Screens {
    private const val PAGE = "https://appassets.androidplatform.net/assets/screens/index.html"

    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context, page: String, gone: () -> Boolean, handle: (WebView, String, List<Any?>) -> Any?): WebView {
        val view = WebView(context)
        view.setBackgroundColor(Color.WHITE)
        view.settings.javaScriptEnabled = true
        view.settings.allowFileAccess = false
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            // The screens never go anywhere else.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = gone()
        }
        val main = Handler(Looper.getMainLooper())
        view.addJavascriptInterface(object {
            @JavascriptInterface
            fun post(text: String) = main.post {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return@post
                val args = message.optJSONArray("args")
                val list = (0 until (args?.length() ?: 0)).map { args!!.opt(it) }
                val result = handle(view, message.optString("method"), list)
                if (message.has("id")) send(view, JSONObject().put("id", message.getLong("id")).put("result", result ?: JSONObject.NULL))
            }
        }, "ReevunAndroid")
        view.loadUrl("$PAGE#$page")
        return view
    }

    // An event for a screen: "siteState" or "updateStatus".
    fun emit(view: WebView, event: String, data: Any) = send(view, JSONObject().put("event", event).put("data", data))

    private fun send(view: WebView, message: JSONObject) =
        view.evaluateJavascript("window.reevunNative && window.reevunNative.receive($message)", null)

    // What every screen asks first.
    fun info(): JSONObject = JSONObject()
        .put("platform", "android")
        .put("version", BuildConfig.VERSION_NAME)
        .put("locale", Locale.getDefault().toLanguageTag())
        .put("titleBar", JSONObject().put("insetLeft", 0).put("windowButtons", false))
}
