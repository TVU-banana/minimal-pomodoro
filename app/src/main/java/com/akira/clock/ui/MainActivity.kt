package com.akira.clock.ui

import android.os.Bundle
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.akira.clock.R

/** Single-activity host for Clock's four top-level screens. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !nightMode
        findViewById<android.view.View>(R.id.nav_timer).setOnClickListener {
            show(TimerFragment(), R.id.nav_timer)
        }
        findViewById<android.view.View>(R.id.nav_history).setOnClickListener {
            show(HistoryFragment(), R.id.nav_history)
        }
        findViewById<android.view.View>(R.id.nav_reports).setOnClickListener {
            show(ReportsFragment(), R.id.nav_reports)
        }
        findViewById<android.view.View>(R.id.nav_settings).setOnClickListener {
            show(SettingsFragment(), R.id.nav_settings)
        }
        if (savedInstanceState == null) {
            show(TimerFragment(), R.id.nav_timer)
        } else {
            val selectedId = when (supportFragmentManager.findFragmentById(R.id.main_container)) {
                is HistoryFragment -> R.id.nav_history
                is ReportsFragment -> R.id.nav_reports
                is SettingsFragment -> R.id.nav_settings
                else -> R.id.nav_timer
            }
            selectNavigation(selectedId)
        }
    }

    private fun show(fragment: Fragment, selectedId: Int) {
        selectNavigation(selectedId)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .commit()
    }

    private fun selectNavigation(selectedId: Int) {
        NAVIGATION_IDS.forEach { id -> findViewById<android.view.View>(id).isSelected = id == selectedId }
    }

    private companion object {
        val NAVIGATION_IDS = intArrayOf(
            R.id.nav_timer,
            R.id.nav_history,
            R.id.nav_reports,
            R.id.nav_settings,
        )
    }
}
