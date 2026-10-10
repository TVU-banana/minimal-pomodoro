package com.akira.clock.report

/** User-selected optional modules for a focus poster. */
internal data class PosterSections(
    val showChart: Boolean = true,
    val showReflection: Boolean = true,
    val showMotivation: Boolean = true,
)

/** Immutable input shared by preview, gallery export, and save-as export. */
internal data class FocusPosterDraft(
    val data: FocusPosterData,
    val sections: PosterSections = PosterSections(),
    val reflectionText: String = "",
)

/** Validates the bounded, user-written daily reflection before it reaches Room or a poster. */
internal object DailyReflectionText {
    const val MAX_CODE_POINTS = 120

    fun normalize(source: String): String = source.trim()

    fun isValid(source: String): Boolean {
        val normalized = normalize(source)
        return normalized.isNotEmpty() &&
            normalized.codePointCount(0, normalized.length) <= MAX_CODE_POINTS
    }
}

/** A renderer-independent rectangle in poster pixels. */
internal data class PosterRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Packs visible modules in order; preview and export share the resulting canvas height. */
internal object PosterLayoutPlanner {
    private const val LEFT = 112f
    private const val RIGHT = 1328f
    private const val CONTENT_TOP = 520f
    private const val HEADER_BOTTOM = 456f
    private const val CHART_HEIGHT = 650f
    private const val REFLECTION_HEIGHT = 308f
    private const val MOTIVATION_HEIGHT = 64f
    private const val MODULE_GAP = 32f
    private const val BOTTOM_PADDING = 112f

    data class Layout(
        val chart: PosterRect?,
        val reflection: PosterRect?,
        val motivationCenterY: Float?,
        val height: Int,
    )

    fun plan(sections: PosterSections): Layout {
        var cursor = CONTENT_TOP
        var contentBottom = HEADER_BOTTOM
        fun place(height: Float): PosterRect {
            val bounds = PosterRect(LEFT, cursor, RIGHT, cursor + height)
            contentBottom = bounds.bottom
            cursor = bounds.bottom + MODULE_GAP
            return bounds
        }
        val chart = if (sections.showChart) place(CHART_HEIGHT) else null
        val reflection = if (sections.showReflection) place(REFLECTION_HEIGHT) else null
        val motivation = if (sections.showMotivation) place(MOTIVATION_HEIGHT) else null
        return Layout(
            chart,
            reflection,
            motivation?.let { (it.top + it.bottom) / 2f },
            (contentBottom + BOTTOM_PADDING).toInt(),
        )
    }
}
