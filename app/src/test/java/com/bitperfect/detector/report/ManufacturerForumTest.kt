package com.bitperfect.detector.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The draft goes in the app's language only when the forum is in that language; otherwise in English. */
class ManufacturerForumTest {

    @Test
    fun localForumsUseTheAppLanguage() {
        assertEquals(
            ManufacturerForum.Forum("https://new.c.mi.com/tr/", "tr"),
            ManufacturerForum.find("Xiaomi", "tr"),
        )
        assertEquals(
            ManufacturerForum.Forum("https://support.google.com/pixelphone/community?hl=ja", "ja"),
            ManufacturerForum.find("Google", "ja"),
        )
        assertEquals(
            ManufacturerForum.Forum("https://community.sony.es/", "es"),
            ManufacturerForum.find("Sony", "es"),
        )
    }

    @Test
    fun withoutAForumInTheAppLanguageTheDraftGoesInEnglish() {
        assertEquals(
            ManufacturerForum.Forum("https://support.google.com/pixelphone/community?hl=en", "en"),
            ManufacturerForum.find("Google", "tr"),
        )
        assertEquals(
            ManufacturerForum.Forum("https://new.c.mi.com/global/", "en"),
            ManufacturerForum.find("Xiaomi", "ko"),
        )
        assertEquals("en", ManufacturerForum.find("OnePlus", "es").language)
        // Samsung opens the visitor's country community; India's is in English.
        assertEquals("en", ManufacturerForum.find("samsung", "hi").language)
    }

    @Test
    fun motorolaTranslatesEveryLanguage() {
        assertEquals("hi", ManufacturerForum.find("motorola", "hi").language)
    }

    @Test
    fun languagesTheAppDoesNotHaveFallBackToEnglish() {
        assertEquals(
            ManufacturerForum.Forum("https://new.c.mi.com/global/", "en"),
            ManufacturerForum.find("Xiaomi", "ru"),
        )
    }

    @Test
    fun unknownManufacturersGetAWebSearch() {
        val forum = ManufacturerForum.find("Coosea", "es")
        assertEquals("en", forum.language)
        assertTrue(forum.url, forum.url.startsWith("https://www.google.com/search?q=Coosea+community+forum"))
    }
}
