package app.reevun.android

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// Whether a newer version of the app is out (the API knows each app's
// latest release). Can't tell - offline, say - counts as no.
object NewVersion {
    // Blocks: call off the main thread.
    fun isOut(): Boolean = try {
        val connection = URL("${Site.API_URL}/v1/apps/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != 200) {
                false
            } else {
                val versions = JSONObject(connection.inputStream.bufferedReader().readText())
                !versions.isNull("android") && isNewer(versions.getString("android"), BuildConfig.VERSION_NAME)
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    // "1.0.12" over "1.0.9", part by part.
    fun isNewer(latest: String, installed: String): Boolean {
        val a = latest.split(".").mapNotNull { it.toIntOrNull() }
        val b = installed.split(".").mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
