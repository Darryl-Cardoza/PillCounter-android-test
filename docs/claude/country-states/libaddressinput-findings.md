# Country/State Data: libaddressinput Evaluation

## Context

Profile screen needs Country + State/Province dropdowns for US and CA only, sending
short codes to the API (`country`, `state`). Current implementation (`CountryData`,
`StateData`) hardcodes both lists in Kotlin. Asked to evaluate replacing the hardcoded
lists with Google's `libaddressinput` so country/state data isn't hand-maintained.

## Library evaluated

`io.github.tompee26:libaddressinput-android:1.0.0` — a Maven Central republish of
Google's `libaddressinput` (the same library used by Chromium/Android's address
autofill), since the original isn't published to Maven Central directly.

- Maven Central: https://central.sonatype.com/artifact/io.github.tompee26/libaddressinput-android
- Source: https://github.com/tompee26/libaddressinput

## Finding: no offline state/province data

The library ships `RegionDataConstants` — a bundled JSON map of **country-level**
metadata only (country name, address format string, which fields are required,
field labels like "state" vs "province" vs "emirate"). See
[`RegionDataConstants.java`](https://github.com/tompee26/libaddressinput/blob/master/common/src/main/java/com/google/i18n/addressinput/common/RegionDataConstants.java).

Actual state/province lists (e.g. `CA -> California`) are **not** bundled. They're
fetched at runtime by `CacheData` + `AsyncRequestApi`, which call Google's hosted
address-data service (`https://chromium-i18n.appspot.com/ssl-address/data/US/CA`
style endpoints) with a 5-second timeout, via an async callback API. See
[`CacheData.java`](https://github.com/tompee26/libaddressinput/blob/master/common/src/main/java/com/google/i18n/addressinput/common/CacheData.java).

## Why this breaks "same functionality"

Current dropdown: instant, fully offline, synchronous list.

Library-backed dropdown would be: network-dependent, async, needs loading/error/retry
UI, and simply fails when offline. For a pill-counting terminal app that already
guards profile updates behind `NetworkUtils.isNetworkAvailable` checks, adding a
network dependency for a static 50-state/13-province dropdown is a regression, not
an improvement.

## Decision

Keep `CountryData` / `StateData` hardcoded. Not adopting `libaddressinput`.

## Alternative considered (not pursued)

- ICU4J / `java.util.Locale.getISOCountries()` — gives country codes+names built into
  Android, no lib needed, but has no subdivision (state/province) data at all.
- Google `libaddressinput` — full metadata bundle exists internally at Google but
  isn't published in any Maven artifact; only the network-backed variant is public.

Both still require the country list to be filtered down to just US/CA, and neither
solves the state-list problem offline, so neither is worth the added dependency for
a 2-country scope.
