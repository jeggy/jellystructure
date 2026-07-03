package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.Person

@Composable
fun CastCircle(
    person: Person,
    focusRequester: FocusRequester? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    var isFocused by remember { mutableStateOf(false) }
    // R89: snappy content-focus spring, matching Tile/ChannelCard.
    val focusSpec = remember { RaviloMotion.focusSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.focusSpring<Dp>() }
    val scale      by animateFloatAsState(if (isFocused) RaviloMotion.CAST_FOCUS_SCALE else 1f, focusSpec, label = "castScale")
    val shadowElev by animateDpAsState(if (isFocused) 20.dp else 0.dp, dpSpec, label = "castShadow")
    val ringWidth  by animateDpAsState(if (isFocused) 3.dp else 0.dp, dpSpec, label = "castBorder")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { isFocused = true },
            onBlurred = { isFocused = false },
        ),
    ) {
        // R89: the focus animation runs entirely in the draw phase — scale + shadow in the graphicsLayer
        // lambda, the ring in drawWithCache — so the cast rail never recomposes per frame while focusing
        // (was Modifier.scale/.shadow/.border, which re-read animated state at composition every frame).
        Box(
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = shadowElev.toPx()
                    shape = CircleShape
                    clip = false
                    ambientShadowColor = colors.focusGlow
                    spotShadowColor = colors.focusGlow
                }
                .clip(CircleShape)
                .background(colors.surfaceVariant)
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        val bw = ringWidth.toPx()
                        if (bw > 0f) drawCircle(
                            color = colors.focusRing,
                            radius = (size.minDimension - bw) / 2f,
                            style = Stroke(width = bw),
                        )
                    }
                },
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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = sora,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = person.name,
            color = if (isFocused) colors.text else colors.textSecondary,
            fontSize = 13.sp,
            fontFamily = sora,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val role = person.role
        if (role != null) {
            Text(
                text = role,
                color = colors.textDim,
                fontSize = 11.sp,
                fontFamily = sora,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
