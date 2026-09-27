/* Suggestions — mock data for app/suggestions.html (design brief 2026-09-27, "suggested movies from Seerr").
   Every title here is a fictional stand-in (CLAUDE.md: no real titles in the mockups). The shape mirrors
   what the server would send: the list is built in the background, the page only renders it. */
(function () {
  // who the history comes from — production is one viewer deep (brief §1), and the page says so
  const VIEWERS = [
    { id: 'eyd', name: 'Eyð', finished: 101 },
    { id: 'marjun', name: 'Marjun', finished: 7 },
    { id: 'kids', name: 'Kids', finished: 4, kid: true },
    { id: 'olivar', name: 'Olivar', finished: 0 },
  ];
  // six fixed clusters (Q7 lean), in volume order; finished counts sum to 112 → 7·5·3·2·2·1 of 20
  const BUCKETS = [
    { id: 'horror', label: 'Horror', finished: 36, hue: 350 },
    { id: 'family', label: 'Family & animation', finished: 30, hue: 38 },
    { id: 'thriller', label: 'Thriller & crime', finished: 16, hue: 210 },
    { id: 'scifi', label: 'Sci-fi & action', finished: 11, hue: 190 },
    { id: 'drama', label: 'Drama', finished: 10, hue: 280 },
    { id: 'comedy', label: 'Comedy', finished: 9, hue: 95 },
  ];
  // because: [viewerId, 'finished'|'watching', [source titles, newest first]]
  const F = (id, b, title, year, score, mins, cert, syn, because, o) => Object.assign({ id, b, title, year, score, mins, cert, syn, because, state: 'none' }, o || {});
  const LIST = [
    F('s1', 'horror', 'The Hollow Tide', 1978, 7.3, 104, '15', 'A lighthouse crew stops answering the radio, and the relief boat finds the lamp still turning.',
      [['eyd', 'finished', ['Saltmarsh', 'Nightwater']]], { state: 'downloading', pct: 64 }),
    F('s2', 'horror', 'Lamplighter', 2013, 6.9, 97, '15', 'A night-shift keeper on a closed railway line learns why the last station was never lit.',
      [['eyd', 'finished', ['Nightwater', 'The Ninth Bell', 'Grey Water']]], { picker: true }),
    F('s3', 'horror', 'Hollow Men', 1981, 7.0, 99, '18', 'Three brothers inherit a farm, and the scarecrows are not where their father left them.',
      [['eyd', 'finished', ['Harvest of Wire']]], { franchise: 'Hollow Men III' }),
    F('s4', 'horror', 'Grey Parish', 2001, 6.8, 112, '15', 'A new vicar counts one more parishioner at every service than the village has living.',
      [['eyd', 'finished', ['Saltmarsh']]]),
    F('s5', 'horror', 'Coldhouse', 2025, 6.6, 94, '15', 'A cold-storage warehouse keeps one aisle locked, and the inventory keeps growing.',
      [['eyd', 'watching', ['Frost Hours']]], { fresh: true }),
    F('s6', 'horror', 'What the Fog Keeps', 1994, 6.7, 101, '15', 'After the fog lifts, a fishing town finds its harbour holds one boat too many.',
      [['eyd', 'finished', ['Grey Water', 'Saltmarsh']]]),
    F('s7', 'horror', 'Under the Floorboards', 1986, 6.5, 91, '18', 'A couple restoring a manor finds the floorplan in the attic does not match the house.',
      [['eyd', 'finished', ['The Ninth Bell']]]),
    F('s8', 'family', 'Pebble and the Moonfox', 2016, 7.6, 88, 'PG', 'A girl and a fox made of moonlight have one night to return the moon to the sky.',
      [['marjun', 'finished', ['The Lantern Whale']], ['kids', 'finished', ['Tuki and the Tide']]]),
    F('s9', 'family', 'Kettle Island', 2019, 7.2, 84, 'U', 'Four children and a very old kettle keep an island from floating away.',
      [['kids', 'finished', ['Tuki and the Tide']]], { state: 'library' }),
    F('s10', 'family', 'The Paper Lantern Club', 2021, 7.0, 92, 'PG', 'A club of paper lanterns sets out to light a city that forgot how.',
      [['marjun', 'watching', ['The Lantern Whale']]]),
    F('s11', 'family', 'Tobbi Sails North', 2011, 6.9, 79, 'U', 'A small tugboat with big plans sails north to find where the ice begins.',
      [['kids', 'finished', ['Tobbi and the Storm']]], { franchise: 'Tobbi and the Storm 2' }),
    F('s12', 'family', 'Starlings', 2023, 7.1, 86, 'PG', 'A flock of starlings and a lonely weathervane learn to dance together.',
      [['marjun', 'finished', ['The Lantern Whale']], ['eyd', 'finished', ['Moss & Kettle']]]),
    F('s13', 'thriller', 'Night Ferry to Hirtshals', 2018, 7.1, 108, '15', 'On the last crossing of the season, a steward notices a passenger no one checked in.',
      [['eyd', 'finished', ['Carry-On', 'Blue Warrant']]], { state: 'requested' }),
    F('s14', 'thriller', 'The Quiet Auditor', 2009, 6.9, 115, '15', 'An auditor finds a bank that balances perfectly, which is the problem.',
      [['eyd', 'finished', ['Blue Warrant']]]),
    F('s15', 'thriller', 'Black Ice Road', 2022, 7.0, 103, '15', 'Two truckers on a frozen haul road realise the same car keeps passing them.',
      [['eyd', 'finished', ['Frost Hours']], ['marjun', 'finished', ['Carry-On']]]),
    F('s16', 'scifi', 'Orbit Debt', 2020, 6.8, 118, '12', 'A salvage pilot inherits a space station and its creditors in the same week.',
      [['eyd', 'finished', ['Drift 7', 'Cosmos Laundromat']]], { state: 'approved' }),
    F('s17', 'scifi', 'Relay', 2015, 6.7, 96, '12', 'The last operator of a deep-space relay starts receiving calls from next year.',
      [['eyd', 'finished', ['Drift 7']]]),
    F('s18', 'drama', "The Keeper's Daughter", 2017, 7.2, 121, '12', 'A lighthouse keeper’s daughter stays on the island after the light is automated.',
      [['eyd', 'finished', ['Saltvatn']]]),
    F('s19', 'drama', 'Low Tide Letters', 2012, 6.9, 109, '12', 'Letters washed ashore reunite a harbour town with the crew it lost in 1962.',
      [['marjun', 'finished', ['Havets Folk']]]),
    F('s20', 'comedy', "Uncle Bjarni's Wedding", 2019, 6.6, 95, '12', 'A family has one weekend to stop the wedding, attend the wedding, or both.',
      [['eyd', 'finished', ['Fjollerne í Nord']]]),
  ];
  // the other 30 of 50 built — weaker matches, shown only under Show more (Q3)
  const MORE = [
    ['Wicklow Station', 1989, 'horror', 6.4], ['The Salt Choir', 2004, 'horror', 6.5], ['Door Nineteen', 2016, 'horror', 6.4],
    ['Mirelight', 1976, 'horror', 6.6], ['Red Nets', 1999, 'horror', 6.3], ['The Tenant Below', 2008, 'horror', 6.4],
    ['Sleep Harbour', 2025, 'horror', 6.3], ['Hearthless', 1983, 'horror', 6.5], ['Rookery', 2011, 'horror', 6.3],
    ['Mossback and Me', 2014, 'family', 6.8], ['The Kite Keepers', 2020, 'family', 6.7], ['Button Moon Bay', 2009, 'family', 6.6],
    ['Little Lighthouse', 2018, 'family', 6.9], ['Sundew', 2022, 'family', 6.5], ['Otter Post', 2013, 'family', 6.6],
    ['Cold Case Harbour', 2015, 'thriller', 6.7], ['The Ledger', 2003, 'thriller', 6.5], ['Crosswind', 2019, 'thriller', 6.6],
    ['Signal Lost', 2021, 'thriller', 6.4], ['Nine Days at Sea', 2010, 'thriller', 6.5],
    ['Halcyon Drive', 2017, 'scifi', 6.5], ['Parallax Nine', 2012, 'scifi', 6.4], ['Ironwake', 2023, 'scifi', 6.4],
    ['The Winter Orchard', 2014, 'drama', 6.6], ['Harbour Lights', 2006, 'drama', 6.5], ['A Year of Tides', 2020, 'drama', 6.4],
    ['Second Wedding', 2016, 'comedy', 6.4], ['The Borrowed Boat', 2011, 'comedy', 6.5], ['Kaffi & Kaos', 2018, 'comedy', 6.3],
    ['Two Left Boots', 2022, 'comedy', 6.3],
  ].map((m, i) => F('m' + i, m[2], m[0], m[1], m[3], 90 + (i * 7) % 30, m[2] === 'family' ? 'PG' : '15', '', [['eyd', 'finished', ['Saltmarsh']]], { more: true }));
  // Q7: this build's clusters as the AI named them (the fixed six above are the fallback)
  const AI_NAMES = { horror: 'Folk and coastal horror', family: 'Gentle animated adventures', thriller: 'Cold-water thrillers', scifi: 'Lonely-station sci-fi', drama: 'Island family dramas', comedy: 'Nordic family comedy' };
  const DISMISSED = [
    { title: 'Midsummer Static', year: 1997, reason: 'Not interested', who: 'Admin', when: 'Tuesday 21:04' },
    { title: 'The Long Harbour', year: 2008, reason: 'Already seen it', who: 'Admin', when: 'Tuesday 21:02' },
    { title: 'Twelve Winters', year: 1968, reason: 'Too old', who: 'Admin', when: '19 Sep' },
    { title: 'Steel Harbour 4', year: 2019, reason: 'Blacklisted in Seerr', who: 'in Seerr', when: '2 Sep' },
  ];
  window.SUGG = { AI_NAMES, VIEWERS, BUCKETS, LIST, MORE, DISMISSED, built: 'Tuesday 06:30', next: 'next Tuesday 06:30', newSince: 3 };
})();
