package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.focus.backToTopOnBack
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.raviloVersion
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.launch

/**
 * R304 — Profile is a **page** on the phone, not the TV's dropdown floated over a scrolling page. It is the
 * fifth bottom-bar item (R267) and takes the pill like the other four (FR-R304-1). Top to bottom
 * (FR-R304-2): the viewer's photo and name (the photo opens 187's photo screen — no separate row), *Admin*
 * when they are one (from the session's own `is_admin`, never a Jellyfin policy fetch), **My List** as a
 * poster row with *See all*, **Account** (App language on the per-viewer setting R161/R162 already
 * shipped, Change password, and a Settings row for what the phone's Settings screen still holds — dev
 * review item 2), **Sign out** (its one sheet, FR-R304-3), and the version line.
 *
 * No *Switch profile* and no *Unpair* on a phone (FR-R304-5, owner: a phone is one person's). No string
 * on this page names Jellyfin (FR-R304-6) — `ProfileStringsTest` holds that line.
 *
 * Handset only: the caller reaches this destination from the bottom bar, which exists only on R256's
 * `isHandset`; the TV keeps `ProfileMenu`.
 */
@Composable
fun ProfileScreen(
    apiClient: TvApiClient,
    displayName: String,
    isAdmin: Boolean,
    avatarUrl: String?,
    serverHost: String,
    onPhoto: () -> Unit,
    onMyListSeeAll: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    onAppLanguage: () -> Unit,
    onChangePassword: () -> Unit,
    onSettings: () -> Unit,
    /** Fires after this ONE profile's session is revoked and forgotten (R191's `signOutActiveSession`). */
    onSignedOut: () -> Unit,
    /** FR-R304-1 — tap-on-active scrolls to top: the caller bumps this on a re-tap of the bar's item. */
    scrollToTopTick: Int = 0,
) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var myList by remember { mutableStateOf<List<MediaCard>?>(null) }
    var myListTotal by remember { mutableStateOf(0) }
    var showSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Dev review item 4 — My List is the browse kind that already exists; the row reads it with a
        // small page, *See all* opens the same kind as a grid.
        runCatching { apiClient.browse(kind = "mylist", page = 1, pageSize = MY_LIST_ROW_SIZE) }
            .onSuccess { myList = it.items; myListTotal = it.total }
            .onFailure { myList = emptyList() }
    }
    LaunchedEffect(scrollToTopTick) { if (scrollToTopTick > 0) scroll.animateScrollTo(0) }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .backToTopOnBack(atTop = { scroll.value == 0 }, onBackToTop = { scope.launch { scroll.animateScrollTo(0) } })
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 24.dp),
        ) {
            // ── identity ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(AVATAR)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPhoto),
                ) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                        if (avatarUrl != null) RemoteImage(avatarUrl, displayName, Modifier.fillMaxSize())
                        else Text(displayName.take(2).uppercase(), color = colors.onAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                    }
                    // the edit badge — the one hint that the photo is a control
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(26.dp).background(colors.surface, CircleShape).border(2.dp, colors.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("✎", color = colors.text, fontSize = 13.sp) }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(displayName, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isAdmin) Text(str("profile.admin"), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                }
            }

            // ── My List ──
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str("nav.my_list"), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                if (myListTotal > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(myListTotal.toString(), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${str("browse.see_all_short")} ›", color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onMyListSeeAll).padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val list = myList
            when {
                list == null -> Box(Modifier.fillMaxWidth().height(TILE_HEIGHT))
                list.isEmpty() -> Text(
                    str("profile.mylist_empty"), color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 20.sp, fontFamily = Sora,
                    modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(14.dp),
                )
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { card ->
                        Box(Modifier.width(TILE_WIDTH)) { Tile(title = card.title, posterUrl = card.posterUrl, onSelect = { onItemSelect(card) }) }
                    }
                }
            }

            // ── Account ──
            Spacer(Modifier.height(26.dp))
            Text(str("profile.account").uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp))) {
                val currentLang = LocalLang.current
                val langName = SUPPORTED_LANGUAGES.firstOrNull { it.code == currentLang }?.name ?: currentLang
                ProfileRow(str("profile.app_language"), langName, onAppLanguage)
                RowDivider()
                ProfileRow(str("account.pw_change"), null, onChangePassword)
                RowDivider()
                // Dev review item 2 — the phone's Settings still holds skin, autoplay, tile shape and more;
                // a row rather than a deleted screen.
                ProfileRow(str("nav.settings"), null, onSettings)
            }

            // ── Sign out ──
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .border(1.5.dp, DANGER.copy(alpha = 0.45f), RoundedCornerShape(13.dp))
                    .background(DANGER.copy(alpha = 0.08f), RoundedCornerShape(13.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { showSignOut = true }),
                contentAlignment = Alignment.Center,
            ) { Text(str("profile.sign_out"), color = DANGER, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }

            Spacer(Modifier.height(14.dp))
            Text(
                "Ravilo ${raviloVersion()} · ${str("profile.signed_in_to", mapOf("host" to serverHost))}",
                color = colors.textDim, fontSize = 12.sp, fontFamily = Sora,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        // FR-R304-3 — the page's only sheet.
        HandsetSheet(visible = showSignOut, onDismiss = { showSignOut = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
                Text(str("profile.signout_title"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                Spacer(Modifier.height(8.dp))
                Text(str("profile.signout_body"), color = Color.White.copy(0.7f), fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora)
                Spacer(Modifier.height(18.dp))
                SheetButton(str("profile.signout_confirm"), primary = true) {
                    showSignOut = false
                    scope.launch { signOutActiveSession(apiClient); onSignedOut() }
                }
                Spacer(Modifier.height(10.dp))
                SheetButton(str("profile.signout_cancel"), primary = false) { showSignOut = false }
            }
        }
    }
}

/** FR-R304-4 — the pushed App language screen: endonyms, a check on the current one, one line of what
 *  it changes. Choosing writes the per-viewer setting (dev review item 1 — the same call the TV's
 *  Settings makes) and returns; menus redraw on the config re-pull. */
@Composable
fun AppLanguageScreen(
    apiClient: TvApiClient,
    onBack: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val current = LocalLang.current
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹", color = colors.text, fontSize = 26.sp,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(str("profile.app_language"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp))) {
                SUPPORTED_LANGUAGES.forEachIndexed { i, lang ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                LastLanguage.remember(lang.code)
                                scope.launch { runCatching { apiClient.putViewerSettings(uiLanguage = lang.code) } }
                                onBack()
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(lang.name, color = colors.text, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
                        if (lang.code == current) Text("✓", color = colors.accentSecondary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(str("profile.app_language_note"), color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = Sora)
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String?, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 15.sp, fontFamily = Sora)
            if (value != null) Text(value, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
        }
        Text("›", color = colors.textDim, fontSize = 20.sp)
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(RaviloTheme.colors.textDim.copy(alpha = 0.18f)))
}

@Composable
private fun SheetButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (primary) DANGER else Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
}

private val DANGER = Color(0xFFF5667A)
private val AVATAR = 76.dp
private val TILE_WIDTH = 112.dp
private val TILE_HEIGHT = 168.dp
private const val MY_LIST_ROW_SIZE = 12
