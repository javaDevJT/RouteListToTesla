# Route-code filtering and native OCR capacity

User request: exclude the itinerary's `#` route-code rows, preserve real addresses and inline apartment numbers, increase the production OCR resource budget, and measure performance without reducing quality.

Base revision: `5676b7e1e2cd067cacaf4e1b3bdc976da53ecb55` on `main`. Private originals and raw benchmark evidence remain in ignored `output/private/route-code-20260930/`. No geocoding or vehicle commands are authorized for this OCR check.

## Work and evidence

- Primary owns the shared address parser, cache extraction version, focused regressions, documentation, integration checks, commit, publication, and production deployment.
- `ocr_capacity` owns resource configuration/tests and the private native benchmark adapter/evidence. It may inspect TrueNAS and run isolated, disposable OCR qualification containers; it must preserve production and unrelated workloads. Acceptance: compare old/new CPU and memory budgets with identical OCR engine inputs/settings and report timings, exact ordered output, peak memory, and cleanup.
- Worker configuration resolved September 30, 2026 from the native spawn catalog and the swarm model policy: `gpt-6-luna`, `max`. The catalog labels this the current Luna and the 5.6 Luna as older; `max` is its highest exposed effort. Provider internals and speed controls are unexposed.
- Worker runtime: `/root/ocr_capacity`. Accepted resource config/test patch and paired single-thread baseline evidence. The primary reviewed the diff, independently passed all three config checks, and took over the remaining native wrapper qualification after diagnostic adapter errors. The worker completed naturally and returned native capacity; no temporary QA app remained at handoff. The primary fixed the private overlay launch arguments before qualification. Provider internals remain unverified.
- `/root/route_code_review` completed a bounded read-only review of parser false omissions, unit preservation, callers, and image-cache invalidation using the same resolved `gpt-6-luna` / `max` pair. Accepted finding: retain hash decoration when it precedes an actual numeric street address, such as `# 23491 TEACUP CT`. The primary added that narrow exception and regressions for ASCII/fullwidth hash decoration; the reported route codes remain excluded. The worker completed naturally, releasing native capacity. Provider internals remain unverified.

## Acceptance checks

- Qualification review accepted: `/root/ocr_qualification_review` independently verified frozen expectations unchanged from base `5676b7e`, all eleven exact ordered-output matches, cropped-row review flags, and three-engine agreement on both originals. Consumer: primary release decision. Ownership excluded parser/resource edits and external actions. Live spawn catalog on September 30, 2026 had one callable Luna member, `gpt-6-luna`, with maximum supported effort `max`; requested configuration followed the centralized swarm model policy. Provider internals remain unverified. Worker completed naturally, releasing native capacity. Primary verified the report and retained its evidence privately.

- Both new HEICs contain only their visible street addresses, in image order; overlapping stops remain deduplicated.
- Route codes remain excluded even when OCR loses the `#` or confuses letters with digits. Genuine inline or wrapped unit values remain valid.
- Three engines stay enabled with the current quality gates and cleanup/deadlines.
- Native performance comparison records actual limits and results. Production retains cache storage and auth/security settings.
- Main publication, immutable image identity, production recreation, health, served release identity, and protected endpoint checks are verified separately.

## Results

The shared parser now rejects hash-prefixed metadata before scanning for digit-like house-number tokens. A standalone identifier pattern also rejects dotted/underscored codes if OCR drops the hash. Real numbered list prefixes, hash-decorated numeric street addresses, inline units, wrapped units, house-number fractions/ranges, and existing street recognition remain covered by regressions. Extraction version advances from `ocr-consensus-v4` to `ocr-consensus-v5` to prevent old image-cache readings from retaining the bad row.

Local Maven package: 135 tests passed, zero failures/errors/skips. Focused parser/OCR checks also pass after adding the observed dotted-token variant.

The baseline native Linux image passed all 26 Python checks, including timeout/descendant cleanup, and reported zero leftover OCR jobs. The local macOS process-group check encountered host `killpg` permission restrictions even outside the workspace sandbox; its short-lived test children exited, and PID checks found no survivors. Native Linux checks provide the runtime cleanup evidence.

The old immutable image reproduced the failure on both supplied HEICs. Engine readings of the code were `# B.L28.0V`, `# B.L28.OV`, and `#B.L28.OV`; the parser admitted extra `L28. OV` candidates after looking past the hash/code prefix. Each of the ten visible address rows had three-engine agreement. The first baseline extraction times were 27.730 seconds and 27.382 seconds at four CPUs / 4 GiB. Raw private evidence: `output/private/route-code-20260930/baseline-4cpu-4g-1thread-r1.runner.json`.

Resource-only comparison, two repeats per screenshot on the same native host and immutable image:

| Screenshot | 4 CPUs / 4 GiB, one thread | 8 CPUs / 8 GiB, one thread | 8 CPUs / 8 GiB, two threads |
| --- | ---: | ---: | ---: |
| IMG_0539 | 27.786 s | 27.808 s | 19.344 s |
| IMG_0540 | 27.706 s | 27.660 s | 19.066 s |

Resource limits alone produced only noise-level timing differences. The qualified two-thread setting reduced mean application-path extraction time by about 31%. Peak candidate memory was 1.25 GiB, with no OOM events. All three engines remain enabled, Torch interop remains at one thread, and input dimensions, quality gates, one-job admission, deadlines, and the secure subprocess environment allowlist are unchanged.

Candidate qualification used the production OCR wrapper, a read-only mounted candidate Python script, and current parser/service classes on native TrueNAS amd64. Both originals yielded five exact ordered addresses with three-engine agreement, six unique addresses across the overlapping pair, no route-code candidates, and zero leftover jobs. The full frozen nine-case suite plus both originals passed all eleven exact ordered-output expectations without case errors. Evidence: `output/private/route-code-20260930/qualified-2thread-8cpu-8g-{r1,full}.runner.json`. Earlier `PYTHONPATH` and temporary-executable diagnostics were unsuccessful qualification attempts and are excluded from speed claims.

The cropped-top case also retained its review booleans. The benchmark does not expose warning strings or per-engine completion for empty negative cases, so these results do not qualify warning text or measure each engine separately on those cases. The strict application protocol still requires all three engine names; engine dispatch logic is unchanged.

The release gate also validates the published immutable image without parser or thread overlays before production deployment. Registry, runtime, protected-endpoint, and browser proof is retained privately under `output/private/route-code-20260930/`; saved route/cache storage is preserved. OCR qualification does not call Google geocoding or send vehicle commands.
