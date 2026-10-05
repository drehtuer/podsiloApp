// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import android.content.Context
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity
import net.drehtuer.podsilo.core.database.entity.FeedEntity
import net.drehtuer.podsilo.core.database.entity.LogEntryEntity
import net.drehtuer.podsilo.core.database.entity.SyncStateEntity
import net.drehtuer.podsilo.core.model.port.ArchiveFailure
import net.drehtuer.podsilo.core.model.port.ArchiveOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup/restore round trip.
 *
 * File-backed rather than in-memory, unlike [RoomTestBase]: the export copies
 * `context.getDatabasePath(...)` off disk, so an in-memory database would have nothing to copy and
 * the test would prove nothing.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseArchiveStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: PodsiloDatabase
    private lateinit var archive: DatabaseArchiveStore

    @Before
    fun setUp() {
        context.getDatabasePath(PodsiloDatabase.DATABASE_NAME).parentFile?.mkdirs()
        db = openLive()
        // A real dispatcher, not a TestDispatcher: this class does blocking file IO, and a test
        // dispatcher created here would bring a second scheduler into runTest's, which fails.
        archive = DatabaseArchiveStore(context, db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath(PodsiloDatabase.DATABASE_NAME).delete()
    }

    private fun openLive(): PodsiloDatabase =
        Room
            .databaseBuilder(context, PodsiloDatabase::class.java, PodsiloDatabase.DATABASE_NAME)
            .apply { PODSILO_MIGRATIONS.forEach { addMigrations(it) } }
            .build()

    @Test
    fun `round trip restores the ledger the app cannot rebuild from anywhere else`() =
        runTest {
            seed()
            val file = tempFile("backup.zip")

            val exported = archive.exportTo(Uri.fromFile(file).toString())
            assertTrue("export failed: $exported", exported is ArchiveOutcome.Exported)
            assertEquals(1, (exported as ArchiveOutcome.Exported).contents.feeds)
            assertEquals(2, exported.contents.episodes)
            assertEquals(1, exported.contents.ledgerRows)

            // Losing everything is the scenario this feature exists for.
            db.feedDao().deleteAll()
            db.episodeLedgerDao().deleteAll()
            db.logDao().clear()
            assertEquals(0, db.episodeLedgerDao().count())

            val imported = archive.importFrom(Uri.fromFile(file).toString())
            assertTrue("import failed: $imported", imported is ArchiveOutcome.Imported)

            assertEquals(1, db.feedDao().count())
            assertEquals(2, db.episodeDao().count())
            val row = db.episodeLedgerDao().get("ep-1")
            assertNotNull("the ledger row did not come back", row)
            // The two fields that exist nowhere but here: an unsynced row (Nextcloud never saw it)
            // and the name a retry must reuse rather than write a second file (CLAUDE.md §6).
            assertEquals(false, row?.syncedToServer)
            assertEquals("20260101_Episode-one.mp3", row?.writtenFileName)
            assertEquals(715L, db.syncStateDao().get()?.lastEpisodeActionSyncTs)
            // S8's history comes back too, collapse counter and all — it is the diagnostics a user
            // restoring after a failure most needs.
            val logEntry = db.logDao().getAll().single()
            assertEquals("Feed server did not respond", logEntry.message)
            assertEquals(3, logEntry.occurrences)
        }

    @Test
    fun `an export to a destination that cannot be written reports WRITE_FAILED`() =
        runTest {
            seed()
            File(context.cacheDir, "no-such-dir").deleteRecursively()
            val unwritable = File(context.cacheDir, "no-such-dir/backup.zip")

            val outcome = archive.exportTo(Uri.fromFile(unwritable).toString())

            assertEquals(ArchiveFailure.WRITE_FAILED, (outcome as ArchiveOutcome.Failed).reason)
        }

    @Test
    fun `an import from a file that is not there is unreadable and changes nothing`() =
        runTest {
            seed()

            val outcome = archive.importFrom(Uri.fromFile(tempFile("vanished.zip")).toString())

            assertEquals(ArchiveFailure.UNREADABLE, (outcome as ArchiveOutcome.Failed).reason)
            assertEquals(1, db.episodeLedgerDao().count())
        }

    /** Both entries are required: a manifest alone has nothing to restore from. */
    @Test
    fun `a manifest without a database is not a backup`() =
        runTest {
            val file = tempFile("manifest-only.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("podsilo-backup.properties"))
                zip.write("archiveFormat=1\nschemaVersion=1\n".toByteArray())
                zip.closeEntry()
            }

            val outcome = archive.importFrom(Uri.fromFile(file).toString())
            assertEquals(ArchiveFailure.NOT_AN_ARCHIVE, (outcome as ArchiveOutcome.Failed).reason)
        }

    /** A backup file the user may copy to a PC must not double as a credential file. */
    @Test
    fun `the archive holds only the manifest and the database`() =
        runTest {
            seed()
            val file = tempFile("contents.zip")
            archive.exportTo(Uri.fromFile(file).toString())

            val names = mutableListOf<String>()
            ZipInputStream(file.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { names += it.name }
            }
            assertEquals(listOf("podsilo-backup.properties", "podsilo.db"), names)
        }

    @Test
    fun `some other zip is reported as not a backup rather than as unreadable`() =
        runTest {
            val file = tempFile("holiday-photos.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("beach.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }

            val outcome = archive.importFrom(Uri.fromFile(file).toString())
            assertEquals(ArchiveFailure.NOT_AN_ARCHIVE, (outcome as ArchiveOutcome.Failed).reason)
        }

    /**
     * "Update Podsilo" is a different instruction from "that isn't a backup", and the user cannot
     * act on the right one if both arrive as the same sentence.
     */
    @Test
    fun `a backup from a newer Podsilo says so instead of claiming the file is broken`() =
        runTest {
            seed()
            val real = tempFile("real.zip")
            archive.exportTo(Uri.fromFile(real).toString())
            val fromTheFuture =
                rewriteManifest(real, tempFile("future.zip"), manifest = "archiveFormat=1\nschemaVersion=99\n")

            val outcome = archive.importFrom(Uri.fromFile(fromTheFuture).toString())
            assertEquals(ArchiveFailure.NEWER_SCHEMA, (outcome as ArchiveOutcome.Failed).reason)
        }

    /** The all-or-nothing claim: a bad archive must not leave a half-replaced database behind. */
    @Test
    fun `a corrupt archive leaves the existing data exactly as it was`() =
        runTest {
            seed()
            val file = tempFile("corrupt.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("podsilo-backup.properties"))
                zip.write("archiveFormat=1\nschemaVersion=1\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("podsilo.db"))
                zip.write("this is not a database".toByteArray())
                zip.closeEntry()
            }

            val outcome = archive.importFrom(Uri.fromFile(file).toString())
            assertEquals(ArchiveFailure.UNREADABLE, (outcome as ArchiveOutcome.Failed).reason)
            assertEquals(1, db.feedDao().count())
            assertEquals(2, db.episodeDao().count())
            assertEquals(1, db.episodeLedgerDao().count())
        }

    /**
     * The half of the guard the header check cannot cover: a file that opens fine but holds less
     * than it should. Simulated by tampering with the manifest, since the effect is what matters —
     * read and recorded counts disagree, so nothing is replaced.
     */
    @Test
    fun `an archive whose contents disagree with its own manifest is refused`() =
        runTest {
            seed()
            val real = tempFile("real.zip")
            archive.exportTo(Uri.fromFile(real).toString())
            val tampered =
                rewriteManifest(
                    real,
                    tempFile("tampered.zip"),
                    manifest = "archiveFormat=1\nschemaVersion=4\nfeeds=1\nepisodes=99\nledgerRows=1\n",
                )

            val outcome = archive.importFrom(Uri.fromFile(tampered).toString())
            assertEquals(ArchiveFailure.UNREADABLE, (outcome as ArchiveOutcome.Failed).reason)
            assertEquals(1, db.episodeLedgerDao().count())
        }

    private suspend fun seed() {
        db.feedDao().upsertAll(
            listOf(
                FeedEntity(
                    url = "https://example.org/feed.xml",
                    title = "Example",
                    imageUrl = null,
                    firstSeenAt = 100,
                    lastRefreshedAt = 200,
                    httpEtag = null,
                    httpLastModified = null,
                ),
            ),
        )
        db.episodeDao().insertAll(
            listOf(episodeEntity("ep-1", "Episode one"), episodeEntity("ep-2", "Episode two")),
        )
        db.episodeLedgerDao().upsert(
            EpisodeLedgerEntity(
                episodeKey = "ep-1",
                feedUrl = "https://example.org/feed.xml",
                enclosureUrl = "https://example.org/ep-1.mp3",
                state = "DOWNLOADED",
                actionedAt = 1_700_000_000_000,
                syncedToServer = false,
                attempts = 1,
                lastError = null,
                writtenFileName = "20260101_Episode-one.mp3",
                durationSeconds = 1800,
            ),
        )
        db.syncStateDao().upsert(
            SyncStateEntity(
                id = SyncStateEntity.SINGLETON_ID,
                lastEpisodeActionSyncTs = 715,
                deviceId = "podsilo-test",
            ),
        )
        db.logDao().insert(
            LogEntryEntity(
                identity = "FEED https://example.org/feed.xml  feed server did not respond",
                at = 1_700_000_000_000,
                category = "FEED",
                feedUrl = "https://example.org/feed.xml",
                episodeKey = null,
                message = "Feed server did not respond",
                detail = null,
                occurrences = 3,
                firstSeenAt = 1_600_000_000_000,
            ),
        )
    }

    private fun episodeEntity(
        key: String,
        title: String,
    ) = EpisodeEntity(
        episodeKey = key,
        feedUrl = "https://example.org/feed.xml",
        guid = key,
        enclosureUrl = "https://example.org/$key.mp3",
        title = title,
        description = null,
        pubDate = 1_700_000_000_000,
        durationMs = null,
    )

    /** Copies [source] entry for entry, replacing the manifest with [manifest]. */
    private fun rewriteManifest(
        source: File,
        destination: File,
        manifest: String,
    ): File {
        ZipOutputStream(destination.outputStream()).use { out ->
            ZipInputStream(source.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry -> out.copyEntry(entry.name, zip, manifest) }
            }
        }
        return destination
    }

    private fun ZipOutputStream.copyEntry(
        name: String,
        from: ZipInputStream,
        manifest: String,
    ) {
        putNextEntry(ZipEntry(name))
        if (name == "podsilo-backup.properties") write(manifest.toByteArray()) else from.copyTo(this)
        closeEntry()
    }

    private fun tempFile(name: String): File = File(context.cacheDir, name).also { it.delete() }
}
