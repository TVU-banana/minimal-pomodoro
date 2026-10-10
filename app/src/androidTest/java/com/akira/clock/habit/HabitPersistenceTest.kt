package com.akira.clock.habit

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akira.clock.data.ClockDatabase
import com.akira.clock.export.BackupBundle
import com.akira.clock.export.BackupFormatException
import com.akira.clock.export.ClockBackupArchive
import com.akira.clock.export.ClockBackupService
import com.akira.clock.export.HABITS_FILE
import com.akira.clock.export.HABIT_CHECK_INS_FILE
import com.akira.clock.export.HABIT_TARGETS_FILE
import com.akira.clock.export.MANIFEST_FILE
import com.akira.clock.export.REFLECTIONS_FILE
import com.akira.clock.export.sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated file databases verify disk persistence across Room instances. */
@RunWith(AndroidJUnit4::class)
class HabitPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: ClockDatabase
    private lateinit var databaseName: String
    private lateinit var storageDirectory: File
    private val clock = MutableClock(Instant.parse("2026-10-04T01:00:00Z").toEpochMilli())
    private var zone = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "habit-persistence-$suffix.db"
        storageDirectory = File(context.cacheDir, "habit-persistence-$suffix").apply {
            check(mkdirs())
        }
        database = openDatabase(databaseName)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
        if (::storageDirectory.isInitialized) storageDirectory.deleteRecursively()
    }

    @Test
    fun completionAndUndoKeepOneKeyAndSurviveDatabaseReopening() = runBlocking {
        var repository = repository()
        val date = LocalDate.of(2026, 10, 4)
        val habit = repository.create("Read", "Ten pages\nBefore bed")
        repository.setCompleted(habit.id, date, true)
        val completed = database.habitDao().getAllCheckIns().single()
        repository.setCompleted(habit.id, date, true)
        assertEquals(completed, database.habitDao().getAllCheckIns().single())

        reopenDatabase()
        repository = repository()
        val restored = repository.observe(date, date).first()
        assertEquals(listOf(habit), restored.habits)
        assertEquals(listOf(completed), restored.checkIns)

        repository.setCompleted(habit.id, date, false)
        val undone = database.habitDao().getAllCheckIns().single()
        assertFalse(undone.completed)
        assertNull(undone.completedAtUtcMs)
        assertEquals(completed.updatedAtUtcMs + 1, undone.updatedAtUtcMs)
        repository.setCompleted(habit.id, date, false)
        assertEquals(undone, database.habitDao().getAllCheckIns().single())

        reopenDatabase()
        assertEquals(listOf(undone), database.habitDao().getAllCheckIns())
        assertEquals(listOf(habit), database.habitDao().getAll())
    }

    @Test
    fun noteEditsAndClearingSurviveDatabaseReopening() = runBlocking {
        val habit = repository().create("Read", "Ten pages")
        reopenDatabase()
        assertEquals("Ten pages", database.habitDao().getAll().single().note)
        repository().update(habit.id, "Read", "Before bed\nWithout screens")
        reopenDatabase()
        assertEquals("Before bed\nWithout screens", database.habitDao().getAll().single().note)
        repository().update(habit.id, "Read", "")
        reopenDatabase()
        assertEquals("", database.habitDao().getAll().single().note)
    }

    @Test
    fun twoRepositoriesSerializeConcurrentCommandsAndKeepMonotonicRevisions() = runBlocking {
        val repositories = listOf(repository(), repository())
        val date = LocalDate.of(2026, 10, 4)
        val habit = repositories.first().create("Stretch")
        var previousRevision: Long? = null

        for (completed in listOf(true, false, true, false)) {
            // Equal wall-clock timestamps exercise Room serialization across repository instances.
            (0 until 32).map { index ->
                async(Dispatchers.Default) {
                    repositories[index % repositories.size].setCompleted(habit.id, date, completed)
                }
            }.awaitAll()
            val persisted = database.habitDao().getAllCheckIns().single()
            assertEquals(completed, persisted.completed)
            assertEquals(habit.id, persisted.habitId)
            assertEquals(date.toString(), persisted.localDateIso)
            previousRevision?.let { assertEquals(it + 1, persisted.updatedAtUtcMs) }
            previousRevision = persisted.updatedAtUtcMs
            clock.currentUtcMs -= 1_000
        }

        reopenDatabase()
        val persisted = database.habitDao().getAllCheckIns().single()
        assertFalse(persisted.completed)
        assertEquals(previousRevision, persisted.updatedAtUtcMs)
    }

    @Test
    fun sameDayArchivePreservesItsCompletionAndEligibleHistoricalEditing() = runBlocking {
        val date = LocalDate.of(2026, 10, 4)
        var repository = repository()
        val habit = repository.create("Walk")
        repository.setCompleted(habit.id, date, true)
        repository.archive(habit.id)
        var snapshot = repository.observe(date, date).first()
        val archived = snapshot.habits.single()
        assertEquals(date.toString(), archived.archivedLocalDateIso)
        assertTrue(HabitPolicy.visibleHabits(snapshot, date, date).isEmpty())
        assertTrue(HabitPolicy.isEligible(archived, date))
        assertEquals(
            HabitDayState.COMPLETED,
            HabitPolicy.stateFor(archived, date, date, snapshot.checkIns),
        )
        expectInvalid { repository.setCompleted(habit.id, date, false) }
        assertTrue(database.habitDao().getAllCheckIns().single().completed)

        reopenDatabase()
        clock.currentUtcMs = Instant.parse("2026-10-05T01:00:00Z").toEpochMilli()
        repository = repository()
        snapshot = repository.observe(date, date.plusDays(1)).first()
        assertEquals(listOf(archived), HabitPolicy.visibleHabits(snapshot, date, date.plusDays(1)))
        assertTrue(
            HabitPolicy.visibleHabits(snapshot, date.plusDays(1), date.plusDays(1)).isEmpty(),
        )
        expectInvalid { repository.setCompleted(habit.id, date.plusDays(1), true) }
        repository.setCompleted(habit.id, date, false)
        val undone = database.habitDao().getAllCheckIns().single()
        assertFalse(undone.completed)
        assertEquals(date.toString(), undone.localDateIso)

        reopenDatabase()
        assertEquals(listOf(archived), database.habitDao().getAll())
        assertEquals(listOf(undone), database.habitDao().getAllCheckIns())
    }

    @Test
    fun timezoneChangesAffectNewCommandsWithoutRewritingPersistedLocalDateKeys() = runBlocking {
        val repository = repository()
        val createdDate = LocalDate.of(2026, 10, 4)
        val habit = repository.create("Read", "Ten pages\nBefore bed")
        repository.setCompleted(habit.id, createdDate, true)
        val original = database.habitDao().getAllCheckIns().single()

        zone = ZoneId.of("America/Los_Angeles")
        expectInvalid { repository.setCompleted(habit.id, createdDate, false) }
        expectInvalid { repository.setCompleted(habit.id, createdDate.minusDays(1), true) }
        assertEquals(listOf(original), database.habitDao().getAllCheckIns())

        clock.currentUtcMs = Instant.parse("2026-10-05T07:30:00Z").toEpochMilli()
        repository.setCompleted(habit.id, createdDate.plusDays(1), true)
        expectInvalid { repository.setCompleted(habit.id, createdDate.plusDays(2), true) }
        reopenDatabase()
        val persisted = database.habitDao().getAllCheckIns()
        assertEquals(listOf("2026-10-04", "2026-10-05"), persisted.map { it.localDateIso })
        assertEquals(original, persisted.first())
        assertTrue(persisted.all { it.completed })
        assertEquals(habit, database.habitDao().getAll().single())
    }

    @Test
    fun olderBackupCannotResurrectUndoAndLatestBackupRestoresIntoAnotherFileDatabase() =
        runBlocking {
            val repository = repository()
            val date = LocalDate.of(2026, 10, 4)
            val habit = repository.create("Read", "Ten pages\nBefore bed")
            repository.setCompleted(habit.id, date, true)
            val service = ClockBackupService(isolatedStorageContext(), database)
            val completedFile = File(storageDirectory, "completed.clockbackup")
            service.exportArchive(Uri.fromFile(completedFile))
            assertEquals(
                4, ClockBackupArchive.read(completedFile.readBytes()).manifest.schemaVersion,
            )

            repository.setCompleted(habit.id, date, false)
            val undone = database.habitDao().getAllCheckIns().single()
            service.importArchive(Uri.fromFile(completedFile))
            assertEquals(listOf(undone), database.habitDao().getAllCheckIns())
            val latestFile = File(storageDirectory, "latest.clockbackup")
            service.exportArchive(Uri.fromFile(latestFile))

            val restoredName = "habit-restore-${UUID.randomUUID()}.db"
            var restored = openDatabase(restoredName)
            try {
                ClockBackupService(isolatedStorageContext(), restored)
                    .importArchive(Uri.fromFile(latestFile))
                assertEquals(listOf(habit), restored.habitDao().getAll())
                assertEquals(listOf(undone), restored.habitDao().getAllCheckIns())
                restored.close()
                restored = openDatabase(restoredName)
                assertEquals(listOf(habit), restored.habitDao().getAll())
                assertEquals(listOf(undone), restored.habitDao().getAllCheckIns())
            } finally {
                restored.close()
                context.deleteDatabase(restoredName)
            }

            reopenDatabase()
            assertEquals(listOf(undone), database.habitDao().getAllCheckIns())
        }

    @Test
    fun checksumValidArchiveWithMissingHabitForeignKeyMakesNoPartialRoomChanges() = runBlocking {
        val repository = repository()
        val date = LocalDate.of(2026, 10, 4)
        val habit = repository.create("Read", "Ten pages\nBefore bed")
        repository.setCompleted(habit.id, date, true)
        val completed = database.habitDao().getAllCheckIns().single()
        val incoming = BackupBundle(
            categories = emptyList(),
            presets = emptyList(),
            sessions = emptyList(),
            habits = listOf(
                habit.copy(name = "Imported rename", updatedAtUtcMs = habit.updatedAtUtcMs + 5),
                habit.copy(id = UUID.randomUUID().toString(), name = "Imported habit"),
            ),
            habitCheckIns = listOf(completed),
        )
        val files = readZip(ClockBackupArchive.writeToBytes(incoming, "test", clock.instant()))
        files[HABIT_CHECK_INS_FILE] = files.getValue(HABIT_CHECK_INS_FILE).decodeToString()
            .replace(habit.id, UUID.randomUUID().toString()).encodeToByteArray()
        // Matching checksums ensure rejection tests semantic validation before the Room writer.
        rebuildManifest(files, 4)
        val invalidFile = File(storageDirectory, "invalid.clockbackup")
        invalidFile.writeBytes(writeZip(files))

        try {
            ClockBackupService(isolatedStorageContext(), database)
                .importArchive(Uri.fromFile(invalidFile))
            fail("Expected foreign-key validation failure")
        } catch (_: BackupFormatException) {
            assertEquals(listOf(habit), database.habitDao().getAll())
            assertEquals(listOf(completed), database.habitDao().getAllCheckIns())
        }
        reopenDatabase()
        assertEquals(listOf(habit), database.habitDao().getAll())
        assertEquals(listOf(completed), database.habitDao().getAllCheckIns())
    }

    @Test
    fun versionOneAndTwoServiceImportsKeepExistingHabitDefinitionsAndUndoRows() = runBlocking {
        val repository = repository()
        val date = LocalDate.of(2026, 10, 4)
        val habit = repository.create("Read", "Ten pages\nBefore bed")
        repository.setCompleted(habit.id, date, true)
        repository.setCompleted(habit.id, date, false)
        val undone = database.habitDao().getAllCheckIns().single()
        val service = ClockBackupService(isolatedStorageContext(), database)
        val currentFile = File(storageDirectory, "current.clockbackup")
        service.exportArchive(Uri.fromFile(currentFile))

        for (version in listOf(1, 2)) {
            val files = readZip(currentFile.readBytes())
            files.remove(HABITS_FILE)
            files.remove(HABIT_CHECK_INS_FILE)
            files.remove(HABIT_TARGETS_FILE)
            if (version == 1) files.remove(REFLECTIONS_FILE)
            rebuildManifest(files, version)
            val legacyFile = File(storageDirectory, "version-$version.clockbackup")
            legacyFile.writeBytes(writeZip(files))
            assertTrue(ClockBackupArchive.read(legacyFile.readBytes()).bundle.habits.isEmpty())
            service.importArchive(Uri.fromFile(legacyFile))
            assertEquals(listOf(habit), database.habitDao().getAll())
            assertEquals(listOf(undone), database.habitDao().getAllCheckIns())
        }

        reopenDatabase()
        assertEquals(listOf(habit), database.habitDao().getAll())
        assertEquals(listOf(undone), database.habitDao().getAllCheckIns())
    }

    @Test
    fun partialRepetitionsTargetsAndBackupSurviveReopening() = runBlocking {
        val date = LocalDate.of(2026, 10, 4)
        val habit = repository().create("Exercise", targetCount = 2)
        repository().record(habit.id, date)
        reopenDatabase()
        assertEquals(1, database.habitDao().getAllCheckIns().single().completedCount)
        assertFalse(database.habitDao().getAllCheckIns().single().completed)
        val service = ClockBackupService(isolatedStorageContext(), database)
        val file = File(storageDirectory, "partial.clockbackup")
        service.exportArchive(Uri.fromFile(file))
        val bundle = ClockBackupArchive.read(file.readBytes()).bundle
        assertEquals(1, bundle.habitCheckIns.single().completedCount)
        assertEquals(2, bundle.habitTargets.single().targetCount)
        val name = "habit-count-restore-${UUID.randomUUID()}.db"
        val restored = openDatabase(name)
        try {
            ClockBackupService(isolatedStorageContext(), restored).importArchive(Uri.fromFile(file))
            val restoredRepository = HabitRepository(restored, clock, zoneProvider = { zone })
            restoredRepository.record(habit.id, date)
            assertEquals(2, restored.habitDao().getAllCheckIns().single().completedCount)
            assertTrue(restored.habitDao().getAllCheckIns().single().completed)
        } finally {
            restored.close()
            context.deleteDatabase(name)
        }
        repository().record(habit.id, date)
        assertTrue(database.habitDao().getAllCheckIns().single().completed)
        repository().record(habit.id, date)
        reopenDatabase()
        assertEquals(1, database.habitDao().getAllCheckIns().single().completedCount)
    }

    @Test
    fun concurrentRepetitionsFromTwoRepositoriesDoNotLoseUpdates() = runBlocking {
        val date = LocalDate.of(2026, 10, 4)
        val repositories = listOf(repository(), repository())
        val habit = repositories.first().create("Exercise", targetCount = 99)
        (0 until 32).map { index ->
            async(Dispatchers.Default) { repositories[index % 2].record(habit.id, date) }
        }.awaitAll()
        reopenDatabase()
        assertEquals(32, database.habitDao().getAllCheckIns().single().completedCount)
        assertFalse(database.habitDao().getAllCheckIns().single().completed)
    }

    private fun openDatabase(name: String): ClockDatabase =
        Room.databaseBuilder(context, ClockDatabase::class.java, name).build()

    private fun reopenDatabase() {
        database.close()
        database = openDatabase(databaseName)
    }

    private fun repository(): HabitRepository = HabitRepository(
        database,
        clock,
        zoneProvider = { zone },
    )

    private fun isolatedStorageContext(): Context = object : ContextWrapper(context) {
        override fun getFilesDir(): File = storageDirectory
    }

    private fun readZip(bytes: ByteArray): MutableMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return files
    }

    private fun rebuildManifest(files: MutableMap<String, ByteArray>, schemaVersion: Int) {
        val manifest = JSONObject(files.getValue(MANIFEST_FILE).decodeToString())
        val checksums = JSONObject()
        files.filterKeys { it != MANIFEST_FILE }.forEach { (name, bytes) ->
            checksums.put(name, JSONObject().put("sha256", sha256(bytes)))
        }
        manifest.put("schemaVersion", schemaVersion).put("files", checksums)
        files[MANIFEST_FILE] = manifest.toString().encodeToByteArray()
    }

    private fun writeZip(files: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private suspend fun expectInvalid(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected command validation failure")
        } catch (_: IllegalArgumentException) {
            // Invalid commands must fail before changing any persistent row.
        }
    }

    private class MutableClock(var currentUtcMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(currentUtcMs)
    }
}
