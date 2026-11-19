package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class ThemeColorTest {
    @Test
    fun pickerClampsToValidColorPlaneRange() {
        val color = pickerColor(hue = 360f, xFraction = 2f, yFraction = 2f)

        assertEquals(0.25f, color.red, 0.01f)
        assertEquals(0f, color.green, 0.01f)
        assertEquals(0f, color.blue, 0.01f)
    }

    @Test
    fun accentsStayReadableInBothAppearances() {
        val accents = listOf(DefaultAccent, Color.Black, Color.White, Color.Yellow) +
            (0 until 360 step 30).map { Color.hsv(it.toFloat(), 0.5f, 0.95f) }
        for (dark in listOf(false, true)) {
            for (accent in accents) {
                val colors = appColorScheme(dark, accent)
                assertTrue("primary on surface: $dark $accent", contrastRatio(colors.primary, colors.surface) >= 4.5f)
                assertTrue("primary on tools: $dark $accent", contrastRatio(colors.primary, colors.surfaceContainer) >= 4.5f)
                assertTrue("button label: $dark $accent", contrastRatio(colors.primary, colors.onPrimary) >= 4.5f)
                assertTrue("selection label: $dark $accent", contrastRatio(colors.primaryContainer, colors.onPrimaryContainer) >= 4.5f)
                assertTrue("tonal container label: $dark $accent", contrastRatio(colors.secondaryContainer, colors.onSecondaryContainer) >= 4.5f)
                assertEquals(colors.primaryContainer, colors.secondaryContainer)
                assertEquals((if (dark) darkColorScheme() else lightColorScheme()).error, colors.error)
            }
        }
    }

    @Test
    fun defaultPaletteUsesPurpleAndContrastUsesRelativeLuminance() {
        assertEquals(DefaultAccent, appColorScheme(false, DefaultAccent).primary)
        assertEquals(Color(0xFFDBB9F7), appColorScheme(true, DefaultAccent).primary)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.001f)
        assertEquals(1f, contrastRatio(DefaultAccent, DefaultAccent), 0.001f)
    }
}
