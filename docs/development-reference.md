# RouteListToTesla development reference

TAP-managed sign-in and stable-subject authorization replace independent Google OAuth and email allowlists; details are in [TAP setup](../OAUTH_SETUP.md).

## Source flow

### September 30 route-code filtering

The shared `AddressExtractor.addressCandidateText` rejects hash-prefixed itinerary metadata and standalone dotted/underscored identifiers before scanning for house numbers. OCR can turn a route-code token into an apparent house number. A hash used as OCR decoration remains valid when it directly precedes a numeric street address with a recognized street type. Inline and wrapped units such as `123 MAIN ST #212` remain supported. Extraction version `ocr-consensus-v5` invalidates screenshot entries produced by the older parser without deleting saved routes or cache storage. See [native OCR capacity evidence](ocr-route-codes-2026-09-30.md).

### September 29 OCR concurrency and screenshot overlap release

Primary owns address merging, deployment limits, integration checks, and release.
The OCR execution worker owns `ocr/consensus.py`, `ocr/run`, and focused Python
execution tests. Worker selection is `gpt-6-luna` / `max`, resolved from the live
native spawn catalog on September 29, 2026; base revision is `2e45af8`.
Acceptance requires all three engines to execute concurrently within one admitted
OCR job, native runtime qualification, and overlap removal across screenshots
without collapsing distinct units or intentional repeated stops within one image.
The primary reviewed the process orchestration and retained the single-image
admission limit. All three engine processes launch before collection and return
in stable engine order. The deadline is 37 seconds from Python entry; termination
and reap have a three-second allowance inside Java's 45-second limit. No new
environment grants, dependencies, or cache extraction version are required.
An independent overlap reviewer uses the same catalog-resolved `gpt-6-luna` / `max`
selection, owns no implementation files, and checks the Java/browser merge against
mixed cache/upload batches, numeric/unit identity, ordering, and deliberate repeats.
The overlap review completed without blocking findings; completion released that
worker. The 119 Java tests, 11 browser tests, 19 consensus tests, and three release
configuration checks passed locally. macOS disallows the process-group cleanup
check, and the host lacks Pillow-HEIF; the native image qualification runs those
checks before deployment. Per-suite native reports are retained under
`output/ocr-benchmark/results/20260929-parallel-native-*.runner.json`.
The execution worker completed its handoff and timeout-test correction; the
primary owns native qualification and deployment of release `20260929-parallel-ocr`.

The browser preserves the selected image order, calculates each image's SHA-256, and checks the compatible cache before upload. Uncached images are processed in sequential single-image requests so completed images remain reusable when a later image fails. Successful OCR is persisted before geocoding under an extraction-only cache entry; a retry can reuse it after restart, without advertising it as a completed browser cache hit. See [October 4 reliability changes](reliability-2026-10-04.md). Cache identity includes the extraction version and default state as well as image content; file names remain display metadata. Uncached images are uploaded to the application server.

`AddressOcrService` invokes `ocr/run`, which runs Tesseract, PaddleOCR through RapidOCR, and EasyOCR locally.
It requires strict consensus JSON with observed per-engine line evidence; plain Tesseract TSV is supported
only by explicit legacy tests. The container sets `OCR_TESSERACT_EXECUTABLE=/app/ocr/run` and
`OCR_MODEL_MANIFEST=/app/ocr/models.json`. Models are installed and verified at image build time; runtime
downloads and hosted OCR are not used. `GeocodingClient` separately resolves addresses with Google Maps.

The UI keeps extracted candidates visible for review, including unresolved candidates and repeated stops. Users can edit or remove extracted entries. There is no from-scratch Add Address control or manual text-list importer.

Screenshot deduplication matches the longest consecutive suffix/prefix overlap
or a fully contained capture of multiple rows. It retains repeats inside one
image, meaningful house-number punctuation, and unit numbers; a shared geocoding
place ID does not establish an identical stop. Matching copies retain the stronger
OCR evidence. The upload response includes full `imageCandidates` groups so the
browser can merge fresh batches with cached captures without relying on filenames.
Each image cache still stores the full capture before route-level deduplication.

| Area | Entry points |
| --- | --- |
| HTTP routes | `controller/RouteController.java`, `controller/AuthController.java` |
| Local OCR | `service/AddressOcrService.java` |
| Shared address grammar | `util/AddressExtractor.java` (used by production OCR and the legacy text utility) |
| Geocoding | `service/GeocodingClient.java` |
| Image cache and user sessions | `service/ImageCacheService.java`, `service/UserSessionService.java` |
| Route progression | `service/AutoNavigationService.java`, `model/AutoNavSession.java` |
| Vehicle integration | `service/TapVehicleClient.java` |
| Authentication | `config/SecurityConfig.java`, `service/TapAccessService.java` |
| Browser UI | `src/main/resources/templates/` |
| TAP theme | `src/main/resources/static/css/tap-theme.css`; three self-hosted OFL fonts under `static/fonts/` |

TAP-V2 is the only vehicle-discovery, telemetry, and navigation-command integration. The app does not call the old direct Tesla Fleet or Teslemetry clients. TAP command acceptance is distinct from telemetry-confirmed arrival.

The shared address grammar requires an observed digit in a house-number token and preserves ambiguous
characters literally. It joins adjacent wrapped streets, unit/building labels, and localities while keeping
complete neighboring addresses separate. Detector padding may overlap by at most 30% of the shorter line's
height; small unmatched fragments remain review evidence instead of being silently removed.

## Build and tests

The Maven project targets Java 25 LTS and Spring Boot 4.1.1, using Maven 3.9.16.
Jackson 3 is used throughout. `spring.jackson.use-jackson2-defaults` preserves
existing HTTP payload behavior; persisted cache/session mappers retain compatible
field and ISO date formats. Run from the repository root:

```sh
./mvnw test
./mvnw -Dtest=AddressOcrServiceTest,TapVehicleClientTest,AutoNavigationServiceTest,RouteControllerTest test
./mvnw clean package
./mvnw spring-boot:run
```

Image publication uses the private `truenas-routelisttotesla-storage-32g` runner
class, with the existing disk build cache and GitHub cache fallback. This class
selects 32 GiB of runner storage; the deployed application's resources are
configured separately. The operator observed roughly 26 GB peak runner usage
before requesting this reduction from the 64 GiB class.

The Dockerfile builds the JAR from source in a JDK stage, derives modules with
`jdeps`, and creates a stripped `jlink` runtime. The final Ubuntu 26.04 image runs
as UID/GID 10001 and contains neither Maven nor the Java compiler. OCR dependencies
and checksum-verified models are prepared separately using Python 3.14. The
runtime needs writable `/app/cache` and `/tmp`; production uses a retained data
volume and a temporary filesystem with a read-only image filesystem. The release
configuration and candidate qualification harness use eight CPUs and an 8 GiB
memory limit. OCR uses two Torch/OpenMP/BLAS compute threads while Torch interop
stays at one. All three engines still run in parallel; preprocessing, quality
gates and admission control are unchanged. The October 4 reliability patch allows a 60-second shared Python engine deadline inside a 75-second Java subprocess deadline; worker cleanup remains bounded and all three engines are required.

Use native amd64 hardware for OCR performance qualification. Running this amd64
image on Apple Silicon uses emulation and does not establish production timings.

```sh
docker build --platform linux/amd64 -t routelist:local .
python3 output/verify-runtime-image.py routelist:local
```

These commands are not evidence that a particular revision passed. Real consensus OCR needs the wrapper
and all three offline engines/models; use the final-image benchmark described in
[the benchmark guide](../output/ocr-benchmark/README.md). Mocked HTTP tests do not call Google, TAP, or Tesla.

## Configuration and security

The running server's default port is 10088; verify the actual port in the target environment. Configuration names are defined in `src/main/resources/application.yml`.

| Purpose | Configuration |
| --- | --- |
| TAP login client | `TAP_CLIENT_ID=routelist`; fixed `TAP_REDIRECT_URI=https://teslarouter.javadevjt.tech/login/oauth2/code/tap` |
| TAP API base | `TAP_BASE_URL`, including `/api/v1` |
| TAP code exchange | `TAP_CLIENT_KEY`, confidential server-side key used only at the TAP token endpoint |
| Legacy data migration | Both `TAP_LEGACY_OWNER_EMAIL` and `TAP_LEGACY_OWNER_SUB`, only for an explicit one-time ownership migration |
| Google Maps geocoding | `google.api.key`; separate from login and TAP authorization |
| Consensus wrapper | `OCR_TESSERACT_EXECUTABLE=/app/ocr/run` in the container; the legacy property name is retained |
| Offline OCR models | `OCR_MODEL_MANIFEST=/app/ocr/models.json` |
| Arrival radius | `NAV_ARRIVAL_RADIUS_METERS` |
| Maximum telemetry age | `NAV_MAX_TELEMETRY_AGE_SECONDS` |
| Telemetry polling interval | `NAV_POLL_INTERVAL_MS` |

TAP handles sign-in and consent, the `routelist` entitlement, billing state, and per-subject vehicle grants. RouteList keys identity by the stable TAP `sub`; email is unverified profile data and is not an authorization key. Grants last eight hours, have no refresh token, and remain in memory; expiry or restart requires sign-in again. TAP checks current entitlement and grants on hub calls, and revocation blocks browser and navigation requests. The delegated token is sent in `X-TAP-Client-Key`; the confidential `TAP_CLIENT_KEY` is used only for authorization-code exchange.

Spring Security CSRF protection is enabled; preserve token handling for browser mutations. Use the fixed
TAP callback over HTTPS except in loopback tests. Keep credentials out of source, command arguments, logs,
browser code, and docs. Browser/background authority is bound to the establishing grant fingerprint;
replacement grants cannot silently revive old authority. Dispatch and client replacement share a lock.
The image installs pinned OCR packages/models outside the JAR. One OCR job is admitted at a time, with
bounded input/output and process-tree cleanup. Disagreements require confirmation before dispatch.

For local setup see [TAP delegated login setup](../OAUTH_SETUP.md). Google Maps geocoding remains separately configured and is unchanged by the TAP login migration.

HEIC/HEIF uploads are detected by their binary file-type brands, independent of filename.
The pinned Pillow-HEIF 1.8.0 decoder converts the primary image locally to an oriented RGB
PNG before all three OCR engines run. The temporary copy has no source metadata.
The existing 12 MiB and 20 megapixel limits apply; invalid decoding returns HTTP 400
before loading OCR models. Java owns each private job directory and removes it after
success, failure, interruption, or timeout. OCR subprocesses receive only an allowlisted
runtime environment, excluding application credentials.

Run decoder tests inside the release image: mount `ocr/` read-only at `/tests`, then run
`/opt/ocr/bin/python -m unittest discover -s /tests -p 'test_*.py'` with networking disabled.

## HTTP routes

The EasyOCR detector is bounded to a 1280-pixel longest side and an 800,000-pixel
working area. It returns boxes in original-image coordinates; Tesseract and
PaddleOCR retain their existing preprocessing. This leaves memory for Spring
inside the 8 GiB container. Image input limits remain unchanged. OCR has a 60-second shared engine deadline and a 75-second outer process deadline. Extraction cache version `ocr-consensus-v5` invalidates older
readings without deleting saved routes. Bare clock fragments such as `10 PM`
are excluded by the shared address parser; names such as `10 PM ROAD` remain
address candidates.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/route/vehicles` | List vehicles available to the signed-in TAP owner. |
| `POST` | `/route/places` | Upload images, run local OCR, and geocode address candidates. Does not send a route. |
| `POST` | `/route/places/{vin}` | Retired legacy extract-and-send route; returns HTTP 410. |
| `POST` | `/route/send/{vin}` | Send 1–8 reviewed stops with an idempotency key. |
| `POST` | `/route/cache/check` | Check a SHA-256 image and default-state cache entry. |
| `POST` | `/route/auto-navigate/{vin}` | Start automatic navigation for the reviewed ordered route. |
| `GET` | `/route/auto-navigate/status/{sessionId}` | Read an owned navigation session. |
| `GET` | `/route/auto-navigate/active` | Read the signed-in user's active navigation session. |
| `POST` | `/route/auto-navigate/stop/{sessionId}` | Stop an owned navigation session. |
| `POST` / `GET` | `/route/session/save`, `/route/session/load` | Save or load the signed-in user's review draft. |
| `GET` | `/route/session/check` | Check whether a review draft is available. |
| `DELETE` | `/route/session` | Delete the signed-in user's review draft. |

Image upload does not automatically dispatch vehicle commands. The reviewed send path requires usable coordinates and a Google place ID for every stop; unresolved stops remain visible and block sending rather than being silently omitted.

## Cache, geocoding, and route state

Geocoding first looks up the full reviewed address, including apartment/unit
information. If it returns no acceptable destination, one fallback lookup omits
the recognized unit suffix. Both attempts reject broad city/road matches,
partial matches, mismatched house numbers, and invalid coordinates or place IDs.
Stops remain unresolved if neither attempt succeeds; their original text is
preserved. Transient transport errors, HTTP 408/500/502/503/504, and Google `UNKNOWN_ERROR` receive at most three application-owned attempts of the unchanged address, with 100/200 ms backoff. Each extra attempt reserves owner/global usage and uses the 60 ms pacing gate. Permanent, quota, malformed, and definitive no-match responses do not retry. JDK-internal recovery of idempotent GET connections can add wire activity within one application attempt; the application counter is not an exact billing meter. Service failures do not cause unit omission. Fallback
calls use the existing pacing and count against owner/global quotas only when
needed. The `street-address-v2` policy version participates in image-cache keys.
Reviewed addresses are resolved again before dispatch so older drafts and
browser-supplied place IDs cannot bypass current validation.


New cache identities include owner, extraction version, normalized default state, and image content hash.
Legacy entries remain untouched and are not shared across owners. A changed default state cannot reuse
an incompatible entry, and cache hits bind display metadata to the current upload.

Geocoding retains the 60 ms pause and bounded timeouts, with per-owner/global budgets and concurrency
limits. Vehicle authorization precedes geocoding for navigation. Provider errors and `ZERO_RESULTS`
remain explicit for review. Repeated addresses remain repeated stops.

Manual route sends contain at most eight stops. TAP command acceptance does not prove arrival. Route progression requires fresh, ordered TAP evidence with per-field source timestamps; unknown gear, stale location, replay gaps, or ambiguous commands cannot imply arrival. Unconfirmed command outcomes hold further sends until the user reconciles the vehicle and explicitly clears the hold.

## Runtime handoff

A release handoff requires rebuilding the intended worktree, restarting the actual server, verifying health and served identity, and exercising the authenticated browser flow. Report the URL and service lifecycle only when observed. Local tests, mocked HTTP, an OCR result, or a successful browser request do not establish a deployed runtime or confirmed vehicle response. Preserve existing caches, sessions, and processes.

## Deployment evidence

Current verification belongs in the [release record](security-ocr-release-2026-09-26.md) and
[documentation index](README.md). The persistent named volume is `ix-routelisttotesla_route-data`, mounted
at `/app/cache`; restart policy is `unless-stopped`. Do not infer the deployed image from a local build.
