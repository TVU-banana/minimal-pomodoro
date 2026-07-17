package com.akira.clock

import android.app.Application
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.ClockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClockApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: ClockDatabase by lazy { ClockDatabase.get(this) }
    val repository: ClockRepository by lazy { ClockRepository(database) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.ensureDefaults(System.currentTimeMillis())
        }
    }
}
