package com.bitperfect.detector.audio

import android.content.Context
import com.bitperfect.detector.R

enum class Level { YES, LIKELY, NO, UNKNOWN }

data class Verdict(val level: Level, val headline: String, val detail: String)

data class Report(
    val hiRes: Verdict,
    val bitPerfect: Verdict,
    val apps: Verdict,
    val usbDac: Verdict,
    val runtime: RuntimeResult,
    val policy: PolicyScanResult,
    val installedApps: List<BitPerfectApp>,
)

/** Combines the system's evidence (with a DAC) and the manufacturer's configuration (without a DAC). */
object VerdictEngine {

    fun build(
        c: Context,
        runtime: RuntimeResult,
        policy: PolicyScanResult,
        installedApps: List<BitPerfectApp>,
    ): Report = Report(
        hiRes = hiRes(c, runtime, policy),
        bitPerfect = bitPerfect(c, runtime, policy),
        apps = apps(c, installedApps),
        usbDac = usbDac(c, runtime),
        runtime = runtime,
        policy = policy,
        installedApps = installedApps,
    )

    private fun bitPerfect(c: Context, rt: RuntimeResult, policy: PolicyScanResult): Verdict {
        val dacs = rt.usbDacs
        val declared = policy.bitPerfectPorts
        val declaredUsb = declared.filter { it.routesToUsb }

        if (dacs.isNotEmpty()) {
            val ok = dacs.filter { it.bitPerfectSupported }
            if (ok.isNotEmpty()) {
                val best = ok.maxBy { it.bitPerfectMaxRate }
                val detail = if (best.bitPerfectMaxRate > 0) {
                    c.getString(R.string.bp_confirmed_res, khz(c, best.bitPerfectMaxRate), best.bitPerfectMaxBits)
                } else {
                    c.getString(R.string.bp_confirmed)
                }
                return Verdict(Level.YES, c.getString(R.string.h_yes_confirmed), detail)
            }
            return if (declared.isNotEmpty()) {
                Verdict(
                    Level.LIKELY, c.getString(R.string.h_declared_not_this_dac),
                    c.getString(R.string.bp_declared_not_this_dac),
                )
            } else {
                Verdict(Level.NO, c.getString(R.string.h_no), c.getString(R.string.bp_no_with_dac))
            }
        }

        if (!rt.usbHostSupported) {
            return Verdict(Level.NO, c.getString(R.string.h_no), c.getString(R.string.no_usb_host))
        }
        return when {
            declaredUsb.isNotEmpty() -> Verdict(
                Level.LIKELY, c.getString(R.string.h_yes_manufacturer),
                c.getString(R.string.bp_manufacturer, declaredUsb.joinToString { it.mixPort.name }),
            )
            declared.isNotEmpty() -> Verdict(
                Level.LIKELY, c.getString(R.string.h_probable),
                c.getString(R.string.bp_probable, declared.joinToString { it.mixPort.name }),
            )
            policy.readable -> Verdict(
                Level.NO, c.getString(R.string.h_not_declared),
                c.getString(R.string.bp_not_declared) + aidlNote(c, policy),
            )
            else -> Verdict(
                Level.UNKNOWN, c.getString(R.string.h_cannot_know),
                c.getString(R.string.bp_cannot_know),
            )
        }
    }

    private fun aidlNote(c: Context, policy: PolicyScanResult) = when (policy.aidlAudioHal) {
        false -> " " + c.getString(R.string.aidl_missing_note)
        else -> ""
    }

    private fun apps(c: Context, apps: List<BitPerfectApp>): Verdict {
        if (apps.isEmpty()) {
            return Verdict(Level.NO, c.getString(R.string.h_bp_apps_none), c.getString(R.string.bp_apps_none))
        }
        // The card draws the app list itself (InstalledApps), with its own spacing.
        return Verdict(
            Level.YES,
            c.resources.getQuantityString(R.plurals.h_bp_apps_found, apps.size, apps.size),
            c.getString(R.string.bp_apps_intro),
        )
    }

    /** Headline only; it's the only card that shows the DAC's name. */
    private fun usbDac(c: Context, rt: RuntimeResult): Verdict {
        val dacs = rt.usbDacs
        if (dacs.isEmpty()) {
            return Verdict(Level.NO, c.getString(R.string.h_not_connected), "")
        }
        val names = dacs.map { it.displayName }.filter { it.isNotBlank() }.joinToString()
        val headline = if (names.isNotEmpty()) {
            c.getString(R.string.h_connected_dac, names)
        } else {
            c.getString(R.string.h_connected)
        }
        return Verdict(Level.YES, headline, "")
    }

    /** Maximum resolution the phone would have with a USB DAC connected. */
    private fun hiRes(c: Context, rt: RuntimeResult, policy: PolicyScanResult): Verdict {
        // 1) DAC connected: what Android reports for it is definitive.
        rt.usbDacs.filter { it.maxSampleRate > 0 }.maxByOrNull { it.maxSampleRate }?.let { d ->
            return Verdict(
                if (isHiRes(d.maxSampleRate, d.maxBitDepth)) Level.YES else Level.NO,
                c.getString(R.string.h_up_to, khz(c, d.maxSampleRate), d.maxBitDepth),
                c.getString(R.string.hires_dac),
            )
        }

        if (!rt.usbHostSupported) {
            return Verdict(Level.NO, c.getString(R.string.h_no_usb_output), c.getString(R.string.no_usb_host))
        }

        // 2) No DAC: what the manufacturer declares for the USB output.
        val dynamic = policy.usbDynamicOutput
        val declared = declaredUsbResolution(policy)
        if (declared != null) {
            val (rate, bits) = declared
            val detail = listOfNotNull(
                c.getString(R.string.hires_declared),
                if (dynamic) c.getString(R.string.hires_dynamic_extra) else null,
                c.getString(R.string.confirm_exact),
            ).joinToString(" ")
            return Verdict(
                if (isHiRes(rate, bits)) Level.LIKELY else Level.NO,
                c.getString(R.string.h_up_to_manufacturer, khz(c, rate), bits),
                detail,
            )
        }
        if (dynamic) {
            return Verdict(Level.LIKELY, c.getString(R.string.h_dac_dependent), c.getString(R.string.hires_dynamic))
        }
        return if (policy.readable) {
            Verdict(Level.UNKNOWN, c.getString(R.string.h_res_not_declared), c.getString(R.string.hires_not_declared))
        } else {
            Verdict(Level.UNKNOWN, c.getString(R.string.h_cannot_know), c.getString(R.string.hires_cannot_know))
        }
    }

    /** Maximum resolution the manufacturer's configuration declares for the USB output. */
    fun declaredUsbResolution(policy: PolicyScanResult): Resolution? {
        val bitPerfectMax = policy.bitPerfectPorts.filter { it.routesToUsb }
            .flatMap { it.mixPort.profiles }.filter { it.bitDepth > 0 && it.samplingRates.isNotEmpty() }
        val declared = listOfNotNull(policy.usbMaxResolution) +
            bitPerfectMax.map { Resolution(it.samplingRates.max(), it.bitDepth) }
        if (declared.isEmpty()) return null
        return Resolution(declared.maxOf { it.rate }, declared.maxOf { it.bits })
    }

    /** Hi-Res according to the Japan Audio Society: more than 48 kHz and at least 24 bits. */
    private fun isHiRes(rate: Int, bits: Int) = rate > 48_000 && bits >= 24

    /** "44.1 kHz" with the decimal separator of [c]'s language ("44,1 kHz" in Spanish). */
    fun khz(c: Context, rate: Int): String {
        val k = rate / 1000.0
        return if (k % 1.0 == 0.0) "${k.toInt()} kHz" else "%.1f kHz".format(c.resources.configuration.locales[0], k)
    }
}
