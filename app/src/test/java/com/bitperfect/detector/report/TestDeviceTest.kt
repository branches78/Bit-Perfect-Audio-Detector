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

    @Test
    fun recognizesEmulatorHardware() {
        assertTrue(TestDevice.isEmulatorHardware("ranchu", "husky"))
        assertTrue(TestDevice.isEmulatorHardware("goldfish", "sdk_phone_x86"))
        assertTrue(TestDevice.isEmulatorHardware("vbox86", "vbox86p"))
        assertTrue(TestDevice.isEmulatorHardware("cutf_cvm", "aosp_cf_x86_64_phone"))
        assertFalse(TestDevice.isEmulatorHardware("husky", "husky"))
        assertFalse(TestDevice.isEmulatorHardware("qcom", "pudding_global"))
        assertFalse(TestDevice.isEmulatorHardware("mt6878", "cancunf_g"))
    }

    /** An emulator posing as a Pixel 8 Pro: no USB host and outputs without sample rates. */
    @Test
    fun recognizesVirtualAudio() {
        assertTrue(TestDevice.hasVirtualAudio(usbHostSupported = false, outputRates = listOf(0, 0)))
        assertTrue(TestDevice.hasVirtualAudio(usbHostSupported = false, outputRates = emptyList()))
        // A real phone: USB host, or at least a speaker that reports its rate.
        assertFalse(TestDevice.hasVirtualAudio(usbHostSupported = true, outputRates = listOf(0, 0)))
        assertFalse(TestDevice.hasVirtualAudio(usbHostSupported = false, outputRates = listOf(48_000, 16_000)))
    }
}
