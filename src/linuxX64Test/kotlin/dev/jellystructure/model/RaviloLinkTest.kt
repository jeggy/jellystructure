package dev.jellystructure.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaviloLinkTest {
    @Test fun blankAddressHasNoLink() {
        assertNull(RaviloLink.titleUrl("", MediaKind.MOVIE, "abc123"))
    }

    @Test fun invalidAddressHasNoLink() {
        assertNull(RaviloLink.titleUrl("ftp://x", MediaKind.MOVIE, "abc123"))
    }

    @Test fun movieUsesNormalizedAddress() {
        assertEquals("https://r.example.org/#/movie/abc123", RaviloLink.titleUrl("https://r.example.org/", MediaKind.MOVIE, "abc123"))
    }

    @Test fun seriesRetainsAddressPath() {
        assertEquals("https://example.org/ravilo/#/series/abc123", RaviloLink.titleUrl("https://example.org/ravilo", MediaKind.TV_SHOW, "abc123"))
    }

    @Test fun musicVideoHasNoPage() {
        assertNull(RaviloLink.titleUrl("https://r.example.org", MediaKind.MUSIC_VIDEO, "abc123"))
    }

    @Test fun missingIdHasNoLink() {
        assertNull(RaviloLink.titleUrl("https://r.example.org", MediaKind.MOVIE, null))
        assertNull(RaviloLink.titleUrl("https://r.example.org", MediaKind.MOVIE, ""))
        assertNull(RaviloLink.titleUrl("https://r.example.org", MediaKind.MOVIE, "  "))
    }
}
