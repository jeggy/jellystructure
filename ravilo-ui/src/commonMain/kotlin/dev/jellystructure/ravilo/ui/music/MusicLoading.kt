package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
