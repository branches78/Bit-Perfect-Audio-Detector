package com.bitperfect.detector.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emulators (Play's pre-launch report, Android Studio, Genymotion) can't send reports; real phones can. */
class TestDeviceTest {

    @Test
    fun recognizesEmulators() {
        assertTrue(TestDevice.isEmulator("Google", "sdk_gphone64_arm64", "emu64a"))
        assertTrue(TestDevice.isEmulator("Google", "sdk_gphone64_x86_64", "emu64xa"))
        assertTrue(TestDevice.isEmulator("unknown", "Android SDK built for x86", "generic_x86"))
        assertTrue(TestDevice.isEmulator("Genymotion", "Pixel 3", "vbox86p"))
    }

    @Test
    fun letsRealPhonesThrough() {
        assertFalse(TestDevice.isEmulator("Google", "Pixel 8", "shiba"))
        assertFalse(TestDevice.isEmulator("Xiaomi", "25113PN0EI", "pudding"))
        assertFalse(TestDevice.isEmulator("samsung", "SM-S921B", "e1s"))
        assertFalse(TestDevice.isEmulator("motorola", "motorola edge 50 neo", "cancunf"))
    }
}
