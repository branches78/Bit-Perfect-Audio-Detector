package com.bitperfect.detector.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PolicyConfigScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val primary = """
        <?xml version="1.0" encoding="UTF-8"?>
        <audioPolicyConfiguration version="7.0" xmlns:xi="http://www.w3.org/2001/XInclude">
          <modules>
            <module name="primary" halVersion="3.0">
              <mixPorts>
                <mixPort name="primary output" role="source" flags="AUDIO_OUTPUT_FLAG_PRIMARY AUDIO_OUTPUT_FLAG_FAST">
                  <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </mixPort>
                <mixPort name="hifi_playback" role="source" flags="AUDIO_OUTPUT_FLAG_DIRECT">
                  <profile name="" format="AUDIO_FORMAT_PCM_24_BIT_PACKED" samplingRates="44100 48000 96000 192000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </mixPort>
                <mixPort name="primary input" role="sink">
                  <profile name="" format="AUDIO_FORMAT_PCM_24_BIT_PACKED" samplingRates="384000" channelMasks="AUDIO_CHANNEL_IN_STEREO"/>
                </mixPort>
              </mixPorts>
              <devicePorts>
                <devicePort tagName="Speaker" type="AUDIO_DEVICE_OUT_SPEAKER" role="sink"/>
                <devicePort tagName="Wired Headphones" type="AUDIO_DEVICE_OUT_WIRED_HEADPHONE" role="sink"/>
              </devicePorts>
              <routes>
                <route type="mix" sink="Speaker" sources="primary output"/>
                <route type="mix" sink="Wired Headphones" sources="primary output,hifi_playback"/>
              </routes>
            </module>
            <xi:include href="usb_audio_policy_configuration.xml"/>
          </modules>
        </audioPolicyConfiguration>
    """.trimIndent()

    private val usbBitPerfect = """
        <?xml version="1.0" encoding="UTF-8"?>
        <module name="usb" halVersion="2.0">
          <mixPorts>
            <mixPort name="usb_device output" role="source"/>
            <mixPort name="hifi_output" role="source" flags="AUDIO_OUTPUT_FLAG_BIT_PERFECT"/>
          </mixPorts>
          <devicePorts>
            <devicePort tagName="USB Device Out" type="AUDIO_DEVICE_OUT_USB_DEVICE" role="sink"/>
          </devicePorts>
          <routes>
            <route type="mix" sink="USB Device Out" sources="usb_device output,hifi_output"/>
          </routes>
        </module>
    """.trimIndent()

    private val usbPlain = """
        <module name="usb" halVersion="2.0">
          <mixPorts>
            <mixPort name="usb_accessory output" role="source">
              <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="44100" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
            </mixPort>
          </mixPorts>
          <devicePorts>
            <devicePort tagName="USB Host Out" type="AUDIO_DEVICE_OUT_USB_ACCESSORY" role="sink"/>
          </devicePorts>
          <routes>
            <route type="mix" sink="USB Host Out" sources="usb_accessory output"/>
          </routes>
        </module>
    """.trimIndent()

    private fun scanWith(usb: String?): PolicyScanResult {
        val vendor = tmp.newFolder("vendor_etc")
        vendor.resolve("audio_policy_configuration.xml").writeText(primary)
        usb?.let { vendor.resolve("usb_audio_policy_configuration.xml").writeText(it) }
        return PolicyConfigScanner(
            searchDirs = listOf(vendor.absolutePath),
            vintfDirs = emptyList(),
        ).scan()
    }

    @Test
    fun detectsBitPerfectFlagRoutedToUsbThroughInclude() {
        val r = scanWith(usbBitPerfect)
        assertEquals(2, r.filesRead.size)
        assertEquals(listOf("primary", "usb"), r.modules.map { it.name })
        assertEquals(1, r.bitPerfectPorts.size)
        assertEquals("hifi_output", r.bitPerfectPorts[0].mixPort.name)
        assertTrue(r.bitPerfectPorts[0].routesToUsb)
        assertTrue(r.usbDynamicOutput)
        assertNull(r.aidlAudioHal)
    }

    @Test
    fun noBitPerfectWhenFlagMissing() {
        val r = scanWith(usbPlain)
        assertTrue(r.readable)
        assertTrue(r.bitPerfectPorts.isEmpty())
        assertTrue(r.usbOutputDeclared)
        assertFalse(r.usbDynamicOutput)
    }

    @Test
    fun findsHiResOutputButIgnoresInputs() {
        val r = scanWith(usbPlain)
        assertEquals(1, r.hiResPorts.size)
        val h = r.hiResPorts[0]
        assertEquals("hifi_playback", h.mixPort.name)
        assertEquals(192_000, h.maxRate)
        assertEquals(24, h.maxBits)
    }

    @Test
    fun missingIncludeIsReportedNotFatal() {
        val r = scanWith(null)
        assertTrue(r.readable)
        assertTrue(r.notes.any { it is PolicyNote.IncludeNotFound && it.href.contains("usb_audio_policy_configuration.xml") })
    }

    @Test
    fun unreadableConfigIsNotReadable() {
        val r = PolicyConfigScanner(listOf(tmp.newFolder("empty").absolutePath), emptyList()).scan()
        assertFalse(r.readable)
    }

    @Test
    fun legacyCommaSeparatedRates() {
        val parsed = PolicyConfigScanner.parsePolicyXml(
            """<module name="primary"><mixPorts>
                 <mixPort name="direct" role="source" flags="AUDIO_OUTPUT_FLAG_DIRECT|AUDIO_OUTPUT_FLAG_BIT_PERFECT">
                   <profile format="AUDIO_FORMAT_PCM_32_BIT" samplingRates="44100,96000,192000"/>
                 </mixPort></mixPorts></module>""",
            "test",
        )
        val mp = parsed.modules.single().mixPorts.single()
        assertTrue(mp.isBitPerfect)
        assertEquals(listOf(44100, 96000, 192000), mp.profiles.single().samplingRates)
    }
}
