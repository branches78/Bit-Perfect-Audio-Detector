package com.bitperfect.detector.report

import android.content.Context
import android.content.res.Configuration
import com.bitperfect.detector.R
import com.bitperfect.detector.audio.Level
import com.bitperfect.detector.audio.Report
import com.bitperfect.detector.ui.phoneName
import java.util.Locale

/**
 * Text the user can paste in the manufacturer's forum to ask for native bit-perfect support, written
 * in the forum's language (ManufacturerForum), which may not be the app's.
 */
object ForumDraft {

    /** Only phones with no bit-perfect support get the card; without USB host, there's nothing to ask. */
    fun applies(r: Report) = r.bitPerfect.level == Level.NO && r.runtime.usbHostSupported

    fun build(c: Context, r: Report, language: String): String {
        val l = localized(c, language)
        val phone = phoneName()
        // What the app found, one point per finding (at least one is true when applies() is).
        val findings = listOfNotNull(
            R.string.forum_draft_no_flag.takeIf { r.policy.readable && r.policy.bitPerfectPorts.isEmpty() },
            R.string.forum_draft_no_dac_mode.takeIf { r.runtime.usbDacs.isNotEmpty() },
            R.string.forum_draft_no_aidl.takeIf { r.policy.aidlAudioHal == false },
        )
        return buildString {
            appendLine(l.getString(R.string.forum_draft_title, phone))
            appendLine()
            appendLine(l.getString(R.string.forum_draft_intro))
            appendLine()
            appendLine(l.getString(R.string.forum_draft_phone, phone, android.os.Build.VERSION.RELEASE))
            findings.forEach { appendLine("- " + l.getString(it)) }
            appendLine()
            appendLine(l.getString(R.string.forum_draft_request))
            appendLine()
            append(l.getString(R.string.forum_draft_thanks))
        }
    }

    /** [c] with the strings of [language] (the app's resource folders: values-pt-rPT, values-fr-rFR). */
    private fun localized(c: Context, language: String): Context {
        val tag = when (language) {
            "pt" -> "pt-PT"
            "fr" -> "fr-FR"
            else -> language
        }
        val config = Configuration(c.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return c.createConfigurationContext(config)
    }
}
