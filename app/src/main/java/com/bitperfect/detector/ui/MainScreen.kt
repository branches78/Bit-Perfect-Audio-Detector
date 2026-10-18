package com.bitperfect.detector.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bitperfect.detector.BuildConfig
import com.bitperfect.detector.R
import com.bitperfect.detector.audio.AudioRuntimeProbe
import com.bitperfect.detector.audio.BitPerfectApp
import com.bitperfect.detector.audio.BitPerfectAppScanner
import com.bitperfect.detector.audio.Level
import com.bitperfect.detector.audio.PolicyNote
import com.bitperfect.detector.audio.Report
import com.bitperfect.detector.audio.Verdict
import com.bitperfect.detector.audio.VerdictEngine
import com.bitperfect.detector.ui.theme.statusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    report: Report?,
    onRefresh: () -> Unit,
    scanning: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onOpenReport: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showDetails && report != null) {
        TechnicalDetailsDialog(
            report = report,
            onSend = { showDetails = false; onSend() },
            onDismiss = { showDetails = false },
        )
    }
    if (showHelp && report != null) {
        HelpDialog(onDismiss = { showHelp = false })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bit-Perfect Detector") },
                actions = {
                    IconButton(onClick = { showHelp = true }, enabled = report != null) {
                        Icon(
                            painterResource(R.drawable.ic_help_outline),
                            contentDescription = stringResource(R.string.action_help),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        if (report == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DeviceHeader() }
            item { VerdictCard(stringResource(R.string.card_usb_dac), report.usbDac) }
            item { VerdictCard(stringResource(R.string.card_hi_res), report.hiRes) }
            item { VerdictCard(stringResource(R.string.card_bit_perfect), report.bitPerfect) }
            item {
                VerdictCard(stringResource(R.string.card_bp_apps), report.apps) {
                    InstalledApps(report.installedApps)
                    SuggestedApps(report.installedApps)
                }
            }
            // Any phone can send its report: the website's table also lists phones without support.
            item {
                ContributeCard(sending = sending, onSend = { onOpenReport(); showDetails = true })
            }
        }
    }
}

/** Invitation to send the report, with a link to the website (where the phone database is). */
@Composable
private fun ContributeCard(sending: Boolean, onSend: () -> Unit) {
    val context = LocalContext.current
    val site = BuildConfig.WEBSITE_URL
    ExpandableCard(
        title = stringResource(R.string.action_send_report),
        headline = stringResource(R.string.contribute_title),
        icon = Icons.AutoMirrored.Filled.Send,
        tint = MaterialTheme.colorScheme.primary,
    ) {
        // The link to the website sits inside the text itself, on "application's website".
        val linkWord = stringResource(R.string.contribute_link)
        val text = stringResource(R.string.contribute_text, linkWord)
        val linkStyle = TextLinkStyles(
            SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
        )
        Text(
            buildAnnotatedString {
                append(text)
                val start = text.indexOf(linkWord)
                if (start >= 0) {
                    addLink(LinkAnnotation.Clickable("website", linkStyle) { openUrl(context, site) }, start, start + linkWord.length)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Button(onClick = onSend, enabled = !sending) {
                Text(stringResource(R.string.action_send_report))
            }
        }
    }
}

/** Installed apps that use native bit-perfect, below the text that introduces them. */
@Composable
private fun InstalledApps(installed: List<BitPerfectApp>) {
    if (installed.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        installed.forEach { app ->
            Text("· ${app.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Known compatible apps (BitPerfectAppScanner.SUGGESTED_APPS) that aren't installed, linked to the Play Store. */
@Composable
private fun SuggestedApps(installed: List<BitPerfectApp>) {
    val context = LocalContext.current
    val installedPackages = installed.map { it.packageName }.toSet()
    val others = BitPerfectAppScanner.SUGGESTED_APPS.filter { it.packageName !in installedPackages }
    if (others.isEmpty()) return
    // With no apps installed, the card's text already introduces this list.
    if (installed.isNotEmpty()) {
        Text(
            stringResource(R.string.bp_apps_others),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    others.forEach { (name, pkg) ->
        Text(
            "· $name",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { openPlayStore(context, pkg) }
                .padding(vertical = 4.dp),
        )
    }
}

/** Opens the listing in the Play Store app, or in the browser if it isn't installed. */
private fun openPlayStore(context: Context, pkg: String) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
    }
}

/** Opens a link; shows a message instead of crashing if no app on the phone can open it. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open_link, Toast.LENGTH_LONG).show()
    }
}

/**
 * Brand with a capital first letter and model without the repeated brand: some manufacturers include
 * it in Build.MODEL ("motorola" + "motorola edge 50 neo" → "Motorola" + "edge 50 neo").
 * Same as cleanPhoneName in the website's code.
 */
fun cleanPhoneName(manufacturer: String, model: String): Pair<String, String> {
    val brand = manufacturer.replaceFirstChar { it.uppercaseChar() }
    val prefix = "$manufacturer "
    val shortModel = if (model.startsWith(prefix, ignoreCase = true)) model.substring(prefix.length).trim() else model
    return brand to shortModel.ifBlank { model }
}

/** "Brand model" without repeating the brand ("Motorola edge 50 neo"). */
fun phoneName(
    manufacturer: String = android.os.Build.MANUFACTURER,
    model: String = android.os.Build.MODEL,
): String = cleanPhoneName(manufacturer, model).let { (brand, shortModel) -> "$brand $shortModel" }

@Composable
private fun DeviceHeader() {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            phoneName(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VerdictCard(
    title: String,
    verdict: Verdict,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = statusColors(isSystemInDarkTheme())
    val (tint, icon) = when (verdict.level) {
        Level.YES -> colors.yes to Icons.Filled.CheckCircle
        Level.LIKELY -> colors.likely to Icons.Filled.Info
        Level.NO -> colors.no to Icons.Filled.Close
        Level.UNKNOWN -> colors.unknown to Icons.Filled.Warning
    }
    // With no detail or extra content (e.g. USB DAC) there's nothing to expand.
    val body: (@Composable () -> Unit)? =
        if (verdict.detail.isBlank() && extra == null) {
            null
        } else {
            {
                if (verdict.detail.isNotBlank()) {
                    Text(verdict.detail, style = MaterialTheme.typography.bodyMedium)
                }
                extra?.invoke()
            }
        }
    ExpandableCard(title, verdict.headline, icon, tint, body)
}

/**
 * Collapsible card: it always shows the title, the status icon and the main result; tapping it
 * expands the rest ([body]). It starts collapsed. Without [body] it can't be expanded.
 */
@Composable
private fun ExpandableCard(
    title: String,
    headline: String,
    icon: ImageVector,
    tint: Color,
    body: (@Composable () -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Card(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (body != null) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(16.dp),
        ) {
            StatusIcon(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (body != null) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }
        }
        if (body != null) {
            AnimatedVisibility(expanded) {
                // The detail runs to the card's edge (same margin as the icon), below a line that
                // separates it from the always-visible part.
                Column {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) { body() }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(32.dp).background(tint.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sections = listOf(
        R.string.help_bp_title to stringResource(R.string.help_bp),
        R.string.help_android_title to stringResource(R.string.help_android),
        R.string.help_app_title to stringResource(R.string.help_app),
        R.string.help_results_title to
            stringResource(R.string.help_no_dac) + "\n\n" + stringResource(R.string.help_dac),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.action_help), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        // The text doesn't fit on screen: the dialog grows to the available height and the rest scrolls.
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sections.forEachIndexed { i, (title, body) ->
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = if (i > 0) Modifier.padding(top = 10.dp) else Modifier,
                    )
                    Text(withLinks(body, context), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = onDismiss) { Text(stringResource(R.string.button_ok)) }
            }
        },
    )
}

/** Markdown-style link in the texts shared with the website: [text](https://…). */
private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\((https://[^)\s]+)\)""")

/** The text with its [text](https://…) links turned into tappable links (same format as the website). */
@Composable
private fun withLinks(text: String, context: Context): AnnotatedString {
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        var last = 0
        for (m in MARKDOWN_LINK.findAll(text)) {
            append(text.substring(last, m.range.first))
            val (label, url) = m.destructured
            withLink(LinkAnnotation.Clickable(url, linkStyle) { openUrl(context, url) }) { append(label) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Composable
private fun TechnicalDetailsDialog(report: Report, onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.details_title), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            val context = LocalContext.current
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.report_published_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ReportText.published(context, report).forEach { (label, value) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                            append(": $value")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // The server replaces internal model codes (e.g. Xiaomi "25113PN0EI") with the
                // commercial name from Google Play's list, so the published name may differ.
                Text(
                    stringResource(R.string.report_name_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.report_technical_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                Text(
                    ReportText.technical(context, report),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.privacy_policy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable { openUrl(context, "${BuildConfig.WEBSITE_URL}/privacy") }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
                Button(onClick = onSend) { Text(stringResource(R.string.button_send)) }
            }
        },
    )
}

object ReportText {
    /** What will be published in the website's table (one row per field), in the app's language. */
    fun published(c: Context, r: Report): List<Pair<String, String>> {
        fun res(rate: Int, bits: Int) = c.getString(R.string.field_resolution, VerdictEngine.khz(c, rate), bits)
        val none = "—"
        val declared = VerdictEngine.declaredUsbResolution(r.policy)
        val noDac = when {
            declared != null -> res(declared.rate, declared.bits)
            r.policy.usbDynamicOutput -> c.getString(R.string.h_dac_dependent)
            else -> none
        }
        val dac = r.runtime.usbDacs.maxByOrNull { it.maxSampleRate }
        return listOf(
            c.getString(R.string.field_model) to phoneName(),
            c.getString(R.string.field_android) to android.os.Build.VERSION.RELEASE,
            c.getString(R.string.card_bit_perfect) to r.bitPerfect.headline,
            c.getString(R.string.field_res_no_dac) to noDac,
            c.getString(R.string.field_res_dac) to
                (dac?.takeIf { it.maxSampleRate > 0 }?.let { res(it.maxSampleRate, it.maxBitDepth) } ?: none),
            c.getString(R.string.card_usb_dac) to (dac?.displayName?.ifBlank { null } ?: none),
            c.getString(R.string.card_bp_apps) to r.installedApps.joinToString { it.name }.ifBlank { none },
        )
    }

    /** Short summary of the results for the report (the technical details go separately). */
    fun summary(c: Context, r: Report): String = buildString {
        appendLine(c.getString(R.string.app_name))
        appendLine("${phoneName()} · Android ${android.os.Build.VERSION.RELEASE}")
        appendLine()
        appendLine("${c.getString(R.string.card_usb_dac)}: ${r.usbDac.headline}")
        appendLine("${c.getString(R.string.card_hi_res)}: ${r.hiRes.headline}. ${r.hiRes.detail}")
        appendLine("${c.getString(R.string.card_bit_perfect)}: ${r.bitPerfect.headline}. ${r.bitPerfect.detail}")
        appendLine("${c.getString(R.string.card_bp_apps)}: ${r.apps.headline}")
        r.installedApps.forEach { appendLine("· ${it.name} (${it.packageName})") }
    }

    fun technical(c: Context, r: Report): String = buildString {
        val rt = r.runtime
        val p = r.policy
        fun yesNo(b: Boolean) = c.getString(if (b) R.string.yes else R.string.no)

        appendLine(c.getString(R.string.tech_usb_pro, yesNo(rt.usbHostSupported), yesNo(rt.proAudio)))
        rt.mixerSampleRate?.let { appendLine(c.getString(R.string.tech_mixer_rate, it)) }
        append(c.getString(R.string.tech_direct, yesNo(rt.directHiResOnCurrentRoute)))
        if (rt.directMaxRate > 0) {
            append(" (${c.getString(R.string.tech_max, VerdictEngine.khz(c, rt.directMaxRate), rt.directMaxBits)})")
        }
        appendLine()
        appendLine()
        appendLine(c.getString(R.string.tech_outputs))
        rt.devices.forEach { d ->
            append("· ${AudioRuntimeProbe.typeName(c, d.type)}")
            // Only USB devices (DACs) are named: Bluetooth devices often carry a name the user gave
            // them ("John's AirPods"), and the report must stay anonymous.
            if (d.isUsb && d.productName.isNotBlank()) append(" \"${d.productName}\"")
            if (d.maxSampleRate > 0) append(" · ${VerdictEngine.khz(c, d.maxSampleRate)}/${d.maxBitDepth}b")
            if (d.isUsb) {
                append(" · " + c.getString(R.string.tech_mixer_attrs, d.mixerAttributesCount))
                append(" · " + c.getString(R.string.tech_bp_supported, yesNo(d.bitPerfectSupported)))
                d.preferred?.let {
                    val description = "${AudioRuntimeProbe.behaviorName(c, it.behavior)} · ${it.sampleRate} Hz · ${it.bits} bits"
                    append(" · " + c.getString(R.string.tech_preferred, description))
                }
            }
            appendLine()
        }
        appendLine()
        appendLine(
            c.getString(
                R.string.tech_aidl,
                when (p.aidlAudioHal) {
                    true -> c.getString(R.string.yes)
                    false -> c.getString(R.string.tech_not_found)
                    null -> c.getString(R.string.tech_unknown)
                },
            ),
        )
        appendLine(c.getString(R.string.tech_files, p.filesRead.size))
        p.filesRead.forEach { appendLine("· $it") }
        appendLine(c.getString(R.string.tech_modules, p.modules.joinToString { it.name }.ifBlank { "-" }))
        appendLine(c.getString(R.string.tech_usb_declared, yesNo(p.usbOutputDeclared), yesNo(p.usbDynamicOutput)))
        appendLine(c.getString(R.string.tech_bp_ports))
        if (p.bitPerfectPorts.isEmpty()) appendLine("· " + c.getString(R.string.tech_none))
        p.bitPerfectPorts.forEach { bp ->
            appendLine("· ${bp.module}/${bp.mixPort.name} → ${bp.sinks.joinToString { it.tagName }.ifBlank { "?" }}")
        }
        appendLine(c.getString(R.string.tech_hires_ports))
        if (p.hiResPorts.isEmpty()) appendLine("· " + c.getString(R.string.tech_none))
        p.hiResPorts.forEach { h ->
            appendLine("· ${h.module}/${h.mixPort.name}: ${VerdictEngine.khz(c, h.maxRate)}/${h.maxBits}b")
        }
        p.notes.forEach { appendLine(c.getString(R.string.tech_note, noteText(c, it))) }
    }

    private fun noteText(c: Context, n: PolicyNote): String = when (n) {
        PolicyNote.NoPolicyFile -> c.getString(R.string.note_no_policy_file)
        is PolicyNote.ReadError -> c.getString(R.string.note_read_error, n.path, n.error)
        is PolicyNote.InvalidXml -> c.getString(R.string.note_invalid_xml, n.path, n.message)
        is PolicyNote.IncludeNotFound -> c.getString(R.string.note_include_not_found, n.href)
    }
}
