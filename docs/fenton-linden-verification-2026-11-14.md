# Fenton and Linden verification

Release `20261114-fenton-linden` is deployed and verified as of
`2026-11-15T03:48:28.569Z`.

The three supplied screenshots contain twelve correctly extracted addresses.
The original review held `9440 CRYSTAL TARN, FENTON, MI` and
`16402 SEYMOUR RD, LINDEN, MI`. Policy v17 verifies Seymour through three
independent location proofs. Crystal remains unverified because Google does not
return an exact building location for house 9440.

## Seymour Road

Google returns `16402 S Seymour Rd, Linden, MI 48451, USA`, exact house 16402,
rooftop 42.7849967,-83.8480367 and place ID `ChIJx3hrlwJsI4gReI5U7sjbaMM`.
The original full address, unchanged street-only constrained query and reverse
response agree. v16 rejects the extra leading South direction even when all
three proofs agree.

Policy `street-address-v17` permits this specific representation difference
through the shared strict street-structure proof: an input with a known suffix
and no supplied direction may propose exactly one provider-added leading
direction. The residual street name and suffix must match, and the original,
unchanged-street forward and reverse proofs must retain the exact house,
typed city, state/country, ZIP, rooftop and place ID. A requested direction
cannot disappear or change. Competing directional rooftops, added interior or
trailing words, unit conflicts, partial results and missing proofs remain held.
The original address text and source metadata are retained.

An independent test worker supplied real-case and N/S/E/W vectors, all-stage
identity/point/ZIP/unit/locality drift and directional competition in both
orders. Its matrix exposed an original-proposal direction-plus-locality repair;
the implementation now requires the original locality to match for this path.
The focused 36 street-structure methods and 64 other geocoding methods pass.
All 61 frontend methods pass, including v16 session revalidation and the new
provider-coverage message. OCR/extraction version remains `ocr-consensus-v10`.
The exact staged export excludes unrelated telemetry and passes a clean package
with 356 Java methods and no failures, errors or skips. The tested frontend bytes
match that export. Immutable-image and native qualification also passed.

## Crystal Tarn coverage limit

The screenshot says house 9440. The user supplied an approximate hint near
9439 W Crystal Tarn Drive. Google resolves that different house, but every
captured forward form for 9440—including the full hinted street name—returns
only partial, approximate city/township areas. Reverse lookup of the neighbor
does not establish a 9440 rooftop. The neighbor or a city center cannot verify
this delivery stop.

The [Livingston County Road Commission access-name inventory](https://livingstonroads.org/wp-content/uploads/2024/04/AccessNameInventory2024.pdf)
lists Crystal Tarn as a private access in Tyrone Woods MHC. That street record
provides context, not house 9440 coordinates. No street or house whitelist, guessed
point, or neighbor-location substitution is introduced.

When all returned results are broad areas rather than an address, v17 preserves
`NO_MATCH` with reason `provider_area_only`. The review now explains that Google
returned only a city or area and asks for the complete street address; send/start
remain paused. A verified exact building location or corrected target address
is still needed for 9440. This is an unresolved provider-data dependency,
not a successful location verification. Existing editing/removal remains available.

## Authority and release evidence

The user approved up to 16 read-only Google requests for these two cases. All 16
were retained and the budget is closed; all further qualification uses offline
replay. No Tesla commands were authorized or issued. Local Tesseract PSM6/11
preserved all twelve addresses in image order; the native three-engine run
independently reproduced those twelve rows with 3/3 agreement.

Root owns integration, Git and every remote effect. Unrelated dirty telemetry,
AGENTS.md, credentials, route/cache/session data, source image/address order and
the eight-waypoint limit are preserved. Private evidence and release receipts
are under `output/private/fenton-linden-triage-20261114/`. Exact source, CI/image,
native and production identities are recorded below and in the private receipts.

Source `357e3fa80db530a80266f7c53d480baf799ddafe` is committed and pushed.
[CI43668616646, attempt1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/43668616646)
passed build/test/publication, security with zero High/Critical findings, storage
enforcement and cleanup. The qualified publication is
`ghcr.io/javadevjt/routelisttotesla@sha256:740c9c069c21d34596d293778c2141b659f738670ea14bb5f65add4ef66be8b8`.
Its actual extracted application JAR SHA-256 is
`23cc3199324186261f6a47339f3e846f016299051ed572b785e2d9e53c99baaa`;
template, theme and postal resources match committed source, policy is v17,
Tomcat core/websocket/el remain 11.0.27 and unrelated telemetry classes are absent.

Actual old-image and clean-source JAR replays reproduce both v16 failures, then
verify Seymour with the original place ID/point and retain Crystal's hold in
v17. Each uses five saved-response calls with zero external requests. This local
preflight exercises IMG_0744 through one-engine legacy TSV compatibility only;
it does not establish native three-engine OCR.

The isolated actual-image native run `native-43668616646-1` completed successfully
at `2026-11-15T03:43:54.308Z`, attempt 1, QA create job 43765. All 33 screenshots
matched their ordered truth using the strict Tesseract/PaddleOCR/EasyOCR wrapper.
The 26 prior provider outputs were unchanged except for policy version, and both
new cases matched their expected outcomes: Crystal `NO_MATCH/provider_area_only`
after two attempts; Seymour `VERIFIED_BUILDING` after three. The 65 fixture
requests used 61 saved matches and four historical empty fallbacks, with zero
external requests or vehicle commands. Application classes came from the exact
image JAR, without application or OCR overlays. Non-root UID 10001, memory/CPU
limits, authentication/CSRF checks and QA/fixture cleanup passed.

The mobile review used that image's template/assets and actual native case JSON.
Crystal rendered red with the city/area explanation; Seymour rendered green
with the corroborated map address and original input retained. Manual send and
automatic start were disabled and never clicked. There were no script errors or
horizontal overflow.

## Production handoff

Root submitted exactly one guarded TrueNAS `app.update`, job **43808**, after a
fresh predecessor/source/CI/image/native/browser reconciliation. The job
succeeded and runtime readiness was recorded at `2026-11-15T03:47:47.588Z`.
Only the router image, cache-permissions image and `APP_RELEASE` changed.
The retained predecessor is source
`978245d92126a5a27144670f5157facf209db988`, release `20261112-old-shook`, image
`ghcr.io/javadevjt/routelisttotesla@sha256:a7b051f9ce0b0a4830ce98a8949adf5a96c94867fc3c374094251f0b05fdad61`.

The configuration fingerprint changed from
`48867329fcba78e8f46bbb50df134a062c86cc755d910f0fb7d6a1fdb2f0cad0` to
`b57b0ef6690ea7988b5b40b53ed4451ac2aaa58a78dd96221aa5955f535f98f8`;
restoring those three allowed fields reproduces the predecessor fingerprint.
The persistent-volume metadata fingerprint remains
`59d12fbcc26b7690669674a2c80d3e83747be3d3553faf8532f06eec6cf02b19`.
UID `10001:10001`, read-only root, 8 CPUs, 8 GiB memory, thread limits, persistent
cache mount, LAN port and private proxy network are preserved. No Java agent,
entrypoint or command override was introduced. Full secret configuration and
credential values were never persisted in these receipts. No route data was
read or modified by verification.

At `2026-11-15T03:48:27.775Z`, native readback showed the exact new digest and
`APP_RELEASE`, with one running router container. Runtime health was unreported;
the separate public `/health` check returned 200 with exact source
`357e3fa80db530a80266f7c53d480baf799ddafe`. Public login returned 200 and the served
theme hash matched the qualified image. At `03:48:28.569Z`, a 390 px browser
confirmed the live release marker, TAP sign-in link, source health, no script
errors and no horizontal overflow. This read-only browser did not sign in or
issue route commands.

The persistent service is [TeslaRouter](https://teslarouter.javadevjt.tech), with
no scheduled expiry. Crystal 9440 still requires verified exact building data
or a corrected target; deployment does not remove its hold. The application
source and image above remain distinct from the later docs-only release record.

Key private receipts are `release-binding.json`,
`release-ci-verification-43668616646-1.json`,
`release-registry-verification-43668616646-1.json`,
`release-image-verification-43668616646-1.json`, `native-completion.json`,
`browser-addresses.json`, `predeploy-reconciliation.json`,
`deployment-intent.jsonl`, `deployment.json`, `production-verification.json` and
`production-browser.json`. They retain their source/image/hash links and
observation times. No completed CI job logs were fetched, and no CI retry,
production update retry or Tesla command was issued.
