package com.akira.clock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        TaskPresetEntity::class,
        SessionEntity::class,
        SessionSegmentEntity::class,
        TimerCheckpointEntity::class,
        AssetEntity::class,
    ],
    version = 4,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }

        // Adds the abortReason column so early-ended sessions can persist the user's stated reason.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN abortReason TEXT")
            }
        }

        // Tags gain their own default durations so selecting a tag can pre-fill the timer.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN totalDurationMs INTEGER NOT NULL DEFAULT 3600000",
                )
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN warmupDurationMs INTEGER NOT NULL DEFAULT 900000",
                )
            }
        }

        // Adds dailyGoalMs column for daily progress tracking.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN dailyGoalMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
