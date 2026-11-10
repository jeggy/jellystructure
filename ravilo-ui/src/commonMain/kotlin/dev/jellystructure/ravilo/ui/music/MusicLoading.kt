package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.components.ShimmerBox
import dev.jellystructure.ravilo.ui.components.rememberShimmerBrush
import dev.jellystructure.ravilo.ui.theme.raviloHPad

/** DEB-4091 FR-1 — Browse placeholders use the loaded grid's column calculation and page width. */
@Composable
internal fun MusicBrowseLoading(chip: String, gridWidth: Dp, desk: Boolean, minHeight: Dp) {
    val brush = rememberShimmerBrush()
    when (chip) {
        "albums", "artists" -> {
            val floor = if (chip == "albums") 2 else 3
            val gap = if (desk) 18.dp else 12.dp
            val cols = if (desk) maxOf(floor, ((gridWidth + gap) / (150.dp + gap)).toInt()) else floor
            val width = (gridWidth - gap * (cols - 1)) / cols
            val rows = maxOf(1, (minHeight.value / (width.value + if (desk) 78f else 72f)).toInt() + 1)
            Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad)) {
                repeat(rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(cols) {
                            Column(Modifier.width(width)) {
                                ShimmerBox(Modifier.size(width), brush, if (chip == "artists") width.value / 2 else 10f)
                                Spacer(Modifier.height(8.dp))
                                ShimmerBox(Modifier.width(width * 0.72f).height(16.dp), brush)
                                if (chip == "albums") {
                                    Spacer(Modifier.height(6.dp))
                                    ShimmerBox(Modifier.width(width * 0.48f).height(12.dp), brush)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(if (desk) 22.dp else 16.dp))
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad)) {
            repeat(maxOf(1, (minHeight.value / 58f).toInt() + 1)) {
                Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShimmerBox(Modifier.size(44.dp), brush, 5f)
                    Column {
                        ShimmerBox(Modifier.width(180.dp).height(16.dp), brush)
                        Spacer(Modifier.height(7.dp))
                        ShimmerBox(Modifier.width(120.dp).height(12.dp), brush)
                    }
                }
            }
        }
    }
}

/** Genre chips occupy the same wrapping region as the loaded chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MusicGenresLoading(minHeight: Dp, gridWidth: Dp) {
    val brush = rememberShimmerBrush()
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = raviloHPad, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(maxOf(20, ((minHeight.value / 42f).toInt() + 1) * maxOf(1, (gridWidth.value / 100f).toInt()))) { i ->
            ShimmerBox(Modifier.width((72 + (i % 4) * 19).dp).height(34.dp), brush, 18f)
        }
    }
}

/** The New playlist button is drawn by the caller above these rows. */
@Composable
internal fun MusicPlaylistsLoading(minHeight: Dp) {
    val brush = rememberShimmerBrush()
    Column(Modifier.fillMaxWidth()) {
        repeat(maxOf(1, (minHeight.value / 68f).toInt() + 1)) {
            Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShimmerBox(Modifier.size(56.dp), brush, 6f)
                Column {
                    ShimmerBox(Modifier.width(180.dp).height(16.dp), brush)
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(Modifier.width(100.dp).height(12.dp), brush)
                }
            }
        }
    }
}

/** Listen keeps the familiar section heading and horizontal cover rhythm until the first answer. */
@Composable
internal fun MusicListenLoading(minHeight: Dp, desk: Boolean) {
    val brush = rememberShimmerBrush()
    val size = if (desk) 150.dp else 140.dp
    Column(Modifier.fillMaxWidth()) {
        repeat(maxOf(2, (minHeight.value / (size.value + 82f)).toInt() + 1)) {
            ShimmerBox(Modifier.padding(horizontal = raviloHPad).width(190.dp).height(22.dp), brush)
            Spacer(Modifier.height(12.dp))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(5) {
                    Column(Modifier.width(size)) {
                        ShimmerBox(Modifier.size(size), brush)
                        Spacer(Modifier.height(8.dp))
                        ShimmerBox(Modifier.width(size * 0.75f).height(14.dp), brush)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** The track rows under an album, artist or playlist header, at the loaded row's height. */
@Composable
internal fun MusicTrackRowsLoading(minHeight: Dp, cover: Boolean = false) {
    val brush = rememberShimmerBrush()
    val rowHeight = if (cover) 54.dp else 46.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad)) {
        repeat(maxOf(4, (minHeight.value / rowHeight.value).toInt() + 1)) {
            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (cover) ShimmerBox(Modifier.size(40.dp), brush, 5f) else ShimmerBox(Modifier.width(18.dp).height(14.dp), brush)
                Column {
                    ShimmerBox(Modifier.width(190.dp).height(15.dp), brush)
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(Modifier.width(110.dp).height(11.dp), brush)
                }
            }
        }
    }
}

/** DEB-4091 FR-1 — a page's header (a cover or photo block and two title lines) then rows: album, artist, book and author pages. */
@Composable
internal fun MusicDetailLoading(desk: Boolean, round: Boolean = false, cover: Boolean = false) {
    val brush = rememberShimmerBrush()
    val art = if (desk) 210.dp else 180.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        Column(Modifier.fillMaxWidth().padding(top = if (desk) 52.dp else 64.dp)) {
            if (desk) Row(Modifier.padding(horizontal = raviloHPad).padding(top = 18.dp, bottom = 20.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                ShimmerBox(Modifier.size(art), brush, if (round) art.value / 2 else 10f)
                Column {
                    ShimmerBox(Modifier.width(80.dp).height(12.dp), brush)
                    Spacer(Modifier.height(12.dp))
                    ShimmerBox(Modifier.width(340.dp).height(36.dp), brush)
                    Spacer(Modifier.height(12.dp))
                    ShimmerBox(Modifier.width(200.dp).height(14.dp), brush)
                }
            } else Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                ShimmerBox(Modifier.size(art), brush, if (round) art.value / 2 else 10f)
                Spacer(Modifier.height(14.dp))
                ShimmerBox(Modifier.width(220.dp).height(22.dp), brush)
                Spacer(Modifier.height(8.dp))
                ShimmerBox(Modifier.width(130.dp).height(13.dp), brush)
                Spacer(Modifier.height(16.dp))
            }
            MusicTrackRowsLoading((pageHeight - art).coerceAtLeast(0.dp), cover)
        }
    }
}

/** A playlist's rows under its own, already-drawn header: a lazy item has no height to fill, so a screenful is drawn. */
@Composable
internal fun MusicPlaylistRowsLoading() = MusicTrackRowsLoading(720.dp, cover = true)

/** The audiobook shelf's own cover grid (two columns, as [BookCell]s lay out), filling [minHeight]. */
@Composable
internal fun AudiobookShelfLoading(minHeight: Dp) {
    val brush = rememberShimmerBrush()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = raviloHPad)) {
        val gap = 12.dp
        val width = (maxWidth - gap) / 2
        val rows = maxOf(2, (minHeight.value / (width.value + 52f)).toInt() + 1)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(2) {
                        Column(Modifier.width(width)) {
                            ShimmerBox(Modifier.size(width), brush)
                            Spacer(Modifier.height(8.dp))
                            ShimmerBox(Modifier.width(width * 0.72f).height(15.dp), brush)
                            Spacer(Modifier.height(6.dp))
                            ShimmerBox(Modifier.width(width * 0.45f).height(12.dp), brush)
                        }
                    }
                }
            }
        }
    }
}

/**
 * DEB-4091 FR-3 — a page that failed with nothing to show says why and offers Retry, never the empty-library line.
 * Worked out from the loader's own [MusicLoader.lastFailure] (R280).
 */
@Composable
internal fun MusicLoadFailed(failure: Throwable?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("music-load-failed")) {
        dev.jellystructure.ravilo.ui.components.LoadErrorState(dev.jellystructure.ravilo.ui.components.loadErrorKindOf(failure), onRetry = onRetry)
    }
}
