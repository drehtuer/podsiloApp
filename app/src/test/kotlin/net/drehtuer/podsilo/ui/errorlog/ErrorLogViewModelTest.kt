// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.errorlog

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S8's view model (`UI.adoc` §B6b). The screen test covers what renders; this covers the three
 * rules the screen cannot decide on its own: a filter *replaces* the query, Copy/Share export the
 * **whole** log rather than the filtered view, and Clear empties the whole ring buffer behind a
 * confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorLogViewModelTest {
    private val log = FakeLogRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): ErrorLogViewModel =
        ErrorLogViewModel(log).also { vm -> backgroundScope.launch { vm.state.collect { } } }

    private fun entry(
        id: Long,
        category: LogCategory,
        episodeKey: String? = null,
        feedUrl: String? = null,
    ) = LogEntry(
        id = id,
        at = 0,
        category = category,
        feedUrl = feedUrl,
        episodeKey = episodeKey,
        message = "entry $id",
        detail = null,
        occurrences = 1,
        firstSeenAt = 0,
    )

    @Test
    fun `a filter change replaces the query, so no other category renders under the chip`() =
        runTest {
            log.entries.value = listOf(entry(1, LogCategory.SYNC), entry(2, LogCategory.DOWNLOAD))
            val vm = viewModel()
            runCurrent()
            assertEquals(
                listOf(1L, 2L),
                vm.state.value.entries
                    .map { it.id },
            )

            vm.onEvent(ErrorLogEvent.FilterChanged(LogCategory.DOWNLOAD))
            runCurrent()

            assertEquals(LogCategory.DOWNLOAD, vm.state.value.filter)
            assertEquals(
                listOf(2L),
                vm.state.value.entries
                    .map { it.id },
            )
            assertEquals("the repository was asked, not filtered after", LogCategory.DOWNLOAD, log.lastCategory)

            vm.onEvent(ErrorLogEvent.FilterChanged(null))
            runCurrent()
            assertEquals(
                listOf(1L, 2L),
                vm.state.value.entries
                    .map { it.id },
            )
        }

    @Test
    fun `toggling an entry's detail opens and closes only that entry`() =
        runTest {
            log.entries.value = listOf(entry(1, LogCategory.SYNC), entry(2, LogCategory.SYNC))
            val vm = viewModel()

            vm.onEvent(ErrorLogEvent.DetailToggled(1))
            runCurrent()
            assertEquals(setOf(1L), vm.state.value.expanded)

            vm.onEvent(ErrorLogEvent.DetailToggled(2))
            vm.onEvent(ErrorLogEvent.DetailToggled(1))
            runCurrent()
            assertEquals(setOf(2L), vm.state.value.expanded)
        }

    @Test
    fun `only an entry that names an episode opens it`() =
        runTest {
            log.entries.value =
                listOf(
                    entry(1, LogCategory.DOWNLOAD, episodeKey = "e1", feedUrl = "https://example.org/feed.xml"),
                    entry(2, LogCategory.SYNC),
                )
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(ErrorLogEvent.EntryClicked(2))
                vm.onEvent(ErrorLogEvent.EntryClicked(99))
                vm.onEvent(ErrorLogEvent.EntryClicked(1))

                assertEquals(ErrorLogEffect.OpenEpisode("https://example.org/feed.xml", "e1"), awaitItem())
                expectNoEvents()
            }
        }

    /** The export is for pasting into an issue; a filtered one would omit the entry that explains it. */
    @Test
    fun `copy and share export the whole log even while a filter is on`() =
        runTest {
            log.entries.value = listOf(entry(1, LogCategory.SYNC), entry(2, LogCategory.DOWNLOAD))
            val vm = viewModel()
            vm.onEvent(ErrorLogEvent.FilterChanged(LogCategory.DOWNLOAD))
            runCurrent()

            vm.effect.test {
                vm.onEvent(ErrorLogEvent.CopyAllClicked)
                assertEquals(ErrorLogEffect.CopyToClipboard("entry 1\nentry 2"), awaitItem())

                vm.onEvent(ErrorLogEvent.ShareClicked)
                assertEquals(ErrorLogEffect.Share("entry 1\nentry 2"), awaitItem())
            }
        }

    @Test
    fun `an empty log exports nothing rather than an empty clipboard`() =
        runTest {
            val vm = viewModel()

            vm.effect.test {
                vm.onEvent(ErrorLogEvent.CopyAllClicked)
                vm.onEvent(ErrorLogEvent.ShareClicked)

                expectNoEvents()
            }
        }

    @Test
    fun `clearing asks first, and cancelling leaves the log alone`() =
        runTest {
            log.entries.value = listOf(entry(1, LogCategory.SYNC))
            val vm = viewModel()

            vm.onEvent(ErrorLogEvent.ClearRequested)
            runCurrent()
            assertTrue(vm.state.value.pendingClear)

            vm.onEvent(ErrorLogEvent.ClearCancelled)
            runCurrent()
            assertFalse(vm.state.value.pendingClear)
            assertEquals(0, log.clears)
        }

    @Test
    fun `confirming clears the whole log and says how much went`() =
        runTest {
            log.entries.value = listOf(entry(1, LogCategory.SYNC), entry(2, LogCategory.DOWNLOAD))
            val vm = viewModel()
            runCurrent()

            vm.effect.test {
                vm.onEvent(ErrorLogEvent.ClearRequested)
                vm.onEvent(ErrorLogEvent.ClearConfirmed)

                assertEquals(ErrorLogEffect.ShowMessage("Cleared 2 log entries."), awaitItem())
            }
            runCurrent()

            assertEquals(1, log.clears)
            assertFalse(vm.state.value.pendingClear)
            assertTrue(
                vm.state.value.entries
                    .isEmpty(),
            )
        }
}

/** Filters like the DAO does, and exports every entry regardless of the filter on screen. */
private class FakeLogRepository : LogRepository {
    val entries = MutableStateFlow<List<LogEntry>>(emptyList())
    var lastCategory: LogCategory? = null
        private set
    var clears = 0
        private set

    override fun observe(category: LogCategory?): Flow<List<LogEntry>> {
        lastCategory = category
        return entries.map { all -> all.filter { category == null || it.category == category } }
    }

    override suspend fun record(entry: NewLogEntry) = error("S8 never records")

    override suspend fun clear() {
        clears++
        entries.value = emptyList()
    }

    override suspend fun exportPlainText(): String = entries.value.joinToString("\n") { it.message }
}
