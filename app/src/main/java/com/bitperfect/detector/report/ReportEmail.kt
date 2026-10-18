package com.bitperfect.detector.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import com.bitperfect.detector.BuildConfig
import com.bitperfect.detector.R
import com.bitperfect.detector.audio.Level
import com.bitperfect.detector.audio.Report
import com.bitperfect.detector.audio.VerdictEngine
import com.bitperfect.detector.ui.ReportText
import com.bitperfect.detector.ui.cleanPhoneName
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * The report the user sends to the developer from their own email app ("Send" button). It repeats
 * what the send dialog shows, in the app's language: a greeting, a request not to edit the text, the
 * data that will be published, the model code and the entry ready to paste into the website's
 * reports.json (JSON, the same in every language). The technical details stay in English.
 * The app itself sends nothing and has no internet access.
 */
object ReportEmail {

    const val ADDRESS = "bit-perfect-detector@outlook.com"

    /** The same subject in every language, so all reports are easy to find in the inbox. */
    const val SUBJECT = "Bit-Perfect Detector Report"

    class Email(val subject: String, val body: String)

    /** Opens the email app with the report ready to send. false if the phone has no email app. */
    fun open(c: Context, r: Report): Boolean {
        val email = build(c, r)
        // A plain mailto: link, which only email apps handle. Subject and body go both in the link
        // and as extras, because some email apps read one and some the other. (ACTION_SEND with a
        // mailto: selector is blocked on Android 15+ when the email app's screen doesn't declare it.)
        val mailto = "mailto:$ADDRESS?subject=${Uri.encode(email.subject)}&body=${Uri.encode(email.body)}"
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)).apply {
            putExtra(Intent.EXTRA_SUBJECT, email.subject)
            putExtra(Intent.EXTRA_TEXT, email.body)
        }
        return try {
            c.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun build(c: Context, r: Report, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Email {
        val (manufacturer, model) = cleanPhoneName(singleLine(Build.MANUFACTURER), singleLine(Build.MODEL))
        val declared = VerdictEngine.declaredUsbResolution(r.policy)
        val dac = r.runtime.usbDacs.maxByOrNull { it.maxSampleRate }

        // The entry for the website's table, in the same format as the ones in reports.json.
        val entry = linkedMapOf<String, Any?>(
            "date" to today.toString(),
            "manufacturer" to manufacturer,
            "model" to model,
            "android" to Build.VERSION.RELEASE,
            "bitPerfect" to bitPerfectStatus(r),
            "noDac" to linkedMapOf(
                "maxRate" to (declared?.rate ?: 0),
                "maxBits" to (declared?.bits ?: 0),
                "dynamic" to r.policy.usbDynamicOutput,
            ),
            "dac" to dac?.let {
                linkedMapOf(
                    "name" to cleanDacName(singleLine(it.productName)),
                    "maxRate" to it.maxSampleRate,
                    "maxBits" to it.maxBitDepth,
                    "bitPerfect" to it.bitPerfectSupported,
                )
            },
            "apps" to r.installedApps.map { linkedMapOf("name" to it.name, "package" to it.packageName) },
        )
        val entryText = entry.entries.joinToString(",\n", "  {\n", "\n  }") {
            "    ${Json.quote(it.key)}: ${Json.inline(it.value)}"
        }

        val body = listOf(
            c.getString(R.string.email_greeting),
            "",
            c.getString(R.string.email_dont_edit),
            "",
            // The same data the send dialog shows as "This will be published in the website's table".
            ReportText.published(c, r).joinToString("\n") { (label, value) -> "$label: $value" },
            // Some manufacturers put an internal code in Build.MODEL (Xiaomi "25113PN0EI"): with the
            // device name it can be looked up in Google Play's device list to get the commercial name.
            c.getString(R.string.email_model_code, singleLine(Build.MODEL), singleLine(Build.DEVICE)),
            c.getString(R.string.email_app_version, BuildConfig.VERSION_NAME),
            "",
            "=== ${c.getString(R.string.email_entry_title)} ===",
            entryText,
            "",
            "=== ${c.getString(R.string.email_technical_title)} ===",
            ReportText.technical(english(c), r).trim(),
        ).joinToString("\n")

        return Email(SUBJECT, body)
    }

    /** The app's texts in English, for the technical details (their original language). */
    private fun english(c: Context): Context =
        c.createConfigurationContext(Configuration(c.resources.configuration).apply { setLocale(Locale.ENGLISH) })

    /** Same criteria as the Bit-Perfect support card, with the names the website's table uses. */
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

/** Text without control characters or line breaks, so it can't break the entry or the subject. */
internal fun singleLine(s: String): String = s.replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ").trim()

/**
 * DAC name without the "USB-Audio - " prefix some (Qualcomm) phones add ("USB-Audio - Cayin RU7" →
 * "Cayin RU7"). Same as cleanDacName in the website's code.
 */
internal fun cleanDacName(name: String): String =
    name.replace(Regex("^USB[- ]?Audio\\s*-\\s*", RegexOption.IGNORE_CASE), "").trim().ifEmpty { name }

/** JSON written on one line like JavaScript's JSON.stringify, so the entry looks like the ones in reports.json. */
internal object Json {
    fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '\b' -> append("\\b")
                ch == '\u000C' -> append("\\f")
                ch < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    /** { "a": 1, "b": [1, 2] } (the format of the entries in reports.json). */
    fun inline(v: Any?): String = when (v) {
        is Map<*, *> -> v.entries.joinToString(", ", "{ ", " }") { "${quote(it.key as String)}: ${inline(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { inline(it) }
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        else -> throw IllegalArgumentException("Unsupported JSON value: $v")
    }
}
