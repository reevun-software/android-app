package app.reevun.android

import android.net.Uri
import androidx.core.net.toUri

// The site, marked as the app in its user agent (it then opens on the
// dashboard). Pages that open inside the app: the site itself and the other
// Reevun sites, and Discord's bot invite. Reevun ID (signing in, account
// settings) opens in the browser, as does anything else.
object Site {
    const val URL = "https://reevun.app"
    const val ID_URL = "https://id.reevun.app"
    const val API_URL = "https://api.reevun.app"

    private fun inAppHost(host: String) =
        host == "reevun.app" || host.endsWith(".reevun.app") || host == "discord.com" || host == "www.discord.com"

    // The site's "Sign in" (the API's single sign-on for reevun.app): in the
    // app that is the sign-in in the browser instead.
    fun isSignIn(uri: Uri) = uri.scheme == "https" && uri.path == "/v1/auth/app/sso"

    fun stays(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return uri.scheme == "https" && inAppHost(host) && host != ID_URL.toUri().host && !isSignIn(uri)
    }

    fun opensOutside(uri: Uri) = uri.scheme == "https" || uri.scheme == "mailto"
}
