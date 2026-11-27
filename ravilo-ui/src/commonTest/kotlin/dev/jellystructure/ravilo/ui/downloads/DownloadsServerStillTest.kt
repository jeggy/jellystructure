package dev.jellystructure.ravilo.ui.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-4894 (DEB-3995 FR-9) — an episode's still, read from the series page saved beside its download. */
class DownloadsServerStillTest {
    private fun controller(saved: String?, user: String? = "alice"): DownloadsController {
        val rig = PageRig()
        val media = object : LocalMediaSource {
            override fun read(userId: String, itemId: String): SavedDownload? = null
            override fun savedItem(userId: String, itemId: String): String? = saved
        }
        return DownloadsController(
            userId = { user }, api = rig.api, engine = rig.engine, library = rig.library, prefs = InMemoryDownloadPrefs(), baseUrl = { "https://srv" },
            scope = CoroutineScope(Dispatchers.Unconfined), now = { 1L }, media = media,
        )
    }

    private val page = """{"card":{"id":"s","title":"Nordvest","kind":"SERIES","year":null,"genre":null,"rating":null,"poster_url":null,"backdrop_url":null},"synopsis":null,"cast":[],"related":[],
        "seasons":[{"index":1,"name":"S1","episodes":[
        {"id":"e1","episode_number":1,"title":"One","runtime":40,"overview":null,"still_url":"/api/tv/image/e1/still"},
        {"id":"e2","episode_number":2,"title":"Two","runtime":40,"overview":null,"still_url":null}]}]}"""

    @Test fun `the episode's own still is read from the saved page`() {
        assertEquals("/api/tv/image/e1/still", controller(page).serverStill("e1"))
    }

    @Test fun `an episode with no still, a film, no saved page and broken text all give null`() {
        assertNull(controller(page).serverStill("e2"))
        assertNull(controller(page).serverStill("a-film"))
        assertNull(controller(null).serverStill("e1"))
        assertNull(controller("{not json").serverStill("e1"))
        assertNull(controller(page, user = null).serverStill("e1"))
    }
}
