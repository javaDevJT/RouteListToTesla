# Old Shook Road building verification

The Old Shook Road failure is reproduced through the previously deployed v15 JAR
using saved Google responses: it returns `NO_MATCH/unit_conflict` after two
lookups despite the exact house, road, township, rooftop point and place ID.
The shared correction is committed, pushed and deployed as
`20261112-old-shook`, verified November 12, 2026 at 21:47 America/Detroit
(November 13 at 02:47 UTC).

## Cause and general correction

The supplied image has two correctly extracted stops, in order:
`1212 GRISWOLD ST UNIT 1205, DETROIT, MI` and
`26095 OLD SHOOK ROAD, HARRISON TOWNSHIP, MI`. The review verifies Griswold and
holds Old Shook. Installed Tesseract PSM6/11 confirms the source text and unit;
fresh native three-engine qualification is a separate release check.

Four authorized Google responses identify the same Old Shook rooftop, place ID
`ChIJh-x07M8mJYgRK2pANZpyLPk`, at `42.5541668,-82.85222`. They report both
`street_address` and `subpremise` result types but no actual subpremise component
or formatted apartment label. v15 allowed that combination only in one postal
unit-parent path, while the shared stricter street/township proof classified it
as a unit conflict. Role-specific classification caused this repeat failure.

Policy `street-address-v16` fixes the shared classification used by exact
building and parent proofs. A bounded, nonempty formatted building address with
a positive street_address/premise type and no actual unit can retain the
overlapping subpremise tag. Pure subpremise results, malformed/explicit unit
components and formatted unit labels remain held. Original-result checks also
prevent unit evidence from disappearing between corroborating queries.

Acceptance still requires the unchanged house, street, typed locality, ZIP,
state/country and exact place ID/rooftop agreement across original, constrained
and reverse proofs. Unit and parent IDs remain separate; a differing parent
still needs its own forward proof before the original unit is accepted.
No address-specific whitelist or guessed coordinates are introduced.

Regression coverage spans explicit townships, suffixless roads, suffix aliases,
provider suffix omissions and unit-parent proofs; negative vectors cover actual
units, missing/overlong display, pure subpremise tags, identity/ZIP/precision
drift, absent proofs and competing results in both orders. The policy bump
re-resolves saved v15 failures; OCR/extraction version and image order are
unchanged. Independent review of the exact staged patch found no blocking false
accept; actual units, ambiguous metadata and competing identities remain held.

The exact staged source excludes unrelated telemetry and passes a clean package
with 351 Java tests, no failures/errors/skips. All 59 frontend tests pass,
including restoration of the saved v15 Old Shook failure. Its application JAR
replays the retained responses as `VERIFIED_BUILDING`, preserving the original
place ID, coordinates, text, filename and row index in three fixture requests.
The immutable image also passed the complete native qualification: all 30
screenshots preserve ordered text, and all 25 prior provider cases plus Old
Shook pass retained-response replay. On the supplied image, Tesseract, EasyOCR
and PaddleOCR agree on both rows, including `UNIT 1205`; the geocoded Old Shook
row preserves that native OCR metadata, original text, filename and zero-based
index 1. Its three proofs retain the exact original place ID and rooftop point.
The 60 fixture requests use 56 retained matches and four explicitly retained
historical empty fallbacks, with zero external calls or vehicle commands.

This run used the actual image JAR on TrueNAS 27.0.0-RC.1/x86_64, UID10001,
8 CPUs/8 GiB, isolated networking and no application/OCR class overlays.
Startup, unauthenticated-route denial, CSRF and packaged Python dependencies
passed. The QA app and owned fixtures were removed; production's full
configuration remained unchanged. Independent setup review confirmed the
corrected retry chain; completion validates its archived file and receipt hashes.

The actual JAR's template and assets rendered the native Old Shook result green
at a 390px mobile viewport, retained the submitted address and enabled manual
send and automatic start without horizontal overflow or browser errors. This
address rendering check mocked all route APIs and clicked neither command
button; it verifies rendering, not Tesla command delivery.

The first exact-source build passed but publication was blocked by ten
High/Critical findings in the existing Tomcat 11.0.25 pin. Its failed log,
hash-verified minimized report and unqualified image identity are retained.
The release updates the existing `tomcat.version` property to 11.0.27, preserving
Spring Boot 4.1.1 and keeping embedded Tomcat components aligned. The fixed
artifact is available on Maven Central; Apache documents security corrections
in [Tomcat 11 advisories](https://tomcat.apache.org/security-11.html). The gate
remains enabled. Source `978245d92126a5a27144670f5157facf209db988` passed exact push
CI [43345381708, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/43345381708),
including zero High/Critical findings and cleanup. The failed image was never
deployed. The corrected immutable image is
`ghcr.io/javadevjt/routelisttotesla@sha256:a7b051f9ce0b0a4830ce98a8949adf5a96c94867fc3c374094251f0b05fdad61`.
Its extracted application JAR SHA-256 is
`a3acabc3406dfec8bbbc6278f3d891f73a0a269971b90b72a5a37816354c7a6e`;
the packaged template, theme and postal resources match committed source,
and the embedded Tomcat components are all 11.0.27.

The first isolated native-QA setup timed out because terminal prompt detection
left an ANSI keypad-mode sequence behind. The retained banner and sole-send
branch prove the setup command was never sent; read-only reconciliation confirmed
no QA app, process or active relevant job and unchanged production configuration.
The failed invocation and logs are archived before correcting that private
harness. This did not require an application change or another CI build.

## Deployment

The address fix is commit `7a226ddb8aae68a623d0b9488bca223920352cca`; the deployed
application source is `978245d92126a5a27144670f5157facf209db988`, which also includes
the required Tomcat security update. The immutable image and extracted JAR above
were deployed through one TrueNAS `app.update`, job `37309`, which reached
`SUCCESS` and a settled `RUNNING` router. No update was retried.

Only the router image, cache-permissions image and `APP_RELEASE` changed.
Reversing those three changes reproduces the exact predecessor configuration
fingerprint. The persistent route-data volume, UID10001:10001, read-only root,
8 CPUs/8 GiB, existing LAN binding, proxy networks and credentials were preserved.
The previous Miller image remains identified in the private baseline receipts
for recovery; no image or data volume was removed.

Live public health returned 200 with source `978245d92126a5a27144670f5157facf209db988`;
the sign-in page returned 200 and its served theme matched the image/source
hash. A real 390px browser check verified the public TAP sign-in link, release
identity and layout without horizontal overflow or page errors. Root also
inspected the actual native-result and production sign-in screenshots.

Production is [teslarouter.javadevjt.tech](https://teslarouter.javadevjt.tech/),
a persistent deployment with no preset expiry. Refresh the page and retry the
held address; v16 re-resolves saved v15 verification results. Qualification
does not claim a live authenticated route submission or Tesla command test.

## Authority and evidence

The user approved up to twelve read-only Google requests for this new case,
using the existing app key. Four were used and their bodies retained; no more
paid calls are needed for replay. This budget is separate from Miller's 5/12.
No Tesla commands are authorized or issued for qualification.

Root owns integration, Git and all remote execution. Bounded workers supply OCR
evidence and an independent proof review. Preserve unrelated dirty telemetry,
untracked AGENTS.md, credentials, route/cache/session data and vehicle safeguards.
Private evidence, authorization, request journal, probes and release receipts are
under `output/private/old-shook-triage-20261112/`.
