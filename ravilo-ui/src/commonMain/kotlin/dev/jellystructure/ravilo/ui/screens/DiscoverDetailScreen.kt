package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalLiveAcquisition
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.RequestLanguagePicker
import dev.jellystructure.ravilo.ui.components.requestLanguageFlag
import dev.jellystructure.ravilo.ui.components.requestLanguageLabel
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.DiscoverDetail
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Person

@Composable
fun DiscoverDetailScreen(
    store: DiscoverDetailStore,
    onWatchMovie: (itemId: String, title: String) -> Unit,
    onGoToSeries: (itemId: String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    val acq = LocalLiveAcquisition.current
    LaunchedEffect(acq) { acq?.collect { store.applyAcquisition(it) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is DiscoverDetailState.Loading -> {}
            is DiscoverDetailState.Error -> Text(s.message, color = colors.textSecondary, modifier = Modifier.padding(48.dp))
            is DiscoverDetailState.Loaded -> DetailContent(s.detail, store, onWatchMovie, onGoToSeries)
        }
    }
}

/** Phase 139 §A.3 — the picker only ever appears when there's a real choice; 0-1 catalog languages
 *  means every "Request"/"Change language" press resolves server-side with no popup at all. */
private fun DiscoverDetail.hasLanguageChoice(): Boolean = languages.size > 1

@Composable
private fun DetailContent(
    detail: DiscoverDetail,
    store: DiscoverDetailStore,
    onWatchMovie: (String, String) -> Unit,
    onGoToSeries: (String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val e = detail.entry
    val a = detail.acquisition
    val actionFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { actionFR.requestFocus() } }

    val scrim = remember(colors.background) {
        Brush.verticalGradient(0f to Color.Transparent, 0.55f to colors.background.copy(alpha = 0.85f), 1f to colors.background)
    }
    val scrollState = rememberScrollState()

    // Phase 139 §A — 0-1 catalog languages resolves server-side with no popup at all; otherwise the
    // popup appears and the chosen id flows into whichever action asked for it (Request vs Change language).
    var pendingAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
    fun withLanguage(action: (String?) -> Unit) {
        if (detail.hasLanguageChoice()) pendingAction = { lang -> action(lang) } else action(null)
    }

    Box(Modifier.fillMaxSize()) {
        // Fixed full-bleed backdrop at top
        Box(Modifier.fillMaxWidth().height(380.dp)) {
            tmdbImg(e.backdropPath)?.let { RemoteImage(it, e.title, Modifier.fillMaxSize()) }
            Box(Modifier.matchParentSize().background(scrim))
        }

        // Scrollable content overlay
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = raviloHPad)
                .padding(top = 200.dp, bottom = 48.dp),
        ) {
            Text(e.title, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(6.dp))
            // year · kind · runtime
            val runtimeLabel = detail.runtime?.let { mins ->
                val h = mins / 60; val m = mins % 60
                val base = if (h > 0) "${h}h ${m}m" else "${m}m"
                if (detail.isSeries) "$base / ep" else base
            }
            Text(
                listOfNotNull(e.year?.toString(), if (e.mediaKind == MediaKind.SERIES) "Series" else "Movie", runtimeLabel).joinToString("  ·  "),
                color = colors.textSecondary, fontSize = 14.sp,
            )
            // genre chips
            if (detail.genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    detail.genres.take(4).forEach { genre ->
                        Text(
                            genre,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(colors.surfaceVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            // live status line — skip NOT_REQUESTED, the PrimaryAction button below already says "Request"
            // Phase 139 §C — the chosen language's flag rides along every status state.
            val statusText = if (a.languageStrictWaiting) str("request.waiting_for", mapOf("lang" to requestLanguageLabel(detail.languages, a.language).orEmpty()))
                else discoverStatusLabel(a)?.takeIf { a.status != AcquisitionStatus.NOT_REQUESTED }
            if (statusText != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RequestLanguageFlag(requestLanguageFlag(detail.languages, a.language))
                    Text(statusText, color = discoverStatusColor(a.status, colors.accent), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            a.reason?.takeIf { a.status == AcquisitionStatus.FAILED }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = colors.textSecondary, fontSize = 13.sp)
            }
            // Phase 139 §D — switch a still-waiting strict request to a different language.
            if (a.languageStrictWaiting && detail.hasLanguageChoice()) {
                Spacer(Modifier.height(8.dp))
                RaviloButton(str("request.change_language"), style = ButtonStyle.GHOST, onSelect = {
                    pendingAction = { lang -> store.changeLanguage(lang) }
                })
            }
            Spacer(Modifier.height(14.dp))
            e.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(0.66f))
                Spacer(Modifier.height(18.dp))
            }

            // status-driven primary action
            PrimaryAction(detail, actionFR, store, onWatchMovie, onGoToSeries, requestWithLanguage = ::withLanguage)

            if (detail.cast.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                CastSection(detail.cast)
            }
            Spacer(Modifier.height(8.dp))
        }

        pendingAction?.let { action ->
            RequestLanguagePicker(
                languages = detail.languages,
                default = detail.defaultLanguage,
                onSelect = { lang -> action(lang); pendingAction = null },
                onDismiss = { pendingAction = null },
            )
        }
    }
}

/** A 28×20dp flag, or nothing (not a globe) — the globe placeholder belongs in the picker rows where
 *  every language including "original" is listed side by side; a bare status line just omits it. */
@Composable
private fun RequestLanguageFlag(flagCode: String?) {
    val drawable = flagCode?.let { dev.jellystructure.ravilo.ui.components.LANG_CC[it.lowercase()] }
    if (drawable != null) {
        androidx.compose.foundation.Image(
            painter = org.jetbrains.compose.resources.painterResource(drawable),
            contentDescription = null,
            modifier = Modifier.size(width = 24.dp, height = 17.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun PrimaryAction(
    detail: DiscoverDetail,
    fr: FocusRequester,
    store: DiscoverDetailStore,
    onWatchMovie: (String, String) -> Unit,
    onGoToSeries: (String) -> Unit,
    requestWithLanguage: ((String?) -> Unit) -> Unit,
) {
    val a = detail.acquisition
    val itemId = a.itemId
    when {
        // In library (or ≥1 episode): play a movie, or open the proper library series detail.
        itemId != null && (a.status == AcquisitionStatus.AVAILABLE || a.firstAvailable) -> {
            if (detail.entry.mediaKind == MediaKind.SERIES) {
                RaviloButton("Go to series", focusRequester = fr, onSelect = { onGoToSeries(itemId) })
            } else {
                RaviloButton("Watch Now", focusRequester = fr, onSelect = { onWatchMovie(itemId, detail.entry.title) })
            }
        }
        // In flight — a non-actionable progress button reflecting the live stage.
        a.status == AcquisitionStatus.DOWNLOADING || a.status == AcquisitionStatus.QUEUED ||
            a.status == AcquisitionStatus.REQUESTED || a.status == AcquisitionStatus.IMPORTING -> {
            RaviloButton(discoverStatusLabel(a) ?: "Working…", focusRequester = fr, style = ButtonStyle.GHOST)
        }
        a.status == AcquisitionStatus.FAILED -> {
            RaviloButton("Retry request", focusRequester = fr, onSelect = { requestWithLanguage { lang -> store.request(lang) } })
        }
        else -> {
            RaviloButton("Request", focusRequester = fr, onSelect = { requestWithLanguage { lang -> store.request(lang) } })
        }
    }
}

@Composable
private fun CastSection(cast: List<Person>) {
    val colors = RaviloTheme.colors
    Column {
        Text("Cast", color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(cast.size, key = { i -> cast[i].id }) { i ->
                val person = cast[i]
                Column(
                    modifier = Modifier.widthIn(max = 72.dp).focusable(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        val imgUrl = person.imageUrl
                    if (imgUrl != null) {
                            RemoteImage(imgUrl, person.name, Modifier.fillMaxSize().clip(CircleShape))
                        } else {
                            Text(
                                person.name.take(2).uppercase(),
                                color = colors.textSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = Sora,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(person.name, color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Sora)
                    person.role?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Sora)
                    }
                }
            }
        }
    }
}
