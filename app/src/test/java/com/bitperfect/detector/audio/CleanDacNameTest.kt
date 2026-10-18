package com.bitperfect.detector.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** DAC names must look the same in the app as in the website's table. */
class CleanDacNameTest {

    @Test
    fun removesTheUsbAudioPrefix() {
        assertEquals("Cayin RU7", cleanDacName("USB-Audio - Cayin RU7"))
        assertEquals("FiiO KA1", cleanDacName("USB Audio - FiiO KA1"))
        assertEquals("HiBy FC3", cleanDacName("HiBy FC3"))
    }

    @Test
    fun keepsTheNameWhenNothingIsLeft() {
        assertEquals("USB-Audio - ", cleanDacName("USB-Audio - "))
        assertEquals("", cleanDacName(""))
    }
}
