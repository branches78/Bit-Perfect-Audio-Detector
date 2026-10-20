package com.bitperfect.detector.report

import com.bitperfect.detector.audio.uacVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Firmware version and USB Audio Class as the report sends them to the website. */
class FirmwareVersionTest {

    @Test
    fun addsTheIncrementalWhenItCarriesTheRegion() {
        assertEquals("AQ3A.240912.001 (OS2.0.206.0.VNOMIXM)", firmwareVersion("AQ3A.240912.001", "OS2.0.206.0.VNOMIXM"))
        assertEquals("BP2A.250605.031 (A175FXXU1AYG1)", firmwareVersion("BP2A.250605.031", "A175FXXU1AYG1"))
    }

    @Test
    fun keepsTheBuildNumberAloneOtherwise() {
        // Already included, a build counter, or a hash.
        assertEquals("UP1A.231005.007.S918BXXU3BWK5", firmwareVersion("UP1A.231005.007.S918BXXU3BWK5", "S918BXXU3BWK5"))
        assertEquals("BP2A.250605.031", firmwareVersion("BP2A.250605.031", "13034373"))
        assertEquals("V1UBS35H.50-43-3", firmwareVersion("V1UBS35H.50-43-3", "c5b5d0"))
    }

    @Test
    fun usesWhicheverIsThere() {
        assertEquals("OS2.0.5.0", firmwareVersion("", "OS2.0.5.0"))
        assertEquals("13034373", firmwareVersion("", "13034373"))
        assertEquals("", firmwareVersion(" ", ""))
    }

    @Test
    fun removesWhoCompiledTheRom() {
        assertEquals(
            "lineage_vienna-userdebug 15 BP1A.250505.005 eng.20250901.123456 test-keys",
            firmwareVersion("lineage_vienna-userdebug 15 BP1A.250505.005 eng.john.20250901.123456 test-keys", "eng.john.20250901.123456"),
        )
        assertEquals("BP1A.250505.005 (eng.20250901.123456)", firmwareVersion("BP1A.250505.005", "eng.john.20250901.123456"))
    }

    @Test
    fun readsTheUsbAudioClass() {
        assertEquals("UAC1", uacVersion(0x00))
        assertEquals("UAC2", uacVersion(0x20))
        assertEquals("UAC3", uacVersion(0x30))
        assertNull(uacVersion(0xFF))
    }
}
