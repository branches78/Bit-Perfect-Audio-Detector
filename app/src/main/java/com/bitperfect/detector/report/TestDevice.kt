package com.bitperfect.detector.report

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Devices that only run the app to test it: Google Play's pre-launch report (the Firebase Test Lab,
 * whose robot taps every button, "Send report" included) and Android emulators. Their reports say
 * nothing about real phones, so the app doesn't offer to send one. The report server rejects
 * emulators with the same patterns; only the app can tell the Test Lab's physical phones apart.
 */
object TestDevice {

    fun isAutomated(c: Context): Boolean =
        isTestLab(c) || isEmulator(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)

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
}
