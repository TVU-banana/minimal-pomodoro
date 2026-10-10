package com.akira.clock.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exported schema fixtures exercise the production Room migration and its generated validator. */
@RunWith(AndroidJUnit4::class)
class ClockDatabaseMigrationTest {
    @Test
    fun migration7To9PreservesTimerReportsTagsAssetsAndReflections() {
        withMigratedDatabase(seed = { legacy ->
            legacy.execSQL(
                "INSERT INTO categories VALUES " +
                    "('tag', 'Reading', 4292418873, 3600000, 900000, 10800000, 100, NULL)",
            )
            legacy.execSQL(
                "INSERT INTO task_presets VALUES " +
                    "('preset', 'Book', 'tag', 3600000, 900000, 'COUNTDOWN', " +
                    "'DIGITAL', NULL, 100, 200)",
            )
            legacy.execSQL("INSERT INTO task_preset_tags VALUES ('preset', 'tag')")
            legacy.execSQL(
                "INSERT INTO sessions VALUES " +
                    "('session', 'preset', 'tag', 'Reading', 'Book', 3600000, 900000, " +
                    "900000, 2700000, 100, 3600100, 'Asia/Shanghai', 'COMPLETED', NULL, 100, 200)",
            )
            legacy.execSQL(
                "INSERT INTO session_segments VALUES " +
                    "('segment', 'session', 'FOCUS', 900100, 3600100)",
            )
            legacy.execSQL(
                "INSERT INTO timer_checkpoint VALUES " +
                    "(1, 'running', 'FOCUS', NULL, 3600000, 900000, 1000, 2701000, " +
                    "3600100, 900000, 0, 1, 200)",
            )
            legacy.execSQL(
                "INSERT INTO assets VALUES ('asset', 'digest', '/image.png', 32, 32, 1, 100)",
            )
            legacy.execSQL(
                "INSERT INTO daily_reflections VALUES " +
                    "('2026-10-03', 'Asia/Shanghai', 'A good day', 100, 200)",
            )
        }) { db ->
            assertEquals("Reading", text(db, "SELECT name FROM categories WHERE id = 'tag'"))
            assertEquals(
                "COUNTDOWN",
                text(db, "SELECT timerMode FROM task_presets WHERE id = 'preset'"),
            )
            assertEquals("tag", text(db, "SELECT categoryId FROM task_preset_tags"))
            assertEquals(2_700_000L, number(db, "SELECT actualFocusMs FROM sessions"))
            assertEquals("COMPLETED", text(db, "SELECT status FROM sessions"))
            assertEquals("FOCUS", text(db, "SELECT phase FROM session_segments"))
            assertEquals("running", text(db, "SELECT sessionId FROM timer_checkpoint"))
            assertEquals(2_701_000L, number(db, "SELECT deadlineElapsedMs FROM timer_checkpoint"))
            assertEquals("/image.png", text(db, "SELECT localPath FROM assets"))
            assertEquals("A good day", text(db, "SELECT text FROM daily_reflections"))
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM habits"))
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM habit_check_ins"))
        }
    }

    @Test
    fun migratedCheckInsHaveCompositeUniquenessAndAHabitForeignKey() {
        withMigratedDatabase { db ->
            assertEquals(1L, number(db, "PRAGMA foreign_keys"))
            db.execSQL(
                "INSERT INTO habits VALUES " +
                    "('habit', 'Read', 0, '2026-10-04', 100, 100, NULL, '')",
            )
            db.execSQL(
                "INSERT INTO habit_check_ins VALUES ('habit', '2026-10-04', 1, 100, 100, 1)",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO habit_check_ins VALUES ('habit', '2026-10-04', 0, NULL, 101, 0)",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO habit_check_ins VALUES ('missing', '2026-10-04', 1, 100, 100, 1)",
                )
            }
            db.execSQL("DELETE FROM habits WHERE id = 'habit'")
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM habit_check_ins"))
        }
    }

    @Test
    fun migration8To9PreservesExistingHabitsAndCheckInsWithEmptyNotes() {
        withMigratedDatabase(version = 8, seed = { legacy ->
            legacy.execSQL(
                "INSERT INTO habits VALUES " +
                    "('habit', 'Read', 0, '2026-10-04', 100, 200, NULL)",
            )
            legacy.execSQL(
                "INSERT INTO habit_check_ins VALUES ('habit', '2026-10-04', 1, 100, 100)",
            )
        }) { db ->
            assertEquals("Read", text(db, "SELECT name FROM habits"))
            assertEquals("", text(db, "SELECT note FROM habits"))
            assertEquals(200L, number(db, "SELECT updatedAtUtcMs FROM habits"))
            assertEquals(1L, number(db, "SELECT completed FROM habit_check_ins"))
            db.execSQL("UPDATE habits SET note = 'Ten pages' WHERE id = 'habit'")
            assertEquals("Ten pages", text(db, "SELECT note FROM habits"))
        }
    }

    @Test
    fun migration9To10PreservesCompletedAndUndoneRowsAsOneAndZero() {
        withMigratedDatabase(version = 9, seed = { legacy ->
            legacy.execSQL("INSERT INTO habits VALUES " +
                "('habit', 'Read', 0, '2026-10-03', 100, 200, NULL, 'Keep note')")
            legacy.execSQL("INSERT INTO habit_check_ins VALUES " +
                "('habit', '2026-10-03', 1, 100, 100)")
            legacy.execSQL("INSERT INTO habit_check_ins VALUES " +
                "('habit', '2026-10-04', 0, NULL, 101)")
        }) { db ->
            assertEquals(1L, number(db,
                "SELECT completedCount FROM habit_check_ins WHERE localDateIso = '2026-10-03'"))
            assertEquals(0L, number(db,
                "SELECT completedCount FROM habit_check_ins WHERE localDateIso = '2026-10-04'"))
            assertEquals("Keep note", text(db, "SELECT note FROM habits"))
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM habit_targets"))
        }
    }

    @Test
    fun realRoomSchemaValidationRejectsAnUnexpectedLegacyColumn() {
        val failure = assertThrows(IllegalStateException::class.java) {
            withMigratedDatabase(seed = {
                it.execSQL("ALTER TABLE sessions ADD COLUMN unexpectedColumn TEXT")
            }) {
                throw AssertionError("Room must reject the incompatible migrated table")
            }
        }
        assertTrue(failure.message.orEmpty().contains("sessions"))
    }

    private fun withMigratedDatabase(
        version: Int = 7,
        seed: (SQLiteDatabase) -> Unit = {},
        verify: (SupportSQLiteDatabase) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "clock-habit-migration-${UUID.randomUUID()}.db"
        var room: ClockDatabase? = null
        try {
            createLegacySchema(context, name, version, seed)
            val opened = Room.databaseBuilder(context, ClockDatabase::class.java, name)
                .addMigrations(
                    ClockDatabase.MIGRATION_7_8, ClockDatabase.MIGRATION_8_9,
                    ClockDatabase.MIGRATION_9_10,
                )
                .build()
            room = opened
            // Opening the old file invokes Room's generated schema validator after migration.
            val migrated = opened.openHelper.writableDatabase
            assertEquals(10, migrated.version)
            assertEquals(
                schema(10).getString("identityHash"),
                text(migrated, "SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            verify(migrated)
        } finally {
            room?.close()
            context.deleteDatabase(name)
        }
    }

    private fun createLegacySchema(
        context: Context,
        name: String,
        version: Int,
        seed: (SQLiteDatabase) -> Unit,
    ) {
        val schema = schema(version)
        check(schema.getInt("version") == version)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (position in 0 until indices.length()) {
                        val sql = indices.getJSONObject(position).getString("createSql")
                        db.execSQL(sql.replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                db.version = version
                seed(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun schema(version: Int): JSONObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val path = "com.akira.clock.data.ClockDatabase/$version.json"
        val json = assets.open(path).bufferedReader().use { it.readText() }
        return JSONObject(json).getJSONObject("database")
    }

    private fun text(db: SupportSQLiteDatabase, sql: String): String = db.query(sql).use {
        check(it.moveToFirst())
        it.getString(0)
    }

    private fun number(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use {
        check(it.moveToFirst())
        it.getLong(0)
    }
}
