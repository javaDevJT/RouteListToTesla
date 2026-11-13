# Old Shook Road building verification

The Old Shook Road failure is reproduced through the actual deployed v15 JAR
using saved Google responses: it returns `NO_MATCH/unit_conflict` after two
lookups despite the exact house, road, township, rooftop point and place ID.
The implementation and release are in progress; final qualification and deployed
identity will be recorded here.

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
Immutable-image and production qualification remain pending.

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
