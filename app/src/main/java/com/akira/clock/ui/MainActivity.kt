package com.akira.clock.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.akira.clock.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.nav_timer).setOnClickListener { show(TimerFragment()) }
        findViewById<android.view.View>(R.id.nav_history).setOnClickListener { show(HistoryFragment()) }
        findViewById<android.view.View>(R.id.nav_reports).setOnClickListener { show(ReportsFragment()) }
        findViewById<android.view.View>(R.id.nav_settings).setOnClickListener { show(SettingsFragment()) }
        if (savedInstanceState == null) show(TimerFragment())
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .commit()
    }
}
