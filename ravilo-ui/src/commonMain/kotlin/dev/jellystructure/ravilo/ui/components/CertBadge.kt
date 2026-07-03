package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.shared.tv.RatingBadge

// Phase 106/R153 — same 0-4 maturity-tier palette as the admin `.cert.lvl-N` CSS and the design
// mockup's `ravilo.css`, so the badge reads identically everywhere: green -> blue -> amber -> orange -> red.
private val TIER_COLORS = listOf(
    Color(0xFF2E9E6B), // 0 — all ages
    Color(0xFF3A9BD6), // 1
    Color(0xFFD69A2A), // 2 — dark text for contrast (amber)
    Color(0xFFE0792F), // 3
    Color(0xFFD64541), // 4 — adult
)
private val TIER_2_TEXT = Color(0xFF1A1204)

/**
 * R153 — server-resolved certification badge (region tag + code) for the detail hero meta row.
 * Never re-runs the cascade — [badge] is exactly what the backend resolved (Phase 106). Null renders
 * nothing (no reserved space; the detail payload arrives in one shot, so this never causes a layout jump).
 */
@Composable
fun CertBadge(badge: RatingBadge?, modifier: Modifier = Modifier) {
    if (badge == null) return
    val tier = badge.tier.coerceIn(0, 4)
    val codeColor = TIER_COLORS[tier]
    val codeTextColor = if (tier == 2) TIER_2_TEXT else Color.White
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .height(IntrinsicSize.Max),
    ) {
        Text(
            text = badge.region,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        Text(
            text = badge.code,
            color = codeTextColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .fillMaxHeight()
                .background(codeColor)
                .padding(horizontal = 9.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}
