package com.akira.clock.ui

/**
 * Preset tag colors sampled from the macOS window traffic-light buttons, plus hex parsing for
 * custom colors.
 *
 * The three presets match Apple's close (red), minimize (yellow), and zoom (green) button fills so
 * the user gets instantly recognizable semantic colors. Custom colors are entered as #RRGGBB and
 * normalized to a fully opaque ARGB long. Users can add custom colors which are appended to the
 * preset list up to the maximum row capacity.
 */
internal object TagColors {
    val RED: Long = 0xFFFF5F57L
    val YELLOW: Long = 0xFFFEBC2EL
    val GREEN: Long = 0xFF28C840L

    private val defaultPresets: List<Long> = listOf(RED, YELLOW, GREEN)
    private val customColors = mutableListOf<Long>()
    private const val MAX_COLORS = 6

    val presets: List<Long>
        get() = defaultPresets + customColors.take(MAX_COLORS - defaultPresets.size)

    /** Parses `#RRGGBB` or `RRGGBB` into a fully opaque ARGB long, or null when malformed. */
    fun parseHex(input: String): Long? {
        val cleaned = input.trim().removePrefix("#")
        if (cleaned.length != 6 || cleaned.any { it.digitToIntOrNull(16) == null }) return null
        val rgb = cleaned.toLongOrNull(16) ?: return null
        return 0xFF000000L or rgb
    }

    fun formatHex(colorArgb: Long): String = "#%06X".format(colorArgb and 0xFFFFFFL)

    fun addCustomColor(colorArgb: Long): Boolean {
        if (customColors.size >= MAX_COLORS - defaultPresets.size) return false
        if (customColors.contains(colorArgb)) return false
        if (defaultPresets.contains(colorArgb)) return false
        customColors.add(colorArgb)
        return true
    }

    fun hasRoomForMore(): Boolean = customColors.size < MAX_COLORS - defaultPresets.size
}
