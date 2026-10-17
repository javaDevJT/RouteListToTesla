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

Status: implemented with local qualification complete; CI publication and native
image qualification/deployment are pending. Base: main `9a3471b`; unrelated
AGENTS.md is preserved.

## Authorized execution

The user authorized implementation, commit, push and deployment on 2026-10-17.
Execution uses main. Primary owns model/controller integration, checks, release
helpers and deployment. Independent write scopes: geocoder and its test; UI and
its JS test; OCR cache and its test; automatic navigation and its tests. Model
selection checked against the native catalog: the single current Luna entry is
`gpt-6-luna`, maximum effort `max`; explicit fields will be set for all workers.
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

Queued workers (consumer: primary integration/release; base main 9a3471b):
geocoder-builder, feedback-builder, cache-builder, auto-navigation-builder.
Each must hand off a diff and focused checks, then naturally complete/release.

Execution handles: `/root/recovery_geocoder_builder` and
`/root/recovery_feedback_builder` are running. `/root/recovery_cache_builder`
and `/root/recovery_autonav_builder` handed off their scoped implementations and
whitespace checks; primary accepted their interfaces and is reviewing integration
before serialized Maven. Native completion released both workers. A read-only
review of saved-route, cache and command-boundary safety is queued for primary
integration; geocoder/UI review follows their completed diffs.

Geocoder and UI handoffs are now ready for primary checks; UI worker reported
29/29 JS checks. Independent boundary review found a restart-held accepted-session
resume gap and an inaccessible location-retry path. The original automatic-route
owner is correcting its guard/model/tests; primary owns the guarded retry API/UI.
A separate read-only geocoder/UI safety review is queued against completed code.
First primary compilation caught an invalid checked IOException catch in the
cache slice; primary corrected it. Browser preflight exposed an outdated harness
state assertion, which was updated; marked obsolete send feedback now clears its
text and styling as well as visibility.

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
no remaining concrete blocker; the proposed timing budgets still require runtime
qualification before release.

## Confirmed evidence

All three original HEICs were converted locally and visually inspected without
changing the originals. Their 12 visible addresses match the rows in IMG_0633.PNG,
including both unresolved stops and Apt 104. This is a visual comparison, not a
new OCR qualification run.

The user approved bounded Google tests for the two failed stops. Three requests
were made: both original queries and the existing St Clair unitless fallback.
All returned HTTP 200 / OK, non-partial rooftop street-address results with the
expected house number, Howell, MI, coordinates and place ID. The original
St Clair response also contains apartment 104.

| Case | Google response | Current rejection | Required behavior |
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

## Current limits

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

The image key currently combines extraction and geocoding versions. Separate OCR
identity from location-policy identity, with a tested legacy read path, so a
geocoder fix can reuse OCR. Existing OCR entries use the combined image key plus
`.ocr`; derive the old combined key with the original geocoding version and read
that exact entry before writing a new OCR-only key. Do not accept old geocoded
results through the OCR migration path. Test owner/state/extraction isolation and
legacy cache files on disk. Revalidate locations without deleting retained files.

Keep fresh server geocoding at manual send and immediately before each eligible
new automatic-navigation group dispatch in the initial release; do not introduce
cached validation as command authority. Never trust browser-supplied coordinates/
place IDs. Avoid repeating this work on every telemetry poll or after an uncertain
command; existing idempotency and command holds take precedence.

`AutoNavigationService.prepareCurrentGroup()` currently skips geocoding for saved
coordinates. Remove that bypass for new dispatches, including legacy sessions.
Retain sessions and their original text; require current-policy street validation
before a pending group can be sent, and hold that group if validation fails.
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
