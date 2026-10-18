package com.bitperfect.detector

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
import com.bitperfect.detector.report.ReportEmail
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

        setContent {
            BitPerfectTheme {
                MainScreen(
                    report = report,
                    onRefresh = { refresh(rescanPolicy = true) },
                    scanning = scanning,
                    onSend = ::sendReport,
                )
            }
        }
        refresh(rescanPolicy = true)
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        audioManager.addOnPreferredMixerAttributesChangedListener(mainExecutor, mixerListener)
        refresh()
    }

    override fun onStop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnPreferredMixerAttributesChangedListener(mixerListener)
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

    /** Opens the user's email app with the report ready to send to the developer. */
    private fun sendReport() {
        val r = report ?: return
        if (!ReportEmail.open(this, r)) {
            Toast.makeText(this, R.string.no_email_app, Toast.LENGTH_LONG).show()
        }
    }
}
