package com.bitperfect.detector.audio

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Finds, among the installed music apps, those that use Android 14+'s native bit-perfect mode.
 *
 * There's no API to ask, so the scanner reads each app's compiled code (classes*.dex) and looks
 * for the call to AudioManager.setPreferredMixerAttributes: it's the only way to request
 * bit-perfect mode, and R8/ProGuard can't rename system methods.
 * Apps with their own USB driver (e.g. UAPP) don't use that API, so they don't show up.
 */
class BitPerfectAppScanner(private val context: Context) {

    private val pm = context.packageManager

    fun scan(): List<BitPerfectApp> = musicApps()
        // Apps the user disabled in Settings don't count (they can't play anything).
        .filter { it.enabled }
        .filter { it.packageName != context.packageName && it.packageName !in EXCLUDED_PACKAGES }
        .filter { usesBitPerfectApi(it) }
        .map { BitPerfectApp(pm.getApplicationLabel(it).toString(), it.packageName) }
        .sortedBy { it.name.lowercase() }

    /**
     * Apps that declare themselves audio players (visible thanks to <queries> in the manifest), plus
     * the known compatible apps, which are looked up by package name: Android sometimes leaves an
     * app out of the intent queries even though it matches (seen with PureBit on Android 16).
     */
    private fun musicApps(): Collection<ApplicationInfo> {
        val flags = PackageManager.ResolveInfoFlags.of(0)
        val found = mutableListOf<ResolveInfo>()
        for (uri in listOf("content://x/x.flac", "file:///x.flac")) {
            found += pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "audio/*"),
                flags,
            )
        }
        found += pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            flags,
        )
        for (action in MEDIA_SERVICE_ACTIONS) {
            found += pm.queryIntentServices(Intent(action), flags)
        }
        val known = SUGGESTED_APPS.mapNotNull { app ->
            try {
                pm.getApplicationInfo(app.packageName, PackageManager.ApplicationInfoFlags.of(0))
            } catch (e: PackageManager.NameNotFoundException) {
                null // not installed
            }
        }
        return (found.mapNotNull { (it.activityInfo ?: it.serviceInfo)?.applicationInfo } + known)
            .associateBy { it.packageName }
            .values
    }

    private fun usesBitPerfectApi(app: ApplicationInfo): Boolean {
        val apks = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        return apks.any { path ->
            try {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence()
                        .filter { DEX_NAME.matches(it.name) }
                        .any { entry -> zip.getInputStream(entry).use { containsBytes(it, MARKER) } }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        /**
         * Apps known to use native bit-perfect: suggested in the app when they aren't installed, and
         * always checked when they are. Their packages are also listed in <queries> in the manifest.
         */
        val SUGGESTED_APPS = listOf(
            BitPerfectApp("Symfonium", "app.symfonik.music.player"),
            BitPerfectApp("Hi-Res Radio - FLAC & Lossless", "com.frack.hifiradio"),
            BitPerfectApp("Plexamp", "tv.plex.labs.plexamp"),
            BitPerfectApp("NostalgicPod - Retro Player", "com.nostalgicpod.app"),
            BitPerfectApp("Audiophile Music Player", "com.androidexpert35.audiophilemusicplayer"),
            BitPerfectApp("PureBit", "app.purebit"),
            BitPerfectApp("Omnia Music Player", "com.rhmsoft.omnia"),
            BitPerfectApp("Audilux Music Player", "com.audilux.musicplayer.mp3player"),
            BitPerfectApp("jetAudio Hi-Res Music Player", "com.jetappfactory.jetaudio"),
            BitPerfectApp("LastWave", "com.lastwave.app", website = "https://lastwave.app/"),
        )

        /** Name of the system method that turns on bit-perfect mode. */
        private val MARKER = "setPreferredMixerAttributes".toByteArray()

        private val DEX_NAME = Regex("""classes\d*\.dex""")

        private val MEDIA_SERVICE_ACTIONS = listOf(
            "android.media.browse.MediaBrowserService",
            "androidx.media3.session.MediaSessionService",
            "androidx.media3.session.MediaLibraryService",
        )

        /** Apps with their own USB driver that could contain the call without relying on it. */
        private val EXCLUDED_PACKAGES = setOf(
            "com.extreamsd.usbaudioplayerpro",
        )

        /** Looks for [marker] in the stream, reading it in blocks without loading it all into memory. */
        fun containsBytes(input: InputStream, marker: ByteArray): Boolean {
            val overlap = marker.size - 1
            val buf = ByteArray(64 * 1024 + overlap)
            var kept = 0
            while (true) {
                val n = input.read(buf, kept, buf.size - kept)
                if (n <= 0) return false
                val end = kept + n
                if (indexOf(buf, end, marker) >= 0) return true
                // Keep the tail in case the marker is split across two blocks.
                kept = minOf(overlap, end)
                System.arraycopy(buf, end - kept, buf, 0, kept)
            }
        }

        private fun indexOf(buf: ByteArray, end: Int, marker: ByteArray): Int {
            outer@ for (i in 0..end - marker.size) {
                for (j in marker.indices) {
                    if (buf[i + j] != marker[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}

/** [website]: where to get the app when it isn't on Google Play (otherwise its Play Store listing is used). */
data class BitPerfectApp(val name: String, val packageName: String, val website: String? = null)
