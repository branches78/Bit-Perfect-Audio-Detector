package com.bitperfect.detector.audio

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import com.bitperfect.detector.R

/**
 * Queries Android 14+'s public APIs about the connected audio outputs.
 *
 * Important: getSupportedMixerAttributes() needs an AudioDeviceInfo, and Android only provides
 * AudioDeviceInfo objects for connected devices. That's why bit-perfect can only be confirmed
 * definitively with a USB DAC plugged in.
 */
class AudioRuntimeProbe(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val packageManager = context.packageManager
    private val usbManager = context.getSystemService(UsbManager::class.java)

    fun probe(): RuntimeResult {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val classes = usbAudioClasses()
        val devices = outputs.map { describe(it, classes) }

        val hiResFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
            .setSampleRate(192_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val directHiRes = safe(AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED) {
            AudioManager.getDirectPlaybackSupport(hiResFormat, MEDIA_ATTRIBUTES)
        } != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED

        val directProfiles = safe(emptyList()) {
            audioManager.getDirectProfilesForAttributes(MEDIA_ATTRIBUTES)
        }.filter { isPcm(it.format) }
        val directMaxRate = directProfiles.flatMap { it.sampleRates.toList() }.maxOrNull() ?: 0
        val directMaxBits = directProfiles.maxOfOrNull { bitDepth(it.format) } ?: 0

        return RuntimeResult(
            usbHostSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            proAudio = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
            mixerSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull(),
            devices = devices,
            directHiResOnCurrentRoute = directHiRes,
            directMaxRate = directMaxRate,
            directMaxBits = directMaxBits,
        )
    }

    /**
     * USB Audio Class version ("UAC1", "UAC2", "UAC3") of each connected USB audio device, by its
     * name as cleanDacName leaves it. It comes from the descriptors Android already read when the
     * device was plugged in, so no USB permission is needed.
     * A DAC can offer several configurations (one per class): Linux uses the UAC3 one when there is
     * one, and otherwise the first.
     */
    private fun usbAudioClasses(): Map<String, String> = safe(emptyMap()) {
        usbManager?.deviceList.orEmpty().values.mapNotNull { device ->
            val versions = (0 until device.configurationCount).mapNotNull { i ->
                val config = device.getConfiguration(i)
                (0 until config.interfaceCount).map { config.getInterface(it) }
                    .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == AUDIO_CONTROL }
                    ?.let { uacVersion(it.interfaceProtocol) }
            }
            val uac = versions.firstOrNull { it == "UAC3" } ?: versions.firstOrNull()
            val name = device.productName?.let(::cleanDacName)
            if (uac == null || name.isNullOrBlank()) null else name.lowercase() to uac
        }.toMap()
    }

    private fun describe(d: AudioDeviceInfo, classes: Map<String, String>): OutputDevice {
        val profiles = d.audioProfiles.filter { isPcm(it.format) }
        val rates = (profiles.flatMap { it.sampleRates.toList() } + d.sampleRates.toList())
        val bits = (profiles.map { bitDepth(it.format) } + d.encodings.map { bitDepth(it) })

        val isUsb = d.type in USB_TYPES
        val mixerAttrs = if (isUsb) {
            safe(emptyList()) { audioManager.getSupportedMixerAttributes(d) }
        } else {
            emptyList()
        }
        val bitPerfectAttrs = mixerAttrs.filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        val preferred = if (isUsb) {
            safe(null) { audioManager.getPreferredMixerAttributes(MEDIA_ATTRIBUTES, d) }
        } else {
            null
        }

        return OutputDevice(
            type = d.type,
            productName = d.productName?.toString().orEmpty(),
            isUsb = isUsb,
            // With a single USB audio device, its class even if Android names it differently.
            uac = if (!isUsb) {
                null
            } else {
                classes[cleanDacName(d.productName?.toString().orEmpty()).lowercase()]
                    ?: classes.values.singleOrNull()
            },
            maxSampleRate = rates.maxOrNull() ?: 0,
            maxBitDepth = bits.maxOrNull() ?: 0,
            mixerAttributesCount = mixerAttrs.size,
            bitPerfectSupported = bitPerfectAttrs.isNotEmpty(),
            bitPerfectMaxRate = bitPerfectAttrs.maxOfOrNull { it.format.sampleRate } ?: 0,
            bitPerfectMaxBits = bitPerfectAttrs.maxOfOrNull { bitDepth(it.format.encoding) } ?: 0,
            preferred = preferred?.let {
                PreferredMixer(it.mixerBehavior, it.format.sampleRate, bitDepth(it.format.encoding))
            },
        )
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        default
    }

    companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        /** bInterfaceSubClass of the USB audio control interface. */
        private const val AUDIO_CONTROL = 1

        fun isPcm(encoding: Int) = bitDepth(encoding) > 0

        fun bitDepth(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }

        fun behaviorName(c: Context, behavior: Int) = when (behavior) {
            AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "Bit-perfect"
            AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> c.getString(R.string.behavior_default)
            else -> c.getString(R.string.behavior_other, behavior)
        }

        fun typeName(c: Context, type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> c.getString(R.string.dev_speaker)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> c.getString(R.string.dev_speaker_safe)
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> c.getString(R.string.dev_earpiece)
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> c.getString(R.string.dev_wired)
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> c.getString(R.string.dev_wired)
            AudioDeviceInfo.TYPE_USB_DEVICE -> c.getString(R.string.card_usb_dac)
            AudioDeviceInfo.TYPE_USB_HEADSET -> c.getString(R.string.dev_usb_headset)
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> c.getString(R.string.dev_usb_accessory)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> c.getString(R.string.dev_ble_speaker)
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_TELEPHONY -> c.getString(R.string.dev_telephony)
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> c.getString(R.string.dev_remote_submix)
            else -> c.getString(R.string.dev_type_other, type)
        }
    }
}

/** Names are written when shown (typeName, behaviorName), so each text can use its own language. */
data class OutputDevice(
    val type: Int,
    val productName: String,
    val isUsb: Boolean,
    /** USB Audio Class version of a USB device ("UAC1", "UAC2", "UAC3"), or null if unknown. */
    val uac: String? = null,
    val maxSampleRate: Int,
    val maxBitDepth: Int,
    val mixerAttributesCount: Int,
    val bitPerfectSupported: Boolean,
    val bitPerfectMaxRate: Int,
    val bitPerfectMaxBits: Int,
    val preferred: PreferredMixer?,
) {
    /** The name shown to the user: without the "USB-Audio - " prefix of some phones. */
    val displayName get() = cleanDacName(productName)
}

/**
 * DAC name without the "USB-Audio - " prefix some (Qualcomm) phones add to the product name
 * ("USB-Audio - Cayin RU7" → "Cayin RU7"). Same as cleanDacName in the report server and the website.
 */
fun cleanDacName(name: String): String =
    name.replace(Regex("^USB[- ]?Audio\\s*-\\s*", RegexOption.IGNORE_CASE), "").trim().ifEmpty { name }

/**
 * The USB audio control interface's bInterfaceProtocol is the USB Audio Class version: 0x00 for
 * UAC1, 0x20 for UAC2 and 0x30 for UAC3.
 */
fun uacVersion(protocol: Int): String? = when (protocol) {
    0x00 -> "UAC1"
    0x20 -> "UAC2"
    0x30 -> "UAC3"
    else -> null
}

/** Mixer attributes an app chose for a device (AudioManager.getPreferredMixerAttributes). */
data class PreferredMixer(val behavior: Int, val sampleRate: Int, val bits: Int)

data class RuntimeResult(
    val usbHostSupported: Boolean,
    val proAudio: Boolean,
    val mixerSampleRate: Int?,
    val devices: List<OutputDevice>,
    val directHiResOnCurrentRoute: Boolean,
    val directMaxRate: Int,
    val directMaxBits: Int,
) {
    val usbDacs get() = devices.filter { it.isUsb }
}
