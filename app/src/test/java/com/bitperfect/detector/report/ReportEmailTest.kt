package com.bitperfect.detector.report

import org.junit.Assert.assertEquals
import org.junit.Test

/** The email's entry must look exactly like the ones in the website's reports.json. */
class ReportEmailTest {

    @Test
    fun writesJsonLikeJavaScript() {
        val entry = linkedMapOf<String, Any?>(
            "noDac" to linkedMapOf("maxRate" to 48000, "maxBits" to 32, "dynamic" to true),
            "dac" to null,
            "apps" to listOf(linkedMapOf("name" to "PureBit", "package" to "app.purebit")),
            "none" to emptyList<Any>(),
        )
        assertEquals(
            """{ "noDac": { "maxRate": 48000, "maxBits": 32, "dynamic": true }, "dac": null, """ +
                """"apps": [{ "name": "PureBit", "package": "app.purebit" }], "none": [] }""",
            Json.inline(entry),
        )
    }

    @Test
    fun escapesStringsLikeJavaScript() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001/é\"", Json.quote("a\"b\\c\nd\u0001/é"))
    }

    @Test
    fun cleansNames() {
        assertEquals("Cayin RU7", cleanDacName("USB-Audio - Cayin RU7"))
        assertEquals("FiiO KA1", cleanDacName("USB Audio - FiiO KA1"))
        assertEquals("USB-Audio - ", cleanDacName("USB-Audio - "))
        assertEquals("Pixel 8 x", singleLine(" Pixel\r\n8\tx "))
    }
}
