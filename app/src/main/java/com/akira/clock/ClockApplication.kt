package com.akira.clock

import android.app.Application
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.ClockRepository

class ClockApplication : Application() {
    val database: ClockDatabase by lazy { ClockDatabase.get(this) }
    val repository: ClockRepository by lazy { ClockRepository(database) }
}
