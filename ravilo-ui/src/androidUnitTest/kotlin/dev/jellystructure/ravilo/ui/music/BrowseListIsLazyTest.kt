package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R383 (FR-R383-4, DEB-659) — the Songs chip composes the rows on screen, not the library. The Mac measurement
 * (DEB-1466) found 6 000 rows composed at once: 164 k layout nodes, 3 GB footprint, 106 % CPU, because the page put every
 * loaded row in one item and each last row's `more()` pulled the next page in at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class BrowseListIsLazyTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `a long songs page composes the visible rows and pages in only on reaching the end`() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val fetches = AtomicInteger()
        // Its own thread, not the JVM-wide Dispatchers.Default pool the ~1 800 tests of this fork share and can starve (DEB-4346).
        val pool = Executors.newSingleThreadExecutor()
        val fetchThread = pool.asCoroutineDispatcher()
        val total = 6_000
        val paged = MusicPagedList(dispatcher = fetchThread) { page ->
            fetches.incrementAndGet()
            MusicList(tracks = (page * 100 until minOf(total, page * 100 + 100)).map { MusicTrackItem(id = "t$it", title = "Stand-in song $it") }, total = total, page = page, pageSize = 100)
        }
        rule.setContent {
            val state by paged.state.collectAsState()
            LazyColumn(Modifier.fillMaxSize()) {
                listChip(paged, state, 880.dp, 540.dp, true, "songs", null, {}, {}, {}, {})
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Stand-in song 0")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val composed = (0 until total).count { rule.onAllNodes(hasText("Stand-in song $it")).fetchSemanticsNodes().isNotEmpty() }
        pool.shutdown()
        assertTrue(composed in 1..40, "only the visible rows compose; $composed of $total did")
        assertEquals(1, fetches.get(), "no page beyond the first was asked for before the viewer scrolled")
    }
}
