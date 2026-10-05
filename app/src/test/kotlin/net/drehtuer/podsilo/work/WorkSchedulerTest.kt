// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import net.drehtuer.podsilo.core.download.DownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The enqueue policies [WorkScheduler] exists to keep in one place. Each is a one-word choice whose
 * wrong value is a real bug: REPLACE on a download discards its resume progress, KEEP on a sync
 * drops the pass a just-written ledger row needs, and a directional button that loses its mode runs
 * the ordinary pass instead.
 *
 * Every request here carries a network constraint, and the test WorkManager runs nothing until a
 * [androidx.work.testing.TestDriver] says the constraints are met — so what is asserted is what was
 * *enqueued*, which is the scheduler's whole job.
 */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkScheduler

    /** The sync mode each started [SyncWorker] request carried; `null` is the ordinary pass. */
    private val startedModes = mutableListOf<String?>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker {
                    startedModes += workerParameters.inputData.getString(SyncWorker.KEY_SYNC_MODE)
                    return object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = Result.success()
                    }
                }
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkScheduler(workManager)
    }

    private fun infosFor(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun runAll(name: String) {
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        infosFor(name).forEach { driver.setAllConstraintsMet(it.id) }
    }

    @Test
    fun `a second tap on the same download joins the one already queued`() {
        scheduler.enqueueDownload("e1")
        val first = infosFor(DownloadWorker.uniqueWorkName("e1")).single()

        scheduler.enqueueDownload("e1", userRequested = true)

        assertEquals(listOf(first.id), infosFor(DownloadWorker.uniqueWorkName("e1")).map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        // Tagged, or S7 cannot map the work back to its episode (issue #47).
        assertEquals("e1", DownloadWorker.episodeKeyOf(first.tags))
    }

    @Test
    fun `cancelling a download cancels only that episode's work`() {
        scheduler.enqueueDownload("e1")
        scheduler.enqueueDownload("e2")

        scheduler.cancelDownload("e1")

        assertEquals(WorkInfo.State.CANCELLED, infosFor(DownloadWorker.uniqueWorkName("e1")).single().state)
        assertEquals(WorkInfo.State.ENQUEUED, infosFor(DownloadWorker.uniqueWorkName("e2")).single().state)
    }

    /**
     * APPEND, not KEEP: a pass already queued may read the outbox before the row that asked for this
     * one is written, so the new row gets a pass of its own after it.
     */
    @Test
    fun `a sync request while one is pending queues a second pass behind it`() {
        scheduler.requestSyncNow()
        scheduler.requestSyncNow()

        val infos = infosFor(SyncWorker.UNIQUE_WORK_NAME)
        assertEquals(2, infos.size)
        assertTrue("nothing finished", infos.none { it.state.isFinished })
    }

    @Test
    fun `each sync trigger reaches the worker with its own mode`() {
        scheduler.requestSyncNow()
        scheduler.applyRemoteState()
        scheduler.sendLocalState()

        // Appended in order, so each runs as the one before it finishes.
        runAll(SyncWorker.UNIQUE_WORK_NAME)

        assertEquals(listOf(null, SyncWorker.MODE_FORCE_PULL, SyncWorker.MODE_FORCE_PUSH), startedModes)
        assertTrue(infosFor(SyncWorker.UNIQUE_WORK_NAME).all { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun `a manual feed refresh for one feed does not coalesce with the refresh of all feeds`() {
        scheduler.requestFeedRefresh()
        scheduler.requestFeedRefresh("https://example.org/feed.xml")
        scheduler.requestFeedRefresh("https://example.org/feed.xml")

        val all =
            infosFor(
                net.drehtuer.podsilo.core.feed.FeedRefreshWorker
                    .uniqueWorkName(null),
            )
        val one =
            infosFor(
                net.drehtuer.podsilo.core.feed.FeedRefreshWorker
                    .uniqueWorkName("https://example.org/feed.xml"),
            )
        assertEquals(1, all.size)
        assertEquals("two pulls on the same feed coalesce", 1, one.size)
    }
}
