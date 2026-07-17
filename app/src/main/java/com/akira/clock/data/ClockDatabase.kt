package com.akira.clock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CategoryEntity::class,
        TaskPresetEntity::class,
        SessionEntity::class,
        SessionSegmentEntity::class,
        TimerCheckpointEntity::class,
        AssetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class ClockDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun taskPresetDao(): TaskPresetDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionSegmentDao(): SessionSegmentDao
    abstract fun timerCheckpointDao(): TimerCheckpointDao
    abstract fun assetDao(): AssetDao

    companion object {
        @Volatile private var instance: ClockDatabase? = null

        fun get(context: Context): ClockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ClockDatabase::class.java,
                "clock.db",
            ).build().also { instance = it }
        }
    }
}
