package com.bitperfect.detector.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bitperfect.detector.R

private const val TITLE = "Bit-Perfect Audio Detector"
private val ICON_SIZE = 32.dp
private val ICON_GAP = 10.dp

/**
 * The app's icon (as on the launcher: the dongle on its navy square, which reads on light and dark
 * top bars) beside the title. On narrow screens or with large text, the icon and the title shrink
 * together so the title stays on one line.
 */
@Composable
fun AppTitle() {
    BoxWithConstraints {
        val style = LocalTextStyle.current
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val textWidth = remember(style, density) { measurer.measure(TITLE, style, maxLines = 1).size.width }
        val full = with(density) { textWidth.toDp() } + ICON_SIZE + ICON_GAP
        // A 2% margin so rounding never clips the last letter.
        val scale = if (full > maxWidth) maxWidth / full * 0.98f else 1f
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ICON_GAP * scale),
        ) {
            val icon = ICON_SIZE * scale
            Box(
                Modifier.size(icon).clip(RoundedCornerShape(icon / 4))
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                // The foreground's 108-unit canvas has a wide margin around the dongle: drawn larger and clipped.
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(icon * 1.4f),
                )
            }
            Text(TITLE, style = style.copy(fontSize = style.fontSize * scale), maxLines = 1, softWrap = false)
        }
    }
}
