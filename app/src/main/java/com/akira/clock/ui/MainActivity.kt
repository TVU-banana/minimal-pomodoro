package com.akira.clock.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.akira.clock.R

/**
 * Retains visited destinations while allowing only the visible screen to reach RESUMED.
 * Theme changes rebuild themed Views in the same window; Room and Fragment state remain intact.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var navigation: BottomNavigationView
    private var activeTag = TIMER
    private var appliedNightMode = 0
    private var themeRefreshPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTag = savedInstanceState?.getString(STATE_DESTINATION) ?: TIMER
        appliedNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        bindContent()
        showDestination(activeTag)
    }

    private fun bindContent() {
        setContentView(R.layout.activity_main)
        window.setBackgroundDrawableResource(R.color.clock_background)
        navigation = findViewById(R.id.bottom_navigation)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !night
            isAppearanceLightStatusBars = !night
        }
        listOf(
            R.id.nav_timer to TIMER,
            R.id.nav_habits to HABITS,
            R.id.nav_reports to REPORTS,
            R.id.nav_settings to SETTINGS,
        ).forEach { (id, tag) ->
            findViewById<View>(id).setOnClickListener {
                // The highlight is decoration and must never delay destination visibility.
                navigation.selectItem(id)
                showDestination(tag)
            }
        }
        navigation.selectItem(navigationId(activeTag))
    }

    private fun showDestination(tag: String) {
        if (supportFragmentManager.isStateSaved) return
        val target = supportFragmentManager.findFragmentByTag(tag) ?: when (tag) {
            HABITS -> HabitsFragment()
            REPORTS -> ReportsFragment()
            SETTINGS -> SettingsFragment()
            TODAY -> TodayFragment()
            else -> TimerFragment()
        }
        if (target === supportFragmentManager.primaryNavigationFragment &&
            !target.isHidden && !target.isDetached
        ) return
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        supportFragmentManager.fragments.filter { it.id == R.id.main_container }.forEach {
            if (it !== target && !it.isDetached) {
                transaction.hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
            }
        }
        when {
            target.isDetached -> transaction.attach(target)
            !target.isAdded -> transaction.add(R.id.main_container, target, tag)
        }
        transaction.show(target)
            .setMaxLifecycle(target, Lifecycle.State.RESUMED)
            .setPrimaryNavigationFragment(target)
            .commitNow()
        activeTag = tag
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DESTINATION, activeTag)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode == appliedNightMode) return
        appliedNightMode = nightMode
        themeRefreshPending = true
        if (!supportFragmentManager.isStateSaved) refreshThemedViews()
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        if (themeRefreshPending) refreshThemedViews()
    }

    private fun refreshThemedViews() {
        themeRefreshPending = false
        navigation.cancelSelection()
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        // Cached Views hold resolved colors. Detach them and rebuild each only when next visited.
        supportFragmentManager.fragments.filter {
            it.id == R.id.main_container && !it.isDetached
        }.forEach { transaction.detach(it) }
        transaction.commitNow()
        bindContent()
        showDestination(activeTag)
    }

    internal fun showToday() {
        navigation.cancelSelection()
        navigation.selectItem(R.id.nav_timer)
        showDestination(TODAY)
    }

    internal fun showTimer() {
        navigation.cancelSelection()
        navigation.selectItem(R.id.nav_timer)
        showDestination(TIMER)
    }

    private fun navigationId(tag: String): Int = when (tag) {
        HABITS -> R.id.nav_habits
        REPORTS -> R.id.nav_reports
        SETTINGS -> R.id.nav_settings
        else -> R.id.nav_timer
    }

    private companion object {
        const val STATE_DESTINATION = "destination"
        const val TIMER = "screen:timer"
        const val HABITS = "screen:habits"
        const val REPORTS = "screen:reports"
        const val SETTINGS = "screen:settings"
        const val TODAY = "screen:today"
    }
}
