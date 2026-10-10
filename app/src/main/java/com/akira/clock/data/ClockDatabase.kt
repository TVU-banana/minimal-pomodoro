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
        TaskPresetTagEntity::class,
        SessionEntity::class,
        SessionSegmentEntity::class,
        TimerCheckpointEntity::class,
        AssetEntity::class,
        DailyReflectionEntity::class,
        HabitEntity::class,
        HabitCheckInEntity::class,
        HabitTargetEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
/**
 * Room database definition and singleton provider for the Clock application.
 *
 * Serves as the single durable source of truth for timer checkpoints, historical sessions,
 * tag metadata, user reflections, and daily habits. Schema migrations (currently v10) are
 * strictly additive and preserve backward compatibility across open backups.
 */
internal abstract class ClockDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun taskPresetDao(): TaskPresetDao
    abstract fun taskPresetTagDao(): TaskPresetTagDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionSegmentDao(): SessionSegmentDao
    abstract fun timerCheckpointDao(): TimerCheckpointDao
    abstract fun assetDao(): AssetDao
    abstract fun dailyReflectionDao(): DailyReflectionDao
    abstract fun habitDao(): HabitDao

    companion object {
        @Volatile private var instance: ClockDatabase? = null

        fun get(context: Context): ClockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ClockDatabase::class.java,
                "clock.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )
                .build()
                .also { instance = it }
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
                    "ALTER TABLE categories ADD COLUMN totalDurationMs " +
                        "INTEGER NOT NULL DEFAULT 3600000",
                )
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN warmupDurationMs " +
                        "INTEGER NOT NULL DEFAULT 900000",
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

        // Preserves the legacy category relationship as each existing block's first tag.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS task_preset_tags (" +
                        "presetId TEXT NOT NULL, categoryId TEXT NOT NULL, " +
                        "PRIMARY KEY(presetId, categoryId), " +
                        "FOREIGN KEY(presetId) REFERENCES task_presets(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_preset_tags_categoryId " +
                        "ON task_preset_tags(categoryId)",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO task_preset_tags(presetId, categoryId) " +
                        "SELECT id, categoryId FROM task_presets",
                )
            }
        }

        // Daily reflections are independent user-authored content keyed by local calendar day.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_reflections (" +
                        "localDateIso TEXT NOT NULL, timezoneIdAtWrite TEXT NOT NULL, " +
                        "text TEXT NOT NULL, createdAtUtcMs INTEGER NOT NULL, " +
                        "updatedAtUtcMs INTEGER NOT NULL, PRIMARY KEY(localDateIso))",
                )
            }
        }

        // Existing blocks retain countdown semantics after stopwatch support is introduced.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE task_presets ADD COLUMN timerMode TEXT " +
                        "NOT NULL DEFAULT 'COUNTDOWN'",
                )
            }
        }

        /** Legacy completed rows become one repetition; no history is lost or reinterpreted. */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE habit_check_ins ADD COLUMN completedCount " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE habit_check_ins SET completedCount = " +
                        "CASE WHEN completed THEN 1 ELSE 0 END",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS habit_targets (" +
                        "habitId TEXT NOT NULL, effectiveLocalDateIso TEXT NOT NULL, " +
                        "targetCount INTEGER NOT NULL, updatedAtUtcMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(habitId, effectiveLocalDateIso), " +
                        "FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE)",
                )
            }
        }

        /** Existing habits retain their names and history with an empty optional note. */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds independent habits without rewriting timer, session, tag, or reflection tables. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS habits (" +
                        "id TEXT NOT NULL, name TEXT NOT NULL, sortOrder INTEGER NOT NULL, " +
                        "createdLocalDateIso TEXT NOT NULL, createdAtUtcMs INTEGER NOT NULL, " +
                        "updatedAtUtcMs INTEGER NOT NULL, archivedLocalDateIso TEXT, " +
                        "PRIMARY KEY(id))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS habit_check_ins (" +
                        "habitId TEXT NOT NULL, localDateIso TEXT NOT NULL, " +
                        "completed INTEGER NOT NULL, completedAtUtcMs INTEGER, " +
                        "updatedAtUtcMs INTEGER NOT NULL, PRIMARY KEY(habitId, localDateIso), " +
                        "FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_habit_check_ins_localDateIso " +
                        "ON habit_check_ins(localDateIso)",
                )
            }
        }
    }
}
