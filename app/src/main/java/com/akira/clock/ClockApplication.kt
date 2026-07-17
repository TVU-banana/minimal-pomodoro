package com.akira.clock

import android.app.Application
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.ClockRepository

/** Process-level owner of lazily initialized local data dependencies. */
class ClockApplication : Application() {
    internal val database: ClockDatabase by lazy { ClockDatabase.get(this) }
    internal val repository: ClockRepository by lazy { ClockRepository(database) }
}
