package app.reevun.android

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

// Signing in happens in the browser, not in the app: the app opens Reevun
// ID's id.reevun.app/app?challenge=…&state=… in a Custom Tab. There the
// person signs in (or already is) and confirms; Reevun ID sends the browser
// to reevun://signed-in?state=…&code=…, which brings the app back with a
// one-time code. Only this app can trade it for a session: the code is
// bound to the challenge, and the secret behind it (the verifier) never
// leaves the app (PKCE). The sign-in waiting is kept across the app being
// closed meanwhile.
class SignIn(context: Context) {
    private val saved = context.getSharedPreferences("signin", Context.MODE_PRIVATE)

    val url: String?
        get() = saved.getString("url", null)

    fun start(): String {
        val verifier = random(32)
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = random(24)
        val url = "${Site.ID_URL}/app".toUri().buildUpon()
            .appendQueryParameter("challenge", challenge)
            .appendQueryParameter("state", state)
            .build().toString()
        saved.edit {
            putString("state", state)
            putString("verifier", verifier)
            putString("url", url)
        }
        return url
    }

    fun cancel() = saved.edit { clear() }

    // The session token for this reevun://signed-in address, or null (not
    // this sign-in's, or refused). Blocks: call off the main thread.
    fun finish(uri: Uri): String? {
        val state = saved.getString("state", null) ?: return null
        val verifier = saved.getString("verifier", null) ?: return null
        val code = uri.getQueryParameter("code") ?: return null
        if (uri.getQueryParameter("state") != state) return null
        cancel()
        val connection = URL("${Site.API_URL}/v1/auth/code").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("code", code).put("client", "app").put("verifier", verifier)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode != 200) return null
            JSONObject(connection.inputStream.bufferedReader().readText()).optString("token").ifEmpty { null }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun isReturn(uri: Uri?) = uri?.scheme == "reevun" && uri.host == "signed-in"

        private fun random(bytes: Int) = encode(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
