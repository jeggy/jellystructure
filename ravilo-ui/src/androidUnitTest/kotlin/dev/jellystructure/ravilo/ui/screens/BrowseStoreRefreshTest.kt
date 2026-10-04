package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.SearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R364 (FR-R364-1) — a kept [BrowseStore] refreshes on every arrival without a *Loading…* flash; a first `ALL` arrival
 * loads (review item 2); a kind change still passes through `Loading`. Plain JUnit on the JVM fake; the store runs on
 * `Dispatchers.Default`, so states are recorded by an unconfined collector and the test polls with a timeout.
 */
class BrowseStoreRefreshTest {
    private val stand = MediaCard(id = "s1", kind = MediaKind.SERIES, title = "Stand-in Lighthouse", year = 2019,
        genre = null, rating = null, posterUrl = null, backdropUrl = null)

    private val myListCalls = AtomicInteger(0)
    @Volatile private var myList: List<MediaCard> = emptyList()
    private val pages = Collections.synchronizedList(mutableListOf<String>())

    private val api = fakeTvApiClient { path ->
        when (path) {
            "/api/tv/browse" -> {
                myListCalls.incrementAndGet()
                RaviloWireJson.encodeToString(SearchResults.serializer(), SearchResults(query = "", items = myList, total = myList.size))
            }
            "/api/tv/facets" -> RaviloWireJson.encodeToString(BrowseFacets.serializer(), BrowseFacets())
            else -> null
        }
    }

    private fun record(store: BrowseStore): Pair<MutableList<BrowseState>, CoroutineScope> {
        val states = Collections.synchronizedList(mutableListOf<BrowseState>())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        store.state.onEach { states += it }.launchIn(scope)
        return states to scope
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun loaded(store: BrowseStore) = store.state.value as? BrowseState.Loaded

    @Test fun `a kept My List store refreshes on every arrival and never flashes Loading`() {
        val store = BrowseStore(api)
        val (states, scope) = record(store)
        store.refresh(BrowseKind.MY_LIST)                  // first arrival: from nothing → Loading → Loaded
        waitFor("the first load") { loaded(store) != null && myListCalls.get() == 1 }
        assertEquals(0, loaded(store)!!.results.total)
        val afterFirst = states.size
        myList = listOf(stand)                             // a title added (from this TV or a phone)
        store.refresh(BrowseKind.MY_LIST)                  // the next arrival
        waitFor("the refresh") { loaded(store)?.results?.total == 1 }
        assertEquals(2, myListCalls.get(), "two arrivals, two fetches")
        val later = states.drop(afterFirst)
        assertTrue(later.none { it is BrowseState.Loading }, "no Loading after the first Loaded: $later")
        scope.cancel()
    }

    @Test fun `a first ALL arrival loads (it used to stay on Loading forever)`() {
        val store = BrowseStore(api)
        store.refresh(BrowseKind.ALL)
        waitFor("ALL to load") { loaded(store) != null }
    }

    @Test fun `a kind change passes through Loading once`() {
        val store = BrowseStore(api)
        store.refresh(BrowseKind.MY_LIST)
        waitFor("My List") { loaded(store) != null }
        val (states, scope) = record(store)
        store.refresh(BrowseKind.MOVIES)
        waitFor("Movies") { loaded(store) != null && store.activeKind == BrowseKind.MOVIES && states.size >= 3 }
        assertEquals(1, states.count { it is BrowseState.Loading })
        scope.cancel()
    }
}
