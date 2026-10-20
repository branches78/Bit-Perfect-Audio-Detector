package com.bitperfect.detector.report

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.bitperfect.detector.BuildConfig
import com.bitperfect.detector.audio.Level
import com.bitperfect.detector.audio.Report
import com.bitperfect.detector.audio.VerdictEngine
import com.bitperfect.detector.ui.ReportText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class SendResult { SENT, RATE_LIMITED, BUSY, FAILED }

/** "eng.<user>." in the build number of self-built ROMs. */
private val BUILDER = Regex("""\beng\.[^.\s]+\.""")

/** The phone's firmware version, as firmwareVersion(display, incremental) puts it together. */
fun firmwareVersion(): String = firmwareVersion(Build.DISPLAY.orEmpty(), Build.VERSION.INCREMENTAL.orEmpty())

/**
 * The phone's firmware version, which can differ by region: the build number (Build.DISPLAY), plus
 * Build.VERSION.INCREMENTAL when it isn't part of it and reads like a version. Samsung and Xiaomi
 * put the region there ("S918BXXU3BWK5", "OS2.0.206.0.VNOMIXM"); Pixels and Motorola only a build
 * counter or a hash ("13034373", "c5b5d0"), which adds nothing.
 * Self-built ROMs ("eng" builds) include whoever compiled them ("eng.john.20240901.123456"): that
 * name is removed, since it could be the user's own.
 */
fun firmwareVersion(display: String, incremental: String): String {
    val d = display.trim().replace(BUILDER, "eng.")
    val inc = incremental.trim().replace(BUILDER, "eng.")
    val readable = Regex("[G-Zg-z]").containsMatchIn(inc)
    return when {
        inc.isEmpty() || !readable || d.contains(inc) -> d.ifEmpty { inc }
        d.isEmpty() -> inc
        else -> "$d ($inc)"
    }
}

/**
 * Sends the anonymous report to the report server (github.com/branches78/Bit-Perfect-Audio-Detector-Server),
 * which forwards it to the developer by email. The app never talks to Resend: its key only lives on the server.
 */
object ReportSender {

    suspend fun send(c: Context, r: Report): SendResult = withContext(Dispatchers.IO) {
        val body = payload(c, r).toString().toByteArray()
        try {
            val conn = URL("${BuildConfig.REPORT_SERVER_URL}/api/reports").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                // Render's free plan takes up to a minute to wake the server up.
                conn.connectTimeout = 90_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body) }
                when (conn.responseCode) {
                    in 200..299 -> SendResult.SENT
                    429 -> SendResult.RATE_LIMITED
                    // The server's daily report limit; any other 503 (e.g. Render down) is a failure.
                    503 -> if (conn.getHeaderField("X-Report-Limit") == "daily") SendResult.BUSY else SendResult.FAILED
                    else -> SendResult.FAILED
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            SendResult.FAILED
        }
    }

    /**
     * Wakes the server up (Render's free plan puts it to sleep) so it's ready by the time the
     * user sends the report. It's an empty request to /healthz: it sends no data and its result
     * doesn't matter.
     */
    suspend fun wakeUpServer() {
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("${BuildConfig.REPORT_SERVER_URL}/healthz").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 90_000
                    conn.readTimeout = 90_000
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // No connection or server down: sending will try again when the button is tapped.
            }
        }
    }

    /** Report data; texts are in English so the email is the same whatever the app's language. */
    fun payload(c: Context, r: Report): JSONObject {
        val en = c.createConfigurationContext(Configuration(c.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val enReport = VerdictEngine.build(en, r.runtime, r.policy, r.installedApps)
        val declared = VerdictEngine.declaredUsbResolution(r.policy)
        val dac = r.runtime.usbDacs.maxByOrNull { it.maxSampleRate }

        return JSONObject()
            .put("schema", 1)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("android", Build.VERSION.RELEASE)
                    .put("firmware", firmwareVersion())
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .put(
                "noDac",
                JSONObject()
                    .put("maxRate", declared?.rate ?: 0)
                    .put("maxBits", declared?.bits ?: 0)
                    .put("dynamic", r.policy.usbDynamicOutput)
                    .put("bitPerfectDeclared", r.policy.bitPerfectPorts.any { it.routesToUsb }),
            )
            .put(
                "dac",
                dac?.let {
                    JSONObject()
                        .put("name", it.productName)
                        .put("uac", it.uac ?: JSONObject.NULL)
                        .put("maxRate", it.maxSampleRate)
                        .put("maxBits", it.maxBitDepth)
                        .put("bitPerfect", it.bitPerfectSupported)
                        .put("bitPerfectMaxRate", it.bitPerfectMaxRate)
                        .put("bitPerfectMaxBits", it.bitPerfectMaxBits)
                } ?: JSONObject.NULL,
            )
            .put("bitPerfect", bitPerfectStatus(r))
            .put(
                "compatibleApps",
                // Name and package, so the website can link each app to its Play Store listing.
                JSONArray(r.installedApps.map { JSONObject().put("name", it.name).put("package", it.packageName) }),
            )
            .put("summary", ReportText.summary(en, enReport))
            .put("technical", ReportText.technical(en, enReport))
    }

    /** Same criteria as the Bit-Perfect support card, in the format the server understands. */
    private fun bitPerfectStatus(r: Report): String = when (r.bitPerfect.level) {
        Level.YES -> "confirmed"
        Level.LIKELY -> when {
            r.runtime.usbDacs.isNotEmpty() -> "dac_rejected"
            r.policy.bitPerfectPorts.any { it.routesToUsb } -> "manufacturer"
            else -> "probable"
        }
        Level.NO -> "no"
        Level.UNKNOWN -> "unknown"
    }
}
