package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicTrackItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R322 (FR-R322-4/5/8/9) — the queue's rules, once for every platform. */
class MusicQueueTest {
    private fun t(id: String, trackGain: Double? = null, albumGain: Double? = null) = MusicTrackItem(id = id, title = id, trackGainDb = trackGain, albumGainDb = albumGain)
    private val abc = listOf(t("a"), t("b"), t("c"), t("d"))

    @Test
    fun next_and_previous_under_each_repeat_mode() {
        val q = MusicQueue().apply { set(abc, 3, shuffle = false) }
        assertNull(q.nextIndex(RepeatMode.OFF), "queue end: nothing restarts on its own")
        assertEquals(0, q.nextIndex(RepeatMode.ALL))
        assertEquals(3, q.nextIndex(RepeatMode.ONE))
        assertEquals(0, q.skipIndex(RepeatMode.ONE), "a skip is not held by repeat-one")
        assertEquals(2, q.previousIndex())
        q.index = 0
        assertNull(q.previousIndex())
    }

    @Test
    fun shuffle_puts_the_tapped_song_first_and_undoes_to_the_original_order() {
        val q = MusicQueue(Random(7)).apply { set(abc, 2, shuffle = true) }
        assertEquals("c", q.current?.id); assertEquals(0, q.index)
        assertEquals(abc.map { it.id }.toSet(), q.tracks.map { it.id }.toSet())
        q.index = 1
        val playing = q.current
        q.setShuffle(false)
        assertEquals(listOf("a", "b", "c", "d"), q.tracks.map { it.id })
        assertEquals(playing?.id, q.current?.id, "the song that is playing keeps playing")
    }

    @Test
    fun play_next_lands_straight_after_the_current_song_and_moves_keep_it_current() {
        val q = MusicQueue().apply { set(abc, 1, shuffle = false) }
        q.insertNext(t("x"))
        assertEquals(listOf("a", "b", "x", "c", "d"), q.tracks.map { it.id })
        q.append(t("y"))
        assertEquals("y", q.tracks.last().id)
        q.move(1, 4)
        assertEquals("b", q.current?.id); assertEquals(4, q.index)
        q.remove(0)
        assertEquals(3, q.index); assertEquals("b", q.current?.id)
        q.remove(3)
        assertEquals("b", q.current?.id, "the playing song is not removed by a swipe")
    }

    @Test
    fun even_volume_turns_down_only_and_uses_album_gain_on_an_album_in_order() {
        val song = t("s", trackGain = -6.0, albumGain = -3.0)
        val album = MusicContext("album", "Salt")
        assertEquals(0.708f, musicVolumeScale(song, album, shuffled = false, evenVolume = true), 0.001f)
        assertEquals(0.501f, musicVolumeScale(song, album, shuffled = true, evenVolume = true), 0.001f)
        assertEquals(0.501f, musicVolumeScale(song, MusicContext("mix", "Mix"), shuffled = false, evenVolume = true), 0.001f)
        assertEquals(1f, musicVolumeScale(t("loud", trackGain = 4.0), null, false, true), "never raised")
        assertEquals(1f, musicVolumeScale(song, album, false, evenVolume = false))
    }
}
