# General address reliability

The October 19 report exposed two geocoding representation differences and a
deployment gap. Production still served the October 17 image, so the spatial
OCR evidence fix pushed on October 18 had not reached the running application.
The supplied original HEIC was converted and visually inspected; under the newer
parser, all six street/unit lines already had three-engine agreement without
package-counter warnings.

Google's precise Hall Road result uses a different postal city. Reverse geocoding
also returns the exact requested house/street/city 1.59 metres away. The Hilltop
result uses Drive where the screenshot says Court; both its constrained original
query and its canonical query return the same rooftop place ID. These are
observations from private diagnostic responses, not address replacement rules.

## General recovery contract

Strict street matching remains the fast path. Recovery can propose only a unique,
non-partial rooftop street-level result with the requested house number, street
name, directions, state and country. The input must include city and state. Only
one semantic dimension may differ:

- If no recognized locality level explicitly corroborates the requested city,
  reverse geocoding must return a full matching house/street/locality address
  within ten metres. A city, county, postal-code centre or nearby different house
  cannot supply that proof. The original rooftop location remains the target.
- For a different street type or compound-name spacing, a query containing the
  original house/street/unit and structured locality must return the proposed
  same rooftop place ID. A second query containing the canonical street must
  corroborate it again, within two metres. A competing same-number/stem destination
  in either proof blocks correction, regardless of result order.

Recovery never rewrites house numbers, street-name letters, directions, apartment
identifiers, state or country. It cannot combine city and street corrections.
Missing or conflicting provider components, partial matches, invalid coordinates,
ambiguous destinations and genuine unit conflicts remain guarded. Verification
of a recovered apartment requires matching unit evidence in the proposal and
proofs; otherwise only the building is verified and the apartment is retained.

Proof queries share the existing eight-request stop ceiling, 25-second stop and
90-second batch deadlines, per-owner/global budgets, transient retries and 60 ms
global request pacing. They do not use a separate unlimited retry path. No
per-address lookup table, hosted OCR or OpenAI key is introduced.

## OCR, feedback and saved results

Extraction `ocr-consensus-v9` also handles a far-column short counter that is
vertically adjacent rather than overlapping: it cannot interrupt a wrapped unit
or the street-to-city join. Nearby uncertain unit fragments remain evidence and
still require review. Strict consensus packet validation and all three engines
remain intact.

Only validated state codes form standalone state boundaries. Unknown short OCR
fragments cannot prematurely terminate the following city line. Backward evidence
collection requires vertical overlap, or an adjacent nearby plausible unit token
(mixed letters/digits or an explicit unit marker, bounded to 32 characters).
This keeps unrelated header artifacts out of the first address while preserving
uncertain unit evidence.

Hash-prefixed lines before an address remain route metadata, including compact
codes without spaces or punctuation. They cannot become detached-unit review
evidence, whether their boxes overlap the address or sit immediately above it,
unless the address explicitly ends in an apartment/unit marker. In that case a
nearby hash-prefixed unit value remains visible for review instead of being lost.

Geocoding `street-address-v7` invalidates older location metadata in cache/session
validation. Recovered rows show Google's map address and retain the original
address text. Unit verification and building-only verification remain distinct;
the normal retry/loading/success feedback and uncertain-command holds remain.

## Validation and release

- Clean package: 234 Java tests passed, including 36 name/state/unit combinations
  and rejection/proof-budget tests. All 36 frontend tests passed, including v5/v6
  saved-session migration and safe rendering of provider text.
- Eleven user-authorized Google diagnostics were recorded privately. Replaying
  those bodies through the actual geocoder verifies all six supplied rows in
  order while preserving address text: nine fixture requests, no external calls
  or vehicle commands. Hall uses two requests; Hilltop uses three.
- Native baseline on the original HEIC and the prior Southfield screenshot:
  eight exact rows, 3/3 address agreement, no false review warnings, 36 Python
  tests passed, and disposable QA app/fixtures cleaned up. This baseline used
  source overlays and is not final-image qualification.

- Final immutable image qualification: all 20 screenshot cases matched the exact
  expected address order and text using Tesseract, PaddleOCR and EasyOCR. All 36
  Python tests passed. No source overlays were used; the geocoder loaded from the
  image's application JAR and verified all six supplied stops using nine recorded
  fixture requests. Review decisions matched the accepted source baseline.
- One paired native trial measured 37.8 seconds sequentially versus 20.7 seconds
  with two images concurrently. Candidate text, engine agreement and review
  decisions were identical. Qualification peaked at 2.29 GiB with no memory-limit
  events. The disposable QA app and fixtures were removed.

Source [`b2c93c4`](https://github.com/javaDevJT/RouteListToTesla/commit/b2c93c462243e82940f980c170e86c4dd71e6687)
passed [CI run 39294835459, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39294835459/attempts/1),
including storage enforcement, publication and zero High/Critical findings.
The scanned, qualified and deployed image is:

```text
ghcr.io/javadevjt/routelisttotesla@sha256:d982845d2821a1b7ccfcd6b512d3bad060d32110533ad6c4d33f1ce523b9ec83
```

TrueNAS deployment job `25106` completed successfully. Release
`20261019-general-recovery` runs with eight CPUs, 8 GiB, UID 10001, a read-only
root filesystem and no published host ports. Configuration and persistent volume
mounts were preserved. The live `/health` response reports the exact source
commit, the served theme matches the tested CSS, and TAP login redirects and
unauthenticated/CSRF rejection checks passed. A live 390-pixel mobile Chrome
check confirmed the release identity, visible TAP sign-in link and no horizontal
overflow; the rendered page was visually inspected.

Refresh an existing Safari tab before uploading again so it loads the new review
page and saved-result migration. No vehicle commands or additional live Google
requests were part of final image qualification or deployment verification.
Credentials, original screenshots and provider bodies remain outside source
control. Existing data, TAP authorization, CSRF, ordering/dedupe and eight-stop
grouping are preserved.

API references: Google's [forward geocoding response contract](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding)
and [reverse-geocoding contract](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-reverse-geocoding).

## Follow-up diagnosis: postal city and municipality names

The supplied `1.jpeg` reads `878 NOTTINGHAM RD / GROSSE POINTE`. The review page
in `2.jpeg` preserves that street and house number correctly, with agreement from
all three OCR engines. The failure occurs during location validation.

Four explicitly approved Google checks were completed: the original full address,
reverse geocoding its returned coordinate, the unmodified street with original
city/state components, and Google's canonical full address. Each returned the
same first place ID and identical rooftop coordinate, with no partial match:
`878 Nottingham Rd, Grosse Pointe Park, MI 48230, USA`. Reverse geocoding included
neighboring houses and broader regions, but no full street-address result with
the requested `Grosse Pointe` locality. No vehicle commands were issued.

`street-address-v7` requires exact normalized locality components on its direct
path. Its locality recovery also requires a nearby full address containing the
original city name. Consequently it rejects this proposal even though the house
and street match. Repeating the existing lookup cannot address that policy gap;
the provider consistently returns the municipal name `Grosse Pointe Park`.

The missing general capability is independent postal-locality corroboration.
The [GeoNames US postal dataset](https://download.geonames.org/export/zip/)
contains `48230 / Grosse Pointe / MI`, which explains why a postal city can differ
from Google's municipality. This is a candidate source for a general repair,
not implemented or qualified runtime behavior. GeoNames data has completeness
and accuracy limitations and must never supply navigation coordinates.

A guarded repair should require the original city and state to match an
independently maintained postal-name record for the precise result's ZIP, retain
exact house/street/directions and unit conflict checks, and corroborate the same
rooftop destination through reverse address evidence. Original text and the
provider's map address should remain visible. Missing postal evidence, competing
destinations, conflicting units, partial matches and city/ZIP centers must remain
unverified. A city-name prefix alone is insufficient. Google's documentation
also states that `locality` and `administrative_area` components influence
results without being enforced, so repeating a structured query cannot by itself
establish the alias.

This section records diagnosis only. No matcher, OCR policy or production image
was changed for this follow-up. The earlier release proof above applies to the
existing v7 behavior. Private provider responses and the request journal are in
`output/private/nottingham-20261019/`.

The subsequent implementation is tracked in [postal-city recovery](postal-city-recovery-2026-10-19.md),
which records the v8 acceptance rules and its separate release qualification.
