package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Person

@Composable
fun CastCircle(person: Person) {
    val colors = RaviloTheme.colors

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(colors.surfaceVariant),
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
            color = colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val role = person.role
        if (role != null) {
            Text(
                text = role,
                color = colors.textSecondary.copy(alpha = 0.6f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
