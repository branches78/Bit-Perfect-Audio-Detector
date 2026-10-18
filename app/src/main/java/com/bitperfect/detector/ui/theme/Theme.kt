package com.bitperfect.detector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Status colors, the same in light and dark mode except for the shade. */
data class StatusColors(val yes: Color, val likely: Color, val no: Color, val unknown: Color)

fun statusColors(dark: Boolean) = if (dark) {
    StatusColors(Color(0xFF6FD8A0), Color(0xFF9CCBFF), Color(0xFFFF9A8F), Color(0xFFB8B8C0))
} else {
    StatusColors(Color(0xFF1E8E5A), Color(0xFF2F6FB5), Color(0xFFC0392B), Color(0xFF6B6B75))
}

@Composable
fun BitPerfectTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // minSdk 34: dynamic color (Android 12+) is always available.
    val scheme = when {
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

