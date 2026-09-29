# Security remediation and OCR release — September 26, 2026

Status: deployed and verified on September 27, 2026. TrueNAS job **207112** succeeded; the final image is
`ghcr.io/javadevjt/routelisttotesla@sha256:9033ec29d0e58eee6323eda8945813480d9daf83076c609b6d734ad2552ab9fb`.
The unchanged OCR implementation passed 56 screenshots and 157 complete ordered addresses, with zero
false positives, false negatives, or runtime errors.
The user authorized security repairs, OCR improvement with generated screenshots, and TrueNAS deployment.
Base revision: `56bb3ccf701d0541e8ca4d708c2441c7223a4156`; existing dirty modernization work is preserved.

## Requirements and boundaries

- The original apartment is **330**. The earlier 350 interpretation and labels were wrong. Corrected truth
  and independent transcription are in `output/ocr-benchmark/expected-addresses.json` and
  `original-ground-truth-review.json`. Old reports using 350 are not current accuracy evidence.
- Run Tesseract, PaddleOCR through RapidOCR, and EasyOCR locally. No OpenAI key, hosted OCR, or runtime
  model downloads. Preserve literal disagreements for review.
- Preserve address order, repeated stops, saved routes, credentials, volumes, and eight-waypoint batches.
  Users can edit extracted rows; no address creation from scratch is offered.
- Verification must not send vehicle commands, wake vehicles, change billing, or spend geocoding quota.

## Security repairs

- TAP subject remains the data owner. Browser and background authority are also bound to the establishing
  grant fingerprint. Later logins cannot lend replacement grants to older sessions. Dispatch, client
  replacement, and removal share a lock; old logout cannot remove a newer grant.
- Vehicle authorization precedes navigation geocoding. Per-owner/global budgets and concurrency limits
  preserve pacing. OCR cache keys are owner-scoped with no shared legacy fallback.
- Route endpoints require authentication and current TAP entitlement; mutations require CSRF. Public
  login/callback allowances are narrow. Login redirects are relative.
- JSON bodies are limited to 256 KiB. Responses add CSP nonces, Referrer-Policy, and Permissions-Policy.
- The final JAR includes Boot 3.5.16, Tomcat 10.1.60, and Jackson 2.21.7.
- The prepared release joins the existing NPM network, removes host port publication, runs as UID/GID
  10001 with a read-only root filesystem, drops capabilities, and enables no-new-privileges. An isolated
  initializer changes only existing cache ownership without following symlinks.

Source fixes have regression coverage and are present in the verified deployed image. Public boundary checks pass.

## OCR and parser behavior

All models and packages are installed and verified during the image build. One OCR job is admitted at a
time, with a 45-second timeout, bounded output, and descendant-process cleanup on completion or failure.

Each distinct engine has one vote. Missing detections abstain. Whole-line majority is preferred, followed
by token alignment. Typography and observed spacing can normalize; digits and letters are not substituted.
Unresolved readings and omitted fragment evidence require confirmation. Manual and automatic dispatch
both reject outstanding review flags before geocoding.

Small overlapping fragments remain review evidence, including possible units such as `3B`. They can
conservatively lower the displayed minimum per-line support even when the street itself has unanimous
agreement. Fully clipped text cannot be reconstructed; screenshots should overlap and show full rows.

The shared parser preserves wrapped street/unit/locality lines, fractional and hyphenated house numbers,
numbered roads, apostrophes, and duplicate order. BUILDING/BLDG, FLOOR, ROOM/RM, and DEPT join the apartment
and suite labels. Locker/door/section/bay labels are not interpreted as house-number prefixes.

For screenshots narrower than 900 pixels, Tesseract alone receives a 1.5× LANCZOS image; its boxes map back
to original coordinates. PaddleOCR and EasyOCR still read the original image. The offline experiment
recovered all three tiny dark-text addresses and cities, while enlarging EasyOCR did not help and cost
more memory/time. See `output/ocr-benchmark/preprocessing-experiment.md`.

## Acceptance harness and evidence

The harness executes the actual packaged Java service under Linux/amd64, offline, nonroot, read-only,
with two CPUs and 2 GiB RAM. It resolves the image ID before execution and records that immutable identity.
Strict acceptance is the default: exact ordered rows, no runtime errors, consensus metadata, observed
evidence from all three engines across positive cases, expected review behavior, and zero unit/duplicate
FP or FN. A diagnostic mode is explicit. `self_check.py` tests the gate itself.

Every set used for tuning is development data, even if originally held out. Frozen fixtures are not
regenerated to make tests pass. Historical results and raw runner files are retained.

| Candidate | Original | Training | Development 1 | Development 2 | Qualification |
| --- | --- | --- | --- | --- | --- |
| v7 | 28/28 | 37/37 | 29/29 | Initially 23 TP / 5 FP / 4 FN | Not run |
| v8 | 28/28 | 37/37 | 29/29 | 27/27 | 16 TP / 4 FP / 4 FN |
| v9 | Runtime/resource failures during four concurrent suites; not accepted | Not accepted | Not accepted | Not accepted | Locality overlap defect |
| v10 | 28/28 | 37/37 | 29/29 | 27 TP / 1 FP / 0 FN | 20/20; untouched release qualification 16/16 |
| v11 | 28/28 | 37/37 | 29/29 | 27/27 | 20/20; release qualification 16/16 |

Development 2 originally included three incorrect comma expectations: the images show a space between
city and state. Independent visual inspection and renderer review confirmed the corrected labels; images
were unchanged. V8's original run used an incorrect truth-key argument; the saved successful runner output
was rescored with the existing filename-to-case mapping, without changing OCR output or rerunning it.

V8 passed all 42 prior regression screenshots and 121 rows but failed tiny dark text and a BUILDING line
on the fresh eight-case qualification set. V7 and v8 were published for preparation but never deployed.
V9 fixed transcription but exposed a Java locality-attachment defect for detector boxes overlapping by
3–5 pixels. Four simultaneous test containers also caused timeouts and exit-137 failures. Those reports are
retained as failures. V10 bounds allowed adjacency overlap to 30% of the shorter line height, with an exact
geometry regression, and runs at most two OCR containers concurrently. Timeout and memory gates are unchanged.
A new independent six-case, 16-row release qualification set passed on its first run against v10; its author
visually checked every label and image without running OCR or inspecting candidate output.

V10's remaining regression was a clipped line read as `yp cmon ooo. ooo eso`. A legacy house-number
pattern accepted all-letter lookalikes as a number, producing `OOO. OOO ESO`. V11 requires an observed
digit in the shared house-number pattern; it does not substitute characters. The regression also checks
that the synthetic example `5 OLD MILL ROAD` is accepted and a mixed ambiguous number
such as `24O11` remains literal.

Current checks: 110 Java tests, zero failures/errors/skips; 18 Python consensus tests; 6 Node UI/config
checks; benchmark-gate self-check; whitespace check. Packaged startup verifies TAP login, PKCE S256,
anonymous protected-route rejection, and CSRF with disposable storage.

V11 local image ID: `sha256:0f7f289b992edf4e02a55f9a7ba56c7a37fd2c5c7eced351e5083940f82a6b94`.
JAR SHA-256: `1769c18660f70719dacc56695fd300224c2260d82258fa0b9c65858305f75253`.
OCR wrapper SHA-256: `7ec8b6c21d92eb13d72b3575e2364d5f786a5ab5e5012ea4a2ac0a619254da9c`.
Container hashes match the source artifacts. The final JAR has zero matches against known configured
credential values and no private config or screenshot entries.
The fresh Gitleaks scan found no Git-history findings and only two deterministic dummy keys in test fixtures.

Every v11 report in `output/ocr-benchmark/results/final-*-v11.json` binds to that immutable local image ID.
All 56 exact ordered cases passed; unit and repeated-stop metrics have zero false positives/negatives.
On the emulated development host, mean time was 26.10 seconds/image, p95 36.95 seconds, maximum 40.10 seconds,
and maximum measured container memory was 1,695,985,664 bytes under the unchanged 2 GiB limit.
123/157 candidates conservatively require review, often because of nearby small fragments or badges even
when all three engines agree on the street. This is a finite regression result, not a general accuracy guarantee.

## Servlet redirect correction after live verification

The first deployed v11 probe exposed a gap in mocked security coverage: native Tomcat converted the
relative login redirect into an absolute URL using `X-Forwarded-Host`. A new real-server regression in
`output/playwright/verify-packaged-startup.py` failed against v11 with an `audit.invalid` destination.
V12 enables `server.tomcat.use-relative-redirects`; the same real-server spoofing checks then pass.
All 110 Java tests pass again. Uncompressed JAR comparison proves the only changed entry is
`BOOT-INF/classes/application.yml`; all compiled classes, libraries, and templates are identical.
Docker layer comparison proves only the final JAR layer changed. The v11 OCR evidence therefore covers
the unchanged OCR implementation in v12; the final production upload verifies that image's runtime path.
V12 local image ID: `sha256:e50cec249c1f29e35ba7a66e102a8b8ec4e8c8454ef3c2a007fd7db140e3b87e`.
V12 JAR SHA-256: `dca55dcd0955a7d15d10449b584d0ad6785ea989b5d3ddf4d7b0117dfad50cd7`.

## Deployment verification — September 27

Release: `20260926-security-ocr-consensus`; final candidate v13; TrueNAS job **207112** succeeded.
Final registry/runtime image:
`ghcr.io/javadevjt/routelisttotesla@sha256:9033ec29d0e58eee6323eda8945813480d9daf83076c609b6d734ad2552ab9fb`.
Final JAR SHA-256: `1a2fce733fc72f36858b53ee7fdbca53d132191a4b93ecf504a5a996dd24b5b4`.

V13 additionally corrects two upload-disclosure paragraphs to name all three engines and recommend Safari.
Compared with benchmarked v11, only `application.yml` and `templates/index.html` differ inside the JAR.
Every compiled class and dependency remains byte-identical. See
`output/ocr-benchmark/results/v13-equivalence.json` and `accepted-v11.json`.
The final clean package passes 110 Java tests, six Node checks, and real-server TAP/CSRF/spoofed-redirect checks.

The exact live configuration readback matches the scoped release transform. The router is running as
10001:10001 with a read-only root, dropped capabilities, no-new-privileges, bounded tmpfs, two CPUs and 2 GiB.
The existing `ix-routelisttotesla_route-data:/app/cache` volume is preserved. The isolated ownership
initializer exited; restart policy remains `unless-stopped`. Runtime host ports are empty and a direct
connection to 192.168.0.2:10088 was refused.

NPM host **23** now forwards only TeslaRouter to **teslarouter:10088** on the existing
`ix-nginx-proxy-manager_default` network. TrueNAS's app network listing excludes external networks:
the first v11 verification assertion incorrectly assumed otherwise. It was corrected; exact Compose
readback, saved NPM upstream, and the public served release verify the actual path. No unrelated proxy changed.

The final 27 public probes show HTTPS login 200 with the expected release, HTTP-to-HTTPS 301, authenticated
boundaries on route/session/vehicle/internal paths, TAP API 401 without a token, and relative `/login`
redirects under all four spoof-header probes. CSP nonces, HSTS, nosniff, frame denial, no-referrer,
Permissions-Policy, and Secure/HttpOnly/SameSite cookies were observed. A harmless authenticated POST without
CSRF returned 403. No sensitive response markers were found.

Fresh TAP consent/callback after the final restart succeeded and exposed three authorized vehicles.
At a 390x844 desktop Chromium viewport, a **verified server cache miss** on
`release-qualification-ui-only-negative.png` invoked the final deployed OCR and returned HTTP 200,
zero candidates, in **25.552 seconds**. Saved-session response fingerprints before/after were identical.
No vehicle commands, wakes, billing changes, or geocoding requests occurred. Test-only browser interceptors
were removed; the browser was reloaded for normal use.
See `output/playwright/consensus-negative-production-mobile.png`.
Sanitized runtime, public-probe, and browser evidence is saved in
`output/security-ocr-final-verification.json`.

Original rollback baseline is preserved as
`sha256:023375e4c640da1689a1982e91347c7d6b4f0828fee2d3275263ec5faca8fccd`.
Physical iPhone/car acceptance remains untested. The local TrueNAS management connection retains the
explicitly approved self-signed TLS exception; public HTTPS is separate.

## Ownership and lifecycle

September 27 continuation: primary owns candidate v10 build, all OCR runs, deployment, and final evidence. A bounded release-readiness reviewer will inspect only deployment preservation and smoke-test boundaries; it may update only its review artifact. Selected worker configuration remains `gpt-6-luna` / `max`, verified against the current native catalog (which identifies GPT-6 as the current Luna family and GPT-5.6 as older). No provider/speed selector is exposed. Baseline revision: `56bb3ccf701d0541e8ca4d708c2441c7223a4156`. Acceptance: identify any concrete deployment hazard before primary publishes; native completion releases the worker.

Workers use `gpt-6-luna` / `max`, resolved from the live native catalog and centralized swarm policy on
September 26. Provider/speed controls are not exposed. Native completion releases capacity. Primary owns
integration, final acceptance, infrastructure changes, and this record.

| Worker scope | Evidence and downstream use | Disposition |
| --- | --- | --- |
| Auth/security | Grant binding, synchronized dispatch, endpoint/concurrency tests | Reviewed, integrated, completed |
| Data | Authorization before geocoding, budgets, owner cache | Reviewed, integrated, completed |
| Client | Literal readings, confirmation/editing, send blocking, UI tests | Reviewed, integrated, completed |
| Infrastructure/integration | Scoped release transform and config-preservation tests | Reviewed, 3 Node checks, completed |
| OCR/parser | Strict contract, process bounds, pinned models, parser regressions | Reviewed, integrated, completed |
| Independent geometry review | Found unsafe fragment pruning and widened row tolerance | Corrected and tested, completed |
| Benchmark gate review | Strict defaults, review/unit/repeat checks, observed engine evidence, image binding | Primary reviewed and reran self-check, completed |
| Harness | Frozen fixtures and visually verified labels; fresh qualification sets | Artifacts accepted, completed |
| Preprocessing investigator | Tesseract-only upscale experiment, actual mixed-input consensus proof | Evidence reviewed, integrated, completed |
| `/root/release_readiness` | Read-only config/data preservation review; 3 Node checks and syntax checks; primary added runtime port assertion; external network-list limitation resolved by config and public-path verification | Accepted, completed; native capacity released |

The preprocessing worker changed only experiment artifacts and removed its disposable containers. Harness
workers changed only their assigned fixtures/truth/manifests. Neither changed production or contacted
external services. Prior handoffs are integrated or superseded; no unnecessary live worker is retained.

See the [baseline audit](security-audit-2026-09-26.md), [benchmark guide](../output/ocr-benchmark/README.md),
and [iPhone setup](ios-setup.md).
