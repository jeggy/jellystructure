# The Coming Soon card shows the genre, not the year

Task: DEB-2250

> Owner, 2026-10-23 (DEB-2250), with a screenshot of Discover ▸ Coming Soon on the desktop app, every film card ending
> in a line that reads `2026`: *"Why is it showing 2026? that information seems kinda useless. If I'm looking at the
> calendar and seeing that a movie is coming out on Wed 11 Nov, then there's no need to mark that movie as 2026.
> Either show something more useful or just remove that row fully"*

Decided on the task's question card the same day. **Line 3:** show the genre instead of the year. **The repeated
release type:** keep it on the artwork only.

## Scope

One composable, `UpcomingCard` in `ravilo-ui` `UpcomingScreen.kt`. Every Ravilo client that has Coming Soon draws
it: Android TV, phone, web, Mac and Linux. Only the text under the artwork changes. The artwork, its badges, the
focus treatment, the card's size and the Coming Soon detail page stay as they are.

This is client-only. `UpcomingItem.genre` is already on the wire: `UpcomingService` sets it for films and series from
the held title's first genre (R166 FR-R166-2 #6), or from the *arr's own genres when the title is not held. The
backend, the wire and the `UpcomingFeed` baseline do not change. An app built before this keeps drawing the year.

## Today (verified against `main`, 2026-10-23)

Under the artwork, `UpcomingCard` draws three lines:

| Line | Series episode | Film |
|---|---|---|
| 1 | title | title |
| 2 | `S01E04 · {episode title}` | the release type (`up.release_*`), e.g. *Digital Release* |
| 3 | `{network} · {year}` | `{year}`, because a film has no network |

The bottom-left of the film's artwork already shows that same release type (`item.time ?: releaseTypeLabel(item)`).
On a film card the release type appears twice, and the year repeats the date heading above the card.

## Requirements

**FR-1 — The year leaves the card.** No Coming Soon card shows `UpcomingItem.year`, on a film or on a series. The
field stays on the wire, and the Coming Soon detail page keeps showing it.

**FR-2 — A series card's last line is `{network} · {genre}`.** Each part is shown only if it is present and not
blank, and the parts are joined with the existing `  ·  ` separator. If neither is present, the line is not drawn,
which is what the card already does for a blank line.

**FR-3 — A film card's line under the title is the genre.** The release type stops being drawn under the title and
stays on the artwork's bottom-left, unchanged. A film card is the title, then the genre. If the film has no genre,
the card is just the title. A film card has no third line.

**FR-4 — The genre is shown as the server sends it.** No translation and no case change, the same as the detail page's
genre (`UpcomingDetailScreen`, the `genreLabel` fallback). There is no new string key.

**FR-5 — Nothing else on the card changes.** The *Was due {date}* line on a Missing item stays the last line,
unchanged. A series card's line 2 (`S01E04 · {episode title}`) is unchanged. A film with neither a release type nor
an estimate still shows nothing at the art's bottom-left. That is today's behaviour, and this spec does not touch it.

## What the code makes cheap or expensive

Cheap: about five lines in `UpcomingScreen.kt`, around L446–451. `sub` becomes the genre for a film, and `footer`
swaps `item.year` for `item.genre` and is drawn only for a series. A film should draw no empty `Text` for a missing
genre. The existing `if (footer.isNotBlank())` guard already shows the pattern. A small pure function, roughly
`upcomingCardLines(item): List<String>`, would make FR-1 to FR-3 unit-testable in `commonTest`, beside
`UpcomingReleaseLabelTest`.

The stacked series card that DEB-2078 specifies takes the same last line. See the dated amendment in
`coming-soon-stacks-a-series-same-day-episodes.md` FR-3.

## Acceptance

1. A `commonTest` covers: a film with a release type and a genre gives `[title, genre]`. A film with no genre gives
   `[title]`. A series with a network and a genre gives `[title, SxxEyy · …, network · genre]`. A series with only a
   network gives `[title, SxxEyy · …, network]`. No case contains the year.
2. On a device, Discover ▸ Coming Soon: the film cards show the release type on the art only, with the genre under
   the title. The series cards end in `{network} · {genre}`. No card shows a four-digit year. The Coming Soon detail
   page still shows the year.
