@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightPalette =
    lightColorScheme(
        primary = Color(0xFF143B6B),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF3F5F8C),
        tertiary = Color(0xFF17746B),
        background = Color(0xFFF4F6F8),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE2E8F0),
        onSurface = Color(0xFF111827),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFC7D2E0),
    )

private val DarkPalette =
    darkColorScheme(
        primary = Color(0xFF7DB6FF),
        onPrimary = Color(0xFF06203F),
        secondary = Color(0xFFA6C3F2),
        tertiary = Color(0xFF7EDFD3),
        background = Color(0xFF06111E),
        surface = Color(0xFF0B1727),
        surfaceVariant = Color(0xFF132336),
        onSurface = Color(0xFFF8FAFC),
        onSurfaceVariant = Color(0xFFBAC5D3),
        outline = Color(0xFF30445C),
    )

private val ReferenceTypography =
    Typography(
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                lineHeight = 40.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 24.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
    )

/** Editorial-technical Material 3 wrapper for the reference app. */
@Composable
public fun ReferenceTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkPalette else LightPalette,
        typography = ReferenceTypography,
        content = content,
    )
}
