package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Person

@Composable
fun CastCircle(
    person: Person,
    focusRequester: FocusRequester,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val roleColor = remember(colors.textSecondary) { colors.textSecondary.copy(alpha = 0.6f) }
    var isFocused by remember { mutableStateOf(false) }
    val circleScale by animateFloatAsState(if (isFocused) 1.12f else 1f)
    val ringColor by animateColorAsState(if (isFocused) colors.accent else Color.Transparent)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { isFocused = true; onFocused() },
            onBlurred = { isFocused = false },
            onLeft = onLeft,
            onRight = onRight,
            onUp = onUp,
            onDown = onDown,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(circleScale)
                .clip(CircleShape)
                .background(colors.surfaceVariant)
                .border(2.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val photoUrl = person.imageUrl
            if (photoUrl != null) {
                RemoteImage(
                    url = photoUrl,
                    contentDescription = person.name,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                )
            } else {
                Text(
                    text = person.name.take(1),
                    color = colors.textSecondary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = person.name,
            color = if (isFocused) colors.text else colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val role = person.role
        if (role != null) {
            Text(
                text = role,
                color = roleColor,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
