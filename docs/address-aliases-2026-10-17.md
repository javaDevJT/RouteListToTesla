# Street abbreviations and compound-name recovery

Status: implementation and local qualification complete on main, base `12ba99f`;
CI image publication and native qualification/deployment are next.

## Scope and evidence

Review original IMG_0634.HEIC and IMG_0635.HEIC alongside IMG_0637.PNG. Diagnose
the Doves Pt and Countryside Ln failures using the two specifically approved,
bounded Google tests. No vehicle commands or unrelated provider requests.
Preserve house/unit digits, direction, locality/state/country validation, query
and deadline budgets, ordering/dedupe, owner isolation and command holds.

Replace the small suffix switch with a maintained authoritative catalog. Distinct
suffixes must remain distinct; suffix aliases apply only in suffix position.
Investigate compound-name whitespace only after checking the actual provider
response; do not introduce fuzzy spelling correction or guess new street names.

## Ownership and checks

Primary owns GeocodingClient integration, approved provider diagnosis, images,
controller/cache compatibility, serialized Maven checks and release operations.
A bounded catalog worker owns only a new StreetSuffixCatalog class, its resource
and its focused test. It must establish source coverage and collision checks and
may not edit GeocodingClient or use providers/NAS. Independent review of alias
false positives and recovery ordering follows integration.

Worker model policy was read from the swarm skill's model-config reference.
Live native spawn catalog checked 2026-10-17: one callable Luna member,
`gpt-6-luna`, supports maximum effort `max`. Both fields are explicit on workers;
provider internals are not exposed. Workers cannot spawn children and complete
naturally after a source-backed handoff. Consumer is primary integration/release.

Catalog outcome: comprehensive official suffix aliases, deterministic
preferred query spellings, and tests for all aliases, ambiguity/collisions,
unknown tokens and representative uncommon suffixes including PT/POINT.
Acceptance: both supplied failures resolve safely; wrong house, street, unit,
direction, city/state/country and city-center results stay rejected. Original OCR
text remains unchanged. Focused and full application checks precede image release;
deployment must bind source, CI scan, immutable digest and runtime verification.

## Confirmed diagnosis and implementation

Both supplied HEICs were converted and visually reviewed. All ten displayed
street rows agree with the app screenshot, including PT and the single word
COUNTRYSIDE. Two approved original Google queries returned non-partial rooftop
street-address results with the correct house/route/state/country. Doves returns
`Doves Point` in both route names. Countryside returns `Countryside Lane` / `Countryside Ln`;
Highland is a neighborhood within Highland Township, without locality/postal_town.
No alternative spelling requests were needed, and no vehicle command was issued.
Raw responses remain private under `output/private/address-aliases-20261017/`.

The catalog worker completed its three-file handoff: 206 official primary
suffixes, 502 common-form rows, 554 per-primary alias claims, three explicit
compatibility aliases and 550 unique recognized spellings. Five collision groups
have explicit conservative handling. Source: [USPS Publication 28 C1](https://pe.usps.com/text/pub28/28apc_002.htm).
Primary accepted the interface and integrated it with extraction, suffix-only
comparison and bounded full/standard suffix queries. Standalone worker compile
and smoke checks passed. Clean package: 203 Java tests, zero failures/errors/skips;
frontend: 29/29. Both actual recorded responses replay against the packaged
geocoder in one original query each, preserving original text and OCR metadata;
replay issued zero external or vehicle requests.
Extraction policy is v7 and geocoding policy v5; old validated metadata must not
authorize commands under the new policy. Original text and unit values remain.

Completed read-only review (consumer: primary release): suffix/alias false positives,
ambiguous primary identities, unit-first query ordering and budget reservation,
typed township fallback and stale-cache policy. Scope is current source/tests;
no writes, providers/NAS, child agents or build commands. Selected worker pair is
the same explicitly resolved `gpt-6-luna` / `max`; release after concise handoff.

Catalog worker `/root/alias_suffix_catalog` completed naturally; accepted after
primary integration and successful compilation/catalog/extractor tests. Initial
focused run: 60 tests, 13 failures, all older geocoder assertions about exact
query lists/counts after adding the suffix form. New diagnosis and safety tests
passed. Primary retains service edits; a test-only owner is queued to update the
13 expectations without weakening rejection, unit ordering, quotas or dedupe.
No worker may run Maven; primary owns serialized test execution and release.

The test-only owner completed its handoff; primary corrected two fixture
expectations and the complete clean package passed. Independent review found a
township short-name bypass and position-blind direction normalization. Both were
fixed and closure review found no remaining blocker. Tests include the conflicting
strong-city/short-township shape, internal compass-name negatives, eight direction
aliases, typed short-name contradictions and the full six-form budget with
structured lookup and unitless fallback retaining their reserved slots.

Known boundary: the five ambiguous suffix groups retain explicit primary
distinctions. A non-primary abbreviation can match an officially listed primary
in an otherwise fully validated response, with multiple destinations rejected.
It does not launch queries choosing one ambiguous primary spelling. Arbitrary
COUNTRY SIDE/COUNTRYSIDE rewrites are not needed for the supplied case and are not
guessed; typed Google long/short names remain the source of additional name aliases.

Primary final inspection also rejected a shared-short-name bridge between
explicit PARKWAY/PARKWAYS and MEADOW/MEADOWS names. The requested suffix must
agree with the returned long suffix before a typed short name is considered.
The four shared-suffix negatives pass, and a focused independent closure review
found no remaining blocker. The final clean package again passed all 203 tests.
Mobile Chrome preflight passed using the newly built template and mocked route
APIs; it is not yet immutable-image or deployed UI evidence.

A final compatibility review found that v4 accepted exact township long/short
names even when Google omitted the optional neighborhood. The v5 fallback now
retains that shape only with a nonblank matching short name, a descriptor-bearing
same-named US township and no strong city fields. A present conflicting
neighborhood remains rejected. Regression coverage includes missing/wrong short
names, wrong township/state/country, partial and broad results and conflicting
city fields. Independent closure found no blocker; clean package passed all
204 Java tests with zero failures/errors/skips.
