package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

private val ROWS = listOf(
    "1234567890".toList().map { it.toString() },
    "QWERTYUIOP".toList().map { it.toString() },
    "ASDFGHJKL".toList().map { it.toString() },
    listOf("⌫") + "ZXCVBNM".toList().map { it.toString() } + listOf("⎵"),
)

@Composable
fun OnScreenKeyboard(
    onChar: (String) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val keyShape = remember { RoundedCornerShape(6.dp) }
    val containerShape = remember { RoundedCornerShape(12.dp) }
    var focusRow by remember { mutableIntStateOf(0) }
    var focusCol by remember { mutableIntStateOf(0) }

    val grid = remember {
        ROWS.map { row -> List(row.size) { FocusRequester() } }
    }

    // Focus the first key immediately so the keyboard is usable on entry
    LaunchedEffect(Unit) {
        grid[0][0].requestFocus()
    }

    Column(
        modifier = modifier.background(colors.surface, containerShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ROWS.forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEachIndexed { c, key ->
                    // Derive focus from grid-level state so it resets when focus moves away
                    val focused = focusRow == r && focusCol == c

                    Box(
                        modifier = Modifier
                            .size(if (key == "⌫" || key == "⎵") 60.dp else 44.dp, 44.dp)
                            .background(
                                if (focused) colors.accent else colors.surfaceVariant,
                                keyShape,
                            )
                            .then(
                                if (focused) Modifier.border(2.dp, colors.focusRing, keyShape)
                                else Modifier
                            )
                            .onFocusChanged { if (it.isFocused) { focusRow = r; focusCol = c } }
                            .dpadFocusable(
                                focusRequester = grid[r][c],
                                onFocused = {},
                                onLeft  = { if (c > 0) grid[r][c - 1].requestFocus() },
                                onRight = { if (c < keys.lastIndex) grid[r][c + 1].requestFocus() },
                                onUp    = {
                                    if (r > 0) {
                                        val targetC = c.coerceIn(0, ROWS[r - 1].lastIndex)
                                        grid[r - 1][targetC].requestFocus()
                                    }
                                },
                                onDown  = {
                                    if (r < ROWS.lastIndex) {
                                        val targetC = c.coerceIn(0, ROWS[r + 1].lastIndex)
                                        grid[r + 1][targetC].requestFocus()
                                    }
                                },
                                onSelect = {
                                    when (key) {
                                        "⌫" -> onDelete()
                                        "⎵" -> onChar(" ")
                                        else -> onChar(key)
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = key,
                            color = if (focused) colors.onAccent else colors.text,
                            fontSize = 14.sp,
                            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
