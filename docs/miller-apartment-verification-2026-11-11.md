# Miller Avenue apartment verification

The local v15 fix verifies the original apartment destination through explicit
postal-city and rooftop corroboration. Exact staged-source checks, native image
qualification and production deployment are recorded as they complete below.

## Scope and acceptance

The supplied route image contains three ordered stops: Wellington Circle,
Miller Avenue apartment 109, and Royal Berkshire Lane. The supplied review
image verifies the first and third stops but holds the apartment row. Preserve
the source text, apartment, row order, prior unit/parent and locality safeguards,
cached data and unrelated uncommitted telemetry work. Do not send Tesla commands.
Triage, implement a general correction supported by provider evidence, run
focused and appropriate integration/native checks, commit/push and deploy the
exact qualified image. Paid-provider requests remain separately authorized.

## Ownership and evidence

Root owns geocoding diagnosis, code/test integration, documentation, Git and all
external effects. The independent `miller_ocr_evidence` worker owns only the
private `output/private/miller-triage-20261111/ocr-evidence.md` report and OCR
artifacts in that directory. It checks the three source rows and unit without
provider calls, Maven/target writes, application changes or Git mutations.

The live native catalog on November 11 contains one callable Luna family model,
`gpt-6-luna`, with highest supported effort `max`; this is the requested pair
for the bounded evidence worker, with no inherited history. Runtime backend
settings remain unverified where not exposed. Root will review its evidence and
record acceptance and native completion as release.

## Investigation

Initial source is `74cc6a61a4c3984860b70eb9f7ad89afa4759d34`; deployed application
source is `47969db8179d569921402afe3d43ece5386902f8`, release
`20261111-addresses`. Existing telemetry changes and untracked AGENTS.md are
present and must remain preserved. No saved Miller provider response was found
in the existing private evidence or OCR benchmark directories. A separate
approval for up to twelve read-only Google requests is pending; root continues
local diagnosis and OCR verification while awaiting it.

The visible extracted street and apartment match the source. Baseline focused
tests pass: 48 `GeocodingClientTest`, 16 `GeocodingRecoveryTest` and 30
`StreetStructureRecoveryTest` cases, zero failures/errors/skips. These are
loopback/mock tests, not fresh Google verification. The current mismatch hint
requires a unitless input, and the bundled high-accuracy GeoNames catalog has
only `Rochester` rows for ZIPs 48307 and 48309. These explain possible gaps but
do not establish what Google returned for Miller. No verification rule has been
changed, and no live provider call, new commit or deployment has been performed.

The independent OCR worker completed and was accepted/released through native
completion. Its report is `output/private/miller-triage-20261111/ocr-evidence.md`.
PSM 6 and the wrapper's PSM 11 raw Tesseract mode preserve all three ordered rows;
`APT 109` is on one line and recognized correctly. Root checked the source and
four artifact hashes against that report and independently checked row order.
This does not claim a fresh three-engine consensus run. No OCR fix is indicated.

Live Google diagnostics initially depended on explicit approval for this new
case. That request was capped at twelve read-only requests using
the existing key, with no Tesla commands. The earlier approval covered three
previous failures. The capture helper was syntax-checked before use and requires
a scoped authorization receipt. Root waited for that receipt rather than
guessing at the provider response or relaxing unit/city checks.

The user subsequently approved up to twelve read-only Google geocoding requests
for this case, using the existing app key, with no Tesla commands. Root recorded
the scoped approval before invoking the private capture helper. This is a new
case-specific budget; the previous case's accounting remains unchanged.

Five captured requests now show an exact, nonpartial unit-109 rooftop under
`Rochester`, consistent across original and constrained unit queries. Reverse
geocoding returns a unitless parent at the identical point and a ZIP-48307
result whose explicit `postcode_localities` lists both `Rochester` and
`Rochester Hills`. The unitless forward query corroborates the parent ID/point.
The parent has both `street_address` and `subpremise` type tags but no unit
component or formatted unit. No coordinates from the approximate ZIP result
may become a navigation destination.

Root implemented a bounded additive proof: preserve the original unit
destination; require two identical unit rooftop attestations, an explicit
reverse postcode-locality list containing both city labels for the exact ZIP,
and a matching parent reverse/forward rooftop when unit/parent IDs differ. Bare
subpremise tags can be tolerated only in this independently corroborated,
unitless-parent role; actual unit conflicts and competing addresses stay held.
Google documents the field as localities contained in a multi-locality postal
code: [Geocoding responses](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding).
Context7's official Maps JavaScript reference confirms the same result-field
contract; no new API/library is being introduced.

The independent `miller_postal_review` worker uses the same live resolved
`gpt-6-luna` / `max` pair and owns only a private risk/test review. It must not
change application code, run Maven, make provider calls or mutate deployment.
Root uses its findings to check the new proof's city, unit and ambiguity guards.

The additive implementation reuses the unsuccessful legacy reverse response,
so existing successful proofs are unchanged. The explicit postcode metadata is
validated before constrained-unit and parent-forward checks. An actual captured
response replay now verifies Miller's original unit PID/point in four loopback
requests, without external calls. An intermediate worktree passed 351 Java tests and
58 frontend tests; source-only staged qualification remains to be run.

The `miller_release_adapters` worker uses the same resolved model/effort pair
and owns only the ten named private release helper modules and their handoff.
It prepares local adapters from the verified previous release, with a bounded
read-only settling check after a successful update. Root supplies the immutable
source/CI binding and owns native QA, all remote execution and deployment.

The new postal proof retains the existing postal catalog's conservative 10 km
point-to-area-centre plausibility bound, in addition to requiring explicit
postcode bounds containing the queried point. An elongated ZIP can therefore
decline this optional recovery path. The ZIP centre never supplies navigation
coordinates; the independently corroborated original unit rooftop does.

The independent proof review was accepted/released through native completion.
It identified a real missing check for an explicit formatted unit conflicting
with the structured subpremise. Root corrected original, constrained and
reverse-unit proof handling and added conflicting `#110`, `APT 110` and
multiple-label vectors at all three stages. The new six-method suite passes,
including missing/contradictory postal metadata, malformed bounds, wrong
house/street/city/state/country/ZIP, actual unit conflicts and competing roofs.
The existing parent helpers remain unchanged; the bare-tag exception applies
only after the new reverse postal evidence and unit proof succeed.

The exact staged source was exported independently of unrelated telemetry work.
Its clean package passes 347 Java tests with no failures/errors/skips, and all
58 frontend tests pass. The captured Miller replay also passes using only that
export's application classes: original, reverse, constrained-unit and parent
requests, four matched fixture bodies, zero external calls. Five of twelve
authorized live requests were used and all five bodies are retained. No further
paid calls are needed for release qualification, which uses these saved bodies.

A final pre-release ambiguity check exposed a rival in the supplied city whose
different ZIP could hide it from competition detection. The new regression
failed, then passed after root counted supplied-city rivals across ZIP boundaries
in either result order. Positive target acceptance still requires the original
exact ZIP and rooftop proof; this correction only strengthens ambiguity holds.
The first source build was canceled as obsolete before deployment and is retained
in CI history. The corrective source receives a separate exact-source build.
