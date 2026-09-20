package dev.jellystructure.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 239 (FR-239-3). Two things are pinned, and the second is the one that actually broke.
 *
 * The **spelling**: measured live against the household server (12.1.0, 2026-09-20) on `/Users`,
 * which genuinely enforces — 401 with no credential. `?api_key=<valid>` answered **401**;
 * `?apikey=<valid>`, `?ApiKey=<valid>` and `?APIKEY=<valid>` all answered 200. So `api_key` is not
 * merely the old fashion, it does not authenticate.
 *
 * The **separator**: five call sites each wrote their own `?` or `&`, and one of them was a
 * multi-line concatenation whose last fragment began with a hand-written `&`. That is the half a
 * rename breaks, so the helper owns it.
 */
class WithJellyfinTokenTest {

    @Test
    fun aUrlWithNoQueryGetsAQuestionMark() {
        assertEquals(
            "http://jf/Videos/abc/stream?apikey=t0ken",
            withJellyfinToken("http://jf/Videos/abc/stream", "t0ken"),
        )
    }

    @Test
    fun aUrlThatAlreadyHasAQueryGetsAnAmpersand() {
        assertEquals(
            "http://jf/Videos/abc/stream?Static=true&apikey=t0ken",
            withJellyfinToken("http://jf/Videos/abc/stream?Static=true", "t0ken"),
        )
    }

    @Test
    fun theSpellingIsTheOneTwelvePointOneHonours() {
        val url = withJellyfinToken("http://jf/Items", "t0ken")
        assertTrue(url.contains("apikey="), url)
        // The measured failure: `api_key` answers 401 on an enforcing route.
        assertFalse(url.contains("api_key="), "12.1 does not honour api_key: $url")
    }

    @Test
    fun theHelperNeverProducesADoubleSeparator() {
        // The concrete regression: a caller whose own fragment ended in `&` used to be concatenated
        // with another `&` in front of the parameter.
        val url = withJellyfinToken("http://jf/Videos/abc/master.m3u8?DeviceId=d&MediaSourceId=abc", "t0ken")
        assertFalse(url.contains("&&"), url)
        assertFalse(url.contains("?&"), url)
        assertEquals(1, url.count { it == '?' })
    }
}
