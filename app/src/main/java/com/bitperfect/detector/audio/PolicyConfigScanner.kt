package com.bitperfect.detector.audio

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/**
 * Reads the audio policy configuration the manufacturer installs on the phone
 * (audio_policy_configuration.xml and the files it includes, such as
 * usb_audio_policy_configuration.xml).
 *
 * It's the only way to know, WITHOUT a USB DAC connected, whether the manufacturer declared a
 * mixPort with AUDIO_OUTPUT_FLAG_BIT_PERFECT: the public API
 * (AudioManager.getSupportedMixerAttributes) only answers for connected devices.
 */
class PolicyConfigScanner(
    private val searchDirs: List<String> = DEFAULT_SEARCH_DIRS,
    private val vintfDirs: List<String> = DEFAULT_VINTF_DIRS,
    /** The phone's vendor SKU (null if unknown): see [readVendorSku]. */
    private val vendorSku: String? = readVendorSku(),
) {

    fun scan(): PolicyScanResult {
        val notes = mutableListOf<PolicyNote>()
        val readFiles = mutableListOf<String>()
        val modules = mutableListOf<PolicyModule>()

        val roots = findPolicyFiles(notes)
        val visited = mutableSetOf<String>()
        for (root in roots) {
            parseFileRecursive(root, visited, readFiles, modules, notes)
        }

        return PolicyScanResult(
            filesRead = readFiles,
            modules = modules,
            aidlAudioHal = detectAidlAudioHal(),
            notes = notes,
        )
    }

    /** Root files, in the priority order libaudiopolicy uses. */
    private fun findPolicyFiles(notes: MutableList<PolicyNote>): List<File> {
        val found = mutableListOf<File>()
        for (dirPath in searchDirs) {
            val dir = File(dirPath)
            val children = try {
                dir.listFiles()
            } catch (e: SecurityException) {
                null
            }
            if (children == null) {
                // Missing directory or no permission to list it: try the standard file name.
                val std = File(dir, "audio_policy_configuration.xml")
                if (canRead(std)) found += std
                continue
            }
            children.filter { it.isFile && isRootPolicyName(it.name) }
                .sortedBy { it.name }
                .forEach { found += it }
            // Per-SKU subfolders, e.g. /vendor/etc/audio/sku_xxx/. Some vendor images carry the
            // configuration of several chips; Android only loads the phone's own (sku_<vendorSku>).
            // When the SKU can't be read, all of them are read, as before.
            children.filter { it.isDirectory && isOwnSku(it.name) }.forEach { sub ->
                sub.listFiles()?.filter { it.isFile && isRootPolicyName(it.name) }
                    ?.forEach { found += it }
            }
        }
        if (found.isEmpty()) {
            notes += PolicyNote.NoPolicyFile
        }
        return found.distinctBy { it.absolutePath }
    }

    private fun isOwnSku(dirName: String) =
        vendorSku == null || !dirName.startsWith("sku_") || dirName == "sku_$vendorSku"

    private fun isRootPolicyName(name: String) =
        name.startsWith("audio_policy_configuration") && name.endsWith(".xml")

    private fun canRead(f: File) = try {
        f.isFile && f.canRead()
    } catch (e: SecurityException) {
        false
    }

    private fun parseFileRecursive(
        file: File,
        visited: MutableSet<String>,
        readFiles: MutableList<String>,
        modules: MutableList<PolicyModule>,
        notes: MutableList<PolicyNote>,
    ) {
        if (!visited.add(file.absolutePath)) return
        val text = try {
            file.readText()
        } catch (e: Exception) {
            notes += PolicyNote.ReadError(file.absolutePath, e.javaClass.simpleName)
            return
        }
        readFiles += file.absolutePath
        val parsed = try {
            parsePolicyXml(text, file.absolutePath)
        } catch (e: Exception) {
            notes += PolicyNote.InvalidXml(file.absolutePath, e.message.orEmpty())
            return
        }
        modules += parsed.modules
        for (href in parsed.includes) {
            val included = resolveInclude(file.parentFile, href)
            if (included != null) {
                parseFileRecursive(included, visited, readFiles, modules, notes)
            } else {
                notes += PolicyNote.IncludeNotFound(href)
            }
        }
    }

    private fun resolveInclude(baseDir: File?, href: String): File? {
        val direct = if (href.startsWith("/")) File(href) else File(baseDir, href)
        if (canRead(direct)) return direct
        val name = File(href).name
        return searchDirs.map { File(it, name) }.firstOrNull { canRead(it) }
    }

    /**
     * Bit-perfect is only supported with the AIDL audio HAL
     * (android.hardware.audio.core). We look for it in the VINTF manifests;
     * not finding it isn't conclusive (it can ship inside an APEX).
     */
    private fun detectAidlAudioHal(): Boolean? {
        var anyRead = false
        for (dirPath in vintfDirs) {
            val f = File(dirPath)
            val files = when {
                f.isDirectory -> f.listFiles()?.filter { it.name.endsWith(".xml") } ?: emptyList()
                f.isFile -> listOf(f)
                else -> emptyList()
            }
            for (file in files) {
                val text = try {
                    file.readText()
                } catch (e: Exception) {
                    continue
                }
                anyRead = true
                if (text.contains("android.hardware.audio.core")) return true
            }
        }
        return if (anyRead) false else null
    }

    companion object {
        val DEFAULT_SEARCH_DIRS = listOf(
            "/odm/etc",
            "/vendor/etc/audio",
            "/vendor/etc",
            "/system/etc",
        )
        val DEFAULT_VINTF_DIRS = listOf(
            "/vendor/etc/vintf/manifest.xml",
            "/vendor/etc/vintf/manifest",
            "/odm/etc/vintf/manifest.xml",
            "/odm/etc/vintf/manifest",
        )

        private const val XINCLUDE_NS = "http://www.w3.org/2001/XInclude"

        /**
         * ro.boot.product.vendor.sku, which Android uses to pick /vendor/etc/audio/sku_<sku>/. Read
         * with getprop (there's no public API); null if it's empty or can't be read.
         */
        fun readVendorSku(): String? = try {
            val process = ProcessBuilder("getprop", "ro.boot.product.vendor.sku").redirectErrorStream(true).start()
            val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
        } catch (e: Exception) {
            null
        }

        /** Android-independent parser, so it can be tested on the JVM. */
        fun parsePolicyXml(xml: String, source: String): ParsedPolicyFile {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val p = factory.newPullParser()
            p.setInput(StringReader(xml))

            val modules = mutableListOf<PolicyModule>()
            val includes = mutableListOf<String>()

            var module: ModuleBuilder? = null
            var mixPort: MixPortBuilder? = null
            var devicePort: DevicePort? = null
            var deviceProfiles: MutableList<PolicyProfile>? = null

            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when {
                        p.name == "include" && (p.namespace == XINCLUDE_NS || p.prefix == "xi") ->
                            p.attr("href")?.let { includes += it }

                        p.name == "module" ->
                            module = ModuleBuilder(p.attr("name") ?: "?", source)

                        p.name == "mixPort" && module != null ->
                            mixPort = MixPortBuilder(
                                name = p.attr("name") ?: "?",
                                role = p.attr("role") ?: "",
                                flags = splitList(p.attr("flags")),
                            )

                        p.name == "devicePort" && module != null -> {
                            deviceProfiles = mutableListOf()
                            devicePort = DevicePort(
                                tagName = p.attr("tagName") ?: "?",
                                type = p.attr("type") ?: "",
                                role = p.attr("role") ?: "",
                                profiles = deviceProfiles,
                            )
                        }

                        p.name == "profile" -> {
                            val profile = PolicyProfile(
                                format = p.attr("format") ?: "",
                                samplingRates = splitList(p.attr("samplingRates"))
                                    .mapNotNull { it.toIntOrNull() },
                            )
                            when {
                                mixPort != null -> mixPort.profiles += profile
                                devicePort != null -> deviceProfiles?.add(profile)
                            }
                        }

                        p.name == "route" && module != null -> module.routes += PolicyRoute(
                            sink = p.attr("sink") ?: "",
                            sources = p.attr("sources")?.split(",")
                                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                        )
                    }

                    XmlPullParser.END_TAG -> when (p.name) {
                        "mixPort" -> {
                            mixPort?.let { module?.mixPorts?.add(it.build()) }
                            mixPort = null
                        }
                        "devicePort" -> {
                            devicePort?.let { module?.devicePorts?.add(it) }
                            devicePort = null
                            deviceProfiles = null
                        }
                        "module" -> {
                            module?.let { modules += it.build() }
                            module = null
                        }
                    }
                }
            }
            return ParsedPolicyFile(modules, includes)
        }

        private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

        /** Older versions separate with commas, the 7.0 format with spaces. */
        private fun splitList(value: String?): List<String> =
            value?.split(Regex("[,|\\s]+"))?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
    }

    private class ModuleBuilder(val name: String, val source: String) {
        val mixPorts = mutableListOf<MixPort>()
        val devicePorts = mutableListOf<DevicePort>()
        val routes = mutableListOf<PolicyRoute>()
        fun build() = PolicyModule(name, source, mixPorts, devicePorts, routes)
    }

    private class MixPortBuilder(val name: String, val role: String, val flags: List<String>) {
        val profiles = mutableListOf<PolicyProfile>()
        fun build() = MixPort(name, role, flags, profiles)
    }
}

data class ParsedPolicyFile(val modules: List<PolicyModule>, val includes: List<String>)

data class PolicyProfile(
    val format: String,
    val samplingRates: List<Int>,
) {
    val bitDepth: Int get() = PolicyFormats.bitDepth(format)
}

data class MixPort(
    val name: String,
    val role: String,
    val flags: List<String>,
    val profiles: List<PolicyProfile>,
) {
    val isOutput get() = role == "source"
    val isBitPerfect get() = flags.contains(PolicyFormats.FLAG_BIT_PERFECT)

    /** No profiles = dynamic port: it adopts whatever the connected device supports. */
    val isDynamic get() = profiles.isEmpty() ||
        profiles.all { it.format.isEmpty() && it.samplingRates.isEmpty() }
}

data class DevicePort(
    val tagName: String,
    val type: String,
    val role: String,
    val profiles: List<PolicyProfile>,
) {
    val isUsbOutput get() = role == "sink" && type in PolicyFormats.USB_OUT_TYPES
}

data class PolicyRoute(val sink: String, val sources: List<String>)

data class PolicyModule(
    val name: String,
    val source: String,
    val mixPorts: List<MixPort>,
    val devicePorts: List<DevicePort>,
    val routes: List<PolicyRoute>,
) {
    /** Output devices a mixPort can be routed to. */
    fun sinksOf(mixPort: MixPort): List<DevicePort> {
        val sinkNames = routes.filter { mixPort.name in it.sources }.map { it.sink }.toSet()
        return devicePorts.filter { it.tagName in sinkNames }
    }
}

data class BitPerfectPort(val module: String, val mixPort: MixPort, val sinks: List<DevicePort>) {
    val routesToUsb get() = sinks.any { it.isUsbOutput }
}

data class HiResPort(
    val module: String,
    val mixPort: MixPort,
    val maxRate: Int,
    val maxBits: Int,
)

data class Resolution(val rate: Int, val bits: Int)

/** Scan issues; the UI translates them with string resources. */
sealed interface PolicyNote {
    data object NoPolicyFile : PolicyNote
    data class ReadError(val path: String, val error: String) : PolicyNote
    data class InvalidXml(val path: String, val message: String) : PolicyNote
    data class IncludeNotFound(val href: String) : PolicyNote
}

data class PolicyScanResult(
    val filesRead: List<String>,
    val modules: List<PolicyModule>,
    /** true = AIDL HAL declared, false = not found, null = VINTF couldn't be read. */
    val aidlAudioHal: Boolean?,
    val notes: List<PolicyNote>,
) {
    val readable get() = filesRead.isNotEmpty() && modules.isNotEmpty()

    val bitPerfectPorts: List<BitPerfectPort>
        get() = modules.flatMap { m ->
            m.mixPorts.filter { it.isOutput && it.isBitPerfect }
                .map { BitPerfectPort(m.name, it, m.sinksOf(it)) }
        }

    /** There's a module with a USB output and some dynamic mixPort routed to it. */
    val usbDynamicOutput: Boolean
        get() = modules.any { m ->
            m.mixPorts.any { mp ->
                mp.isOutput && mp.isDynamic && m.sinksOf(mp).any { it.isUsbOutput }
            }
        }

    val usbOutputDeclared: Boolean
        get() = modules.any { m -> m.devicePorts.any { it.isUsbOutput } }

    /**
     * Highest sample rate and bit depth declared in fixed profiles of the USB outputs
     * (mixPorts routed to USB and the USB devicePorts themselves). null if there are none.
     */
    val usbMaxResolution: Resolution?
        get() {
            val profiles = modules.flatMap { m ->
                m.mixPorts.filter { mp -> mp.isOutput && m.sinksOf(mp).any { it.isUsbOutput } }
                    .flatMap { it.profiles } +
                    m.devicePorts.filter { it.isUsbOutput }.flatMap { it.profiles }
            }.filter { it.bitDepth > 0 && it.samplingRates.isNotEmpty() }
            if (profiles.isEmpty()) return null
            return Resolution(
                rate = profiles.maxOf { it.samplingRates.max() },
                bits = profiles.maxOf { it.bitDepth },
            )
        }

    /** Output mixPorts with a fixed profile of >= 192 kHz and >= 24 bits. */
    val hiResPorts: List<HiResPort>
        get() = modules.flatMap { m ->
            m.mixPorts.filter { it.isOutput }.mapNotNull { mp ->
                val hi = mp.profiles.filter {
                    it.bitDepth >= 24 && (it.samplingRates.maxOrNull() ?: 0) >= 192_000
                }
                if (hi.isEmpty()) null else HiResPort(
                    module = m.name,
                    mixPort = mp,
                    maxRate = hi.maxOf { it.samplingRates.max() },
                    maxBits = hi.maxOf { it.bitDepth },
                )
            }
        }
}

object PolicyFormats {
    const val FLAG_BIT_PERFECT = "AUDIO_OUTPUT_FLAG_BIT_PERFECT"

    val USB_OUT_TYPES = setOf(
        "AUDIO_DEVICE_OUT_USB_DEVICE",
        "AUDIO_DEVICE_OUT_USB_HEADSET",
        "AUDIO_DEVICE_OUT_USB_ACCESSORY",
    )

    fun bitDepth(format: String): Int = when (format) {
        "AUDIO_FORMAT_PCM_8_BIT" -> 8
        "AUDIO_FORMAT_PCM_16_BIT" -> 16
        "AUDIO_FORMAT_PCM_24_BIT_PACKED", "AUDIO_FORMAT_PCM_8_24_BIT" -> 24
        "AUDIO_FORMAT_PCM_32_BIT", "AUDIO_FORMAT_PCM_FLOAT" -> 32
        else -> 0
    }
}
