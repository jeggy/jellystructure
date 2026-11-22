package dev.jellystructure.ui

import kotlinx.serialization.Serializable

/*
 * DEB-5321 (FR-6) — the admin's season heading on a series' Episodes tab: the name, a *custom* tag when the admin
 * chose it, and the Rename control. In `commonMain` for the reason `EpisodeOrderHtml.kt` gives: the markup is the
 * requirement and nothing here may touch the DOM.
 */

/** One entry of FR-5's `season_names`, and of FR-4's answer. */
@Serializable
data class SeasonNameState(
    val season: Int,
    val name: String,
    /** `admin` · `jellyfin` · `default`. */
    val source: String = "default",
)

/** What a season is called before the server has answered, or when it could not be read: today's label. */
internal fun seasonFallbackName(season: Int): String = "Season $season"

internal const val SEASON_NAME_MAX_CHARS = 100

/**
 * The heading's contents for [state]. [editing] opens the text field: it carries [typed] — what the admin has typed, kept
 * across a refusal — and, when it is empty, the current name as its placeholder. [error] is the server's own words.
 */
internal fun seasonHeadHtml(state: SeasonNameState, editing: Boolean, typed: String = "", error: String? = null): String {
    val custom = if (state.source == "admin") """<span class="tiny muted sn-custom">custom</span>""" else ""
    if (!editing) {
        return """<span class="row center" style="gap:8px;">
          <h4 style="margin:0;" class="sn-name">${state.name.esc()}</h4>$custom
          <button class="btn sm ghost sn-rename" style="padding:3px 9px;font-size:.75rem;" title="Name this season">Rename</button>
        </span>"""
    }
    val errorHtml = if (error != null) """<div class="tiny sn-error" style="color:var(--bad);">${error.esc()}</div>""" else ""
    return """<span class="col" style="gap:4px;">
      <span class="row center" style="gap:8px;">
        <input class="input sn-input" type="text" maxlength="$SEASON_NAME_MAX_CHARS" value="${typed.esc()}" placeholder="${state.name.esc()}" style="width:240px;" autocomplete="off">
        <button class="btn sm primary sn-save" style="padding:3px 9px;font-size:.75rem;">Save</button>
        <button class="btn sm ghost sn-cancel" style="padding:3px 9px;font-size:.75rem;">Cancel</button>
      </span>$errorHtml
    </span>"""
}

/** What the field opens with: the admin's own name, or empty — the scanned name is the placeholder, not a value. */
internal fun seasonRenameStart(state: SeasonNameState): String = if (state.source == "admin") state.name else ""

/** The slot a season block's heading is painted into. [season] is the block's own number. */
internal fun seasonHeadMountHtml(season: Int, state: SeasonNameState = SeasonNameState(season, seasonFallbackName(season))): String =
    """<span class="sn-mount" data-season-name="$season">${seasonHeadHtml(state, editing = false)}</span>"""
