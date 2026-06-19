package dev.jellystructure.ravilo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val genres = listOf("Action", "Drama", "Comedy", "Sci-Fi", "Horror", "Romance")

private val colorBg = Color(0xFF0D0D1A)
private val colorCard = Color(0xFF16162A)
private val colorCardFocused = Color(0xFF1E1B4B)
private val colorAccent = Color(0xFF8B5CF6)
private val colorText = Color(0xFFE2E8F0)
private val colorMuted = Color(0xFF64748B)

@Composable
fun RaviloRoot() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequesters = remember { List(genres.size) { FocusRequester() } }

    LaunchedEffect(selectedIndex) {
        runCatching { focusRequesters[selectedIndex].requestFocus() }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colorBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Text(
                text = "Ravilo",
                color = colorAccent,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Text(
                text = "Focus scaffold — use ← → to navigate",
                color = colorMuted,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                genres.forEachIndexed { i, genre ->
                    GenreCard(
                        label = genre,
                        isFocused = selectedIndex == i,
                        focusRequester = focusRequesters[i],
                        onFocused = { selectedIndex = i },
                        onLeft = { if (selectedIndex > 0) selectedIndex-- },
                        onRight = { if (selectedIndex < genres.lastIndex) selectedIndex++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreCard(
    label: String,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(width = 140.dp, height = 200.dp)
            .then(
                if (hasFocus)
                    Modifier.border(2.dp, colorAccent, RoundedCornerShape(12.dp))
                else
                    Modifier
            )
            .background(
                color = if (hasFocus) colorCardFocused else colorCard,
                shape = RoundedCornerShape(12.dp),
            )
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { onLeft(); true }
                    Key.DirectionRight -> { onRight(); true }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                hasFocus = state.isFocused
                if (state.isFocused) onFocused()
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (hasFocus) colorText else colorMuted,
            fontSize = if (hasFocus) 15.sp else 13.sp,
            fontWeight = if (hasFocus) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
