# October 17 screenshot parsing, address recovery and performance

Scope: fix complex-name/city joining in IMG_0625.heic, reject Expected-by UI text
in IMG_0624.heic, resolve usable street destinations before route sending, and
qualify processing speed improvements. Commit and push main, pass CI, deploy an
immutable image and verify its runtime/UI. Preserve three local OCR engines,
unit-first geocoding, street validation, ordering/overlap dedupe, TAP/CSRF,
retained data and command safeguards. No vehicle commands.

Baseline: main 6de0b1e, advanced by fast-forward; unrelated untracked AGENTS.md
is preserved. Originals are read-only; temporary PNG previews were made with
macOS image conversion. IMG_0625 shows Fordline St APT 201, Jamestown Village
(complex), Allen Park (city). IMG_0624 shows five street rows and Expected-by
times/route codes that must never become destinations.

Primary owns controller/frontend integration, native qualification, operational
evidence, docs, commits/push and deployment. Parser worker owns AddressExtractor,
AddressOcrService and their Java tests, including extraction-version invalidation.
Geocoding worker owns GeocodingClient and tests. Performance investigator owns
read-only OCR/concurrency profiling recommendations; no shared writes. Maven
target writers are serialized by the primary.

Worker configuration resolved 2026-10-17 against the native spawn catalog: its
single callable Luna is gpt-6-luna, with maximum effort max, as required by
swarm-driven-development/references/model-config.md. Spawn fields are explicit;
backend model/provider internals are not separately exposed.

Acceptance: original HEICs and existing fixtures parse in exact order with no
time/code/complex-as-city rows; bounded address permutations retain the most
specific validated street match; provider errors do not trigger blind query
permutations; unresolved stops are visible before route send. Speed changes
require native comparison without quality loss or relaxed engine consensus.

## Implementation and qualification ledger

Parser worker completed its four-file slice; primary focused checks passed.
Performance investigation ruled out unbounded upload parallelism: the old
server rejected a second OCR job. Its owner was explicitly reassigned the
frontend and JS tests to implement an ordered two-worker pool and early location
resolution. The parser owner supplied a two-slot prototype and a deterministic
third-job rejection test. Primary owns the test-only counterbalanced benchmark
runner and controller endpoint. Maven target writes remain serialized.

Independent reviewer `/root/oct17_review` uses the same resolved Luna/max policy,
with read-only ownership of integration/security acceptance. Its consumer is the
primary's commit/deploy decision; no external actions or shared build writes.

The deployed image reproduced the complex-name bug on the supplied HEIC: it
retained the complex as locality and dropped the actual city. The other HEIC
returned the five correct addresses in that baseline run; schedule/code
regressions cover the intermittent false positive. Combined baseline extraction
time was 35.870 seconds, with three engines and a 1,530,634,240-byte memory peak.
Private evidence: `output/private/address-recovery-20261017/baseline.runner.json`.

Primary's staged clean package passed 162 Java tests. The automatic approval
review initially rejected a live Google test because an earlier approval covered
a different address. The user then explicitly approved this screenshot's address;
the bounded production-configured Java-client test resolved it successfully,
without vehicle commands or credentials in arguments/files/logs. Evidence:
`output/private/address-recovery-20261017/google-recovery-verification.json`.

The independent review found no blocking integration/security issue. Its cache
observation is addressed at the controller boundary: unresolved image rows are
not stored as a completed image result; saved OCR remains available for the next
lookup attempt. The success-only cache rule has a focused recovery regression.

Five counterbalanced paired trials on native AMD, eight CPUs/8 GiB, measured
serial pair times of 35.042, 34.676, 34.990, 35.511 and 35.491 seconds, versus
22.160, 22.020, 23.047, 21.951 and 22.896 seconds with two image jobs. Medians
were 35.042 and 22.160 seconds: **36.76% less OCR wall time** for this pair.
Every trial retained exact ordered text, agreement and review flags. Peak memory
was 2,679,484,416 bytes, with zero OOM/limit events and zero leftover OCR jobs.
The disposable app and inputs were removed. This qualifies the two-image limit
on this workload, not an unrestricted upload pool or a general latency promise.
The prototype used the existing immutable runtime with candidate parser classes
overlaid; final immutable-image qualification remains a release prerequisite.
Evidence: `prototype-speed.runner.json` under the private directory above.

The full native source preview passed 13/13 ordered-exact screenshots (including
both new HEICs) and 36 Python checks, with all three OCR engines. The final clean
package passed 167 Java tests. Mobile Chrome verification caught a background
lookup replacing another row's active textarea; the follow-up updates status and
retry controls in place. Its regression and all 23 frontend tests pass. The real
browser preview verifies overlapping-image dedupe, cached upload recovery,
apartment preservation, unresolved/pending send blocking, location retry, and
preservation of a different row's draft, focus and selection. API responses in
that browser check are mocked; it sends no Google or vehicle requests.

Source commit `0d3ed90` contains the parser/geocoding/concurrency work; the focus
correction follows it before deployment. CI image publication, security gates,
final immutable-image OCR qualification and production verification remain
required. The current build's cache import is missing; the preceding successful
image build spent about 44 minutes compiling OpenCV. The runner coordinator
confirmed ordinary releases should reuse those layers, but has not established
why this import was unavailable. Shared infrastructure and gates are unchanged.
