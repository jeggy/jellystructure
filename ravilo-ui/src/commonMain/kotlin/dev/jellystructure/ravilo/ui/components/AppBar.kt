package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun AppBar(
    navItems: List<String> = listOf("Home", "Movies", "Series", "My List", "Search"),
    activeNav: Int = 0,
    onNavSelect: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.95f),
                    1f to colors.background.copy(alpha = 0f),
                )
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // Brand wordmark
            Text(
                text = "Ravilo",
                color = colors.accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            // Nav items
            navItems.forEachIndexed { i, label ->
                Text(
                    text = label,
                    color = if (i == activeNav) colors.text else colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = if (i == activeNav) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            // Spacer
            Box(modifier = Modifier.weight(1f))
            // 24-hour clock
            ClockDisplay()
        }
    }
}

@Composable
private fun ClockDisplay() {
    val colors = RaviloTheme.colors
    var timeStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            timeStr = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
            delay(30_000L)
        }
    }

    Text(
        text = timeStr,
        color = colors.textSecondary,
        fontSize = 14.sp,
        textAlign = TextAlign.End,
    )
}
