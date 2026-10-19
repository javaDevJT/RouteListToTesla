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

Geocoding `street-address-v7` invalidates older location metadata in cache/session
validation. Recovered rows show Google's map address and retain the original
address text. Unit verification and building-only verification remain distinct;
the normal retry/loading/success feedback and uncertain-command holds remain.

## Validation and release

- Clean package: 228 Java tests passed, including 36 name/state/unit combinations
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

Final image qualification and deployment evidence will be recorded after the
source CI, security/storage/publication gates and immutable image checks finish.
No vehicle commands are part of validation. Credentials, original delivery
screenshots and provider bodies remain outside source control. Existing data,
TAP authorization, CSRF, ordering/dedupe and eight-stop grouping are preserved.

API references: Google's [forward geocoding response contract](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding)
and [reverse-geocoding contract](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-reverse-geocoding).
