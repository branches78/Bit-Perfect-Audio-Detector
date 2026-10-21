package com.bitperfect.detector

import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.bitperfect.detector.audio.AudioRuntimeProbe
import com.bitperfect.detector.audio.BitPerfectApp
import com.bitperfect.detector.audio.BitPerfectAppScanner
import com.bitperfect.detector.audio.PolicyConfigScanner
import com.bitperfect.detector.audio.PolicyScanResult
import com.bitperfect.detector.audio.Report
import com.bitperfect.detector.audio.VerdictEngine
import com.bitperfect.detector.report.ReportSender
import com.bitperfect.detector.report.SendResult
import com.bitperfect.detector.report.TestDevice
import com.bitperfect.detector.ui.MainScreen
import com.bitperfect.detector.ui.theme.BitPerfectTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var audioManager: AudioManager
    private lateinit var probe: AudioRuntimeProbe

    private var report by mutableStateOf<Report?>(null)
    private var sending by mutableStateOf(false)

    /** Google Play's test devices and emulators don't offer to send a report. */
    private var canReport = true

    /** This phone already sent [MAX_REPORTS] reports in the last 24 hours; "Send report" waits. */
    private var rateLimited by mutableStateOf(false)
    private val rateLimitEnded = Runnable { updateRateLimit() }

    /** A full scan (policy and apps) is running or queued; "Analyze again" waits for it. */
    private var scanning by mutableStateOf(false)
    private var policy: PolicyScanResult? = null
    private var installedApps: List<BitPerfectApp>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * On launch, onCreate, onStart and the device callback (which Android calls right after it's
     * registered) all ask for a refresh. Running them one at a time means only the first one does
     * the slow scans; the rest reuse their results.
     */
    private val refreshLock = Mutex()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val mixerListener =
        AudioManager.OnPreferredMixerAttributesChangedListener { _, _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        audioManager = getSystemService(AudioManager::class.java)
        probe = AudioRuntimeProbe(this)
        canReport = !TestDevice.isAutomated(this)

        setContent {
            BitPerfectTheme {
                MainScreen(
                    report = report,
                    onRefresh = { refresh(rescanPolicy = true) },
                    scanning = scanning,
                    canReport = canReport,
                    sending = sending,
                    rateLimited = rateLimited,
                    onSend = ::sendReport,
                    // While the user reads the send dialog, the report server wakes up.
                    onOpenReport = { lifecycleScope.launch { ReportSender.wakeUpServer() } },
                )
            }
        }
        refresh(rescanPolicy = true)
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        audioManager.addOnPreferredMixerAttributesChangedListener(mainExecutor, mixerListener)
        updateRateLimit()
        refresh()
    }

    override fun onStop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnPreferredMixerAttributesChangedListener(mixerListener)
        mainHandler.removeCallbacks(rateLimitEnded)
        super.onStop()
    }

    private fun refresh(rescanPolicy: Boolean = false) {
        if (rescanPolicy) {
            if (scanning) return
            scanning = true
        }
        lifecycleScope.launch {
            try {
                refreshLock.withLock { doRefresh(rescanPolicy) }
            } finally {
                if (rescanPolicy) scanning = false
            }
        }
    }

    private suspend fun doRefresh(rescanPolicy: Boolean) {
        val scanned = policy.takeIf { !rescanPolicy }
            ?: withContext(Dispatchers.IO) { PolicyConfigScanner().scan() }
        policy = scanned
        // Reading the apps' code takes a few seconds: only on launch and when analyzing again.
        val apps = installedApps.takeIf { !rescanPolicy }
            ?: withContext(Dispatchers.IO) { BitPerfectAppScanner(this@MainActivity).scan() }
        installedApps = apps
        val runtime = probe.probe()
        report = VerdictEngine.build(this@MainActivity, runtime, scanned, apps)
    }

    private fun sendReport() {
        val r = report ?: return
        if (sending || !canReport) return
        // At most two reports every 24 hours; the server has the same per-IP limit as a backup.
        val prefs = getSharedPreferences("reports", MODE_PRIVATE)
        val recent = recentSentTimes(prefs)
        if (recent.size >= MAX_REPORTS) {
            updateRateLimit()
            Toast.makeText(this, R.string.report_rate_limited, Toast.LENGTH_LONG).show()
            return
        }
        sending = true
        Toast.makeText(this, R.string.report_sending, Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val result = ReportSender.send(this@MainActivity, r)
            if (result == SendResult.SENT) {
                val times = (recent + System.currentTimeMillis()).takeLast(MAX_REPORTS)
                prefs.edit().putString(KEY_SENT_AT, times.joinToString(",")).remove(KEY_LAST_SENT).apply()
                updateRateLimit()
            }
            val message = when (result) {
                SendResult.SENT -> R.string.report_sent
                // This phone passed its own check, so other reports came from the same IP.
                SendResult.RATE_LIMITED -> R.string.report_rate_limited_network
                SendResult.BUSY -> R.string.report_busy
                SendResult.FAILED -> R.string.report_failed
            }
            sending = false
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Disables "Send report" while this phone has used up its reports, and enables it again when
     * the oldest of them is 24 hours old. Also run on every onStart, since the handler's clock
     * stops while the phone sleeps.
     */
    private fun updateRateLimit() {
        mainHandler.removeCallbacks(rateLimitEnded)
        val recent = recentSentTimes(getSharedPreferences("reports", MODE_PRIVATE))
        rateLimited = recent.size >= MAX_REPORTS
        if (rateLimited) {
            val wait = recent.min() + REPORT_INTERVAL_MS - System.currentTimeMillis()
            mainHandler.postDelayed(rateLimitEnded, wait.coerceAtLeast(0) + 1000)
        }
    }

    /** The reports this phone sent in the last 24 hours, in milliseconds. */
    private fun recentSentTimes(prefs: SharedPreferences): List<Long> {
        val now = System.currentTimeMillis()
        return sentTimes(prefs).filter { now - it in 0 until REPORT_INTERVAL_MS }
    }

    /** When this phone sent its latest reports (up to [MAX_REPORTS]), in milliseconds. */
    private fun sentTimes(prefs: SharedPreferences): List<Long> {
        val times = prefs.getString(KEY_SENT_AT, null)?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
        // Versions up to 1.0.4 kept only the last one.
        val legacy = prefs.getLong(KEY_LAST_SENT, 0L).takeIf { it > 0 }
        return listOfNotNull(legacy) + times
    }

    private companion object {
        const val KEY_SENT_AT = "sent_at"
        const val KEY_LAST_SENT = "last_sent_at"
        const val MAX_REPORTS = 2
        const val REPORT_INTERVAL_MS = 24 * 3600_000L
    }
}
