package app.longscreenshot.capture

internal object CaptureChecks {
    fun isNearlyBlack(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        val dark = pixels.count {
            (it ushr 16 and 0xff) <= 8 &&
                (it ushr 8 and 0xff) <= 8 &&
                (it and 0xff) <= 8
        }
        return dark.toDouble() / pixels.size >= 0.995
    }
}
