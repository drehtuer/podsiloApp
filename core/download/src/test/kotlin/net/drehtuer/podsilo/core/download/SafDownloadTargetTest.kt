// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The half of [SafDownloadTarget] a headless runner *can* reach: what it does when there is no
 * usable folder. Writing into a real tree needs a `DocumentsProvider`, which Robolectric does not
 * supply, so the success path stays verified on a device only.
 *
 * What is pinned is the contract the pipeline depends on (CLAUDE.md §11): a missing or lost grant
 * comes back as a [DownloadFolderUnavailableException] *inside the Result* — which `EpisodeDownloader`
 * turns into FOLDER_UNAVAILABLE and S8's "choose it again" — never as an exception that would crash
 * the worker, and free space degrades to "unknown" rather than to a spurious "will not fit".
 */
@RunWith(RobolectricTestRunner::class)
class SafDownloadTargetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun target(folderUri: String?) =
        SafDownloadTarget(RuntimeEnvironment.getApplication(), FakeSettingsRepository(downloadFolderUri = folderUri))

    private suspend fun assertUnusable(target: SafDownloadTarget) {
        val source = temporaryFolder.newFile("episode.mp3")
        assertTrue(target.existingNames("Der Podcast").exceptionOrNull() is DownloadFolderUnavailableException)
        val delivery = target.deliver("Der Podcast", "episode.mp3", source)
        assertTrue(delivery.exceptionOrNull() is DownloadFolderUnavailableException)
        assertNull(target.freeBytes())
    }

    @Test
    fun `with no folder chosen every operation fails as a value`() =
        runBlocking {
            assertUnusable(target(folderUri = null))
        }

    @Test
    fun `a stored folder whose provider no longer answers is unavailable, not a crash`() =
        runBlocking {
            // What a pulled SD card or a revoked grant looks like from here: the URI survives in
            // settings, but nothing behind it says it is a writable directory.
            assertUnusable(target("content://com.android.externalstorage.documents/tree/primary%3APodcasts"))
        }
}
