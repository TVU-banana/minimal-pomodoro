package com.akira.clock.ui

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akira.clock.ClockApplication
import com.akira.clock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises navigation and themed View lifetimes without changing user records. */
@RunWith(AndroidJUnit4::class)
class InteractionPerformanceUiTest {
    @Test
    fun destinationsKeepViewsAndRapidTapsSelectOnlyTheLastDestination() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val views = mutableMapOf<Int, View>()
            val destinations = listOf(
                R.id.nav_timer, R.id.nav_habits, R.id.nav_reports, R.id.nav_settings,
            )
            for (id in destinations) {
                select(scenario, id)
                scenario.onActivity { activity ->
                    val active = activity.supportFragmentManager.primaryNavigationFragment!!
                    views[id] = active.requireView()
                    assertEquals(Lifecycle.State.RESUMED, active.lifecycle.currentState)
                    activity.supportFragmentManager.fragments.filter { it !== active }.forEach {
                        assertTrue(it.isHidden)
                        assertEquals(Lifecycle.State.STARTED, it.lifecycle.currentState)
                    }
                }
            }
            for ((id, originalView) in views) {
                select(scenario, id)
                scenario.onActivity {
                    assertSame(originalView,
                        it.supportFragmentManager.primaryNavigationFragment!!.requireView())
                }
            }
            scenario.onActivity {
                it.findViewById<View>(R.id.nav_habits).performClick()
                it.findViewById<View>(R.id.nav_reports).performClick()
                it.findViewById<View>(R.id.nav_timer).performClick()
                assertTrue(it.supportFragmentManager.primaryNavigationFragment is TimerFragment)
            }
            await(scenario) { it.supportFragmentManager.primaryNavigationFragment is TimerFragment }
            scenario.recreate()
            await(scenario) { it.supportFragmentManager.primaryNavigationFragment is TimerFragment }
        }
    }

    @Test
    fun themeSwitchKeepsTheWindowAndRebuildsBothCachedScreens() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ClockApplication
        val originalMode = app.settings.themeMode
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var host: MainActivity
                lateinit var timer: Fragment
                lateinit var timerView: View
                var initialNight = false
                val timings = mutableListOf<String>()
                scenario.onActivity {
                    host = it
                    timer = it.supportFragmentManager.primaryNavigationFragment!!
                    timerView = timer.requireView()
                    initialNight = isNight(it)
                    val start = SystemClock.elapsedRealtimeNanos()
                    it.findViewById<View>(R.id.timer_theme_toggle).performClick()
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    timings += "timer theme handler: $elapsedMs ms"
                }
                await(scenario) { isNight(it) != initialNight }
                scenario.onActivity {
                    assertSame(host, it)
                    assertSame(timer, it.supportFragmentManager.primaryNavigationFragment)
                    assertNotSame(timerView, timer.requireView())
                }
                select(scenario, R.id.nav_settings)
                lateinit var settingsView: View
                scenario.onActivity {
                    settingsView = it.supportFragmentManager.primaryNavigationFragment!!
                        .requireView()
                    it.findViewById<View>(
                        if (initialNight) R.id.theme_dark else R.id.theme_light,
                    ).performClick()
                }
                await(scenario) { isNight(it) == initialNight }
                scenario.onActivity {
                    assertSame(host, it)
                    assertNotSame(settingsView,
                        it.supportFragmentManager.primaryNavigationFragment!!.requireView())
                    assertEquals(if (initialNight) R.id.theme_dark else R.id.theme_light,
                        it.findViewById<RadioGroup>(R.id.theme_radio_group).checkedRadioButtonId)
                }
                select(scenario, R.id.nav_timer)
                await(scenario) {
                    it.findViewById<LinearLayout>(R.id.tag_rows)?.isLaidOut == true
                }
                scenario.onActivity {
                    val screen = it.supportFragmentManager.primaryNavigationFragment!!
                    val start = SystemClock.elapsedRealtimeNanos()
                    val rows = it.findViewById<LinearLayout>(R.id.tag_rows)
                    val action = rows.getChildAt(0) ?: it.findViewById<View>(R.id.add_time_block)
                    action.performClick()
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    timings += "editor handler: $elapsedMs ms"
                    val editor = screen.childFragmentManager.fragments
                        .filterIsInstance<PresetEditorDialogFragment>().single()
                    assertTrue(editor.requireDialog().isShowing)
                    editor.dismissNow()
                    val memory = Debug.MemoryInfo()
                    Debug.getMemoryInfo(memory)
                    val systemMemory = ActivityManager.MemoryInfo()
                    (it.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .getMemoryInfo(systemMemory)
                    timings += "app PSS: ${memory.totalPss} KB; " +
                        "lowMemory: ${systemMemory.lowMemory}"
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", timings.joinToString("\n", postfix = "\n"))
                })
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                app.settings.themeMode = originalMode
                app.applyThemeMode(originalMode)
            }
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: Int) {
        scenario.onActivity { activity ->
            val start = SystemClock.elapsedRealtimeNanos()
            activity.findViewById<View>(id).performClick()
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val fragment = activity.supportFragmentManager.primaryNavigationFragment!!
            val expected = when (id) {
                R.id.nav_habits -> fragment is HabitsFragment
                R.id.nav_reports -> fragment is ReportsFragment
                R.id.nav_settings -> fragment is SettingsFragment
                else -> fragment is TimerFragment
            }
            // Assert before yielding the main thread so animation/post delays cannot pass.
            assertTrue("Destination must change inside the click handler", expected)
            assertTrue(activity.findViewById<View>(id).isSelected)
            assertTrue(fragment.requireView().visibility == View.VISIBLE)
            assertTrue(!fragment.isHidden)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "navigation handler ($id): $elapsedMs ms\n")
            })
        }
    }

    private fun isNight(activity: MainActivity) =
        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun await(
        scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var success = false
            scenario.onActivity { success = condition(it) }
            if (success) return
            Thread.sleep(20)
        }
        throw AssertionError("Interaction did not reach the expected state")
    }
}
