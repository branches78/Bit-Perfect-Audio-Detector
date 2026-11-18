package com.bitperfect.detector.report

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.bitperfect.detector.audio.RuntimeResult

/**
 * Devices that only run the app to test it: Google Play's pre-launch report (the Firebase Test Lab,
 * whose robot taps every button, "Send report" included) and Android emulators. Their reports say
 * nothing about real phones, so the app doesn't offer to send one. The report server rejects
 * emulators with the same name patterns; only the app can tell the Test Lab's physical phones apart,
 * and only the app sees the hardware and the audio outputs of emulators that pose as real phones.
 */
object TestDevice {

    fun isAutomated(c: Context): Boolean =
        isTestLab(c) || isEmulator(Build.MANUFACTURER, Build.MODEL, Build.DEVICE) ||
            isEmulatorHardware(Build.HARDWARE, Build.PRODUCT)

    /**
     * After the audio probe: true when the phone can send a report. Emulators that copy a real
     * phone's Build values (a "Pixel 8 Pro" with device "husky") still give themselves away by
     * their audio (hasVirtualAudio).
     */
    fun canReport(automated: Boolean, runtime: RuntimeResult?): Boolean =
        !automated && (runtime == null ||
            !hasVirtualAudio(runtime.usbHostSupported, runtime.devices.map { it.maxSampleRate }))

    /** The Firebase Test Lab (and so Play's pre-launch report) sets this system setting. */
    private fun isTestLab(c: Context): Boolean =
        runCatching { Settings.System.getString(c.contentResolver, "firebase.test.lab") == "true" }
            .getOrDefault(false)

    private val EMULATOR_MODEL =
        Regex("^(sdk_gphone|sdk_phone|google_sdk|sdk$|Android SDK built for|Emulator\\b)", RegexOption.IGNORE_CASE)
    private val EMULATOR_DEVICE =
        Regex("^(emu64|emulator|generic(_|$)|vbox86|goldfish|ranchu)", RegexOption.IGNORE_CASE)

    /** Android Studio's emulator, Google's virtual devices and Genymotion (Build values). */
    fun isEmulator(manufacturer: String, model: String, device: String): Boolean =
        EMULATOR_MODEL.containsMatchIn(model) || EMULATOR_DEVICE.containsMatchIn(device) ||
            manufacturer.equals("Genymotion", ignoreCase = true)

    private val EMULATOR_HARDWARE =
        Regex("^(ranchu|goldfish|vbox86|nox|ttvm|cutf_cvm|vsoc)", RegexOption.IGNORE_CASE)
    private val EMULATOR_PRODUCT =
        Regex("^(sdk_gphone|sdk_phone|google_sdk|sdk$|vbox86|emulator|aosp_cf_|cf_x86)", RegexOption.IGNORE_CASE)

    /**
     * The emulator's virtual hardware (Build.HARDWARE: "ranchu" and "goldfish" in Android's emulator,
     * "vbox86" in Genymotion, "cutf_cvm"/"vsoc" in Cuttlefish), which emulators that rename the
     * model and the device usually keep, and the emulator's product names (Build.PRODUCT).
     */
    fun isEmulatorHardware(hardware: String, product: String): Boolean =
        EMULATOR_HARDWARE.containsMatchIn(hardware) || EMULATOR_PRODUCT.containsMatchIn(product)

    /**
     * An emulator's audio: no USB host and no output that reports any sample rate (a real phone's
     * speaker always reports one, usually 48 kHz). [outputRates] are the outputs' highest rates
     * (0 = none reported).
     */
    fun hasVirtualAudio(usbHostSupported: Boolean, outputRates: List<Int>): Boolean =
        !usbHostSupported && outputRates.all { it <= 0 }
}
