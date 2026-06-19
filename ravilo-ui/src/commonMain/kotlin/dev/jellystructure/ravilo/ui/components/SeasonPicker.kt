package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Season

@Composable
fun SeasonPicker(
    seasons: List<Season>,
    selectedIndex: Int,
    focusedIndex: Int,
    focusRequesters: List<FocusRequester>,
    onSelect: (Int) -> Unit,
    onLeft: (Int) -> Unit,
    onRight: (Int) -> Unit,
    onDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val pillShape = remember { RoundedCornerShape(20.dp) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 40.dp),
    ) {
        items(seasons.size, key = { i -> seasons[i].index }) { i ->
            val isSelected = i == selectedIndex
            var focused by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) colors.accent else colors.surfaceVariant,
                        pillShape,
                    )
                    .then(
                        if (focused && !isSelected) Modifier.border(2.dp, colors.focusRing, pillShape)
                        else Modifier
                    )
                    .dpadFocusable(
                        focusRequester = focusRequesters[i],
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onLeft  = { onLeft(i) },
                        onRight = { onRight(i) },
                        onDown  = onDown,
                        onSelect = { onSelect(i) },
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = seasons[i].name,
                    color = if (isSelected) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
