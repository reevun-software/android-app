package app.reevun.android

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

// Reevun for Android: reevun.app in the app, with the app's own loading
// and offline screens over it until it has loaded (or while the sign-in in
// the browser is open).
class MainActivity : ComponentActivity() {
    private enum class SiteState { LOADING, OFFLINE, BROWSER, READY }

    private lateinit var root: FrameLayout
    private lateinit var site: WebView
    private lateinit var signIn: SignIn
    private var loading: WebView? = null
    // The loading screen fading out, if one is.
    private var hiding: WebView? = null
    private var shownAt = 0L
    private var state = SiteState.LOADING
    private var failed = false

    private var files: ValueCallback<Array<Uri>>? = null
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val picked = if (result.resultCode != RESULT_OK || data == null) null
        else data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
        files?.onReceiveValue(picked)
        files = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // White system bars with dark icons, whatever the phone's theme: the
        // site is light.
        val white = SystemBarStyle.light(Color.WHITE, Color.WHITE)
        enableEdgeToEdge(white, white)
        signIn = SignIn(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        // The site between the system bars (and above the keyboard).
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        site = siteView()
        root.addView(site, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        showOverlay(SiteState.LOADING)
        onBackPressedDispatcher.addCallback(this) {
            if (site.canGoBack()) site.goBack() else moveTaskToBack(true)
        }
        if (savedInstanceState == null || site.restoreState(savedInstanceState) == null) dashboard()
        signedIn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        signedIn(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        site.saveState(outState)
    }

    private fun dashboard() = site.loadUrl("${Site.URL}/dashboard")

    @SuppressLint("SetJavaScriptEnabled")
    private fun siteView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString ReevunApp/${BuildConfig.VERSION_NAME} (android)"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.isForMainFrame && leaves(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                failed = false
            }

            // A page that failed (offline, the site down) keeps the screen
            // up in its offline state until a load succeeds.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                failed = true
                showOverlay(SiteState.OFFLINE)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!failed && state != SiteState.READY) showSite()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = restart()
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                popup(resultMsg)
                return true
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                files?.onReceiveValue(null)
                files = callback
                val intent = params.createIntent()
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    pickFiles.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    files = null
                    false
                }
            }
        }
        view.setDownloadListener { url, _, _, _, _ -> openOutside(url.toUri()) }
        return view
    }

    // Reevun pages stay; signing in goes to the browser sign-in; Reevun ID's
    // pages and other links open in the browser. Whether `uri` leaves.
    private fun leaves(uri: Uri): Boolean {
        if (Site.stays(uri)) return false
        if (Site.isSignIn(uri)) startSignIn() else openOutside(uri)
        return true
    }

    private fun openOutside(uri: Uri) {
        if (!Site.opensOutside(uri)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }

    // A new window from the site (Discord's bot invite): over the app while
    // it's a Reevun or Discord page, else in the browser.
    @SuppressLint("SetJavaScriptEnabled")
    private fun popup(resultMsg: Message) {
        val dialog = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        val popup = WebView(this)
        popup.settings.javaScriptEnabled = true
        popup.settings.domStorageEnabled = true
        popup.settings.userAgentString = site.settings.userAgentString
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val leaving = leaves(request.url)
                if (!leaving && !dialog.isShowing) dialog.show()
                if (leaving && !dialog.isShowing) view.destroy()
                return leaving
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!dialog.isShowing && Site.stays(url.toUri())) dialog.show()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dialog.dismiss()
                return true
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = dialog.dismiss()
        }
        dialog.setContentView(popup)
        dialog.setOnDismissListener { popup.destroy() }
        (resultMsg.obj as WebView.WebViewTransport).webView = popup
        resultMsg.sendToTarget()
    }

    // A web view's page process gone (out of memory, say): the app starts
    // over rather than closing.
    private fun restart(): Boolean {
        recreate()
        return true
    }

    private fun report(next: SiteState) {
        state = next
        loading?.let { Screens.emit(it, "siteState", next.name.lowercase()) }
    }

    // The screen over the site, in a given state (made again if it's gone
    // or going).
    private fun showOverlay(next: SiteState) {
        if (loading == null || loading == hiding) {
            val view = Screens.view(this, "loading", ::restart) { _, method, _ -> loadingMessage(method) }
            root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            loading = view
            shownAt = SystemClock.uptimeMillis()
        }
        report(next)
    }

    // The site has loaded: the screen fades out (its page does that on
    // "ready"), then goes. It stays at least MIN_LOADING_MS, so it fades
    // instead of flashing.
    private fun showSite() {
        val overlay = loading ?: return
        if (hiding == overlay) return
        hiding = overlay
        val wait = (MIN_LOADING_MS - (SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0)
        root.postDelayed({
            report(SiteState.READY)
            root.postDelayed({
                root.removeView(overlay)
                overlay.destroy()
                if (loading == overlay) loading = null
                if (hiding == overlay) hiding = null
            }, FADE_MS)
        }, wait)
    }

    private fun loadingMessage(method: String): Any? {
        when (method) {
            "info" -> return Screens.info()
            "siteState" -> return state.name.lowercase()
            "retry" -> {
                report(SiteState.LOADING)
                dashboard()
            }
            "reopenSignIn" -> signIn.url?.let { openSignInPage(it) } ?: startSignIn()
            "cancelSignIn" -> {
                signIn.cancel()
                showSite()
            }
        }
        return null
    }

    // While the browser sign-in is open the app waits on its own screen;
    // signed in, the dashboard loads with the new session.
    private fun startSignIn() {
        showOverlay(SiteState.BROWSER)
        openSignInPage(signIn.start())
    }

    private fun openSignInPage(url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
        } catch (_: ActivityNotFoundException) {
            openOutside(url.toUri())
        }
    }

    private fun signedIn(intent: Intent?) {
        val uri = intent?.data
        if (!SignIn.isReturn(uri)) return
        setIntent(Intent(this, MainActivity::class.java))
        showOverlay(SiteState.LOADING)
        thread {
            val token = signIn.finish(uri!!)
            runOnUiThread {
                if (token == null) return@runOnUiThread dashboard()
                val cookie = "$SESSION_COOKIE=$token; Path=/; Max-Age=${SESSION_DAYS * 24 * 60 * 60}; Secure; HttpOnly; SameSite=Lax"
                CookieManager.getInstance().setCookie(Site.URL, cookie) {
                    CookieManager.getInstance().flush()
                    dashboard()
                }
            }
        }
    }

    companion object {
        private const val MIN_LOADING_MS = 900L
        private const val FADE_MS = 400L
        private const val SESSION_COOKIE = "reevun_session"
        private const val SESSION_DAYS = 60
    }
}
