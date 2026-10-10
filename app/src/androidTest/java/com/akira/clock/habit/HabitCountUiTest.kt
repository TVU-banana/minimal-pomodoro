package com.akira.clock.habit

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.ThemeMode
import com.akira.clock.ui.HabitListAdapter
import com.akira.clock.ui.MainActivity
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only a temporary habit, then removes its rows and restores the original theme. */
@RunWith(AndroidJUnit4::class)
class HabitCountUiTest {
    @Test
    fun circleCountdownCompletionAndUndoRenderInBothThemes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ClockApplication
        val original = app.settings.themeMode
        val habit = app.habits.create("Exercise 2030", targetCount = 2)
        val today = LocalDate.now()
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                app.habits.setCompleted(habit.id, today, false)
                instrumentation.runOnMainSync { app.applyThemeMode(mode) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.findViewById<View>(R.id.nav_habits).performClick() }
                    withRow(scenario, habit.id, "2") { row ->
                        val remaining = row.findViewById<TextView>(R.id.habit_remaining)
                        val check = row.findViewById<View>(R.id.habit_check)
                        assertEquals(check.parent, remaining.parent)
                        assertEquals(check.width, remaining.width)
                        assertEquals(check.height, remaining.height)
                        assertTrue(ColorUtils.calculateContrast(remaining.currentTextColor,
                            row.context.getColor(R.color.clock_background)) >= 4.5)
                        assertFalse(row.findViewById<View>(R.id.habit_check).isSelected)
                    }
                    capture(mode, "initial")
                    withRow(scenario, habit.id, "2") {
                        it.findViewById<View>(R.id.habit_check).performClick()
                    }
                    withRow(scenario, habit.id, "1") {
                        assertFalse(it.findViewById<View>(R.id.habit_check).isSelected)
                    }
                    capture(mode, "partial")
                    withRow(scenario, habit.id, "1") {
                        it.findViewById<View>(R.id.habit_check).performClick()
                    }
                    withRow(scenario, habit.id, null) {
                        assertTrue(it.findViewById<View>(R.id.habit_check).isSelected)
                    }
                    capture(mode, "complete")
                    withRow(scenario, habit.id, null) {
                        it.findViewById<View>(R.id.habit_check).performClick()
                    }
                    withRow(scenario, habit.id, "1") {
                        assertFalse(it.findViewById<View>(R.id.habit_check).isSelected)
                    }
                    scenario.onActivity {
                        it.findViewById<View>(R.id.nav_timer).performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        it.findViewById<View>(R.id.nav_habits).performClick()
                    }
                    withRow(scenario, habit.id, "1") {}
                }
            }
        } finally {
            app.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM habits WHERE id = ?", arrayOf(habit.id),
            )
            instrumentation.runOnMainSync { app.applyThemeMode(original) }
        }
    }

    @Test
    fun selectingHistoryShowsStoredDateAndReturningTodayKeepsItsOwnCount(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ClockApplication
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val habit = app.habits.create("History verification", targetCount = 2)
        try {
            app.database.habitDao().upsertHabits(listOf(habit.copy(
                createdLocalDateIso = yesterday.toString(),
            )))
            app.database.habitDao().upsertTargets(listOf(
                com.akira.clock.data.HabitTargetEntity(
                    habit.id, yesterday.toString(), 2, System.currentTimeMillis(),
                ),
            ))
            app.habits.setCompleted(habit.id, yesterday, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.nav_habits).performClick() }
                withRow(scenario, habit.id, "2") {}
                scenario.onActivity { activity ->
                    val days = activity.findViewById<android.widget.LinearLayout>(R.id.habit_days)
                    days.getChildAt(5).performClick()
                }
                withRow(scenario, habit.id, null) {}
                scenario.recreate()
                withRow(scenario, habit.id, null) {}
                scenario.onActivity { it.findViewById<View>(R.id.habit_today).performClick() }
                withRow(scenario, habit.id, "2") {}
                scenario.onActivity { it.findViewById<View>(R.id.habit_history).performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.fragments
                        .filterIsInstance<com.akira.clock.ui.HabitsFragment>().single()
                    assertTrue(fragment.isVisible)
                }
                instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                )
            }
        } finally {
            app.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM habits WHERE id = ?", arrayOf(habit.id),
            )
        }
    }

    private fun withRow(
        scenario: ActivityScenario<MainActivity>, id: String, number: String?,
        action: (View) -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var found = false
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.habit_list) ?: return@onActivity
                val adapter = list.adapter as HabitListAdapter
                val position = adapter.currentList.indexOfFirst { it.habit.id == id }
                if (position < 0) return@onActivity
                list.scrollToPosition(position)
                val row = list.findViewHolderForAdapterPosition(position)?.itemView
                    ?: return@onActivity
                val remaining = row.findViewById<TextView>(R.id.habit_remaining)
                val matches = if (number == null) remaining.visibility == View.GONE else {
                    remaining.visibility == View.VISIBLE && remaining.text.toString() == number
                }
                if (matches && row.findViewById<View>(R.id.habit_check).isEnabled) {
                    action(row)
                    found = true
                }
            }
            if (found) return
            Thread.sleep(50)
        }
        throw AssertionError("Habit row did not reach expected remaining count: $number")
    }

    private fun capture(mode: ThemeMode, state: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.cacheDir, "habit-count-$mode-$state.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
