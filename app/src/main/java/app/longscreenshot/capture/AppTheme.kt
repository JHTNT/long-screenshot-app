package app.longscreenshot.capture

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val DefaultAccent = Color(0xFF72538F)
internal const val PreferencesName = "appearance"
internal const val AccentPreference = "accent"
internal const val SystemColorsPreference = "systemColors"

internal fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

internal fun appColorScheme(dark: Boolean, accent: Color): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val target = if (dark) Color.White else Color.Black
    val seed = if (dark && accent == DefaultAccent) Color(0xFFDBB9F7) else accent
    // 自訂色也會用於文字按鈕，須對背景維持可讀對比。
    val primary = (0..20).asSequence().map { lerp(seed, target, it / 20f) }
        .first { contrastRatio(it, base.surface) >= 4.5f && contrastRatio(it, base.surfaceContainer) >= 4.5f }
    val onPrimary = if (contrastRatio(primary, Color.Black) > contrastRatio(primary, Color.White)) {
        Color.Black
    } else {
        Color.White
    }
    val container = lerp(primary, base.surface, 0.85f)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = base.onSurface,
        secondaryContainer = container,
        onSecondaryContainer = base.onSurface,
    )
}

internal fun themedColorScheme(context: Context, dark: Boolean, accent: Color, systemColors: Boolean): ColorScheme =
    if (systemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        appColorScheme(dark, accent)
    }

@Composable
internal fun LongScreenshotTheme(accent: Color, systemColors: Boolean, content: @Composable () -> Unit) {
    val colors = themedColorScheme(LocalContext.current, isSystemInDarkTheme(), accent, systemColors)
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}
