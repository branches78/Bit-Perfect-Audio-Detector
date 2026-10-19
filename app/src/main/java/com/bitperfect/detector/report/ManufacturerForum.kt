package com.bitperfect.detector.report

import java.net.URLEncoder

/**
 * Where to ask the manufacturer for native bit-perfect support: its community forum, in the app's
 * language when there's one, and the language the draft should be written in (the forum's; English
 * when the forum isn't in the app's language). Forums checked on 2026-10-19: they move (Motorola's
 * old one became read-only in 2025), so check them again before changing this list.
 */
object ManufacturerForum {

    /** Languages the app is translated into; any other one shows the app in English. */
    val APP_LANGUAGES = setOf("en", "es", "de", "fr", "it", "pt", "tr", "ja", "ko", "hi")

    data class Forum(val url: String, val language: String)

    private const val SAMSUNG = "https://r1.community.samsung.com/"
    private val XIAOMI = mapOf(
        "es" to "https://new.c.mi.com/es/",
        "it" to "https://new.c.mi.com/it/",
        "fr" to "https://new.c.mi.com/fr/",
        "de" to "https://new.c.mi.com/de/",
        "tr" to "https://new.c.mi.com/tr/",
        "pt" to "https://new.c.mi.com/br/",
    )
    private val PIXEL = listOf("en", "es", "de", "fr", "it", "ja")
        .associateWith { "https://support.google.com/pixelphone/community?hl=$it" }
    private val SONY = mapOf(
        "en" to "https://community.sony.co.uk/",
        "de" to "https://community.sony.de/",
        "fr" to "https://community.sony.fr/",
        "it" to "https://community.sony.it/",
        "es" to "https://community.sony.es/",
        "pt" to "https://community.sony.pt/",
    )
    private val ASUS = listOf("en", "es", "de", "fr", "it", "pt")
        .associateWith { "https://zentalk.asus.com/t5/$it/ct-p/$it" }

    /** English-only forums. */
    private val GLOBAL = mapOf(
        "oneplus" to "https://community.oneplus.com/",
        "oppo" to "https://community.oppo.com/",
        "realme" to "https://c.realme.com/global/",
        "nothing" to "https://nothing.community/",
        "honor" to "https://www.honor.com/global/club/",
        "fairphone" to "https://forum.fairphone.com/",
    )

    /** [manufacturer] is Build.MANUFACTURER; [appLanguage], the language code the app runs in. */
    fun find(manufacturer: String, appLanguage: String): Forum {
        val lang = appLanguage.lowercase().takeIf { it in APP_LANGUAGES } ?: "en"
        val brand = manufacturer.trim().lowercase()

        /** The forum in the app's language, or [fallback] (in English). */
        fun local(forums: Map<String, String>, fallback: String) =
            forums[lang]?.let { Forum(it, lang) } ?: Forum(fallback, "en")

        return when (brand) {
            // It opens the community of the visitor's country by itself; India's is in English.
            "samsung" -> Forum(SAMSUNG, if (lang == "hi") "en" else lang)
            "xiaomi", "redmi", "poco" -> local(XIAOMI, "https://new.c.mi.com/global/")
            "google" -> local(PIXEL, PIXEL.getValue("en"))
            // One forum for every language since September 2025: it translates the posts with AI.
            "motorola" -> Forum("https://forums.lenovo.com/t5/One-Motorola-Community/ct-p/Community-OLC", lang)
            "sony" -> local(SONY, SONY.getValue("en"))
            "asus" -> local(ASUS, ASUS.getValue("en"))
            else -> GLOBAL[brand]?.let { Forum(it, "en") } ?: Forum(search(manufacturer), "en")
        }
    }

    /** Web search for the forum of a manufacturer that isn't on the list. */
    private fun search(manufacturer: String) =
        "https://www.google.com/search?q=" + URLEncoder.encode("${manufacturer.trim()} community forum", "UTF-8")
}
