package dev.jellystructure.seerr

import dev.jellystructure.shared.tv.PickerOption

// Phase 138 — curated defaults for the admin Request-tab add-row Studio/Network pickers, shown when
// the operator hasn't typed a search query. All ids/names/logo paths verified live against TMDB
// (GET /company/{id}, GET /network/{id}) rather than guessed — a wrong id would silently point a feed
// at the wrong studio/network. TMDB has no `/search/network` endpoint (confirmed: it 200s with empty
// results), so unlike studios, network "search" is a client-side substring filter over this fixed list
// rather than a live TMDB query — there's no way to search the full TMDB network catalogue by name.

val CURATED_STUDIOS = listOf(
    PickerOption(2, "Walt Disney Pictures", "/wdrCwmRnLFJhEoH8GSfymY85KHT.png"),
    PickerOption(3, "Pixar", "/1TjvGVDMYsj6JBxOAkUHpPEwLf7.png"),
    PickerOption(420, "Marvel Studios", "/hUzeosd33nzE5MCNsZxCGEKTXaQ.png"),
    PickerOption(1, "Lucasfilm Ltd.", "/tlVSws0RvvtPBwViUyOFAO0vcQS.png"),
    PickerOption(25, "20th Century Fox", "/qZCc1lty5FzX30aOCVRBLzaVmcp.png"),
    PickerOption(174, "Warner Bros. Pictures", "/zhD3hhtKB5qyv7ZeL4uLpNxgMVU.png"),
    PickerOption(1957, "Warner Bros. Television", "/pJJw98MtNFC9cHn3o15G7vaUnnX.png"),
    PickerOption(128064, "DC Films", "/13F3Jf7EFAcREU0xzZqJnVnyGXu.png"),
    PickerOption(33, "Universal Pictures", "/8lvHyhjr8oUKOOy2dKXoALWKdp0.png"),
    PickerOption(6704, "Illumination", "/fOG2oY4m1YuYTQh4bMqqZkmgOAI.png"),
    PickerOption(521, "DreamWorks Animation", "/3BPX5VGBov8SDqTV7wC1L1xShAS.png"),
    PickerOption(4, "Paramount Pictures", "/jay6WcMgagAklUt7i9Euwj1pzTF.png"),
    PickerOption(5, "Columbia Pictures", "/71BqEFAF4V3qjjMPCpLuyJFB9A.png"),
    PickerOption(34, "Sony Pictures", "/xAb1o9HrSvKBo9mnXC8fJKDNu00.png"),
    PickerOption(2251, "Sony Pictures Animation", "/5ilV5mH3gxTEU7p5wjxptHvXkyr.png"),
    PickerOption(559, "TriStar Pictures", "/eC0bWHVjnjUducyA6YFoEFqnPMC.png"),
    PickerOption(3287, "Screen Gems", "/bz6GbCQQXGNE56LTW9dwgksW0Iw.png"),
    PickerOption(21, "Metro-Goldwyn-Mayer", "/usUnaYV6hQnlVAXP6r4HwrlLFPG.png"),
    PickerOption(1632, "Lionsgate", "/cisLn1YAUuptXVBa0xjq7ST9cH0.png"),
    PickerOption(12, "New Line Cinema", "/2ycs64eqV5rqKYHyQK0GVoKGvfX.png"),
    PickerOption(923, "Legendary Pictures", "/5UQsZrfbfG2dYJbx8DxfoTr2Bvu.png"),
    PickerOption(3172, "Blumhouse Productions", "/rzKluDcRkIwHZK2pHsiT667A2Kw.png"),
    PickerOption(41077, "A24", "/1ZXsGaFPgrgS6ZZGS37AqD5uU12.png"),
    PickerOption(178464, "Netflix", "/tyHnxjQJLH6h4iDQKhN5iqebWmX.png"),
    PickerOption(20580, "Amazon Studios", "/oRR9EXVoKP9szDkVKlze5HVJS7g.png"),
    PickerOption(194232, "Apple Studios", "/oE7H93u8sy5vvW5EH3fpCp68vvB.png"),
    PickerOption(10342, "Studio Ghibli", "/uFuxPEZRUcBTEiYIxjHJq62Vr77.png"),
    PickerOption(694, "StudioCanal", "/5LEHONGkZBIoWvp1ygHOF8iyi1M.png"),
    PickerOption(10146, "Focus Features", "/xnFIOeq5cKw09kCWqV7foWDe4AA.png"),
    PickerOption(14, "Miramax", "/m6AHu84oZQxvq7n1rsvMNJIAsMu.png"),
).sortedBy { it.name }

val CURATED_NETWORKS = listOf(
    PickerOption(49, "HBO", "/tuomPhY2UtuPTqqFnKMVHvSb724.png"),
    PickerOption(3186, "HBO Max", "/nmU0UMDJB3dRRQSTUqawzF2Od1a.png"),
    PickerOption(213, "Netflix", "/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"),
    PickerOption(453, "Hulu", "/pqUTCleNUiTLAVlelGxUgWn1ELh.png"),
    PickerOption(2739, "Disney+", "/1edZOYAfoyZyZ3rklNSiUpXX30Q.png"),
    PickerOption(2552, "Apple TV", "/bngHRFi794mnMq34gfVcm9nDxN1.png"),
    PickerOption(1024, "Prime Video", "/w7HfLNm9CWwRmAMU58udl2L7We7.png"),
    PickerOption(4330, "Paramount+", "/fi83B1oztoS47xxcemFdPMhIzK.png"),
    PickerOption(3353, "Peacock", "/gIAcGTjKKr0KOHL5s4O36roJ8p7.png"),
    PickerOption(88, "FX", "/aexGjtcs42DgRtZh7zOxayiry4J.png"),
    PickerOption(19, "FOX", "/1DSpHrWyOORkL9N2QHX7Adt31mQ.png"),
    PickerOption(6, "NBC", "/cm111bsDVlYaC1foL0itvEI4yLG.png"),
    PickerOption(2, "ABC", "/2uy2ZWcplrSObIyt4x0Y9rkG6qO.png"),
    PickerOption(16, "CBS", "/wju8KhOUsR5y4bH9p3Jc50hhaLO.png"),
    PickerOption(71, "The CW", "/hEpcdJ4O6eitG9ADSnDXNUrlovS.png"),
    PickerOption(67, "Showtime", "/Allse9kbjiP6ExaQrnSpIhkurEi.png"),
    PickerOption(318, "STARZ", "/qx3Y9LCaK4mq1ykFuDIfjshlo3U.png"),
    PickerOption(359, "Cinemax", "/6mSHSquNpfLgDdv6VnOOvC5Uz2h.png"),
    PickerOption(174, "AMC", "/pmvRmATOCaDykE6JrVoeYxlFHw3.png"),
    PickerOption(80, "Adult Swim", "/tHZPHOLc6iF27G34cAZGPsMtMSy.png"),
    PickerOption(56, "Cartoon Network", "/c5OC6oVCg6QP4eqzW6XIq17CQjI.png"),
    PickerOption(47, "Comedy Central", "/6ooPjtXufjsoskdJqj6pxuvHEno.png"),
    PickerOption(30, "USA Network", "/g1e0H0Ka97IG5SyInMXdJkHGKiH.png"),
    PickerOption(77, "Syfy", "/iYfrkobwDhTOFJ4AXYPSLIEeaAT.png"),
    PickerOption(68, "TBS", "/65r0kR6MfOBYF0gEQsJGM6v5fEG.png"),
    PickerOption(64, "Discovery", "/tmttRFo2OiXQD0EHMxxlw8EzUuZ.png"),
    PickerOption(43, "National Geographic", "/q9rPBG1rHbUjII1Qn98VG2v7cFa.png"),
    PickerOption(4, "BBC One", "/uJjcCg3O4DMEjM0xtno9OWFciRP.png"),
    PickerOption(214, "Sky One", "/pNoTYkElreTiHtxhF1UpUnOyDPF.png"),
    PickerOption(2076, "Paramount Network", "/4knr4ozp2IQrA3SMQlLSYKcM3ML.png"),
).sortedBy { it.name }
