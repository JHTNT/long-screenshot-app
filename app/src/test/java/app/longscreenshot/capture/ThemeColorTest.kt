package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorTest {
    @Test
    fun pickerKeepsCustomAccentReadable() {
        val color = pickerColor(hue = 360f, xFraction = 2f, yFraction = 2f)

        assertEquals(0.90f, color.red, 0.01f)
        assertEquals(0.45f, color.green, 0.01f)
        assertEquals(0.45f, color.blue, 0.01f)
    }
}
