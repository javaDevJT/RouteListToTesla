# TAP design and navigation integration

## Scope and ownership

TeslaRouter adopts TAP's existing warm surfaces, orange accents, dark sidebar,
IBM Plex Sans body text, Space Grotesk headings, and IBM Plex Mono labels.
Its workflow, authentication, OCR, saved routes, and vehicle safeguards remain
the existing implementation. Navigation links return to TAP's dashboard and
`/dashboard/tools`. TAP owns the Other Tools page and both navigation menus.

- Primary: TeslaRouter templates, shared stylesheet, browser checks, release.
- Existing TAP task `019ceda5-f4ca-7e31-96bf-2696d5835ebc`: TAP implementation,
  frontend release, and deployment evidence. No TeslaRouter writes.
- Asset worker: only bundled font assets, exact public GET asset matching,
  and focused security checks. No templates or deployment changes.

Worker configuration resolved 2026-09-27 from the callable collaboration
catalog and swarm model policy: `gpt-6-luna`, reasoning effort `max`.
Asset worker starts from dirty working tree on base commit
`56bb3ccf701d0541e8ca4d708c2441c7223a4156`; unrelated changes are preserved.
Release after handing off asset sources/licenses, security checks, and risks.

## Acceptance checks

- Main and login pages render with the same tokens and typography as TAP.
- Desktop and narrow mobile layouts have no clipped controls or horizontal overflow.
- TAP Other Tools reaches TeslaRouter; TeslaRouter links return to TAP.
- Existing review, upload ordering, CSRF, and command holds remain intact.
- Only explicit static CSS/font GET requests join public login/auth routes.
- Build identity, actual TrueNAS runtime, and public served bytes agree for both apps.
- Verification does not send vehicle commands or change billing or saved routes.

## Implementation and checks

The two Thymeleaf templates share `static/css/tap-theme.css`. The theme reuses
TAP's canonical color and font tokens, sidebar, surfaces, controls, and narrow
screen menu. Keyboard users can skip to the planner and close the menu with
Escape, which restores focus. Font files are self-hosted; CSP requires no
external font provider. IBM Plex Sans covers 400–700, Space Grotesk 600–700,
and IBM Plex Mono 500. SIL OFL licenses accompany the three font files.

The asset worker `/root/tap_style_assets` completed and its changes were
accepted after primary review and the full package build. It used the requested
`gpt-6-luna` / `max` dispatch configuration; backend execution settings are
not independently exposed. Native completion released its worker capacity.

- Java 21 clean package: **112 tests passed**, zero failures/errors.
- JavaScript OCR-review and deployment-config checks: **6 passed**.
- Packaged startup: public login 200, PKCE S256, anonymous vehicle access 302,
  missing-CSRF POST 403, and disposable storage removed after the check.
- Browser preview: **320, 390, 844, and 1440 px** widths, no horizontal overflow;
  all three fonts loaded; desktop/mobile menu visibility and Escape/focus passed.
- Synthetic address review: nine stops remain two groups (eight plus one);
  a long address and OCR alternatives fit the mobile layout.
- Public verification: login marker, CSS, and all three font files match the
  deployed source. Vehicle/session data and unknown font paths redirect to
  login; POST to the theme without CSRF returns 403.

Runnable checks: `output/playwright/preview-tap-design.mjs`,
`output/playwright/verify-tap-design.js`,
`output/playwright/verify-packaged-startup.py`, and
`output/verify-tap-design-release.py`. Run the preview server with Node,
then pass the verify script's function to Playwright CLI `run-code`.
The local preview accepts only GET requests and serves synthetic vehicle data.

Cloudflare rejected Python's default user-agent with error 1010; the release
probe uses a descriptive browser user-agent. The public browser check is
independent of that command-line probe.

## TeslaRouter release

- Release: `20260927-tap-design`; TrueNAS job **207179**, succeeded.
- Immutable image:
  `ghcr.io/javadevjt/routelisttotesla@sha256:020b763bde0320a86c2ec5222a63a74f31591dbda9cb028340c4ef838040d4fd`.
- Packaged JAR SHA-256:
  `06d3824bff3c9514376dbe32ee45689e1424564bd934b5705e0a6ece4e560969`.
- Public CSS SHA-256:
  `4229e5c28ebed288295b3b134f5b11fd15a6acc1edc5e7a90be53910457b290e`.
- Runtime readback matches the immutable image, retains the existing
  `ix-routelisttotesla_route-data` volume, and publishes no host port.

Screenshots and local visual evidence are in `output/playwright/tap-design-*`.
The OCR engines/models were not changed or re-benchmarked for this UI release.
Physical iPhone and vehicle-command tests are outside these browser checks.

## TAP release and cross-app verification

TAP's `20260927-other-tools-mobile` release completed in TrueNAS job **207185**.
Its immutable image is
`ghcr.io/javadevjt/tapv2-frontend@sha256:000e44d6674598b0e5144a68079f64ee2170f06251c13b9d4665e60a84c5bf95`.
The running image ID is
`sha256:68b356550d309a8356a13a566016cea2ec4efd28c8af75bc1a77823c8c49c669`,
and public build ID is `ojlcwRCICxn4R8aNE2LTc`. The TAP agent confirmed served
navigation JavaScript/CSS equal the package bytes and the unchanged backend is
healthy. Evidence is in TAP's `output/tools-navigation-verification.json` and
`docs/ui/2026-09-27-other-tools.md`.
The existing TAP task's implementation and deployment handoff was accepted;
it remains a user-owned task and was not archived by this integration.

The first Other Tools build passed **300 Chromium/Firefox checks**. A visual
review then found an inherited mobile link contrast problem; the two CSS
declarations were corrected, **10 focused navigation checks** rerun, and the
final release passed **8 public smoke tests**, TypeScript, and the production build.

Primary live verification completed TAP consent and returned to TeslaRouter's
new release with three authorized vehicles. A transient 502 overlapped TAP's
normal redeployment window; retry after job completion succeeded. No vehicle,
billing, saved-route, or geocoding mutation was part of verification.

The real mobile round-trip also passed: TeslaRouter **Menu → Other Tools**
reached TAP; **Open TeslaRouter** opened the authenticated route planner in a
new tab with `noopener noreferrer`. The returned page reported the expected
release, loaded all three fonts, and had scroll width equal to its 390 px
viewport. Desktop and mobile production screenshots were inspected. Consolidated
evidence is in `output/tap-design-release-verification.json`; the temporary
preview server and its browser session were stopped. The existing signed-in
browser remains on the deployed TeslaRouter page.

## Follow-up: screenshot-reading 503

### Confirmed reproduction and correction

The genuine decoded pixels from the first uploaded image killed the old OCR child with exit 137 in
13.309 seconds under 2 CPU / 2 GiB. Cgroup `oom` and `oom_kill` were both 1;
peak memory reached 2,147,483,648 bytes. This reproduces a 503-producing OCR
failure consistent with the reported timing. Production incident logs were not
available, so this is reproduction evidence rather than a recovered incident log.

EasyOCR now bounds its detector to a 1280-pixel longest side and an 800,000-pixel
working area; the other engines, three-engine requirement, resource limits,
and 45-second timeout are unchanged. The shared address parser also rejects
detached AM/PM clock fragments while retaining synthetic street-name examples such as `10 PM ROAD`.
Cache extraction version is `ocr-consensus-v2`; saved routes are preserved.

The final packaged image, with no source override and no network, extracted the
exact four visible address/city rows from each screenshot in order. Every row
had agreement from all three engines, with no review flags or extra candidates.
Times were 33.163 and 32.087 seconds; peak memory was 1,507,422,208 bytes and all
OOM counters stayed zero. This was Linux/amd64 emulation on an arm64 development
host, not a native TrueNAS timing measurement. Inputs were faithfully decoded
locally with libheif; direct raw HEIC upload is not covered by these PNG tests.

The worker's result was accepted after primary comparison against both visible
screenshots. Worker native completion released its capacity. The private raw
reports remain under `/private/tmp/ocr-503-exact/reports/`; only aggregate
evidence is kept here. No newly supplied addresses were sent to Google.

Source identity: JAR SHA-256
`825b5d117bf80fc552fdb70315b32f7e973f827dcda5b5393586525dc957d5af`;
OCR wrapper SHA-256
`8f9ee46181f28f564f3ddbd406507323ad0f60b43714617a005d9b659d6b7042`.
Java 21 clean package passed 113 tests; Python consensus checks passed 19;
browser/deployment helper checks passed 7. Expanded strict final-image
qualification passed **9/9 cases**, **20 correct rows**, **0 false positives**,
**0 missed rows**, all unit and duplicate checks, and three no-address cases.
Per-case times were 21.898–31.619 seconds, with peak memory 1,529,184,256 bytes.
Tall and square screenshots are now permanent regressions. Full results:
`output/ocr-benchmark/results/20260927-ocr-memory-release.json`.

### Corrective release

The correction was published and deployed as `20260927-ocr-memory`, TrueNAS
job **208933**, using immutable image
`ghcr.io/javadevjt/routelisttotesla@sha256:a9ad996fe2c172bae6d02697f4b17cdd5ac26c24567aba489a792edc5cc06fcf`.
Runtime readback matches that image, retains the route-data volume, and publishes
no host ports. Public login, CSS/font byte equality, anonymous data-access
rejection, and CSRF rejection passed. Fresh TAP consent returned to the release
and loaded three authorized vehicles. See `output/ocr-memory-deployment.log`.

A public authenticated upload of two uncached no-address images (1260 × 2736
and 1600 × 1600) returned **HTTP 200 in 65.487 seconds**. It exercised both
three-engine jobs in the full production application, produced zero candidates,
and preserved the saved-session fingerprint from before deployment. The served
upload function includes the JSON error-message fix. Test file selections were
cleared afterward. No geocoding of private addresses, route save, or vehicle
command occurred. Consolidated evidence:
`output/ocr-memory-release-verification.json`. TAP's deployment hold was released.

### Initial investigation record

Update: both original HEIC uploads are now available locally.
Both report 1260 × 2736 HEIF/HEVC Main 10 metadata. macOS exports failed or
produced black images, including the explicit sRGB conversion. These exports
were discarded as invalid reproductions. The installed libheif decoder then
produced faithful PNGs, verified by pixel ranges and visual inspection.
The same worker resumed local reproduction under the production OCR limits.
No addresses from these files have been sent to Google.

The user reported an HTTP 503 while reading screenshots on September 27.
Primary owns live runtime diagnosis, reproduction, and any correction/release.
A bounded read-only OCR worker investigates the subprocess timeout/failure path;
it owns no files and must preserve three-engine consensus and fail-closed behavior.
TAP's existing task checks provider health only; no concurrent TAP deployment.
The native worker uses the callable catalog's `gpt-6-luna` / `max` configuration.
The user reported two images and approximately ten seconds before failure.
At the initial investigation stage the cause was not yet reproduced. Runtime/public assets and fresh
TAP consent were healthy. A new uncached no-address upload completed all three
engines in **25.494 seconds**, HTTP 200. After explicit user approval for one
full screenshot/geocoding test, one original supplied PNG
completed in **29.354 seconds**, HTTP 200, with **four candidates/four resolved**.
No route was saved or sent to a vehicle. The subsequently supplied HEIC files
are now being tested locally using the faithful decoded pixels described above.

The OCR worker completed read-only review and was released. It confirmed that
process failure/timeout and geocoding failures can map to 503; it did not
establish either as the reported cause. No timeout, resource, consensus, or
authorization limit was changed speculatively.

One concrete UI defect was corrected: upload failures discarded the JSON error
reason and displayed only the HTTP status. Upload now shows the server's safe
`error` message, with the original HTTP fallback for non-JSON responses. Its
regression check verifies both forms and preserves selected images for retry.

## Automatic iPhone HEIC upload release (2026-09-27)

This supersedes the raw-HEIC limitation in the preceding memory-correction
release. `20260927-heic` is deployed by TrueNAS job **209049** using immutable image
`sha256:191f711504cbb02290aa666493f6bfacb54f6a76a576b3059b6982e226ef414c`. The router is running,
the existing route-data volume is mounted at `/app/cache`, and no host port is
published.

HEIC/HEIF is recognized from binary file-type brands rather than filename/MIME.
Pillow-HEIF 1.8.0 decodes the primary image locally before all three OCR engines
run. The temporary PNG has orientation applied and metadata removed; Java owns
and cleans the private job directory even after subprocess failure. Limits remain
12 MiB, 20 megapixels, and 45 seconds per OCR process. Decode failures return
HTTP 400 before OCR model loading. Child process environments now allowlist
runtime settings and exclude application credentials; this is not a separate
OS sandbox. The extraction cache version is `ocr-consensus-v3`.

Final-image verification used both untouched original HEIC uploads
through `AddressOcrService`, with networking disabled and 2 CPU / 2 GiB limits.
Each returned four exact ordered rows, preserving repeats across images, with
three-engine agreement. Times were 36.310 and 31.438 seconds; peak cgroup memory
was 1,592,836,096 bytes; no temporary job directories remained. No original-image
addresses were sent to Google. A prior cold candidate took 45.027 seconds overall;
the final tested image uses low-compression temporary PNG output and passed below
the existing subprocess deadline.

The clean build passed 116 Java tests; final-image Python checks passed 24 tests,
including real HEIC decoding, orientation, metadata removal, malformed input,
pre-decode dimension limits, and cleanup. Seven JavaScript/deployment checks passed.
The final image also passed nine strict frozen screenshot regressions with 20 exact
rows, no false/missing rows, and correct units/repeats.

Fresh public TAP consent loaded three authorized vehicles. The deployed picker
accepts `image/*,.heic,.heif`. Two uncached, generated no-address HEIC files were
posted as raw HEIC multipart bodies to the authenticated public upload endpoint:
HTTP 200 in 69,898 ms, zero candidates. A malformed HEIC returned HTTP 400.
The saved session fingerprint was unchanged during this test, and selected
fixtures were cleared afterward. No route was saved or sent. Public identity,
theme/font bytes, authentication redirects, and CSRF rejection checks passed.
Physical iPhone hardware was not exercised.

Evidence:
- [Release verification](../output/heic-release-verification.json)
- [TrueNAS deployment log](../output/heic-deployment.log)
- [Strict final-image qualification](../output/ocr-benchmark/results/20260927-heic-release.json)
- [Authenticated HEIC browser test](../output/playwright/verify-heic-release.js)
- Original-image raw report (local, contains extracted addresses):
  `/private/tmp/ocr-503-exact/reports/heic-originals-final.log`
