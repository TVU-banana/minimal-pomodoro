package com.akira.clock.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Reads and writes the bounded, checksummed `.clockbackup` ZIP container. */
internal object ClockBackupArchive {
    fun write(
        bundle: BackupBundle,
        appVersion: String,
        exportedAt: Instant,
        output: OutputStream,
    ) {
        require(appVersion.isNotBlank())
        val normalized = BackupValidator.validate(bundle).normalized()
        val openFiles = OpenBackupCodec.encode(normalized).asMap()
        val content = linkedMapOf<String, ByteArray>().apply {
            putAll(openFiles)
            normalized.media.toSortedMap().forEach { (digest, bytes) ->
                put("media/$digest.webp", bytes)
            }
        }
        val manifest = encodeManifest(
            BackupManifest(
                schemaVersion = BACKUP_SCHEMA_VERSION,
                appVersion = appVersion,
                exportedAt = exportedAt.toString(),
                files = content.mapValues { sha256(it.value) },
            ),
        )
        ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
            writeStoredEntry(zip, MANIFEST_FILE, manifest)
            content.forEach { (name, bytes) -> writeStoredEntry(zip, name, bytes) }
        }
    }

    fun writeToBytes(bundle: BackupBundle, appVersion: String, exportedAt: Instant): ByteArray =
        ByteArrayOutputStream().also { write(bundle, appVersion, exportedAt, it) }.toByteArray()

    fun read(bytes: ByteArray): ValidatedBackup = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): ValidatedBackup {
        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        try {
            ZipInputStream(input, StandardCharsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    validateEntryName(entry.name)
                    if (entry.isDirectory) {
                        throw BackupFormatException("Invalid directory entry: ${entry.name}")
                    }
                    if (entries.containsKey(entry.name)) {
                        throw BackupFormatException("Duplicate ZIP entry: ${entry.name}")
                    }
                    if (entries.size >= MAX_ENTRY_COUNT) {
                        throw BackupFormatException("Backup contains too many files")
                    }
                    val content = readLimited(zip, MAX_ENTRY_SIZE)
                    totalSize += content.size
                    if (totalSize > MAX_TOTAL_SIZE) {
                        throw BackupFormatException("Backup is too large")
                    }
                    entries[entry.name] = content
                    zip.closeEntry()
                }
            }
        } catch (failure: BackupFormatException) {
            throw failure
        } catch (failure: IOException) {
            throw BackupFormatException("Invalid or truncated clockbackup archive", failure)
        }

        val manifestBytes = entries.remove(MANIFEST_FILE)
            ?: throw BackupFormatException("Missing required file: $MANIFEST_FILE")
        val manifest = decodeManifest(manifestBytes)
        if (manifest.schemaVersion !in MIN_READABLE_BACKUP_SCHEMA_VERSION..BACKUP_SCHEMA_VERSION) {
            throw BackupFormatException(
                "Unsupported backup schema version: ${manifest.schemaVersion}",
            )
        }
        val actualNames = entries.keys
        if (manifest.files.keys != actualNames) {
            throw BackupFormatException("Manifest file list does not match archive contents")
        }
        manifest.files.forEach { (name, digest) ->
            if (!digest.matches(Regex("[0-9a-f]{64}"))) {
                throw BackupFormatException("Invalid SHA-256 for $name")
            }
            if (sha256(entries.getValue(name)) != digest) {
                throw BackupFormatException("Checksum mismatch for $name")
            }
        }

        if (manifest.schemaVersion >= 2 && REFLECTIONS_FILE !in entries) {
            throw BackupFormatException("Missing required file: $REFLECTIONS_FILE")
        }
        val allowedData = setOf(
            CATEGORIES_FILE,
            PRESETS_FILE,
            SESSIONS_FILE,
            SEGMENTS_FILE,
            ASSETS_FILE,
            REFLECTIONS_FILE,
        ) + if (manifest.schemaVersion >= 3) {
            setOf(HABITS_FILE, HABIT_CHECK_INS_FILE)
        } else {
            emptySet()
        }
        val requiredData = allowedData + if (manifest.schemaVersion >= 4) {
            setOf(HABIT_TARGETS_FILE)
        } else emptySet()
        if (manifest.schemaVersion >= 3) {
            requiredData.forEach { name ->
                if (name !in entries) throw BackupFormatException("Missing required file: $name")
            }
        }
        entries.keys.forEach { name ->
            if (name !in requiredData && !name.matches(Regex("media/[0-9a-f]{64}\\.webp"))) {
                throw BackupFormatException("Unexpected backup entry: $name")
            }
        }
        val bundle = OpenBackupCodec.decode(entries, manifest.schemaVersion)
        val media = entries.filterKeys { it.startsWith("media/") }.mapKeys { (name, _) ->
            name.removePrefix("media/").removeSuffix(".webp")
        }
        return ValidatedBackup(
            manifest,
            BackupValidator.validate(bundle.copy(media = media)).normalized(),
        )
    }

    private fun encodeManifest(manifest: BackupManifest): ByteArray = buildString {
        append('{')
        append("\"format\":${JsonCodec.quote(BACKUP_FORMAT)},")
        append("\"schemaVersion\":${manifest.schemaVersion},")
        append("\"appVersion\":${JsonCodec.quote(manifest.appVersion)},")
        append("\"exportedAt\":${JsonCodec.quote(manifest.exportedAt)},")
        append("\"timeUnit\":\"milliseconds\",")
        append("\"files\":{")
        manifest.files.entries.forEachIndexed { index, (name, digest) ->
            if (index > 0) append(',')
            append(JsonCodec.quote(name))
            append(":{\"sha256\":")
            append(JsonCodec.quote(digest))
            append('}')
        }
        append("}}")
    }.toByteArray(StandardCharsets.UTF_8)

    private fun decodeManifest(bytes: ByteArray): BackupManifest {
        val fields = JsonCodec.parse(bytes.toString(StandardCharsets.UTF_8)).objectValue("manifest")
        if (fields.requiredString("format") != BACKUP_FORMAT) {
            throw BackupFormatException("Unknown backup format")
        }
        val version = fields.requiredLong("schemaVersion")
        if (version !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw BackupFormatException("Invalid schemaVersion")
        }
        val appVersion = fields.requiredString("appVersion")
        val exportedAt = fields.requiredString("exportedAt")
        try {
            if (!exportedAt.endsWith('Z')) throw IllegalArgumentException("UTC designator required")
            Instant.parse(exportedAt)
        } catch (failure: Exception) {
            throw BackupFormatException("exportedAt must be an ISO-8601 UTC timestamp", failure)
        }
        val fileFields = (fields["files"] ?: throw BackupFormatException("files is required"))
            .objectValue("files")
        val files = linkedMapOf<String, String>()
        fileFields.forEach { (name, value) ->
            files[name] = value.objectValue("files.$name").requiredString("sha256")
        }
        return BackupManifest(version.toInt(), appVersion, exportedAt, files)
    }

    private fun writeStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
            time = 0L
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BackupFormatException("Backup entry is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateEntryName(name: String) {
        if (
            name.isBlank() || name.startsWith('/') || name.startsWith('\\') ||
            name.contains("\\") ||
            name.split('/').any { it == ".." || it.isEmpty() }
        ) throw BackupFormatException("Unsafe ZIP entry name: $name")
    }

    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 256L * 1024 * 1024
    private const val MIN_READABLE_BACKUP_SCHEMA_VERSION = 1
}
