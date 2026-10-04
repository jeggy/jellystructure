package dev.jellystructure.music

/**
 * Phase 305 (test 9) — MusicBrainz answers in exactly the shape musicbrainz.org sends, renamed to stand-ins: a paged
 * release browse, a release group with `release-group-rels`, a recording with `video`. Built as text so the client's
 * real decoder reads them.
 */
object MbEditionsFixtures {
    private fun track(i: Int, rec: String, video: Boolean = false) =
        """{"id":"t-$rec","position":$i,"number":"$i","title":"Song $rec","length":200000,"recording":{"id":"$rec","title":"Song $rec","length":200000,"video":$video,"disambiguation":""}}"""

    fun release(id: String, recs: List<String>, date: String = "2003", country: String = "GB", title: String = "Signal Found", dvd: List<String> = emptyList(), videos: Set<String> = emptySet()): String {
        val cd = """{"position":1,"format":"CD","track-count":${recs.size},"tracks":[${recs.mapIndexed { i, r -> track(i + 1, r, r in videos) }.joinToString(",")}]}"""
        val dvdMedium = """{"position":2,"format":"DVD-Video","track-count":${dvd.size},"tracks":[${dvd.mapIndexed { i, r -> track(i + 1, r, true) }.joinToString(",")}]}"""
        val media = if (dvd.isEmpty()) cd else "$cd,$dvdMedium"
        return """{"id":"$id","title":"$title","status":"Official","status-id":"4e304316-386d-3409-af2e-78857eec5cfe","country":"$country","date":"$date","disambiguation":"","barcode":"","quality":"normal",""" +
            """"label-info":[{"catalog-number":"FJ-1","label":{"id":"l1","name":"Fjord Records"}}],"media":[$media],"artist-credit":[{"name":"Harbour Lights","joinphrase":"","artist":{"id":"mb-hl","name":"Harbour Lights","sort-name":"Harbour Lights"}}]}"""
    }

    /** One browse page: `release-count`, `release-offset` and the releases. */
    fun page(count: Int, offset: Int, releases: List<String>) =
        """{"release-count":$count,"release-offset":$offset,"releases":[${releases.joinToString(",")}]}"""

    /** *Signal Found*'s 30 pressings: the first 4 carry the 20th Anniversary's 26 songs. */
    val SIGNAL_FOUND: List<String> = (1..30).map { i ->
        val songs = if (i <= 4) (1..26).map { "sf-r$it" } else (1..14).map { "sf-r$it" }
        release("sf-rel-$i", songs, date = "${2002 + i}", title = if (i <= 4) "Signal Found 20th Anniversary" else "Signal Found")
    }

    /** *Kite Weather*'s 9 pressings; the Japanese CD carries *Lantern Swing*. */
    val KITE_WEATHER: List<String> = (1..8).map { release("kw-rel-$it", (1..11).map { r -> "kw-r$r" }, "2006", title = "Kite Weather") } +
        release("kw-rel-jp", (1..12).map { "kw-r$it" }, "2006-03-08", "JP", "Kite Weather")

    /** A CD+DVD deluxe with a video recording on the CD and a DVD-Video medium. */
    val DELUXE: String = release("kw-deluxe", (1..11).map { "kw-r$it" } + "kw-video", "2007", dvd = listOf("kw-dvd1"), videos = setOf("kw-video"), title = "Kite Weather")

    /** *Northern Line* (single), *single from* *Kite Weather*. */
    const val NORTHERN_LINE_GROUP = """{"id":"rg-nl","title":"Northern Line","primary-type":"Single","secondary-types":[],"first-release-date":"2005-11-01","artist-credit":[{"name":"Harbour Lights","joinphrase":"","artist":{"id":"mb-hl","name":"Harbour Lights"}}],"genres":[],"relations":[{"type":"single from","type-id":"fc8e7a6f-ebf9-41a1-aa4e-ac1ff0cc26a0","direction":"forward","target-type":"release_group","attributes":[],"release_group":{"id":"rg-kw","title":"Kite Weather","primary-type":"Album","secondary-types":[]}}]}"""

    /** A *Lighthouse Keepers* soundtrack single. */
    const val SOUNDTRACK_SINGLE_GROUP = """{"id":"rg-st","title":"Kite Weather Theme","primary-type":"Single","secondary-types":["Soundtrack"],"artist-credit":[{"name":"Lighthouse Keepers","joinphrase":"","artist":{"id":"mb-lk","name":"Lighthouse Keepers"}}],"relations":[]}"""

    /** An EP with no relationships. */
    const val EP_GROUP = """{"id":"rg-ep","title":"Shoreline EP","primary-type":"EP","secondary-types":[],"artist-credit":[{"name":"Harbour Lights","joinphrase":"","artist":{"id":"mb-hl","name":"Harbour Lights"}}],"relations":[]}"""

    const val VIDEO_RECORDING = """{"id":"kw-video","title":"Kite Weather (video)","length":210000,"video":true,"releases":[]}"""
}
