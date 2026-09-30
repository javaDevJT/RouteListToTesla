# Canfield geocoding diagnosis (2026-09-29)

The destination became a city before it reached TAP or Tesla. OCR correctly
transcribed the Canfield street address and apartment. With the apartment in the
query, Google's Geocoding API returned `OK`, but its first result was a
`partial_match` of type `locality`/`political`, formatted as Dearborn Heights,
Michigan. The old client accepted any first result with coordinates.

The reported review screenshot shows the same rounded coordinates as that city
result. Authenticated, read-only inspection found its unchanged city place ID
and coordinates in two production image-cache records and one saved review
draft. No Canfield row was found in the active-navigation files inspected.
Removing the apartment from the lookup returned the numbered Canfield street
address. Its location is range-interpolated by Google; an apartment entrance is
not established by this lookup.

The downstream trace found no place-ID transformation: RouteList preserves
the selected result's ID, sends it as `placeId`, and TAP prefixes it with
`refId:` for Tesla. No TAP change is needed for this incident.

## Remediation

- Preserve apartment/unit information in the reviewed stop, but omit secondary
  unit identifiers from the street-address geocoding query.
- Accept only a complete numbered address result; leave broad, partial, or
  mismatched results unresolved for review instead of using a city center.
- Include a separate geocoding policy version in image-cache keys. Existing
  cache files remain intact, but old resolved results are no longer reused.
- Resolve reviewed addresses again before dispatch, including candidates with
  plausible cached or browser-supplied coordinates and IDs. This covers saved
  drafts and browser tabs opened before the fix.

Google keys were read from the existing TrueNAS app configuration in memory.
Diagnostic responses were filtered to address-match evidence; keys, auth
tokens, owner identifiers, and session contents were not logged or committed.
No vehicle command was issued during diagnosis or verification.

## Verification

All 128 Java tests passed, including unit-suffix handling, city/partial/wrong
house-number rejection, stale reviewed-route revalidation, and cache-key
versioning. A live run of the updated Java geocoder against Google resolved
the supplied Canfield street address, replaced the old city ID, and preserved
the apartment, source-image metadata, and reviewed text. Public regression
fixtures use synthetic addresses.

The image release uses the existing private-runner workflow and scoped TrueNAS
release helper. Runtime verification is retained separately in
`output/private/canfield-release-verification.json`; this report records the
published digest, source revision, deployment job, health, retained volume,
and served release identity without credentials or route contents.
