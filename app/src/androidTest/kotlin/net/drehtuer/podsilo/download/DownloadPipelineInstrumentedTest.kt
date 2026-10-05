// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.download

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.database.PodsiloDatabase
import net.drehtuer.podsilo.core.database.repository.EpisodeLedgerRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.EpisodeRepositoryImpl
import net.drehtuer.podsilo.core.database.repository.FeedRepositoryImpl
import net.drehtuer.podsilo.core.download.ArtworkFetcher
import net.drehtuer.podsilo.core.download.AudioTagWriter
import net.drehtuer.podsilo.core.download.DownloadOutcome
import net.drehtuer.podsilo.core.download.DownloadRequest
import net.drehtuer.podsilo.core.download.EnclosureDownloader
import net.drehtuer.podsilo.core.download.EpisodeDownloader
import net.drehtuer.podsilo.core.download.SafDownloadTarget
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.NamingSettings
import okhttp3.OkHttpClient
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **The download pipeline end to end, against a real feed and a real SAF folder** — the last thing
 * `backlog.adoc` had listed as unverifiable without a device: enclosure fetch → tag write →
 * artwork embed → SAF copy → ledger row → outbox.
 *
 * The chain is real at every link. The bytes come from the podcast's own CDN over the phone's
 * network, `AudioTagWriter` writes into the real container the publisher shipped, `SafDownloadTarget`
 * writes through a real `DocumentsProvider` into the folder the user granted, and the ledger row is
 * written by the production repository over real Room.
 *
 * ### What it deliberately does *not* touch
 *
 * **The user's ledger, and therefore their Nextcloud.** The episode and feed are *read* from the
 * app's real database — that is the only way to get a genuine enclosure URL, since subscriptions
 * arrive from Nextcloud and cannot be seeded (`UI.adoc` §4) — but the row this test writes goes
 * into an **in-memory** database. Writing to the real one would mark an episode handled in the user's
 * app and push a `DOWNLOAD`/`PLAY` pair into their shared action log, where it is not retractable
 * (`architecture.adoc` §6). The outbox is still exercised for real: the row is written unsynced
 * and `getUnsynced()` is what proves it is queued. Only the POST is left to `SyncOrchestrator`, which
 * has its own verification against a real server.
 *
 * **The delivered file is deleted at the end.** The app itself never deletes anything a user owns
 * (CLAUDE.md §1), but a test that leaves an episode in someone's library is a test that has changed
 * the thing it was measuring.
 *
 * ### Requirements, both of which skip rather than fail
 *
 * A missing SAF grant or an empty database is a setup gap, not a regression, and a red suite is one
 * people learn to ignore. Both need a human: pick a download folder, and connect the account.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPipelineInstrumentedTest {
    private lateinit var context: Context
    private lateinit var appDatabase: PodsiloDatabase
    private lateinit var scratchDatabase: PodsiloDatabase
    private lateinit var treeUri: String

    private var deliveredFile: String? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        val granted =
            context.contentResolver.persistedUriPermissions
                .firstOrNull { it.isWritePermission }
                ?.uri
                ?.toString()
        assumeTrue(
            "no download folder has been granted — pick one in the app first (S1 → Choose folder)",
            granted != null,
        )
        treeUri = granted!!

        // The app's own database, opened read-only in intent: this test reads a feed and an episode
        // from it and writes nothing back.
        appDatabase =
            Room
                .databaseBuilder(context, PodsiloDatabase::class.java, PodsiloDatabase.DATABASE_NAME)
                .build()

        scratchDatabase =
            Room
                .inMemoryDatabaseBuilder(context, PodsiloDatabase::class.java)
                .build()
    }

    @After
    fun tearDown() {
        // Delete only the file this run created, by the exact name it was written under.
        deliveredFile?.let { findDelivered(it)?.delete() }
        // JUnit runs @After even when setUp() skipped at its assumeTrue, before either database was
        // opened. Unguarded, that turned "no folder granted" from a skip into an
        // UninitializedPropertyAccessException -- a red result for a setup gap (2026-10-05 device run).
        if (::appDatabase.isInitialized) appDatabase.close()
        if (::scratchDatabase.isInitialized) scratchDatabase.close()
    }

    /**
     * The smallest real episode available, so a live download over the phone's network costs seconds
     * rather than tens of megabytes. `sizeBytes` is the feed's own advisory `<enclosure length>`;
     * episodes that do not declare one are skipped rather than gambled on.
     */
    private suspend fun smallestRealEpisode(): Pair<Feed, Episode>? {
        val feeds = FeedRepositoryImpl(appDatabase.feedDao()).observeAll().first()
        val episodes = EpisodeRepositoryImpl(appDatabase.episodeDao())
        val candidates =
            feeds.flatMap { feed ->
                episodes
                    .observeForFeed(feed.url)
                    .first()
                    .mapNotNull { episode -> episode.sizeBytes?.let { size -> Triple(feed, episode, size) } }
            }
        val smallest = candidates.minByOrNull { it.third } ?: return null
        return smallest.first to smallest.second
    }

    @Test
    fun aRealEpisodeIsFetchedTaggedDeliveredAndLedgered() =
        runBlocking {
            val picked = smallestRealEpisode()
            assumeTrue(
                "no subscribed feed with a sized episode — connect the account and refresh first",
                picked != null,
            )
            val (feed, episode) = picked!!

            val downloader =
                EpisodeDownloader(
                    enclosureDownloader = EnclosureDownloader(OkHttpClient()),
                    audioTagWriter = AudioTagWriter(),
                    downloadTarget = SafDownloadTarget(context, GrantedFolderSettings(treeUri)),
                    cacheDir = File(context.cacheDir, "pipeline-test").apply { mkdirs() },
                    artworkFetcher = ArtworkFetcher(OkHttpClient()),
                )

            val outcome =
                downloader.download(DownloadRequest(feed, episode, NamingSettings()))

            val delivered = outcome as? DownloadOutcome.Delivered
            assertNotNull("download failed: $outcome", delivered)
            deliveredFile = delivered!!.fileName

            // 1. The file is really in the user's folder, through a real DocumentsProvider. The
            //    folder is the naming engine's, resolved from the template — found rather than
            //    assumed, so the test does not restate the rule it is checking.
            val written = findDelivered(delivered.fileName)
            assertNotNull("nothing named ${delivered.fileName} anywhere under the granted tree", written)
            assertTrue("delivered an empty file", written!!.length() > 0)

            // 2. The cache is clean — a partial or untagged file must never outlive the delivery.
            assertEquals(
                "the cache file was not cleaned up",
                emptyList<String>(),
                File(context.cacheDir, "pipeline-test").listFiles().orEmpty().map { it.name },
            )

            // 3. The tags the author actually browses by, read back out of the delivered container.
            val readBack = copyOfDelivered(written).let { AudioFileIO.read(it).tag }
            assertEquals(feed.title, readBack.getFirst(FieldKey.ARTIST))
            assertEquals(feed.title, readBack.getFirst(FieldKey.ALBUM))
            assertEquals("Podcast", readBack.getFirst(FieldKey.GENRE))
            assertTrue("the episode title did not survive", readBack.getFirst(FieldKey.TITLE).isNotBlank())

            // 4. The ledger row, written by the production repository — into the scratch database,
            //    so the user's own "already handled" record is untouched. See the class KDoc.
            val ledger = EpisodeLedgerRepositoryImpl(scratchDatabase.episodeLedgerDao())
            ledger.upsert(
                EpisodeLedgerRow(
                    episodeKey = episode.episodeKey,
                    feedUrl = episode.feedUrl,
                    enclosureUrl = episode.enclosureUrl,
                    state = LedgerState.DOWNLOADED,
                    actionedAt = System.currentTimeMillis(),
                    syncedToServer = false,
                    attempts = 1,
                    lastError = null,
                    writtenFileName = delivered.fileName,
                    durationSeconds = null,
                ),
            )

            assertEquals(LedgerState.DOWNLOADED, ledger.get(episode.episodeKey)?.state)
            assertEquals(delivered.fileName, ledger.get(episode.episodeKey)?.writtenFileName)

            // 5. The outbox: the row is queued for the server and nothing has marked it sent.
            val unsynced = ledger.getUnsynced()
            assertEquals(1, unsynced.size)
            assertEquals(episode.episodeKey, unsynced.single().episodeKey)
        }

    /** The delivered document, wherever the naming template put it under the granted tree. */
    private fun findDelivered(fileName: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: return null
        return root.listFiles().firstNotNullOfOrNull { child ->
            if (child.isDirectory) child.findFile(fileName) else child.takeIf { it.name == fileName }
        }
    }

    /**
     * jaudiotagger needs a real `File`, and a SAF document is not one — the same constraint that
     * shapes the whole pipeline (`architecture.adoc` §11). Reading the delivery back therefore
     * means copying it out again.
     */
    private fun copyOfDelivered(document: DocumentFile): File {
        val extension = deliveredFile!!.substringAfterLast('.', "mp3")
        val copy = File.createTempFile("delivered", ".$extension", context.cacheDir)
        context.contentResolver.openInputStream(document.uri)!!.use { input ->
            copy.outputStream().use { input.copyTo(it) }
        }
        return copy
    }
}
