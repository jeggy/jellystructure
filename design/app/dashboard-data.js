/* Owner 2026-09-28: a missing intro is not a problem jellystructure reports — the "No intro or credits found" and
   "Waiting for intro detection" rows are gone (Q6). A row counts the things that have the problem (Q2: 1 065 tracks,
   not 48 series); the titles go in the sentence.
   Dashboard as one overview (design brief 2026-09-28, prospective phase 285). Every number is the household's
   own from the brief's §A (2026-09-28); every title is a stand-in. The film/series split of the file-issue types
   is drawn at a plausible split — /api/triage/count does not split by kind today (§F Q9).
   Row: g group · sev critical|warning|info · n titles (the badge, §D rule 3) · u unit · inst/iu the instances
   (carried in the sentence). Severity is the server's (§D rule 4): lean — a row that stops playback is critical
   · fix here|open|elsewhere|info (§C's fourth field) · act the one action · where /
   path for "change a setting elsewhere" · now the live value · zero rows exist only so Q3's "dimmed" can draw. */
window.DASH = {
  GROUPS: [
    { id: 'films', label: 'Films', size: '321 films', lib: 'library.html?kind=films' },
    { id: 'series', label: 'Series', size: '9 269 episodes', lib: 'library.html?kind=series' },
    { id: 'music', label: 'Music', size: '30 albums · 60 songs', lib: 'library.html?kind=music' },
    { id: 'books', label: 'Audiobooks', size: '1 book · 14 parts', lib: 'library.html?kind=books' },
    { id: 'subs', label: 'Subtitles', size: 'Bazarr · 2 of 3 providers healthy', lib: 'subtitles.html' },
    { id: 'jf', label: 'Jellyfin', size: '6 libraries · 10.11', lib: 'settings.html#advisor' },
    { id: 'host', label: 'This server', size: '2 disks · 32 GB', lib: 'settings.html#host' },
    { id: 'svc', label: 'Services', size: 'Seerr · Radarr · Sonarr · qBittorrent · Chromecast', lib: 'settings.html#connections' },
  ],
  UNITS: { pair: ['pair', 'pairs'], film: ['film', 'films'], series: ['series', 'series'], album: ['album', 'albums'], artist: ['artist', 'artists'], song: ['song', 'songs'], book: ['book', 'books'], subtitle: ['subtitle', 'subtitles'], library: ['library', 'libraries'], setting: ['setting', 'settings'], episode: ['episode', 'episodes'], title: ['title', 'titles'], disk: ['disk', 'disks'], speaker: ['speaker', 'speakers'] },
  ROWS: [
    // ---- Films
    { id: 'f-damage', g: 'films', sev: 'critical', label: 'Damaged video files', s: 'Parts that can’t be read, in {titles}. qBittorrent still seeds a clean copy of each.', n: 4, u: 'film', inst: 9, iu: 'files', fix: 'here', act: 'Replace from the clean copy', href: 'library.html?filter=file_damage&kind=films' },
    { id: 'f-cover', g: 'films', sev: 'critical', label: 'Cover art muxed as a video track', s: 'In {titles}: a still image rides as a second video stream — players may open the file and never start.', n: 2, u: 'film', inst: 2, iu: 'files', fix: 'here', act: 'Repair 2', href: 'library.html?filter=cover_as_video&kind=films' },
    { id: 'f-mkv', g: 'films', sev: 'critical', label: 'Unplayable in Ravilo (MKV structure)', s: 'The Tracks element sits after the first Cluster. Repair rewrites the header in place.', n: 1, u: 'film', fix: 'here', act: 'Repair', href: 'library.html?filter=mkv_track_layout' },
    { id: 'f-early', g: 'films', sev: 'critical', label: 'Audio or video stops before the file ends', s: 'Viewers hear silence or see black from that point.', n: 2, u: 'film', fix: 'open', href: 'library.html?filter=track_ends_early&kind=films' },
    { id: 'f-zero', g: 'films', sev: 'critical', label: 'No audio tracks', s: 'Usually a truncated file.', n: 1, u: 'film', fix: 'open', href: 'library.html?filter=zero_audio' },
    { id: 'f-untag', g: 'films', sev: 'warning', label: 'Tracks with no language', s: 'Across {titles}. Ravilo can’t choose by language until they have one.', n: 31, u: 'film', inst: 212, iu: 'tracks', fix: 'open', act: 'Tracks tab', href: 'library.html?filter=untagged&kind=films' },
    { id: 'f-casc', g: 'films', sev: 'warning', label: 'Wrong default audio track', s: 'The default audio isn’t the film’s metadata language.', n: 30, u: 'film', fix: 'open', href: 'library.html?filter=cascade_mismatch&kind=films' },
    { id: 'f-multi', g: 'films', sev: 'warning', label: 'More than one default audio track', s: 'A file should have exactly one.', n: 11, u: 'film', fix: 'open', href: 'library.html?filter=multi_default&kind=films' },
    { id: 'f-len', g: 'films', sev: 'warning', label: 'File claims to be longer than it is', s: 'It never reaches 90 %, so it is never marked watched.', n: 3, u: 'film', fix: 'open', href: 'library.html?filter=duration_header_wrong&kind=films' },
    { id: 'f-poster', g: 'films', sev: 'warning', label: 'No poster', s: 'No poster.jpg on disk.', n: 1, u: 'film', fix: 'here', act: 'Re-pull artwork', href: 'library.html?filter=missing_artwork' },
    { id: 'f-dup', g: 'films', sev: 'warning', label: 'Duplicate library entries', s: 'The same Jellyfin item appears twice.', n: 0, u: 'film', fix: 'open', zero: true, href: 'library.html?filter=duplicate' },
    { id: 'f-gone', g: 'films', sev: 'warning', label: 'No longer in Jellyfin', s: 'Kept for review, never deleted.', n: 0, u: 'title', fix: 'open', zero: true, href: 'library.html?filter=missing_from_source' },
    // ---- Series
    { id: 's-damage', g: 'series', sev: 'critical', label: 'Damaged video files', s: 'Parts that can’t be read, across {titles}. qBittorrent still seeds a clean copy of each.', n: 8, u: 'series', inst: 35, iu: 'episodes', fix: 'here', act: 'Replace from the clean copy', href: 'library.html?filter=file_damage&kind=series' },
    { id: 's-cover', g: 'series', sev: 'critical', label: 'Cover art muxed as a video track', s: 'Across {titles}: a still image rides as a second video stream.', n: 3, u: 'series', inst: 11, iu: 'episodes', fix: 'here', act: 'Repair 11', href: 'library.html?filter=cover_as_video&kind=series' },
    { id: 's-dupep', g: 'series', sev: 'warning', label: 'Two files for one episode', s: 'Across {titles}: each claims an episode another file already has — Ravilo plays one, and play-next stalls on the copy.', n: 3, u: 'series', inst: 12, iu: 'files', fix: 'open', href: 'library.html?filter=duplicate_episode' },
    { id: 's-unres', g: 'series', sev: 'warning', label: 'Episodes Jellyfin never numbered', s: 'Across {titles}: the file name says which episode, Jellyfin never matched it — they drop out of Continue Watching.', n: 3, u: 'series', inst: 12, iu: 'episodes', fix: 'open', href: 'library.html?filter=unresolved_jellyfin_id' },
    { id: 's-early', g: 'series', sev: 'critical', label: 'Audio or video stops before the file ends', s: 'Across {titles}; silence or black from that point.', n: 4, u: 'series', inst: 5, iu: 'episodes', fix: 'open', href: 'library.html?filter=track_ends_early&kind=series' },
    { id: 's-len', g: 'series', sev: 'warning', label: 'Files claim to be longer than they are', s: 'Across {titles}. They never reach 90 %, so they are never marked watched.', n: 4, u: 'series', inst: 5, iu: 'episodes', fix: 'open', href: 'library.html?filter=duration_header_wrong&kind=series' },
    { id: 's-still', g: 'series', sev: 'warning', label: 'Episodes with no image', s: 'Across {titles}: no TMDB still and no screen-grab, so Ravilo shows a blank card.', n: 3, u: 'series', inst: 165, iu: 'episodes', fix: 'here', act: 'Grab stills from the video', href: 'library.html?filter=missing_still' },
    { id: 's-untag', g: 'series', sev: 'warning', label: 'Tracks with no language', s: 'Across {titles}. Ravilo can’t choose by language until they have one.', n: 48, u: 'series', inst: 1065, iu: 'tracks', fix: 'open', act: 'Tracks tab', href: 'library.html?filter=untagged&kind=series' },
    { id: 's-seglow', g: 'series', sev: 'warning', label: 'Intro and credits worth a look', s: 'Across {titles}: found by the heuristic with low confidence.', n: 94, u: 'series', inst: 539, iu: 'episodes', fix: 'open', act: 'Segment editor', href: 'segments.html' },
    { id: 's-casc', g: 'series', sev: 'warning', label: 'Wrong default audio track', s: 'The default audio isn’t the series’ metadata language.', n: 22, u: 'series', fix: 'open', href: 'library.html?filter=cascade_mismatch&kind=series' },
    { id: 's-mix', g: 'series', sev: 'warning', label: 'Episodes disagree on audio language', s: 'The majority language is used for metadata.', n: 39, u: 'series', fix: 'open', href: 'library.html?filter=language_mix' },
    { id: 's-multi', g: 'series', sev: 'warning', label: 'More than one default audio track', s: 'A file should have exactly one.', n: 7, u: 'series', fix: 'open', href: 'library.html?filter=multi_default&kind=series' },
    // ---- Music
    { id: 'm-match', g: 'music', sev: 'warning', label: 'Albums need a match', s: 'MusicBrainz found several candidates and none clearly won, or found nothing.', n: 6, u: 'album', fix: 'open', act: 'Find match…', href: 'library.html?kind=music&match=needs' },
    { id: 'm-tags', g: 'music', sev: 'warning', label: 'Songs whose files don’t say what they are', s: 'Across {titles}. Matched, but none of it is in the files — no ids, most without an album artist. Write it and every player sees the match.', n: 24, u: 'album', inst: 49, iu: 'songs', fix: 'here', act: 'Write tags…', href: 'library.html?kind=music&mview=songs&file=noids', p284: true },
        { id: 'm-folder', g: 'music', sev: 'warning', label: 'Folder and songs disagree', s: 'The folder’s name and the songs’ tags name different things.', n: 12, u: 'album', fix: 'open', href: 'library.html?kind=music&needs=folder' },
    { id: 'm-shared', g: 'music', sev: 'warning', label: 'One album in several folders', s: 'Often a band’s singles whose files all name one compilation.', n: 10, u: 'album', fix: 'open', href: 'library.html?kind=music&needs=shared' },
    { id: 'm-wma', g: 'music', sev: 'warning', label: 'Songs a phone plays only by re-encoding', s: 'WMA. Convert… makes AAC copies and keeps the originals.', n: 38, u: 'song', fix: 'here', act: 'Convert…', href: 'library.html?kind=music&mview=songs', wma: true },
    { id: 'm-pic', g: 'music', sev: 'warning', label: 'Artists without a picture', s: 'Neither fanart.tv nor Wikimedia Commons had one.', n: 8, u: 'artist', fix: 'open', href: 'library.html?kind=music&mview=artists' },
    { id: 'm-cover', g: 'music', sev: 'warning', label: 'Albums without a cover', s: 'Matched, but no cover on disk.', n: 0, u: 'album', fix: 'open', zero: true, href: 'library.html?kind=music' },
    // song-versions brief 2026-10-01 §D5 — only the owner's button removes them; the second action is also by hand only
    { id: 'm-instlyr', g: 'music', sev: 'warning', label: 'Lyrics on an instrumental', s: 'These songs have no singing, but have lyrics beside them — nine instrumental demos on Tide Tables 1999–2012 and one B-side, Kite Weather (instrumental).', n: 10, u: 'song', fix: 'here', act: 'Remove the lyrics', act2: 'Tell LRCLIB it is instrumental', done: 'Lyrics removed from 10 songs — only the sidecars jellystructure wrote; they are never fetched again', done2: 'Told LRCLIB: 10 songs are instrumental', href: 'library.html?kind=music&mview=songs&vi=instrumental&lyr=has' },
    // music-editions brief 2026-10-03 §D4 — two MusicBrainz recordings that sound alike; never joined until the owner says so (editions.js opens Listen and decide)
    { id: 'm-same', g: 'music', sev: 'info', label: 'Songs that may be the same', s: 'Two MusicBrainz recordings that sound alike — Fog Bank on Kite Weather and on its single, and two more. Never joined until you say so.', n: 3, u: 'pair', fix: 'here', act: 'Listen and decide' },
    // ---- Audiobooks
    { id: 'a-part', g: 'books', sev: 'warning', label: 'A part is missing', s: 'The folder’s files skip a number — the book will jump.', n: 1, u: 'book', fix: 'open', href: 'audiobook.html?b=vinterfaergen&tab=parts', fixable: true },
    { id: 'a-cover', g: 'books', sev: 'warning', label: 'No cover', s: 'No cover.jpg, no embedded art, no provider had one.', n: 1, u: 'book', fix: 'open', act: 'Upload', href: 'audiobook.html?b=vinterfaergen&tab=artwork', fixable: true },
    { id: 'a-narr', g: 'books', sev: 'info', label: 'No narrator', s: 'A book plays the same without one.', n: 1, u: 'book', fix: 'info', href: 'audiobook.html?b=vinterfaergen' },
    { id: 'a-two', g: 'books', sev: 'warning', label: 'Folder holds two books', s: '', n: 0, u: 'book', fix: 'open', zero: true, href: 'library.html?kind=books' },
    // ---- Subtitles (Bazarr's advisor + 273's fit numbers)
    // Phase 302 — one row per cause; critical while viewers are offered them; the fix on the row, through Bazarr.
    { id: 'b-speed', g: 'subs', sev: 'critical', label: 'Subtitles at the wrong speed', s: 'Timed for video at another frame rate, so they drift further off through the file. Bazarr’s sync fixes the speed; each is checked again after, and one that still doesn’t fit is replaced.', n: 41, u: 'subtitle', fix: 'here', act: 'Sync them in Bazarr', href: 'subtitles.html?fit=speed' },
    { id: 'b-shift', g: 'subs', sev: 'critical', label: 'Subtitles early or late by 2 s or more', s: 'The whole file is early or late by the same amount. Bazarr’s sync moves it into place; each is checked again after, and one that still doesn’t fit is replaced.', n: 23, u: 'subtitle', fix: 'here', act: 'Sync them in Bazarr', href: 'subtitles.html?fit=shift' },
    { id: 'b-wrong', g: 'subs', sev: 'critical', label: 'Subtitles made for another video', s: 'They match nothing in this video. Bazarr blacklists each one and searches again. A language with nothing right stays empty: rather nothing than wrong.', n: 9, u: 'subtitle', fix: 'here', act: 'Replace them in Bazarr', href: 'subtitles.html?fit=wrong' },
    { id: 'b-slight', g: 'subs', sev: 'info', label: 'Subtitles slightly early or late', s: 'Under 2 s anywhere in the file; readable. Bazarr’s sync lines them up, and each is checked again after.', n: 28, u: 'subtitle', fix: 'here', act: 'Sync them in Bazarr', href: 'subtitles.html?fit=slight' },
    { id: 'b-hook', g: 'subs', sev: 'warning', label: 'Bazarr doesn’t say when it places a subtitle', s: 'jellystructure only finds it on the next scan.', now: 'Off', fix: 'here', act: 'Apply in Bazarr', href: 'settings.html#bazarr' },
    { id: 'b-align', g: 'subs', sev: 'warning', label: 'Bazarr doesn’t align subtitles when it downloads them', s: '', now: 'Off', fix: 'here', act: 'Apply in Bazarr', href: 'settings.html#bazarr' },
    { id: 'b-off', g: 'subs', sev: 'warning', label: 'Bazarr’s sync gives up on large offsets', s: '', now: '60', fix: 'here', act: 'Apply in Bazarr', href: 'settings.html#bazarr' },
    { id: 'b-fps', g: 'subs', sev: 'warning', label: 'Subtitles made for 25 fps stay slow after sync', s: '', now: 'On', fix: 'here', act: 'Apply in Bazarr', href: 'settings.html#bazarr' },
    { id: 'b-upg', g: 'subs', sev: 'info', label: 'Bazarr’s upgrades can swap a right subtitle for a wrong one', s: 'Every 12 h for 7 days.', now: 'On', fix: 'info', href: 'settings.html#bazarr' },
    { id: 'b-title', g: 'subs', sev: 'info', label: 'Bazarr searches 40 shows by title', s: 'They have no IMDb id, so matches are weaker.', fix: 'info', href: 'settings.html#bazarr' },
    // ---- Jellyfin (212/257's advisor, per finding — a finding repeated per library is one row counting libraries)
    { id: 'j-nfo', g: 'jf', sev: 'critical', label: 'Jellyfin’s NFO saver is on for Musik', s: 'It rewrites every album.nfo a second after jellystructure writes it.', now: 'Metadata savers: Nfo', fix: 'elsewhere', where: 'Jellyfin', path: 'Libraries › Musik › Metadata savers', href: 'index.html' },
    { id: 'j-hw', g: 'jf', sev: 'critical', label: 'Hardware encoding is on with no accelerator', s: 'Every transcode tries the hardware first, fails, then falls back.', now: 'Hardware acceleration: None', fix: 'elsewhere', where: 'Jellyfin', path: 'Playback › Transcoding › Hardware acceleration', href: 'index.html' },
    { id: 'j-chap', g: 'jf', sev: 'warning', label: 'Chapter images on rotational storage', s: 'Film · Serier · Blandet · Musik Videoer.', n: 4, u: 'library', fix: 'elsewhere', where: 'Jellyfin', path: 'Libraries › each library › Chapter images', href: 'index.html' },
    { id: 'j-trick', g: 'jf', sev: 'warning', label: 'Trickplay on rotational storage', s: 'Film · Serier · Blandet · Musik Videoer.', n: 4, u: 'library', fix: 'elsewhere', where: 'Jellyfin', path: 'Libraries › each library › Trickplay', href: 'index.html' },
    { id: 'j-scan', g: 'jf', sev: 'warning', label: 'Chapter images during the scan', s: 'Film · Serier · Blandet.', n: 3, u: 'library', fix: 'elsewhere', where: 'Jellyfin', path: 'Libraries › each library › Extract chapter images during the library scan', href: 'index.html' },
    { id: 'j-lufs', g: 'jf', sev: 'warning', label: 'Loudness scan on rotational storage', s: 'Film · Serier · Musik.', n: 3, u: 'library', fix: 'elsewhere', where: 'Jellyfin', path: 'Libraries › each library › LUFS scan', href: 'index.html' },
    { id: 'j-prov', g: 'jf', sev: 'warning', label: 'Jellyfin fetches its own metadata for a library jellystructure manages', s: 'Bøger · Musik.', n: 2, u: 'library', fix: 'elsewhere', where: 'Jellyfin', path: 'Libraries › each library › Metadata downloaders', href: 'index.html' },
    { id: 'j-nfocov', g: 'jf', sev: 'warning', label: 'Titles without an NFO', s: 'NFO coverage is 99 %; Jellyfin reads these from its own cache.', n: 4, u: 'title', fix: 'here', act: 'Sync NFOs to Jellyfin', href: 'library.html?filter=no_nfo' },
    { id: 'j-anon', g: 'jf', sev: 'info', label: 'Jellyfin serves media to callers with no credential', s: 'Remote access is enabled.', fix: 'info', href: 'index.html' },
    { id: 'j-key', g: 'jf', sev: 'info', label: 'On-demand keyframe extraction for mkv', s: '', fix: 'info', href: 'index.html' },
    { id: 'j-rst', g: 'jf', sev: 'info', label: 'Jellyfin is waiting for a restart', s: 'Some settings take effect after it.', fix: 'info', href: 'index.html' },
    { id: 'j-art', g: 'jf', sev: 'info', label: 'Jellyfin saves artwork into the audiobook folders', s: 'Bøger.', fix: 'info', href: 'index.html' },
    // ---- This server
    { id: 'h-swap', g: 'host', sev: 'warning', label: 'The host swaps heavily', s: 'vm.swappiness is 60 and 585 GB has been paged out; 47 GB is in swap now.', now: '60', fix: 'elsewhere', where: 'the host', path: '/etc/sysctl.d · vm.swappiness', href: 'index.html' },
    { id: 'h-sdc', g: 'host', sev: 'warning', label: 'sdc is tuned for a different kind of disk', s: 'mq-deadline not BFQ · read-ahead 128 KB · max_sectors_kb 1280.', n: 3, u: 'setting', fix: 'elsewhere', where: 'the host', path: '/sys/block/sdc/queue', href: 'index.html' },
    { id: 'h-sda', g: 'host', sev: 'warning', label: 'sda is tuned for a different kind of disk', s: 'mq-deadline not BFQ · read-ahead 128 KB · max_sectors_kb 1280.', n: 3, u: 'setting', fix: 'elsewhere', where: 'the host', path: '/sys/block/sda/queue', href: 'index.html' },
    { id: 'h-proxy', g: 'host', sev: 'warning', label: 'A public caller is treated as in-network', s: 'Known proxies is set, but the server still classifies one outside address as local.', fix: 'elsewhere', where: 'Jellyfin', path: 'Networking › Known proxies', act: 'Re-check', href: 'index.html' },
    // ---- Services
    { id: 'v-speak', g: 'svc', sev: 'warning', label: 'Chromecast isn’t registered for speakers', s: 'Stue and Gæsteværelse can’t be cast to until the receiver says it supports audio-only devices.', n: 2, u: 'speaker', fix: 'elsewhere', where: 'Google’s Cast console', path: 'Applications › Ravilo › Edit › Supports casting to audio-only devices', act: 'Show the step', href: 'settings.html#chromecast', speak: true },
    { id: 'v-cast', g: 'svc', sev: 'info', label: 'Chromecast · TVs reachable', s: 'Receiver confirmed by a real cast yesterday. Speakers wait for step 5a — the row above.', fix: 'info', href: 'settings.html#chromecast' },
    { id: 'v-sugg', g: 'svc', sev: 'info', label: 'Suggested films waiting', s: '3 new since Tuesday — mostly horror and family films.', n: 16, u: 'film', fix: 'info', act: 'Suggestions', href: 'suggestions.html', seerr: true },
  ],
  // "Since your last visit" = since this admin last opened the Dashboard (per admin user, server-side; my pick, owner 2026-09-28)
  SINCE: { when: 'Tuesday 21:40', items: [['films', '3 films arrived · 1 damaged file replaced'], ['music', '2 albums matched'], ['subs', '41 subtitles checked · 7 out of sync'], ['series', '118 episodes got an intro']] },
  RECENT: ['Nordvest S05E24 · subtitle check', 'Nordvest S05E23 · subtitle check', 'Nordvest S05E22 · subtitle check', 'Nordvest S05E21 · subtitle check', 'Nordvest S05E20 · subtitle check', 'Nordvest S05E16 · subtitle check'],
};
