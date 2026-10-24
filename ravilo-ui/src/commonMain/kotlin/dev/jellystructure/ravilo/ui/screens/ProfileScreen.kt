package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.ArrowRow
import dev.jellystructure.ravilo.ui.components.CheckGlyph
import dev.jellystructure.ravilo.ui.components.PencilGlyph
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
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
    /** FR-R304-7 — the viewer's last My List answer, kept for the app run (R40) so the row is drawn on the first frame. */
    myListStore: ProfileMyListStore,
    displayName: String,
    isAdmin: Boolean,
    avatarUrl: String?,
    serverHost: String,
    onPhoto: () -> Unit,
    onMyListSeeAll: () -> Unit,
    onItemSelect: (MediaCard) -> Unit,
    onAppLanguage: () -> Unit,
    onChangePassword: () -> Unit,
    /** DEB-1854 (FR-6) — the account's password place; the form row until the config says otherwise. */
    passwordRow: PasswordRow = PasswordRow.Form,
    /** DEB-2233 (FR-6) — *Sign in a TV*, after the password row; null (no row at all) where a TV cannot be signed in from. */
    onSignInTv: (() -> Unit)? = null,
    onSettings: () -> Unit,
    /** DEB-1501 (FR-1) — opens My tickets; null (no row at all) while the support lane is closed, and on every TV. */
    onSupport: (() -> Unit)? = null,
    /** Fires after this ONE profile's session is revoked and forgotten (R191's `signOutActiveSession`). */
    onSignedOut: () -> Unit,
    /** FR-R304-1 — tap-on-active scrolls to top: the caller bumps this on a re-tap of the bar's item. */
    scrollToTopTick: Int = 0,
    /** R321 (FR-R321-3) — the listening mode card under the photo and name; null (absent, never greyed) for a viewer
     *  without the music library, on a TV, or where this build has no music player (FR-R321-2). */
    modeCard: (@Composable () -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    // FR-R304-7 — what is drawn is the store's last server answer: null until the viewer's first read of this app
    // run. A failed read with nothing remembered draws the empty sentence (as before) and is held here, not in the
    // store, so it is not remembered: the next visit starts from placeholders again.
    var readFailed by remember { mutableStateOf(false) }
    val myList = myListStore.cards ?: if (readFailed) emptyList() else null
    val myListTotal = myListStore.total
    var showSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(myListStore) {
        // Dev review item 4 — My List is the browse kind that already exists; the row reads it with a
        // small page, *See all* opens the same kind as a grid.
        readFailed = !myListStore.refresh()
    }
    dev.jellystructure.ravilo.ui.components.OnReselect(scrollToTopTick) { scroll.animateScrollTo(0) }
    // FR-R304-3 — Back with the sheet up closes the sheet, not the page (seen on the Pixel 9: R275's
    // ladder took Back straight to Home with the sheet still open). Registered after RaviloApp's own
    // handler, so it wins while enabled.
    dev.jellystructure.ravilo.ui.PlatformBackHandler(enabled = showSignOut) { showSignOut = false }

    val desk = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
    Box(Modifier.fillMaxSize().background(colors.background)) {
        // R337 — in a computer's window the page is the viewer and their list: the album header's shape (`.alh`) with
        // the photo, the name and the two things a viewer does here (Settings…, Sign out), then My List as a row of the
        // desktop's tiles. The language and the password are in the Settings window; the mode switch heads the sidebar.
        if (desk) Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 58.dp, bottom = 28.dp)) {
            val hPad = dev.jellystructure.ravilo.ui.theme.raviloHPad
            Row(Modifier.padding(horizontal = hPad).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(132.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPhoto)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                        if (avatarUrl != null) RemoteImage(avatarUrl, displayName, Modifier.fillMaxSize())
                        else Text(displayName.take(2).uppercase(), color = colors.onAccent, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).size(30.dp).background(colors.surface, CircleShape).border(2.dp, colors.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { PencilGlyph(colors.text, 14.dp) }
                }
                Column(Modifier.weight(1f)) {
                    if (isAdmin) Text(str("profile.admin"), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                    Text(displayName, color = colors.text, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.7).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Ravilo ${raviloVersion()} · ${str("profile.signed_in_to", mapOf("host" to serverHost))}", color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        dev.jellystructure.ravilo.ui.components.DeskButton(str("nav.settings") + "…", onClick = onSettings)
                        if (onSupport != null) Box {
                            dev.jellystructure.ravilo.ui.components.DeskButton(str("support.title"), onClick = onSupport)
                            if (dev.jellystructure.ravilo.ui.support.supportUnreadShows()) {
                                dev.jellystructure.ravilo.ui.components.UpdateDot(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
                            }
                        }
                        dev.jellystructure.ravilo.ui.components.DeskButton(str("profile.sign_out")) { showSignOut = true }
                    }
                }
            }
            Column(Modifier.testTag(PROFILE_MY_LIST_TAG)) {
                MyListHeadingLine(myListTotal, desk = true, onSeeAll = onMyListSeeAll, modifier = Modifier.padding(horizontal = hPad).padding(top = 30.dp, bottom = 10.dp))
                val list = myList
                if (list != null && list.isEmpty()) Text(
                    str("profile.mylist_empty"), color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 20.sp, fontFamily = Sora,
                    modifier = Modifier.padding(horizontal = hPad).widthIn(max = 520.dp).background(colors.surface, RoundedCornerShape(12.dp)).padding(14.dp),
                ) else MyListRow(
                    list, tileWidth = 138.dp, onItemSelect = onItemSelect,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = hPad),
                    spacing = dev.jellystructure.ravilo.ui.theme.raviloItemSpacing,
                )
            }
        } else Column(
            Modifier
                .fillMaxSize()
                .backToTopOnBack(atTop = { scroll.value == 0 }, onBackToTop = { scope.launch { scroll.animateScrollTo(0) } })
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 24.dp),
        ) {
            // ── R386 (FR-R386-8, D6/D11): the page's first row says why the bar's Profile has a dot ──
            if (dev.jellystructure.ravilo.ui.components.updateDotShows()) {
                dev.jellystructure.ravilo.ui.components.UpdateNoticeRow(desk = false)
                Spacer(Modifier.height(16.dp))
            }
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
                    ) { PencilGlyph(colors.text, 13.dp) }   // R315 — drawn, not typed
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(displayName, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isAdmin) Text(str("profile.admin"), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                }
            }

            // ── R321: the mode card, in the same place in both modes (it is the way back) ──
            if (modeCard != null) {
                Spacer(Modifier.height(22.dp))
                modeCard()
            }

            // ── My List ──
            Spacer(Modifier.height(26.dp))
            Column(Modifier.testTag(PROFILE_MY_LIST_TAG)) {
                MyListHeadingLine(myListTotal, desk = false, onSeeAll = onMyListSeeAll)
                Spacer(Modifier.height(10.dp))
                val list = myList
                if (list != null && list.isEmpty()) Text(
                    str("profile.mylist_empty"), color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 20.sp, fontFamily = Sora,
                    modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(14.dp),
                ) else MyListRow(list, tileWidth = TILE_WIDTH, onItemSelect = onItemSelect, spacing = 10.dp)
            }

            // ── Account ──
            Spacer(Modifier.height(26.dp))
            Text(str("profile.account").uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp,
                modifier = Modifier.testTag(PROFILE_ACCOUNT_TAG))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp))) {
                val currentLang = LocalLang.current
                val langName = SUPPORTED_LANGUAGES.firstOrNull { it.code == currentLang }?.name ?: currentLang
                ProfileRow(str("profile.app_language"), langName, onAppLanguage)
                RowDivider()
                // DEB-1854 (FR-6) — the link row opens the page; the sentence is a row of its own, not a control.
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                when (passwordRow) {
                    is PasswordRow.Form -> { ProfileRow(str("account.pw_change"), null, onChangePassword); RowDivider() }
                    is PasswordRow.Link -> {
                        ProfileRow(str("account.pw_change"), str("account.pw_elsewhere_sub"), { uriHandler.openUri(passwordRow.url) }, external = true)
                        RowDivider()
                    }
                    is PasswordRow.Line -> {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) { PasswordElsewhereLine(13) }
                        RowDivider()
                    }
                    is PasswordRow.Hidden -> {}
                }
                if (onSignInTv != null) { ProfileRow(str("tvcode.row"), null, onSignInTv, modifier = Modifier.testTag(TvApproveTags.ROW)); RowDivider() }
                // Dev review item 2 — the phone's Settings still holds skin, autoplay, tile shape and more;
                // a row rather than a deleted screen.
                ProfileRow(str("nav.settings"), null, onSettings, dot = dev.jellystructure.ravilo.ui.components.updateDotShows())
                // DEB-1501 (FR-1, FR-2) — after Settings; an unread reply puts a dot here and on the bar's Profile item.
                if (onSupport != null) {
                    RowDivider()
                    ProfileRow(str("support.title"), null, onSupport, dot = dev.jellystructure.ravilo.ui.support.supportUnreadShows())
                }
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

        if (desk) dev.jellystructure.ravilo.ui.components.AppBar()
        // FR-R304-3 — the page's only sheet.
        HandsetSheet(visible = showSignOut, onDismiss = { showSignOut = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
                Text(str(if (dev.jellystructure.ravilo.ui.isDesktopPlatform) "profile.signout_title_desk" else "profile.signout_title"), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                Spacer(Modifier.height(8.dp))
                Text(str("profile.signout_body"), color = RaviloTheme.colors.fg.copy(0.7f), fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora)
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
                        if (lang.code == current) CheckGlyph(colors.accentSecondary, 17.dp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(str("profile.app_language_note"), color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = Sora)
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String?, onClick: () -> Unit, dot: Boolean = false, external: Boolean = false, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 15.sp, fontFamily = Sora)
            if (value != null) Text(value, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
        }
        if (dot) { dev.jellystructure.ravilo.ui.components.UpdateDot(ring = colors.surface); Spacer(Modifier.width(12.dp)) }
        if (external) ExternalLinkGlyph(colors.textDim, 16.dp) else Text("›", color = colors.textDim, fontSize = 20.sp)
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
            .background(if (primary) DANGER else RaviloTheme.colors.fg.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) Color.White else RaviloTheme.colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
}

private val DANGER = Color(0xFFF5667A)
private val AVATAR = 76.dp
private val TILE_WIDTH = 112.dp
private const val MY_LIST_ROW_SIZE = 12

/** The *Account* heading — what FR-R304-7's test reads to see whether anything above it moved. */
internal const val PROFILE_ACCOUNT_TAG = "profile-account"

/** The My List section (heading line and row) — what the test measures on the desktop layout, which has no Account heading. */
internal const val PROFILE_MY_LIST_TAG = "profile-my-list"

/** How many placeholder tiles the loading row draws — more than any phone's width holds; the row clips. */
private const val MY_LIST_PLACEHOLDERS = 6

/**
 * FR-R304-7 (2) — the My List heading line, one height whether or not the count is known. The count and
 * *See all ›* are always laid out; until [total] says there is a list they are invisible and unreachable (no
 * semantics, no click), so the line neither grows when they appear nor needs a second layout of its own.
 */
@Composable
internal fun MyListHeadingLine(total: Int, desk: Boolean, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val known = total > 0
    val hidden = if (known) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            str("nav.my_list"), color = colors.text, fontSize = if (desk) 18.sp else 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = SpaceGrotesk,
        )
        Spacer(Modifier.width(8.dp))
        Text(total.toString(), color = colors.textDim, fontSize = if (desk) 13.sp else 12.sp, fontFamily = if (desk) SpaceGrotesk else Sora, modifier = hidden)
        Spacer(Modifier.weight(1f))
        Text(
            "${str("browse.see_all_short")} ›",
            color = if (desk) colors.textSecondary else colors.accentSecondary,
            fontSize = if (desk) 12.5.sp else 13.sp,
            fontWeight = if (desk) null else FontWeight.SemiBold,
            fontFamily = Sora,
            modifier = hidden
                .clickable(enabled = known, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSeeAll)
                .padding(vertical = if (desk) 4.dp else 6.dp),
        )
    }
}

/**
 * FR-R304-7 (1) — the My List row. [cards] null is "not read yet": the same row, with [MY_LIST_PLACEHOLDERS]
 * poster-shaped blanks in the cells the loaded tiles use — the same `ArrowRow`, the same cell box and the row's
 * own [Tile] (`placeholder = true`) — so the loading row and the loaded row are one layout, not two that agree.
 */
@Composable
internal fun MyListRow(
    cards: List<MediaCard>?,
    tileWidth: Dp,
    spacing: Dp,
    onItemSelect: (MediaCard) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    ArrowRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        if (cards == null) {
            items(MY_LIST_PLACEHOLDERS) {
                Box(Modifier.width(tileWidth)) { Tile(title = "", posterUrl = null, placeholder = true) }
            }
        } else {
            items(cards, key = { it.id }) { card ->
                Box(Modifier.width(tileWidth)) { Tile(title = card.title, posterUrl = card.posterUrl, onSelect = { onItemSelect(card) }) }
            }
        }
    }
}

/**
 * FR-R304-7 (3) — the viewer's last My List answer, kept for the app run through R40's `keptStore`, keyed by the
 * viewer (the registry is never cleared on a user switch). Holds only what the server last said: [refresh] replaces
 * it, nothing is inserted or removed locally.
 */
class ProfileMyListStore(private val apiClient: TvApiClient) {
    /** The first [MY_LIST_ROW_SIZE] titles of the last successful read; null until the first one this run. */
    var cards by mutableStateOf<List<MediaCard>?>(null)
        private set
    /** The whole list's size from the same read (the heading's count); 0 while unknown. */
    var total by mutableStateOf(0)
        private set

    /** Reads My List and replaces the kept answer. False on a failed read, which leaves the kept answer as it was. */
    suspend fun refresh(): Boolean = try {
        val r = apiClient.browse(kind = "mylist", page = 1, pageSize = MY_LIST_ROW_SIZE)
        cards = r.items
        total = r.total
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
