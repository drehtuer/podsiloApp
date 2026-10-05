// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import net.drehtuer.podsilo.core.feed.FeedRefreshWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * The one thing `decisions/0026` has to be true for: **nothing syncs on a timer.**
 *
 * Worth a test of its own because the failure is invisible in the code. Periodic work lives in
 * WorkManager's database, not in the APK, so an install that already carries the four-hour job
 * keeps running it against a build that no longer mentions it — visible only in battery stats.
 */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulerPeriodicTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkScheduler

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkScheduler(workManager)
    }

    private fun infosFor(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `startup scheduling enqueues no periodic sync`() {
        scheduler.schedulePeriodicWork(240)

        assertEquals(
            "a periodic sync pass was scheduled",
            emptyList<WorkInfo>(),
            infosFor(SyncWorker.PERIODIC_WORK_NAME).filterNot { it.state.isFinished },
        )
    }

    @Test
    fun `a periodic sync persisted by an older build is cancelled`() {
        // Exactly what the author's phone carries: the four-hour job an earlier install enqueued.
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            FeedRefreshWorker.periodicRequest(240),
        )
        assertTrue(infosFor(SyncWorker.PERIODIC_WORK_NAME).any { !it.state.isFinished })

        scheduler.schedulePeriodicWork(240)

        assertEquals(
            "the leftover periodic sync survived an app start",
            listOf(WorkInfo.State.CANCELLED),
            infosFor(SyncWorker.PERIODIC_WORK_NAME).map { it.state },
        )
    }

    @Test
    fun `feed refresh is still periodic`() {
        // CLAUDE.md §1 requirement 2 asks for it, and it never talks to Nextcloud.
        scheduler.schedulePeriodicWork(240)

        assertEquals(
            listOf(WorkInfo.State.ENQUEUED),
            infosFor(FeedRefreshWorker.PERIODIC_WORK_NAME).map { it.state },
        )
    }

    @Test
    fun `an interval below WorkManager's floor is clamped rather than silently ignored`() {
        scheduler.schedulePeriodicWork(1)

        val refresh = infosFor(FeedRefreshWorker.PERIODIC_WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, refresh.state)
        assertEquals(TimeUnit.MINUTES.toMillis(15), refresh.periodicityInfo?.repeatIntervalMillis)
    }

    /** UPDATE, not a second schedule: changing the interval re-times the one job that exists. */
    @Test
    fun `rescheduling with a new interval re-times the existing feed refresh`() {
        scheduler.schedulePeriodicWork(240)
        val first = infosFor(FeedRefreshWorker.PERIODIC_WORK_NAME).single()

        scheduler.schedulePeriodicWork(60)

        val after = infosFor(FeedRefreshWorker.PERIODIC_WORK_NAME).single()
        assertEquals("the same job, updated in place", first.id, after.id)
        assertEquals(TimeUnit.MINUTES.toMillis(60), after.periodicityInfo?.repeatIntervalMillis)
    }
}
