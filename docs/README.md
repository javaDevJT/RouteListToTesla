# Documentation

- [Apartment and building identities (deployed 2026-10-26)](unit-parent-recovery-2026-10-26.md): independent unit/parent place-ID corroboration, strict conflict handling, accurate unit messages and complete release evidence.

- [Explicit township recovery (deployed 2026-10-24)](explicit-municipality-recovery-2026-10-24.md): supplied municipal labels, typed locality validation, exact rooftop corroboration and release verification.

- [Street structure recovery (deployed 2026-10-22)](structural-address-recovery-2026-10-21.md): suffixless street names, terminal suffix omissions in either representation, strict provider confirmation and release verification.

- [Municipal postal recovery (deployed 2026-10-21)](municipal-postal-recovery-2026-10-21.md): missing-city township responses, typed municipal proof, conflicting destinations, saved-result migration and release verification.

- [Postal city recovery (deployed 2026-10-20)](postal-city-recovery-2026-10-19.md): ZIP-based locality evidence, exact reverse rooftop proof, saved-result migration and complete release qualification.

- [General address reliability (2026-10-19)](general-address-reliability-2026-10-19.md): deployed provider-corroborated recovery, OCR metadata boundaries, native quality/speed qualification, release proof and follow-up postal-city diagnosis.

- [Apartment lookup and OCR evidence (2026-10-18)](southfield-address-and-ocr-evidence-2026-10-18.md): provider unit-label normalization, spatial metadata exclusion and saved-location migration.

- [Street suffix and township recovery (2026-10-17)](address-aliases-2026-10-17.md): deployed comprehensive USPS catalog, supplied HEIC diagnosis, final native qualification and release proof.
- [Automatic address recovery (2026-10-17)](address-resolution-recovery-plan-2026-10-17.md): deployed street-name validation, bounded recovery, clear outcomes and guarded saved-route retries; original HEIC review and complete release evidence.
- [Address recovery and processing improvements (2026-10-17)](address-recovery-2026-10-17.md): complex names, time-label filtering, pre-send resolution and native speed qualification.
- [Container vulnerability repair (2026-10-10)](security-release-2026-10-10.md): unused Pebble runtime removal and release verification.
- [CI recovery (2026-10-05)](ci-recovery-2026-10-05.md): vulnerability remediation, runner storage sizing, and release acceptance evidence.
- [Screenshot processing reliability (2026-10-04)](reliability-2026-10-04.md): staged cache recovery, bounded OCR and Google failure handling, qualification and release evidence.

- [OCR errors and route-send holds (2026-10-01)](errors-2026-10-01.md): definite pre-send rejections, unresolved-location feedback, privacy-preserving failure diagnostics, and validation evidence.

- [Route-code filtering and native OCR capacity (2026-09-30)](ocr-route-codes-2026-09-30.md): hash metadata filtering, unit preservation, quality and speed comparisons, release evidence.

- [Canfield geocoding diagnosis (2026-09-29)](geocoding-canfield-2026-09-29.md): city-result root cause, address validation, and stale-cache safeguards.

- [GitHub Actions publishing](github-actions.md): main-branch image builds on the
  private TrueNAS runner and publication to GHCR.

- [Container and dependency modernization (2026-09-28)](container-modernization-2026-09-28.md):
  Java 25, multi-stage jlink image, dependency compatibility, frontend checks, and release evidence.

- [TAP design and navigation integration (2026-09-27)](tap-design-integration-2026-09-27.md):
  shared styling, Other Tools navigation, font access boundaries, and deployment evidence.

- [Security remediation and OCR release (2026-09-26)](security-ocr-release-2026-09-26.md): deployed fixes,
  generated-image evaluation, and release acceptance evidence.

- [Security audit (2026-09-26)](security-audit-2026-09-26.md): endpoint access, delegation, data isolation,
  deployment exposure, and audit evidence.
- [TAP delegated authentication and release verification](tap-auth-integration-2026-09-26.md)

- [Development reference](development-reference.md) : current application flow, configuration, routes, cache
  behavior, and runtime handoff.
- [iPhone setup](ios-setup.md) : Safari sign-in, native screenshot selection, ordering, review, route actions,
  and the optional Home Screen browser bookmark.
- [2026-09-26 modernization](modernization-2026-09-26.md) : audit findings, TAP-V2 command and arrival
  contracts, and current OCR/capture decisions.

- [Local OCR benchmark](../output/ocr-benchmark/README.md) : image and OCR row-matching records, including the
  Linux application-image check.

## Verified deployment (2026-10-26)

TrueNAS job **15856** deployed **20261026-cherrywood**, application source
`bfe06ec`, immutable image
`sha256:3b3256e0e2f719074cbdf09d25c67a045a0e99c7233d6b72f9bea59da7257024`.
[Push CI](https://github.com/javaDevJT/RouteListToTesla/actions/runs/40356401515)
passed security, storage, publication and cleanup with zero High/Critical
findings. The exact image passed 26 ordered-exact screenshot regressions, all
three local OCR engines, 36 Python tests and 19 address cases over 40 saved
provider responses. Local checks passed 324 Java and 51 frontend tests.

The [public app](https://teslarouter.javadevjt.tech) serves the verified source,
release and theme. Public health, mobile login, TAP redirects and protected
route/CSRF checks passed. The mobile review uses the exact native-image data:
Cherrywood's unit verifies and Steele shows no fabricated suite. Existing
credentials, volumes, resources and private LAN/proxy bindings are preserved.
See the [complete release record](unit-parent-recovery-2026-10-26.md).

## Historical deployment (2026-10-24)

TrueNAS job **9493** deployed release **20261024-gateway**, application source
`22ab299`, immutable image
`sha256:4e839e4cf1dff5131977a747921c092fd2d2dc8444bd6af7828a6272864cf5cb`.
[Push CI](https://github.com/javaDevJT/RouteListToTesla/actions/runs/40094618455)
passed security, storage, publication and cleanup with zero High/Critical
findings. Native qualification passed 25 ordered-exact screenshots with all three
local OCR engines, 36 Python tests and 15 recorded geocoding cases. Local checks
passed 317 Java and 48 frontend tests.

The [public app](https://teslarouter.javadevjt.tech) serves the verified source,
release and stylesheet. Live source health, mobile login, TAP redirect and
signed-out route/CSRF checks passed. The 15-row mobile review exactly matches
the native image's resolved data; Gateway Ledge shows positive verification
with original text retained. All existing mounts, security/resource settings
and private LAN/proxy bindings are preserved. Tests and deployment verification
sent no vehicle commands. See the
[complete release record](explicit-municipality-recovery-2026-10-24.md).

## Historical deployment (2026-10-22)

TrueNAS job **2968** deployed release **20261022-street-structure**, application
source `7db86fc`, immutable image
`sha256:0aabde24f148c62b9bdf15365139836239da1153e44348fe1c2f9eaaa5a403bd`.
[Push CI](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39802095789)
passed security, storage, publication and cleanup with zero High/Critical
findings. The exact image passed 24 ordered-exact screenshot regressions with
all three OCR engines, 36 Python tests and 14 recorded geocoding cases. Local
checks passed 293 Java and 46 frontend tests.

The [public app](https://teslarouter.javadevjt.tech) serves the verified source,
release and stylesheet. Live source health, mobile login, TAP redirect and
signed-out route/CSRF checks passed; the 14-row mobile review matches the native
image results. Data mounts, security/resource settings and private LAN/proxy
bindings are preserved. Qualification used 30 saved responses without external
geocoding or vehicle commands. See the
[complete release record](structural-address-recovery-2026-10-21.md).

## Historical deployment (2026-10-21)

TrueNAS job **99** deployed release **20261021-municipal-postal**, application
source `38d28d0`, immutable image
`sha256:a9892609a03b2ea7501ce247d4d8aa3f99ca96aa610203da1aca4b44ba24abae`.
[CI attempt 2](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39617731110/attempts/2)
passed all security, storage and publication gates with zero High/Critical
findings. Native qualification passed 22 screenshots with all three OCR engines,
36 Python tests and nine recorded geocoding cases. Local checks passed 266 Java
and 42 frontend tests.

The [public app](https://teslarouter.javadevjt.tech) serves the verified source and
release. Live mobile login, packaged stylesheet, source health, TAP redirect,
signed-out route/CSRF checks and mocked nine-row review passed. Data mounts,
resource/security settings and the existing private LAN/proxy binding are
preserved. Two approved Google requests diagnosed the failure; qualification
used recorded responses and sent no vehicle commands. See the
[complete release record](municipal-postal-recovery-2026-10-21.md).

## Historical deployment (2026-10-20)

TrueNAS job **27395** deployed release **20261019-postal-city**, application source
`22df9aa`, immutable image
`sha256:c2c09f47328e4b667680331a41987362cb18ab622875549f61cefd5c2cb4e1a4`.
The [scheduled CI build](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39401101397)
of that exact source passed all gates with zero High/Critical findings; the
preceding push build also passed. Native qualification passed 21/21 screenshots
with all three OCR engines, 36 Python tests and seven recorded address cases
against the packaged geocoder. Local checks passed 254 Java and 39 frontend tests.

At that deployment, the [public app](https://teslarouter.javadevjt.tech) served the
verified source and release. Mobile review, live login/source health, TAP redirect and signed-out
route/CSRF boundaries passed. Existing data mounts and security/resource settings
are preserved. No additional Google requests or vehicle commands were made during
implementation/release qualification. See the [complete release record](postal-city-recovery-2026-10-19.md).

## Historical deployment (2026-10-17)

TrueNAS job **19348** deployed release **20261017-location-recovery**, application
source `48f86f2`, immutable image
`sha256:a0c46d17ed8e10afac9deacfc9ced42dd13c5213d0ef5f2b5321bd5ff7c6ac13`.
[CI attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39067020403)
passed all gates with zero High/Critical findings. Native qualification passed
16/16 screenshots and 36 Python checks, including the three newly supplied HEICs,
with all three OCR engines and no overlays. One three-image trial took 64.34
seconds sequentially versus 40.90 seconds at image concurrency two, with identical
text and review decisions; these timings exclude Google lookup.

At that deployment, the [public app](https://teslarouter.javadevjt.tech) served the verified release.
Eight CPUs/8 GiB, UID 10001, read-only root, no host ports and retained data mounts
are preserved. The health probe, TAP redirect, signed-out route/CSRF boundaries
and mobile browser checks passed. Local checks passed 191 Java and 29 frontend
tests. The browser interactions used the digest-proven packaged template with
mocked APIs; live login release and runtime image were checked separately. Both
recorded failing Google responses resolve against the packaged geocoder in one
query each using offline replay. No new Google requests or vehicle commands were
issued during this release. See the [complete release record](address-resolution-recovery-plan-2026-10-17.md).

## Historical deployment (2026-09-28)

TrueNAS job **212741** deployed `20260928-jlink`, source commit `0fbb774`, image
`sha256:a62e3e8ff5390d2665030bd3306b7608c42bb0cde49acaad505e6bfd70df34bd`.
The Java 25 jlink application runs as UID 10001 with 4 GiB and two CPUs. Existing
data remains mounted; the root filesystem is read-only and no host ports are
published. The native TrueNAS qualification passed all 11 images and 28 expected
rows, including both original HEICs, at a maximum 1.12 GiB memory peak.

The public browser smoke at iPhone width passed two uncached raw HEICs in 63.5
seconds, malformed input rejection, upload control restoration, duplicate-submit
protection, TAP delegation, and unchanged saved-session data. No geocoding of
personal addresses or vehicle commands occurred. See the
[complete release evidence](container-modernization-2026-09-28.md).

## Historical deployment (2026-09-27)

TrueNAS job **209049** deployed `20260927-heic`, image
`sha256:191f711504cbb02290aa666493f6bfacb54f6a76a576b3059b6982e226ef414c`. HEIC/HEIF uploads now
convert automatically on the server before the three local OCR engines run.

Both original iPhone HEIC files yielded eight exact ordered rows with all three
engines agreeing. Nine strict screenshot regressions passed: 20 correct rows,
no missing or extra rows. Java/Python/JavaScript checks passed 116/24/7.
Fresh TAP consent loaded three vehicles. The public upload accepted two uncached
raw HEIC files (HTTP 200, 69.9 seconds) and rejected malformed HEIC (HTTP 400).
The saved session was unchanged during the smoke test. The existing data volume
is retained, no host port is published, and no vehicle command or personal-address
geocoding was used. Physical iPhone hardware has not been tested.

See [release record](tap-design-integration-2026-09-27.md),
[machine-readable evidence](../output/heic-release-verification.json),
[preceding OCR/security qualification](security-ocr-release-2026-09-26.md),
[closed security findings](security-audit-2026-09-26.md), and
[iPhone instructions](ios-setup.md).

## Historical deployment (2026-09-26)

The following records the superseded baseline; current verification is above.

### Deployed and source authentication status

**Status: READY (September 26, 2026).** Provider job **206100** and TeslaRouter auth job **206108** succeeded;
fresh public TAP consent/callback after router restart, current-entitlement checks, and three scoped
`/api/v1/vehicles` reads passed. TrueNAS job **206218** succeeded, and the router runs pinned image
`ghcr.io/javadevjt/routelisttotesla@sha256:023375e4c640da1689a1982e91347c7d6b4f0828fee2d3275263ec5faca8fccd`
(tag `20260926-tap-auth-ui` ); full configuration readback matched, `ix-routelisttotesla_route-data` is mounted
at `/app/cache` , and port **10088** is active. Browser smoke at **390x844 desktop Chromium viewport** confirmed
parseable served scripts, three authorized vehicles and three picker options, fixture upload HTTP 200 with
**4/4** geocoding, four visible `.address-item` rows, and cache-check HTTP 200. The browser forced only the
cache miss needed for upload coverage and intercepted one `/route/session/save` ; the saved fingerprint remained
unchanged. No auto-started `RUNNING` job, manual Add control, forbidden mutation, or vehicle command occurred;
no billing charge was made. Physical iPhone/car and vehicle acceptance remain untested. Evidence:
[upload screenshot](../output/playwright/tap-auth-mobile.png) and
[review screenshot](../output/playwright/tap-auth-review-mobile.png) .

The deployed UI uses **Continue with TAP** at `/oauth2/authorization/tap` and callback `/login/oauth2/code/tap`
; see [TAP setup](../OAUTH_SETUP.md) .

### Build and browser checks

The clean Maven package passed **77 tests**, with **0 failures, 0 errors, and 0 skipped**. The Thymeleaf
page-rendering regression passed, and the packaged JAR SHA-256 is
`8b298d9a2d685449f8bc23a65f2b00519a9ff1008cfad7de17ee173c8a747229` . The image uses Eclipse Temurin 21 JRE on
Ubuntu 24.04 Noble.

Synthetic browser checks at **390 × 844** passed for upload ordering, duplicate-image handling, inert markup,
one-request pending-command behavior, and holding edits through reload.

The final authenticated RouteList UI smoke at **390 × 844 desktop Chromium** returned HTTP 200 for the fixture
screenshot upload and displayed **four extracted/geocoded address rows** in review. The fixture route was not
saved; **zero vehicle commands were issued**.

The user confirmed **APT 330**. Independent transcription verified that the historical Java output matches
**all 28 visible rows across seven images**. Earlier 27/28 counts used the wrong label.
See the [OCR benchmark record](../output/ocr-benchmark/README.md).

### Vehicle and arrival boundaries

Earlier TAP provider job **205730** was healthy; its authenticated vehicle read returned two scoped vehicles at
that time. The current post-restart smoke returned three scoped vehicles (above). The TAP credential expires at
**2026-12-24T17:35:44Z** and must be rotated through TAP before expiry.

Per-field timestamp fields were verified with a live GET. Gear registration and parsing are configured as
intended, and both cached vehicles report `REGISTERED` ; Tesla-side field readback was not independently
verified. No vehicle commands or wakes were issued, and no physical navigation acceptance was performed.

Arrival confirmation requires a GPS timestamp strictly later than the navigation command and no more than 10
seconds old when Park is accepted. The existing 60-second GPS freshness check remains in force. A held-Park
state supports a location-only packet under the same boundary gate.

### Proxy and release status

Nginx Proxy Manager host **#23** is configured for `teslarouter` over HTTPS with the wildcard certificate. The
deployed proxy configuration is running with the full configuration preserved; its SHA-256 is
`752c5beb8ff6c53aec913b7f0759e679c5e4f32ea607f54284de6bca4a0fa361` .

The desktop Chromium smoke does not establish physical-iPhone or car compatibility or physical vehicle
navigation acceptance; no vehicle command or wake was issued.
