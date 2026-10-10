package com.akira.clock.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akira.clock.R
import com.akira.clock.report.FocusPosterData
import com.akira.clock.report.PosterSections
import com.akira.clock.report.ReportPeriod
import com.akira.clock.ui.render.LineChartView
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies live rendering without exporting images or changing user records. */
@RunWith(AndroidJUnit4::class)
class ReportPreviewUiTest {
    @Test
    fun entireDayFitsAndCheckboxesUpdateTheVisiblePoster() {
        val today = LocalDate.now()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_reports).performClick()
            }
            awaitCondition(scenario) { activity ->
                val chart = activity.findViewById<LineChartView>(R.id.chart_linechart)
                chart != null && chart.width > 0 && chart.width <= (chart.parent as View).width
            }
            scenario.onActivity { activity ->
                PosterCustomizeDialogFragment.newInstance(
                    FocusPosterData(
                        ReportPeriod.DAY, today, today.plusDays(1), 7_200_000L,
                        (0..23).map { if (it == 12) 3_600_000L else 0L },
                    ),
                    PosterSections(showReflection = false), "",
                ).showNow(activity.supportFragmentManager, "previewVerification")
            }
            var first: Bitmap? = null
            awaitCondition(scenario) { activity ->
                val dialog = activity.supportFragmentManager
                    .findFragmentByTag("previewVerification") as PosterCustomizeDialogFragment
                val image = dialog.requireDialog().findViewById<ImageView>(R.id.poster_live_preview)
                first = (image.drawable as? BitmapDrawable)?.bitmap
                first != null
            }
            var originalHeight = 0
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager
                    .findFragmentByTag("previewVerification") as PosterCustomizeDialogFragment
                assertEquals(720, first!!.width)
                originalHeight = first!!.height
                dialog.requireDialog().findViewById<CheckBox>(R.id.poster_chart_choice)
                    .performClick()
            }
            awaitCondition(scenario) { activity ->
                val dialog = activity.supportFragmentManager
                    .findFragmentByTag("previewVerification") as PosterCustomizeDialogFragment
                val image = dialog.requireDialog().findViewById<ImageView>(R.id.poster_live_preview)
                val current = (image.drawable as? BitmapDrawable)?.bitmap
                current != null && current !== first && current.height < originalHeight
            }
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager
                    .findFragmentByTag("previewVerification") as PosterCustomizeDialogFragment
                assertFalse(dialog.requireDialog().findViewById<CheckBox>(R.id.poster_chart_choice)
                    .isChecked)
                assertTrue(first!!.isRecycled)
                dialog.dismissNow()
            }
        }
    }

    private fun awaitCondition(
        scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var success = false
            scenario.onActivity { success = condition(it) }
            if (success) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(50)
        }
        throw AssertionError("Report preview did not reach the expected rendered state")
    }
}
