package com.astrmobile.core.ui

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

/** 终端气质的主色：青绿 + 深墨，避免"又一个聊天 App"的观感。 */
private val Ink = Color(0xFF0B0F14)
private val InkElevated = Color(0xFF121820)
private val Mint = Color(0xFF3DDC97)
private val MintDim = Color(0xFF2AA876)
private val Amber = Color(0xFFFFB454)
private val Rose = Color(0xFFFF6B6B)
private val Paper = Color(0xFFF7F9F8)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = MintDim,
    onPrimaryContainer = Paper,
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFE6EDEA),
    surface = InkElevated,
    onSurface = Color(0xFFE6EDEA),
    surfaceVariant = Color(0xFF1B232D),
    onSurfaceVariant = Color(0xFF9FB0AA),
    error = Rose,
    onError = Ink,
    outline = Color(0xFF2C3947),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF167A57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F5E8),
    onPrimaryContainer = Color(0xFF06281C),
    secondary = Color(0xFF9A5B00),
    background = Paper,
    onBackground = Color(0xFF101614),
    surface = Color.White,
    onSurface = Color(0xFF101614),
    surfaceVariant = Color(0xFFE8EFEC),
    onSurfaceVariant = Color(0xFF44524D),
    error = Color(0xFFB3261E),
    outline = Color(0xFFC3CFCA),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun AstrMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/** 日志控制台专用配色。 */
object LogColors {
    val Info = Color(0xFF9FB0AA)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB454)
    val Error = Color(0xFFFF6B6B)
    val Timestamp = Color(0xFF5E6E78)
}

fun classifyLogLine(line: String): Color = when {
    line.contains("error", ignoreCase = true) ||
        line.contains("failed", ignoreCase = true) ||
        line.contains("exception", ignoreCase = true) ||
        line.contains("失败") -> LogColors.Error

    line.contains("warn", ignoreCase = true) -> LogColors.Warning
    line.contains("完成") || line.contains("ready", ignoreCase = true) ||
        line.contains("启动", ignoreCase = true) -> LogColors.Success

    else -> LogColors.Info
}
