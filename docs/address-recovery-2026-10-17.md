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
overlaid; final immutable-image qualification is recorded below.
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

Source commit `0d3ed90` contains the parser/geocoding/concurrency work; `fcf21f5`
adds the focus correction. The final release below binds that corrected source
to CI, security gates, native OCR and production verification. The preceding
successful image build imported no cache and spent about
44 minutes compiling OpenCV, then completed a mode=max cache export. The runner
coordinator confirmed ordinary releases should reuse those layers. Client export
does not establish controller acceptance or future cache availability. Shared
infrastructure and gates are unchanged.

Run `39046684715` (`0d3ed90`) completed its publish job successfully. Its log
confirmed `/ci-cache-import/missing`, zero cached steps, OpenCV compilation of
2,121.9 seconds, and a completed mode=max export of 114.4 seconds. This is evidence
of a client export, not controller acceptance. The corrected source is `fcf21f5`,
run `39048490852`; only that corrected image is eligible for this deployment.

Independent operational review found a missing explicit comparison between the
selected deployment digest and CI's scanned digest; the private deployment
adapter now requires equality before credentials or mutation. QA cleanup checks
the sole task-owned service, non-root/read-only/network-disabled configuration,
no added capabilities/configs/devices/ports, and exact read-only temporary mounts.
An isolated guard check accepts both sanctioned layouts and rejects eight unsafe
variants. No production mutation occurred during these checks.

The corrected push's first attempt stalled after concurrency handoff: its job
was queued while the workflow was pending. The coordinator observed zero workers,
zero reserved storage/memory and a registered 40 GiB pool reporting fresh zero
demand, ruling out ordinary capacity contention. Cancellation exposed another
stalled queued cleanup job. No jobs executed and the artifact inventory was
empty; forced cancellation completed that cancellation, then the same push run
was retried exactly once. Attempt 2, job `117450159762`, began building at
16:52:59 UTC. No workflow, cache, admission, resource or infrastructure setting
was changed for this recovery.

The coordinator reproduced a separate omission of pending workflow runs in the
installed scanner's source lineage. Its isolated fix passed three regressions
and the existing WorkflowDiscovery suite, and was handed to the infrastructure
owner for review without requesting live changes. It is not deployed and does
not fully explain the queued-state stalls. Cache acceptance/rejection/pruning
cause also remains unproved. These infrastructure findings do not waive any
application release gate.

## Verified release

Source `fcf21f50fe9c9a44e38271576ab323cf63ba548e` passed
[CI run 39048490852, attempt 2](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39048490852/attempts/2),
including artifact cleanup. The retained report binds the revision and image to
zero High/Critical findings; storage and publication gates passed. The build
step took 41 minutes 14 seconds and the scan took 4 minutes 43 seconds.

The immutable image is
`ghcr.io/javadevjt/routelisttotesla@sha256:cbb7cf3d6c1cb26645397c39dd99176d56e498fd2d1944461abbb2e9ed0efe56`.
Its native run used no parser overlays and passed 13/13 ordered-exact screenshots
and 36 Python checks with all three engines. The final paired comparison took
35.426 seconds sequentially and 22.616 seconds with two image jobs, preserving
text, agreement and review decisions. Peak memory was 2,720,223,232 bytes, all
memory-limit/OOM counters were zero, and no OCR jobs remained. The disposable app
and input files were removed. Native report SHA-256:
`a0d287ca2ba3f7872b1d5ebee034515a5b04fe7ef9ad34ec1a6fb8dad1e558c9`.

TrueNAS job **18850** deployed release **20261017-address-recovery** successfully
at [TeslaRouter](https://teslarouter.javadevjt.tech). Direct configuration and
workload checks confirmed the selected digest, eight CPUs/8 GiB, UID 10001,
read-only root, no host ports, and unchanged retained volume mounts. Only the two
service-image fields and `APP_RELEASE` changed. The configured `/login` health
probe returned HTTP 200; TrueNAS did not expose a separate container-health
field. TAP redirect and signed-out endpoint/CSRF protections passed, including
the new `/route/resolve` endpoint.

Mobile Chrome at 390×844 verified the public release identity and exercised the
exact template extracted from this immutable image (SHA-256
`a878dce7d29a365ae1a1b3cdf501a7e8778fc70209303b8a4af78061e966ed74`).
Upload retry/cache reuse, screenshot-seam dedupe, apartment retention, unresolved
send blocking, location retry, and draft/focus/selection preservation passed
without page errors. These interaction APIs were mocked; the approved single
Fordline lookup separately tested the real Google client. No vehicle commands
were issued. The public deployment remains available until a later update; this
was desktop mobile-viewport verification, not physical iPhone testing.

Final evidence under `output/private/address-recovery-20261017/` includes
`ci-verification.json`, `registry-verification.json`, `ui-image-verification.json`,
`native.runner.json`, `native-completion.json`, `deployment.json`,
`production-verification.json` and `browser-verification.json`.
