package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.components.PlayPauseGlyph
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay

/**
 * R245 (FR-R245-7/8/9/10) — the remote in your hand, direction 2 · "Now playing": a 16:9 art card on a
 * solid ground (never ink on a picture — a title without art gets a wordmark tile, R243's rule), the
 * device chip as the header AND the control (it is the platform's own cast button), kicker + title,
 * "Playing on {device}", a draggable seek bar, −10 s · ▶/❚❚ · +30 s, and a footer of Subtitles & audio ·
 * Next episode · Stop casting. Seven states, all read from what the receiver reports; the position
 * ticks from its reports, never a local clock. Leaving never stops the cast (FR-R245-10).
 */
@Composable
fun CastRemoteScreen(
    cast: CastController,
    onBack: () -> Unit,
    /** Ended · "Play again" — the app re-casts the same item from the start. */
    onPlayAgain: (itemId: String) -> Unit,
    /** R299 (FR-R299-2) · Failed · "Play on this phone" — the app ends the cast and opens its own player. */
    onPlayHere: (itemId: String, title: String, kicker: String?) -> Unit,
    /** A computer's window (macOS, Linux): the device's volume, the desktop's failure line, and every control kept on
     *  screen (FR-R350-18). A parameter so a test can draw the computer's remote. */
    desktop: Boolean = dev.jellystructure.ravilo.ui.isDesktopPlatform,
) {
    val colors = RaviloTheme.colors
    val portrait = LocalPortrait.current
    val link by cast.sender.link.collectAsState()
    val status by cast.sender.status.collectAsState()
    val device by cast.sender.deviceName.collectAsState()
    val s = status ?: CastRemoteStatus()
    val name = device ?: ""
    val unreachable = link != CastLinkState.CONNECTED
    // The sheet itself is drawn at the root (CastTrackSheetHost), above the bottom bar; leaving the remote closes it.
    DisposableEffect(cast) { onDispose { cast.trackSheetOpen.value = false } }
    var convertedOpen by remember { mutableStateOf(false) }
    // Optimistic on drag, resolved to the receiver's number on release (open question 3: it eases by
    // simply adopting the next report; nothing animates a snap).
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableLongStateOf(0L) }
    // FR-R245-9 · Server busy — the elapsed wait, the one number in the whole viewer half.
    var busyElapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.busySinceMs) {
        val since = s.busySinceMs ?: return@LaunchedEffect
        while (true) { busyElapsed = ((nowMillis() - since) / 1000).toInt().coerceAtLeast(0); delay(1_000) }
    }

    // R350 (FR-R350-18) — every control on screen on a computer's window, down to its minimum height (600 dp): the art
    // card takes what the controls leave (16:9, never larger than it was), and a short window tightens the spacing and
    // the transport. It used to be 60 % of the width at 16:9 — about 500 dp tall in a 1512 × 859 window, which put
    // Audio & Subs, Next and Stop casting below the fold. A phone keeps its own layout (and its scroll).
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val fit = desktop
        val short = fit && maxHeight < REMOTE_SHORT_HEIGHT
        fun gap(d: androidx.compose.ui.unit.Dp) = if (short) d / 2 else d
        RemoteFitLayout(
            fitHeight = if (fit) maxHeight - 16.dp else null,
            artFraction = if (portrait) 1f else 0.6f,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            top = {
                // ── Header: back · device chip (the platform's dialog: switch device or stop) ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(20.dp)) {
                            val p = Path().apply { moveTo(size.width * 0.62f, size.height * 0.12f); lineTo(size.width * 0.28f, size.height * 0.5f); lineTo(size.width * 0.62f, size.height * 0.88f) }
                            drawPath(p, colors.text, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.background(colors.surfaceVariant, RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                        // R265 — the same glyph and sheet as everywhere else (Stop casting lives in it), not the SDK's dialog.
                        dev.jellystructure.ravilo.ui.components.CastButton()
                    }
                }
                Spacer(Modifier.height(gap(18.dp)))
            },
            art = {
                // ── Art card (16:9, solid ground) ──
                val artDim = !s.playing || unreachable || s.failed
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceVariant).alpha(if (artDim) 0.6f else 1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = s.artUrl
                    if (art != null) RemoteImage(url = art, contentDescription = s.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, requestedWidth = 1280)
                    else Text(s.title ?: name, color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(24.dp))
                    if (s.buffering && !unreachable) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                }
            },
            bottom = {
                Spacer(Modifier.height(gap(16.dp)))

                // ── Kicker + title + state line ──
                s.kicker?.let { Text(it.uppercase(), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1) }
                Text(s.title ?: "", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val state = remoteState(s, unreachable)
                val stateLine = when (state) {
                    RemoteState.FAILED -> str("cast.failed", mapOf("device" to name))
                    RemoteState.UNREACHABLE -> str("cast.lost", mapOf("device" to name))
                    RemoteState.NO_SERVER -> str("cast.no_server")
                    RemoteState.BUSY -> str("srv.busy")
                    RemoteState.ENDED -> str("player.end_of_episode").takeIf { s.hasNext } ?: str("player.end_of_movie")
                    RemoteState.PLAYING -> str("cast.playing_on", mapOf("device" to name))
                    RemoteState.PAUSED -> str("cast.paused_on", mapOf("device" to name))
                }
                Text(stateLine, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora, textAlign = TextAlign.Center)
                // second line for the two "cannot" states + the busy wait
                when (state) {
                    RemoteState.FAILED -> Text(str(if (desktop) "cast.failed_sub_desk" else "cast.failed_sub"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    RemoteState.UNREACHABLE -> Text(str("cast.lost_sub"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    RemoteState.NO_SERVER -> Text(str("cast.no_server_sub"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    RemoteState.BUSY -> Text("${str("srv.busy_sub")} · ${str("cast.waiting", mapOf("n" to busyElapsed.toString()))}", color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    else -> {}
                }
                // ── FR-R245-19 — the stream is a conversion, not the file: say so, quietly, and explain on tap.
                // Shown only while the receiver has media and is reachable; never a codec, protocol or product name.
                if (s.transcoding && s.loaded && !unreachable && !s.noServer && !s.ended) {
                    Row(
                        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { convertedOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoGlyph(colors.textDim)
                        Spacer(Modifier.width(6.dp))
                        Text(str("cast.converted", mapOf("device" to name)), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
                    }
                }
                Spacer(Modifier.height(gap(18.dp)))

                // ── Next-up mirrored (the receiver owns the countdown; cancelling is SENT) ──
                val nextUp = s.nextUpSecs
                if (nextUp != null && !unreachable) {
                    Column(
                        Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
                    ) {
                        Text(str("player.up_next"), color = colors.accentSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(s.nextTitle ?: str("detail.episode"), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemotePill(str("player.play_in", mapOf("secs" to nextUp.toString())), primary = true) { cast.command("nextup_play") }
                            RemotePill(str("player.watch_credits"), primary = false) { cast.command("nextup_cancel") }
                        }
                    }
                    Spacer(Modifier.height(gap(18.dp)))
                }

                // ── Seek bar + times ──
                val transportEnabled = remoteTransportEnabled(s, unreachable)
                RemoteSeekBar(
                    colors = colors, positionMs = if (scrubbing) scrubPos else s.positionMs, durationMs = s.durationMs, enabled = transportEnabled,
                    onSeekStart = { ms -> scrubbing = true; scrubPos = ms },
                    onSeekDrag = { ms -> scrubPos = ms },
                    onSeekEnd = { scrubbing = false; cast.sender.seekTo(scrubPos) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(hmsLabel(if (scrubbing) scrubPos else s.positionMs), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                    Text(hmsLabel(s.durationMs), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
                }
                Spacer(Modifier.height(gap(16.dp)))

                // ── Transport ──
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (short) 24.dp else 30.dp), modifier = Modifier.alpha(if (transportEnabled) 1f else 0.4f)) {
                    RemoteSkip(label = "10 s", back = true, enabled = transportEnabled, colors = colors, size = if (short) 44.dp else 54.dp) { cast.sender.seekTo((s.positionMs - 10_000L).coerceAtLeast(0L)) }
                    Box(
                        Modifier.size(if (short) 64.dp else 80.dp).clip(CircleShape).background(colors.text)
                            .clickable(enabled = transportEnabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { if (s.playing) cast.sender.pause() else cast.sender.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (s.buffering && transportEnabled) CircularProgressIndicator(color = colors.background, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        else PlayPauseGlyph(playing = s.playing, tint = colors.background, sizeDp = 30)
                    }
                    RemoteSkip(label = "30 s", back = false, enabled = transportEnabled, colors = colors, size = if (short) 44.dp else 54.dp) { cast.sender.seekTo((s.positionMs + 30_000L).coerceAtMost(s.durationMs.coerceAtLeast(0L))) }
                }
                // R351 (FR-R351-6) — a computer has no volume keys that reach the device: the remote carries its volume, the
                // same control the music bar has while casting (R337). The phone keeps its hardware keys.
                if (desktop && !unreachable) {
                    Spacer(Modifier.height(gap(18.dp)))
                    RemoteVolume(cast, colors, Modifier.widthIn(max = 360.dp).fillMaxWidth())
                }
                Spacer(Modifier.height(gap(22.dp)))

                // ── Footer / state actions ──
                when {
                    state == RemoteState.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.itemId?.let { id -> RemotePill(str(dev.jellystructure.ravilo.ui.seams.CastPlatform.playHereKey), primary = true) { onPlayHere(id, s.title ?: "", s.kicker) } }
                        RemotePill(str("cast.stop"), primary = false) { cast.sender.stop(); onBack() }
                    }
                    unreachable -> RemotePill(str("action.retry"), primary = true) { cast.command("status") }
                    s.ended -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.itemId?.let { id -> RemotePill(str("action.play_again"), primary = true) { onPlayAgain(id) } }
                        RemotePill(str("cast.stop"), primary = false) { cast.sender.stop(); onBack() }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        RemotePill(str("player.audio_subs"), primary = false, modifier = Modifier.weight(1f)) { cast.trackSheetOpen.value = true }
                        if (s.hasNext) RemotePill(str("player.next"), primary = false, modifier = Modifier.weight(1f)) { cast.command("next") }
                        RemotePill(str("cast.stop"), primary = false, modifier = Modifier.weight(1f)) { cast.sender.stop(); onBack() }
                    }
                }
                Spacer(Modifier.height(gap(24.dp)))
            },
        )
    }

    // ── FR-R245-8 — subtitles & audio: the SAME picker component the local player opens ──
    if (convertedOpen) ConvertedPopover(deviceName = name, colors = colors) { convertedOpen = false }
}

/**
 * R351 (FR-R351-6) — the device's volume on a computer: what it last reported ([CastSender.volume]), else the level set
 * here last; a click or a drag sets it ([CastSender.setVolume]). A speaker glyph, then the line.
 */
@Composable
private fun RemoteVolume(cast: CastController, colors: RaviloColors, modifier: Modifier = Modifier) {
    val reported by cast.sender.volume.collectAsState()
    var local by remember { mutableStateOf<Float?>(null) }
    val level = (reported?.toFloat() ?: local ?: 0.5f).coerceIn(0f, 1f)
    var held by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width; val h = size.height
            val body = Path().apply {
                moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.30f, h * 0.36f); lineTo(w * 0.56f, h * 0.12f)
                lineTo(w * 0.56f, h * 0.88f); lineTo(w * 0.30f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
            }
            drawPath(body, colors.textSecondary)
            drawArc(colors.textSecondary, -45f, 90f, false, topLeft = Offset(w * 0.40f, h * 0.24f), size = Size(w * 0.44f, h * 0.52f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).height(24.dp)) {
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            fun set(x: Float) { val v = (x / wPx).coerceIn(0f, 1f); local = v; cast.sender.setVolume(v.toDouble()) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectDragGestures(onDragStart = { o -> held = true; set(o.x) }, onDragEnd = { held = false }, onDragCancel = { held = false }) { ch, _ -> set(ch.position.x) } }
                    .pointerInput(Unit) { detectTapGestures { o -> set(o.x) } },
            ) {
                val y = size.height / 2
                val th = 4.dp.toPx()
                drawRoundRect(colors.textDim.copy(alpha = 0.35f), Offset(0f, y - th / 2), Size(size.width, th), CornerRadius(th / 2, th / 2))
                drawRoundRect(colors.text, Offset(0f, y - th / 2), Size(size.width * level, th), CornerRadius(th / 2, th / 2))
                drawCircle(colors.text, (if (held) 7.dp else 6.dp).toPx(), Offset(size.width * level, y))
            }
        }
    }
}

/** FR-R245-19 — a small ⓘ: a ring with a dot and a stem, drawn, so it needs no icon asset on any platform. */
@Composable
private fun InfoGlyph(color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val r = size.minDimension / 2
        drawCircle(color, radius = r - 1.dp.toPx(), style = Stroke(width = 1.6.dp.toPx()))
        drawCircle(color, radius = 1.1.dp.toPx(), center = Offset(size.width / 2, size.height * 0.30f))
        drawLine(color, Offset(size.width / 2, size.height * 0.45f), Offset(size.width / 2, size.height * 0.74f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** FR-R245-19 — the explanation, one card over a scrim; tap anywhere to dismiss. */
@Composable
private fun ConvertedPopover(deviceName: String, colors: RaviloColors, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).background(colors.surfaceVariant, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoGlyph(colors.text)
                Spacer(Modifier.width(8.dp))
                Text(str("cast.converted", mapOf("device" to deviceName)), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            }
            Spacer(Modifier.height(10.dp))
            Text(str("cast.converted_body", mapOf("device" to deviceName)), color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            RemotePill(str("action.close"), primary = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

/** FR-R245-8 — the remote's audio & subtitles sheet, drawn by RaviloApp at the root (over the bottom bar) while the remote shows. */
@Composable
fun CastTrackSheetHost(cast: CastController) {
    val open by cast.trackSheetOpen.collectAsState()
    val status by cast.sender.status.collectAsState()
    val device by cast.sender.deviceName.collectAsState()
    CastTrackSheet(cast = cast, status = status ?: CastRemoteStatus(), deviceName = device ?: "", open = open, onClose = { cast.trackSheetOpen.value = false })
}

/** The same sheet as R244's local one — one component, two destinations; the only difference is the line naming the device. */
@Composable
private fun CastTrackSheet(cast: CastController, status: CastRemoteStatus, deviceName: String, open: Boolean, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    val lang = LocalLang.current
    var tab by remember { mutableIntStateOf(1) }
    var level by remember { mutableIntStateOf(0) }
    var idx by remember { mutableIntStateOf(0) }
    var vIdx by remember { mutableIntStateOf(0) }
    val audioGroups = remember(status.audioTracks, lang) {
        val effective = status.audioTracks.ifEmpty { listOf(dev.jellystructure.shared.tv.CastTrack(0, "Default", null)) }
        buildLanguageGroups(effective.mapIndexed { i, t ->
            PickerEntryInput(t.language, t.label, forced = false, isDefault = t.isDefault, badges = audioBadges(PlayerAudioTrack(i, t.label ?: "", t.language, isDefault = t.isDefault), null, lang))
        })
    }
    val subGroups = remember(status.subtitleTracks, lang) {
        buildLanguageGroups(status.subtitleTracks.mapIndexed { i, t ->
            PickerEntryInput(t.language, t.label, forced = t.forced, isDefault = t.isDefault, badges = subtitleBadges(PlayerSubtitleTrack(i, t.label ?: "", t.language, forced = t.forced, isDefault = t.isDefault), lang))
        })
    }
    val offVersion = PickerVersion(flatIndex = -1, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = false, isDefault = false, hadTitleText = false, ordinal = 0, clusterSize = 1)
    val subGroupsWithOff = listOf(PickerLanguage(language = null, isOff = true, versions = listOf(offVersion), isUnnamed = false)) + subGroups
    val groups = if (tab == 0) audioGroups else subGroupsWithOff
    val selectedFlat = if (tab == 0) status.selectedAudio else status.selectedSub
    fun retarget(newTab: Int) {
        tab = newTab; level = 0
        val g = if (newTab == 0) audioGroups else subGroupsWithOff
        val sel = if (newTab == 0) status.selectedAudio else status.selectedSub
        idx = g.indexOfFirst { grp -> grp.versions.any { it.flatIndex == sel } }.coerceAtLeast(0)
    }
    LaunchedEffect(open) { if (open) retarget(1) }
    fun choose(flat: Int) {
        if (tab == 0) cast.sender.selectAudio(status.audioTracks.getOrNull(flat)?.trackId)
        else cast.sender.selectSubtitle(if (flat < 0) null else status.subtitleTracks.getOrNull(flat)?.trackId)
        onClose()
    }
    HandsetSheet(visible = open, onDismiss = { if (level == 1) level = 0 else onClose() }) {
        TrackPicker(
            colors = colors, pickerTab = tab, pickerLevel = level,
            audioGroups = audioGroups, subGroups = subGroupsWithOff,
            pickerIdx = idx, pickerVersionIdx = vIdx,
            selectedAudio = status.selectedAudio, selectedSub = status.selectedSub,
            onTapLanguage = { i ->
                idx = i
                val g = groups.getOrNull(i) ?: return@TrackPicker
                if (g.versions.size > 1) { level = 1; vIdx = 0 } else choose(g.versions.first().flatIndex)
            },
            onTapVersion = { i -> vIdx = i; groups.getOrNull(idx)?.versions?.getOrNull(i)?.let { choose(it.flatIndex) } },
            onTapBack = { if (level == 1) level = 0 else onClose() },
            onTapTab = { t -> retarget(t) },
            handset = true,
            extraRow = {
                Column {
                    if (tab == 1 && level == 0) SubtitleSizeRow(colors, status.subSize, str("cast.applies_on", mapOf("device" to deviceName))) { cast.command("subsize", size = it.toString()) }
                    else Text(str("cast.applies_on", mapOf("device" to deviceName)), color = Color.White.copy(0.5f), fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(top = 10.dp))
                }
            },
        )
    }
}

/** FR-R350-18 — below this a computer's remote tightens its spacing and its transport. */
private val REMOTE_SHORT_HEIGHT = 760.dp
/** FR-R350-18 — an art card that would be smaller than this is left out rather than drawn as a sliver. */
private val REMOTE_ART_MIN_HEIGHT = 72.dp

/**
 * R350 (FR-R350-18) — the remote's column: [top] (the header), the 16:9 [art] card, then [bottom] (titles, next-up,
 * seek, transport, volume, the footer). The art card is [artFraction] of the width at 16:9, as before; with a
 * [fitHeight] it is no taller than what [top] and [bottom] leave of it (narrower to keep 16:9), and left out below
 * [REMOTE_ART_MIN_HEIGHT]. Without one (a phone) nothing changes: the page scrolls.
 */
@Composable
private fun RemoteFitLayout(
    fitHeight: androidx.compose.ui.unit.Dp?,
    artFraction: Float,
    modifier: Modifier,
    top: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    art: @Composable () -> Unit,
    bottom: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fitPx = fitHeight?.let { with(density) { it.roundToPx() } }
    val minArtPx = with(density) { REMOTE_ART_MIN_HEIGHT.roundToPx() }
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            { Column(horizontalAlignment = Alignment.CenterHorizontally, content = top) },
            art,
            { Column(horizontalAlignment = Alignment.CenterHorizontally, content = bottom) },
        ),
        modifier = modifier,
    ) { (topM, artM, bottomM), constraints ->
        val w = constraints.maxWidth
        val loose = androidx.compose.ui.unit.Constraints(maxWidth = w)
        val t = topM.map { it.measure(loose) }
        val b = bottomM.map { it.measure(loose) }
        val used = t.sumOf { it.height } + b.sumOf { it.height }
        val size = remoteArtSize(w, artFraction, fitPx?.let { it - used }, minArtPx)
        val a = if (size == null) emptyList() else artM.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(size.first, size.second)) }
        val height = maxOf(used + (size?.second ?: 0), constraints.minHeight)
        layout(w, height) {
            var y = 0
            for (p in t + a + b) { p.place((w - p.width) / 2, y); y += p.height }
        }
    }
}

/**
 * FR-R350-18 — the art card's (width, height) in px: [fraction] of [width] at 16:9, no taller than [room] when there is
 * a limit; null (left out) when that leaves less than [minHeight].
 */
internal fun remoteArtSize(width: Int, fraction: Float, room: Int?, minHeight: Int): Pair<Int, Int>? {
    val naturalW = (width * fraction).toInt().coerceAtLeast(0)
    val naturalH = naturalW * 9 / 16
    if (room == null || room >= naturalH) return if (naturalH > 0) naturalW to naturalH else null
    if (room < minHeight) return null
    return room * 16 / 9 to room
}

@Composable
private fun RemotePill(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        modifier
            .heightIn(min = 46.dp)
            .background(if (primary) colors.text else colors.surfaceVariant, RoundedCornerShape(23.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) colors.background else colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RemoteSkip(label: String, back: Boolean, enabled: Boolean, colors: RaviloColors, size: androidx.compose.ui.unit.Dp = 54.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(colors.surfaceVariant)
                .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // R301 — the local player's glyph (R257 FR-R257-6), not a second copy of it.
            SkipGlyph(back = back, tint = colors.text)
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
    }
}

@Composable
private fun RemoteSeekBar(colors: RaviloColors, positionMs: Long, durationMs: Long, enabled: Boolean, onSeekStart: (Long) -> Unit, onSeekDrag: (Long) -> Unit, onSeekEnd: () -> Unit) {
    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    fun posAt(x: Float, w: Int): Long = if (durationMs <= 0 || w <= 0) 0L else ((x / w) * durationMs).toLong().coerceIn(0L, durationMs)
    val accent = colors.accent; val accentS = colors.accentSecondary; val track = colors.surfaceVariant; val thumb = colors.text
    Canvas(
        Modifier.fillMaxWidth().height(HANDSET_TARGET)
            .pointerInput(durationMs, enabled) { if (enabled) detectTapGestures(onTap = { o -> onSeekStart(posAt(o.x, size.width)); onSeekEnd() }) }
            .pointerInput(durationMs, enabled) {
                if (enabled) detectDragGestures(
                    onDragStart = { o -> onSeekStart(posAt(o.x, size.width)) },
                    onDrag = { ch, _ -> ch.consume(); onSeekDrag(posAt(ch.position.x, size.width)) },
                    onDragEnd = { onSeekEnd() }, onDragCancel = { onSeekEnd() },
                )
            },
    ) {
        val barH = 5.dp.toPx(); val y = center.y; val w = size.width
        drawRoundRect(track, Offset(0f, y - barH / 2), Size(w, barH), CornerRadius(barH / 2))
        if (frac > 0f) drawRoundRect(Brush.linearGradient(listOf(accent, accentS), Offset.Zero, Offset(w, 0f)), Offset(0f, y - barH / 2), Size(w * frac, barH), CornerRadius(barH / 2))
        drawCircle(thumb, 9.dp.toPx(), Offset(w * frac, y))
    }
}

internal fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
