package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemInsetsTest {
    @Test
    fun usesTheLargerAvailableInset() {
        assertEquals(122, resolveSystemInset(0, 122))
        assertEquals(142, resolveSystemInset(142, 0))
    }
}
