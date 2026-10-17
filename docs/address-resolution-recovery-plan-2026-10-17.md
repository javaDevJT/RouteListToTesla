# Automatic address recovery and lookup feedback plan

## Objective and boundaries

Investigate IMG_0629.HEIC, IMG_0630.heic, IMG_0631.heic and IMG_0633.PNG, then
plan automatic address recovery that requires user intervention only after safe
recovery is exhausted. Never accept a city/state center as a street destination.
Preserve house numbers, apartment-first lookup, original OCR evidence, order and
overlap dedupe, three local OCR engines, TAP/CSRF and command safeguards. No hosted
OCR or OpenAI key. The initial investigation was plan-only; the user subsequently
authorized implementation, commit, push and deployment. No vehicle commands are
authorized as part of qualification.

Status: deployed and verified as `20261017-location-recovery`, application source
`48f86f2`. Implementation base: main `9a3471b`; unrelated
AGENTS.md is preserved.

## Authorized execution

The user authorized implementation, commit, push and deployment on 2026-10-17.
Execution uses main. Primary owns model/controller integration, checks, release
helpers and deployment. Independent write scopes: geocoder and its test; UI and
its JS test; OCR cache and its test; automatic navigation and its tests. Model
selection checked against the native catalog: the single current Luna entry is
`gpt-6-luna`, maximum effort `max`; both fields were explicit for all workers.
Workers may not commit, use real providers/NAS, modify infrastructure, or issue
vehicle commands; primary serializes Maven writes and external actions.

Contract: PlaceCandidate gains optional LocationResolution metadata with statuses
VERIFIED_UNIT, VERIFIED_BUILDING, NO_MATCH, AMBIGUOUS, DEFERRED,
LOOKUP_UNAVAILABLE; fields status, strategy, reasonCode, attempts, policyVersion,
retryAfterSeconds. Existing constructors remain compatible. Helpers
withResolution(metadata) and withLocationFrom(candidate) retain original row
provenance. The geocoder owns authoritative outcomes; callers never trust inbound
metadata for command authorization. Its existing batchGeocode(owner, rows) stays;
an overload batchGeocode(owner, rows, absoluteDeadlineNanos) supports parent
request deadlines. Primary owns these model classes and controller calls.

The four scoped builders completed their handoffs and released their workers.
Primary integrated the model/controller contracts and serialized the build checks.
Independent reviews covered saved-session command boundaries, cache trust,
geocoder locality/unit validation, cancellation and the retry UI. Their findings
were corrected and closure reviews found no remaining concrete blocker.

Restart-held accepted sessions cannot resume through the generic start path.
Location-validation pauses have a dedicated guarded retry endpoint and UI action;
it returns to telemetry monitoring without immediately issuing a command. Marked
obsolete location errors clear their text and styling after successful recovery,
while command holds remain intact.

Primary integration checks passed: 191 Java tests in a clean package, 29 JS
tests, and mobile Chrome at 390×844 with mocked route APIs. The actual recorded
Google responses replay successfully against the packaged geocoder with one
request per original stop, including unit-level St Clair verification and
unchanged OCR provenance; no new Google or vehicle requests were made. Browser
checks cover terminal success, hidden obsolete send errors, draft/caret retention,
focus after disabling/removing retry controls, location-only automatic retry, and
absence of retry for restart-held accepted commands. Geocoder and saved-session/
API review corrections were independently closed with no concrete blocker.
The native image suite now includes all three supplied HEICs (16 cases total).
Existing image concurrency and all three OCR engines are retained; latency gains
here primarily remove rejected valid matches, duplicate lookups and OCR rereads.

## Native baseline

The prior deployed image `sha256:cbb7cf3d6c1cb26645397c39dd99176d56e498fd2d1944461abbb2e9ed0efe56`
passed the expanded 16-case suite, including all three supplied HEICs, on native
TrueNAS hardware with eight CPUs, 8 GiB and two OCR threads. All 36 Python checks
passed; all three engines ran. Peak memory was 2.23 GiB with no memory events or
leftover OCR jobs; the isolated QA app and fixtures were removed afterward.

One three-image timing trial took 63.10 seconds sequentially and 42.42 seconds
at image concurrency two, a 32.8% elapsed-time reduction with identical ordered
text, engine agreement and review flags. This measures existing concurrency on
the supplied HEICs, not a new OCR algorithm or an end-to-end Google lookup time.
The release image must pass its own qualification before deployment.

## Release qualification

Application source `48f86f2419c81e8f316e9145e6bbe03477092736` is committed and
pushed to main. [CI run 39067020403](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39067020403),
attempt 1, passed build/tests, SBOM/vulnerability checks with zero High/Critical
findings, existing storage hooks, publication and artifact cleanup. This does
not independently calibrate runner storage capacity.

Published image:
`ghcr.io/javadevjt/routelisttotesla@sha256:a0c46d17ed8e10afac9deacfc9ced42dd13c5213d0ef5f2b5321bd5ff7c6ac13`.
Its OCI source revision matches the application commit and its packaged frontend
template matches the tested local build. Native qualification of this image passed
16/16 ordered-exact screenshots and 36 Python tests with all three local engines,
no parser overlays and no memory events or leftover OCR jobs. Peak memory was
2,571,866,112 bytes (2.40 GiB). Task-owned QA resources were cleaned up afterward.

The three supplied HEICs took 64.34 seconds sequentially and 40.90 seconds at
image concurrency two in one timing trial (36.4% less elapsed time). Ordered text,
engine agreement and review decisions matched exactly. The existing two-image
concurrency remains enabled; these timings exclude Google lookup and are not a
claim of a new OCR algorithm. The two actual recorded Google responses separately
passed against the final packaged geocoder in one query each, using offline
replay with zero new provider or vehicle requests.

TrueNAS update job **19348** succeeded. The router runs that exact image and
serves release **20261017-location-recovery** at
[teslarouter.javadevjt.tech](https://teslarouter.javadevjt.tech). Full configuration
readback preserved every setting except the two image references and APP_RELEASE;
data mounts remain unchanged. Eight CPUs/8 GiB, UID 10001, a read-only root and
no published host ports remain in effect. The public HTTP health probe returned
200; TrueNAS did not expose a separate health field.

Live login identity, TAP authorization redirect, signed-out read protection and
CSRF protection (including the new retry endpoint) passed. Chrome at 390×844
verified the digest-proven packaged template with mocked route APIs: explicit
retry success, aggregate/row feedback, stale location-error removal, retained
draft/caret/focus, overlap dedupe and the guarded automatic-route retry. There
were no browser script errors. These interactions do not constitute a live
provider/vehicle or physical-iPhone test; no Google request or vehicle command
was issued during release qualification or deployment.

Private evidence is under `output/private/location-recovery-20261017/`: CI,
registry, packaged-template, native completion, deployment, production and browser
verification JSON; native reports/logs; recorded-response replay and rendered
screenshots. Deployment requires the selected digest to match the CI-scanned
digest and native report hash. Final runtime/browser proofs also cross-check the
same source, image and release. The old-image baseline report cannot satisfy the
new-image deployment gate. The later documentation commit records this evidence;
the deployed application source remains `48f86f2`.

## Investigation ownership

Primary inspected the original images, integrated evidence and prepared this
design. Two bounded read-only investigators traced backend validation and UI
feedback. No worker used Google, changed source, mutated TrueNAS or accessed a
vehicle. Primary alone performed the three specifically approved provider tests.

Worker selection: native spawn catalog on 2026-10-17 exposes one current Luna,
`gpt-6-luna`, with maximum effort `max`. Both fields were explicit, following
the centralized swarm model policy; provider internals are unexposed. Workers
completed with source-backed handoffs. A third read-only worker independently
reviewed destination safety, cache trust, saved sessions and deadline behavior;
the substantive findings are incorporated below. A focused closure review found
no remaining concrete blocker. Mocked timing/cancellation checks and native image
qualification subsequently passed. A real provider-outage load test was not run.

## Confirmed evidence

All three original HEICs were converted locally and visually inspected without
changing the originals. Their 12 visible addresses match the rows in IMG_0633.PNG,
including both unresolved stops and Apt 104. This initial visual comparison did
not run OCR; the native release qualification above covers the same HEICs through
all three engines.

The user approved bounded Google tests for the two failed stops. Three requests
were made: both original queries and the existing St Clair unitless fallback.
All returned HTTP 200 / OK, non-partial rooftop street-address results with the
expected house number, Howell, MI, coordinates and place ID. The original
St Clair response also contains apartment 104.

| Case | Google response | Baseline rejection | Implemented behavior |
| --- | --- | --- | --- |
| St Clair Court | Long route `Saint Clair Court`, short route `St Clair Ct` | Only long name is checked; leading `ST` is not equivalent to `SAINT` | Compare both typed route names and keep the original unit-specific result |
| Knotty Pine Trail | Both route names use `Knotty Pine Tr` | Terminal `TRL` becomes `TRAIL`, but `TR` is absent from the suffix map | Add this controlled terminal suffix equivalence |

An offline probe of the existing compiled comparator reproduced both failures
and the successful short-name/full-suffix comparisons. Neither stop needs a
fallback query once the original response is correctly validated. Locality
mismatch was investigated but is not the cause of these cases.

Private evidence, excluded from Git:

- `output/private/address-recovery-20261017/howell-provider-evidence.json`:
  three responses, mode 0600; credentials are never written.
- `output/private/address-recovery-20261017/howell-geocoding-probe.mjs`:
  bounded approved probe; configuration read in memory, no TrueNAS mutations.
- `output/private/address-recovery-20261017/HowellStreetComparisonProbe.java`:
  offline production-comparator reproduction.
- Converted originals: `/private/tmp/routelist-0629.png`,
  `/private/tmp/routelist-0630.png`, `/private/tmp/routelist-0631.png`.

## Baseline limits before this release

`GeocodingClient.addressVariants()` tries the original, optionally removes a
marked intermediate complex while retaining a separate locality, then removes
a trailing unit. St Clair gets two forms; Knotty Pine gets one. Retrying repeats
those forms. Provider failures have bounded retries and must not trigger address
permutations.

The frontend looks up unresolved uploads again before displaying review, while
its progress notice is inside that hidden review step. Successful manual retry
changes small metadata to “Location verified” and removes the button, without a
prominent terminal announcement. Request failures and no-match outcomes share
row text. An obsolete location-related send error can remain in `sendStatus`.

## Automatic recovery pipeline

### Validate equivalent responses first

Parse immutable house, street, suffix, direction, unit, optional complex,
locality, state and postal code. Keep original OCR text/source separate from
lookup forms. Never invent house/unit digits, remove directionals or silently
choose an OCR alternative.

Inspect typed `address_components`, including both long and short route names.
Normalize casing, punctuation, established terminal suffixes and full cardinal
directions. Add terminal `TR`/`TRL`/`TRAIL` equivalence with negative interior-token
tests. Prefer Google's explicit short-name equivalence for St Clair. Any later
`ST`/`SAINT` query alias must be narrow and non-terminal, never changing a street
suffix into a title.

Require a street-address/premise/subpremise result, exact house, equivalent full
route and direction, allowed locality, requested state and expected country,
valid finite coordinates and place ID. Inspect applicable locality/postal-town/
sublocality components instead of accepting only the first. A municipality alias
requires explicit tested evidence; proximity, shared county or a neighboring
stop's ZIP is insufficient. Preserve conflicting-city/state rejection.

Assess all candidates at a specificity tier. Collapse duplicate representations
of the same destination. Distinct equally supported street destinations mean
ambiguity, not “take the first.” Reject a conflicting returned unit entirely,
including as a building fallback; never strip the conflict to salvage it. Compare
required components against the original input even when querying a less specific
variant. Missing unit information can establish only building-level verification.

Keep partial matches as rejected evidence, never final destinations. A follow-up
may remove a recognized ancillary element only when house/route/locality/state
already agree. It must independently return an acceptable complete street result.

### Run a bounded ladder only for real misses

1. Original complete address, including apartment and complex when supplied.
2. Unit-preserving cleanup: remove a recognized intermediate complex while
   retaining the actual city; normalize unit-label/punctuation formatting; try
   a supported street alias if response evidence warrants it. Dedupe query forms.
3. A structured locality/country query for a remaining genuine miss, using only
   screenshot locality information or established typed aliases. Validate the
   response even when request filters were supplied.
4. After unit-preserving forms are exhausted, query the same street/building
   without the unit. Keep the apartment in UI/session text and label this
   “Building verified — Apt 104 retained,” not “Apartment verified.”

The current TAP navigation payload contains coordinates and place ID, not address
text or the apartment. Preserve the unit for user review and session continuity;
do not imply this change adds unit text to the vehicle payload.

Stop at the first unambiguous sufficiently specific tier. Do not generate a
Cartesian product of spellings. Retain a valid building candidate while the
bounded unit-preserving tier completes, then use it if no unit-level result
exists. Prefer verified unit over verified building. City, route, postal-code and
complex centers remain invalid at every stage. Do not delete locality/state/
direction constraints to broaden a search. Use the existing Geocoding API;
Places/Address Validation activation or billing is not an implicit dependency.

### Bound work and return accurate outcomes

Initial limits to qualify: six distinct forms and eight total HTTP attempts per
unique stop, including transient retries; 25 seconds per stop and 90 seconds per
recovery batch, using a monotonic clock. These are proposed budgets, not measured
performance. Each HTTP timeout is at most the smaller of 15 seconds and the
remaining stop/batch deadlines. Pacing waits, retry backoff and new request
admission must check those deadlines; cancel unfinished work and release its
slots without late row updates. Eight attempts is a ceiling, not a promise to
complete eight slow requests within 25 seconds. Preserve 60 ms global request-start pacing, owner/global hourly
limits and concurrent-batch admission. Time/quota exhaustion means deferred or
retryable, not “address not found.” Provider-wide failure stops permutations and
preserves successful rows. Qualify the batch deadline against actual application,
proxy and browser request timeouts before setting it; retain headroom to return
partial outcomes. Ordinary verified fixtures must not become deferred under the
qualified concurrency/load. Exhausted provider retries should say temporarily
unavailable, without asking the user to edit an otherwise valid address.
Propagate an absolute parent request deadline from the controller: OCR time
already spent and response-serialization headroom reduce the geocoding allowance.
The effective deadline is the earliest parent, batch or stop deadline.

Return compatible per-row metadata: `VERIFIED_UNIT`, `VERIFIED_BUILDING`,
`NO_MATCH`, `AMBIGUOUS`, `DEFERRED`, `LOOKUP_UNAVAILABLE`; strategy, policy version,
safe reason code, attempt count and optional retry-after. Original text/order and
OCR provenance remain unchanged. Older cached/session rows may omit metadata and
must be revalidated before command authorization.

One server recovery pass completes before review. The browser must not immediately
repeat an exhausted pass from `/route/places`. Manual retry can force an eligible
fresh pass; edits create a new version. Log request IDs, strategy, timings and
rejection categories, never addresses, provider bodies, keys or request URLs.

## Feedback and last resort

Show “Reading screenshots…” followed by “Checking street addresses and trying
safe alternatives…” in the visible processing step. Do not claim success only
because OCR found text. Use honest coarse progress for the synchronous request,
without pretending there is per-permutation streaming.

Each row gets a stable accessible status: checking; apartment verified; building
verified with unit retained; no safe match after automatic attempts; ambiguous;
or lookup temporarily unavailable. Manual retry ends with a persistent green
success or explicit failure/deferred message and an aggregate “12 of 12 stops
verified” count. Use a polite live region and keep useful focus when the retry
control changes. Clear an obsolete location-blocked send error after its relevant
stops resolve; preserve unrelated auth errors and uncertain-command holds.

Keep in-place row updates, focused drafts/carets, stale-response guards and
removed-row guards. Enable only batches whose own locations are verified.
Do not add a from-scratch address entry/import control. Manual retry/edit is the
last resort after safe recovery yields no unambiguous street match or required
components conflict. Outages and unfinished budget-limited work are separate
states, never a false permanent no-match.

## Implementation sequence and ownership

Paths below are relative to the worktree root. Assign one owner per write scope.

| Work | Files | Acceptance |
| --- | --- | --- |
| Response equivalence and recovery | `src/main/java/com/jtdev/routelisttotesla/service/GeocodingClient.java`, `src/test/java/com/jtdev/routelisttotesla/service/GeocodingClientTest.java` | Both recorded original responses resolve in one call, including Apt 104; safety negatives stay rejected |
| Outcome contract/partial success | `src/main/java/com/jtdev/routelisttotesla/model/PlaceCandidate.java` or a dedicated optional resolution record, `src/main/java/com/jtdev/routelisttotesla/controller/RouteController.java`, `src/test/java/com/jtdev/routelisttotesla/controller/RouteControllerTest.java` | Upload/resolve/send share rules; successful rows survive outages; preserve text/provenance |
| Visible feedback | `src/main/resources/templates/index.html`, `src/test/js/index-consensus-ui.test.cjs` | Visible pending and terminal states; no duplicate pass, stale error, lost draft or unsafe command enabling |
| Cache/session compatibility | `src/main/java/com/jtdev/routelisttotesla/service/ImageCacheService.java`, `src/test/java/com/jtdev/routelisttotesla/service/ImageCacheServiceTest.java`, `docs/development-reference.md` | New location-policy version, retained OCR/sessions, no stale text/policy validation |
| Saved automatic-navigation safety | `src/main/java/com/jtdev/routelisttotesla/service/AutoNavigationService.java`, `src/main/java/com/jtdev/routelisttotesla/model/AutoNavSession.java`, `src/test/java/com/jtdev/routelisttotesla/service/AutoNavigationServiceTest.java`, `src/test/java/com/jtdev/routelisttotesla/model/AutoNavSessionTest.java` | Legacy coordinates alone cannot authorize a new dispatch; preserve pause and uncertain-command state |

Implement comparator regressions first. Establish the outcome contract before
parallel UI/recovery-ladder implementation. Integrate cache compatibility and
independently review safety before release. Preserve TAP stable-sub entitlement,
CSRF, eight-stop grouping, command idempotency and uncertainty holds throughout.

Before this release the image key combined extraction and geocoding versions.
OCR identity is now separate from location-policy identity, with a tested legacy
read path, so a geocoder fix can reuse OCR. Legacy OCR entries use the combined image key plus
`.ocr`; derive the old combined key with the original geocoding version and read
that exact entry before writing a new OCR-only key. Do not accept old geocoded
results through the OCR migration path. Test owner/state/extraction isolation and
legacy cache files on disk. Revalidate locations without deleting retained files.

Keep fresh server geocoding at manual send and immediately before each eligible
new automatic-navigation group dispatch in the initial release; do not introduce
cached validation as command authority. Never trust browser-supplied coordinates/
place IDs. Avoid repeating this work on every telemetry poll or after an uncertain
command; existing idempotency and command holds take precedence.

`AutoNavigationService.prepareCurrentGroup()` previously skipped geocoding for
saved coordinates. The bypass is removed for new dispatches, including legacy
sessions. Sessions and original text remain; a pending group requires fresh
current-policy street validation and is held if validation fails.
Migration/revalidation must not resume paused, cancelled, completed or uncertain
sessions, reissue an accepted group, or change vehicle preferences.

## Verification and speed gates

- Add sanitized fixtures of both actual provider response shapes; keep raw
  screenshots and precise provider records private. Assert one call for each
  failing original input and unchanged apartment text.
- Cover long/short names; terminal/interior `TR` and `ST`; house/direction/city/
  state/country conflicts; missing/conflicting units; locality component order;
  equally supported candidates; partial/city/route/complex results; invalid
  coordinates/place IDs; outage/quota/time/query-budget exhaustion and dedupe.
- Assert unit-preserving forms precede unitless fallback, complex removal retains
  the city, and outages cannot fan out permutations.
- UI tests: all retry outcomes, mixed-success batches, stale send error removal,
  command-hold preservation, concurrent edits/removals, restored sessions,
  visible progress and repeated clicks. Verify focus/live feedback in a real
  mobile-width browser in addition to the fake-DOM suite.
- Saved-session tests start with legacy coordinates/place IDs, including a city
  result: assert current validation runs before a new group dispatch and rejected
  locations cause no TAP call. Assert paused/uncertain sessions remain held,
  accepted groups are not replayed, and a restored later group is validated.
- Run `./mvnw -Dtest=GeocodingClientTest,RouteControllerTest,AutoNavigationServiceTest,ImageCacheServiceTest,AutoNavSessionTest test`,
  `node --test src/test/js/index-consensus-ui.test.cjs`, then `./mvnw test` and
  `./mvnw clean package`. Extend the existing Python/native OCR qualification with
  these three original HEICs: 12 ordered exact rows, retained units, no route-code/
  time-label extras, correct overlap dedupe. Retain all three local OCR engines.
- Measure OCR, Google, total time, query counts and peak resources separately on
  native production architecture. Image concurrency already exists. First remove
  duplicate lookups and unnecessary fallbacks. Consider two concurrent geocoding
  requests only behind existing pacing/admission, with order/quota/outage tests
  and a measured quality-preserving improvement before enabling it.
- Exercise the recorded failures through upload/review/mocked send preflight;
  issue no real vehicle command. Additional real provider traffic needs appropriate
  address/quota authorization.
- A later release requires passing CI/security gates, immutable image identity,
  TrueNAS runtime and public served-template identity, rendered mobile retry
  outcomes and preserved TAP/CSRF/session/command-hold checks.

Location-validation pause recovery uses the CSRF-protected current-grant/current-
vehicle-command authorized `POST /route/auto-navigate/retry-location/{sessionId}`.
It only re-enters telemetry monitoring for an eligible pending group; it does not
dispatch in that request. Explicit LOCATION_VALIDATION/NONE eligibility is
separate from restart and uncertain-command holds, which cannot use this action.

Google's [Geocoding response reference](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding)
defines typed long/short components and partial matches, and notes that returned
components can change. Its component-filter section distinguishes filters from
influences: validate results rather than treating locality/administrative-area
request fields as proof of a matching street destination.
