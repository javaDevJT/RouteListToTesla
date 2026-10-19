# Street suffix and township address recovery

Application source: `d1e86ee7300feac148bb56c460d90a8e50a8dc5b` on main.
Status: deployed and verified at https://teslarouter.javadevjt.tech as release
`20261017-address-aliases` (2026-10-18 00:07 UTC / October 17 in Michigan).

## Supplied cases

Both original HEICs, IMG_0634 and IMG_0635, were converted and visually reviewed
alongside IMG_0637.PNG. All ten extracted rows match the originals.

Two specifically approved Google requests established the causes:

- Doves Pt returned an exact, non-partial rooftop street address whose route
  name is Doves Point. The previous small suffix switch did not recognize PT.
- Countryside Ln returned an exact, non-partial rooftop street address. Its
  spelling is correctly one word. Google represents Highland as a neighborhood
  in Highland Township, without a locality or postal town. The former locality
  check rejected that result.

Both actual responses now verify on their original queries, once each. Recorded
response replay preserves the original text and OCR provenance and makes no
external requests. No speculative COUNTRY SIDE rewrite or further provider
request was needed. No vehicle command was issued.

## Matching and automatic recovery

Extraction and geocoding share the checked-in catalog at
`src/main/resources/address/street-suffixes.tsv`. Its source is
[USPS Publication 28, Appendix C1](https://pe.usps.com/text/pub28/28apc_002.htm),
retrieved 2026-10-17: 206 primary suffixes, 502 common-form rows and 554 official
per-primary alias claims. Three explicitly documented compatibility aliases
bring the catalog to 550 unique recognized spellings.

Aliases apply in suffix position. Distinct official primary names stay distinct;
typed short names cannot bridge incompatible long names such as PARKWAY and
PARKWAYS. Five shared-spelling groups have conservative handling: an ambiguous
non-primary abbreviation can match an officially listed meaning in an otherwise
valid provider result, but does not launch queries choosing an arbitrary family.
Multiple distinct matching destinations remain unresolved.

All eight compass directions normalize at route ends, including two-word
diagonals. Leading ST/MT/FT titles have explicit aliases. Internal street-name
words and arbitrary compound-name spacing remain literal.

The complete address, including the unit, is queried first. Bounded cleanup,
normalized units and official full/standard suffix forms precede structured
locality and the building fallback. Structured and unitless forms retain reserved
slots within the existing six-form/eight-attempt, deadline and pacing limits.
A valid original response stops immediately. UI/session text retains the unit.

An unincorporated community can match a same-named, descriptor-bearing US township
when strong city fields are absent. A supplied neighborhood must match. If Google
omits the optional neighborhood, require a nonblank matching township short name
as well. County proximity, a neighborhood alone, contradictory city fields,
partial results and city-center results cannot establish a verified destination.
House, route, direction, unit, state, country and coordinate checks remain.

That release uses extraction policy `ocr-consensus-v7` and geocoding policy
`street-address-v5`. The [October 18 source update](southfield-address-and-ocr-evidence-2026-10-18.md)
addresses spatial OCR evidence separately.
Existing incompatible location metadata cannot authorize navigation. TAP stable
subject authorization, CSRF, ordering/dedupe, eight-stop grouping, command holds
and all three local OCR engines are preserved.

## Verification

Clean package passed 204 Java tests, with zero failures/errors/skips; frontend
checks passed 29/29. Focused independent reviews covered suffix collisions,
direction/title position, unit-first ordering and reserved query slots, township
contradictions and the absent-neighborhood compatibility case. No blocker remained.
The packaged geocoder, catalog/resource and template match the tested classes.

The final immutable image passed 18/18 ordered-exact screenshots and 36 Python
tests on the native AMD EPYC host, using all three OCR engines and no source
overlays. The two supplied HEICs took 44.75 seconds sequentially and 22.24 seconds
in parallel, with identical text, OCR agreement and review flags. This is a
single OCR-only trial at eight CPUs / 8 GiB, not an end-to-end upload latency
guarantee. Peak memory was 2,516,492,288 bytes; memory events and leftover OCR
jobs were zero. The isolated test app and fixtures were cleaned up.

## Release proof

- [CI run 39076997326](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39076997326),
  attempt 1, publish job 117527175419: success. High/Critical findings: zero.
  Existing storage enforcement and gated tag publication passed; this does not
  independently calibrate runner capacity.
- Image: `ghcr.io/javadevjt/routelisttotesla@sha256:f4f48037089220b8ce3feae9432ce4f7ba7d3167b86f9e51076abb07de2fdf53`.
  Its linux/amd64 OCI revision matches the application source above, and its
  embedded UI template matches the tested template.
- TrueNAS deployment job 19687: success. Live runtime uses that digest and
  release. The update preserved the complete existing configuration and volume
  identities, changing only the two service image references and release value.
- Live `/login` returned 200 with the new release. TAP sign-in redirect,
  protected-endpoint redirects and CSRF rejection passed. TrueNAS did not expose
  container health; the configured HTTP healthcheck path was verified directly.
- Mobile Chrome (390 × 844) verified the live login release and rendered the
  exact image-matched template with mocked authenticated APIs. Upload retry,
  cache reuse, overlap dedupe, apartment preservation, visible lookup outcomes,
  blocked unresolved sends, draft/focus preservation and location-only retry
  checks passed, with no browser errors. This did not exercise authenticated
  provider commands or send a route to a vehicle.

Detailed private receipts remain under
`output/private/address-aliases-20261017/` and are excluded from Git.
