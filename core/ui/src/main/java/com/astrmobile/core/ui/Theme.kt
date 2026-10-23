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

/**
 * 配色原则：
 * 1. 正文一律用接近白的 onSurface，不再用主色写正文——青绿在深底上做强调好看，
 *    撑起整段文字会发灰、看久了累。
 * 2. 背景 / 卡片 / 次级容器拉开三档明度，卡片靠明度浮起，而不是靠一条线。
 * 3. 次要文字（说明、时间戳）单独一档亮度：看清，但不和正文抢。
 */
private val Ink = Color(0xFF0E1116)
private val InkElevated = Color(0xFF181E26)
private val InkSurfaceHigh = Color(0xFF222A35)
private val Mint = Color(0xFF4FE3A4)
private val MintDeep = Color(0xFF1B4B3C)
private val Amber = Color(0xFFFFC46B)
private val Rose = Color(0xFFFF7B72)
private val Paper = Color(0xFFF8FAF9)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF05231A),
    primaryContainer = MintDeep,
    onPrimaryContainer = Color(0xFFD8F8EA),
    secondary = Amber,
    onSecondary = Color(0xFF2B1A00),
    secondaryContainer = Color(0xFF3A2A12),
    onSecondaryContainer = Color(0xFFFFE3B8),
    background = Ink,
    onBackground = Color(0xFFF2F6F4),
    surface = InkElevated,
    onSurface = Color(0xFFF2F6F4),
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = Color(0xFFBAC7C2),
    surfaceContainer = InkElevated,
    surfaceContainerHigh = InkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF2B3542),
    error = Rose,
    onError = Color(0xFF2B0705),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A4552),
    outlineVariant = Color(0xFF2A3440),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F4E4),
    onPrimaryContainer = Color(0xFF04241A),
    secondary = Color(0xFF8A5200),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Color(0xFF111614),
    surface = Color.White,
    onSurface = Color(0xFF111614),
    surfaceVariant = Color(0xFFE9F0EC),
    onSurfaceVariant = Color(0xFF4A5751),
    surfaceContainer = Color(0xFFF0F4F2),
    surfaceContainerHigh = Color(0xFFE9F0EC),
    surfaceContainerHighest = Color(0xFFE2EAE6),
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = Color(0xFFB6C4BE),
    outlineVariant = Color(0xFFD2DDD8),
)

/**
 * 字号整体上调一档。
 *
 * 中文在 14sp 以下、行高 20sp 时会显得糊，深色背景上尤其明显。
 * 正文 15sp / 行高 23sp 是手机上读长句比较舒服的位置。
 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    // 终端风格的标签（页面标题、插件目录名）保留等宽，但从 11sp 提到 12sp
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        letterSpacing = 0.3.sp,
        lineHeight = 16.sp,
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
        shapes = AppShapes,
        content = content,
    )
}

/** 日志控制台专用配色：同样按"正文要看清"的原则提亮。 */
object LogColors {
    val Info = Color(0xFFC2CFC9)
    val Success = Color(0xFF4FE3A4)
    val Warning = Color(0xFFFFC46B)
    val Error = Color(0xFFFF8A80)
    val Timestamp = Color(0xFF7C8B93)
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
