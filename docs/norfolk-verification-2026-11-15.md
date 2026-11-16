# Norfolk street estimate review

Release `20261115-norfolk` is deployed and verified as of
`2026-11-16T00:11:28.971Z` (November 15 in America/Detroit).

The two supplied source screenshots contain six addresses in the correct order.
The review held only `13342 S NORFOLK, DETROIT, MI`; its OCR agreement was 2/3.
Visual review and local Tesseract PSM 6/11 both preserve the house number,
street letters and supplied South direction. No OCR implementation change is
needed. Native three-engine qualification independently confirmed all six rows.

## Provider evidence and the hold

The user approved up to 12 read-only Google geocoding requests for this case.
Six requests were made and retained: original full text, unchanged street under
locality/state/country constraints, provider-canonical Court with ZIP, reverse
lookup of the returned point, expanded South Norfolk Court, and the exact
full-text constrained fallback used by the app. No further paid
calls are planned; remaining qualification uses saved responses. No Tesla
commands were authorized or issued.

All five forward forms return `13342 S Norfolk Ct, Detroit, MI 48235, USA`,
house 13342 and structured `South Norfolk Court` / `S Norfolk Ct`, at
42.4430414,-83.179406. The provider labels that point `RANGE_INTERPOLATED`, not
`ROOFTOP`. Reverse lookup returns the same estimated address with a different
generated place ID and nearby rooftops for other house numbers. None of these
responses establishes a rooftop for house 13342. Neighboring rooftops cannot
replace the requested stop.

The supplied street omits Court. Existing street-structure recovery deliberately
requires exact rooftop/place-ID proof before inferring a missing road suffix.
The provider's estimated point does not satisfy that contract. The old generic
message hid this distinction and encouraged retrying the unchanged address.
Confirmation of the complete street from the delivery details or an exact
building map pin was requested from the user. The user thinks Court is likely
but is not sure; that is not exact-street confirmation. The original stop remains
unverified while that evidence is missing.

## Shared correction

Policy `street-address-v18` reports a narrowly validated interpolation as
`NO_MATCH/recovery_exhausted/street_location_estimated` with an unverified
editing hint in `matchedAddress`. It preserves the input text/source/OCR
metadata, leaves place ID null and coordinates zero, and does not substitute the
suggested suffix automatically. The review explains that Google returned an
estimated road location and asks the driver to check the complete street name
against the delivery details before editing and retrying. Manual and automatic
send remain held.

Hints are restricted to original, nonpartial, unitless address responses with
the exact house, preserved name/direction, a single consistent route, typed
locality/state/country and valid constrained postal information. Only a missing
terminal suffix is suggested. Missing or malformed point/ID/components,
unexpected units, wrong names/directions/localities and competing destinations
do not select a hint. Two distinct identities or points suppress the hint even
when their display addresses are equal. Positive recovery retains its existing
`ROOFTOP` guard and independent corroboration requirements.

Frontend hints are literal text, bounded to 512 characters, and never promote a
held status. Existing v17 sessions are revalidated without deleting stored data.
Extraction stays `ocr-consensus-v10`; order, source filenames, default-state
handling and the eight-waypoint limit are unchanged.

## Checks and release evidence

The initial regression reproduced the generic no-safe-match result. The corrected
source passes 106 focused Java methods and 64 frontend checks. The independent
backend matrix covers directional streets, identity/address/component validity,
typed ZIP mismatches, units, partial/approximate responses, competing estimates
and metadata/coordinate preservation. Existing rooftop and prior-recovery tests
remain in place. Local OCR is not native image qualification.

Root owns source integration, Git and all remote effects. Existing dirty
telemetry, AGENTS.md, credentials and route/cache/session data are preserved.
Private records are under `output/private/norfolk-triage-20261115/`, indexed by
its `RELEASE.md`. Exact source, CI/image, native and production identities are
recorded below. This record does not claim that Norfolk's
building location has been verified.

Source `c421e632d7581933c1d3785fc1f6a2a343dbef72` is committed and pushed.
The clean staged export passed all 362 Java methods, with no failures, errors or
skips, and matched the frontend bytes used for the 64 passing checks. The
[exact push CI run 43772735859, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/43772735859)
passed build/test/publication, security with zero High/Critical findings, storage
enforcement, source contract and cleanup. Publish job 132992054870 and cleanup
job 133005430197 succeeded. No CI reruns or completed job-log fetches were used.

The qualified immutable image is
`ghcr.io/javadevjt/routelisttotesla@sha256:d95966351b9073296f471067bd3754efa1519927acf93d037f0193b9dc57751e`.
Its linux/amd64 platform manifest is
`6891368e82798173c48295d10dc71fe049d9d7b0d4f57bccd5794114ddfda073`.
The actual extracted image JAR SHA-256 is
`368c369e12406bb48fc72a6b48df987bebb719b47b69c1889a72968a91cdd005`.
Template, theme and postal resources match committed source; the image retains
UID `10001:10001`, expected Java entrypoint/command and thread limits, no injected
agent, policy v18 and Tomcat core/websocket/el 11.0.27. Unrelated dirty telemetry
classes are absent.

Actual prior-image and clean-source JAR replays each used the two exact saved
app queries, with zero external requests or vehicle commands. The prior v17 JAR
returns `NO_MATCH/no_safe_match` without a suggestion; the new source JAR returns
`NO_MATCH/street_location_estimated` with the unverified Court suggestion. Both
retain null place ID and zero coordinates. Six local normalized OCR rows match
the source order; this preflight explicitly uses one-engine legacy Tesseract
compatibility and does not claim native consensus.

The isolated actual-image run `native-43772735859-1`, attempt 1, QA create job
46292, completed successfully at `2026-11-16T00:08:53.970Z`. All 35 screenshot
cases matched ordered truth using the strict Tesseract/PaddleOCR/EasyOCR wrapper.
The two new images produced six rows: Norfolk retained 2/3 agreement without an
OCR review flag; the other five rows had 3/3 agreement. The 28 prior provider
outputs were unchanged except policy version, and Norfolk matched the expected
unverified estimate outcome. The 67 fixture calls used 63 saved matches and four
historical empty fallbacks; there were zero external requests or vehicle commands.
Application classes came from `/app/app.jar` without application/OCR overlays.
UID 10001, x86_64, memory/CPU/thread limits, authentication/CSRF, installed
dependencies, no compiler, cleanup and zero leftover OCR jobs all passed.

The image's geocoding class matches the locally tested source class byte for
byte; its OCR class is byte-identical to the prior image. The mobile review used
the actual image template/assets and native Norfolk result. It displayed the
estimated Court suggestion in red, retained the original `S NORFOLK` and 2/3
evidence, and left manual send and automatic start disabled and unpressed.
There were no script errors or horizontal overflow. These row interactions used
mocked APIs; they did not sign in or send commands to production.

## Production handoff

After fresh source/CI/image/native/browser and predecessor-state checks, root
submitted one guarded TrueNAS `app.update`, job **46331**, at
`2026-11-16T00:10:52.194Z`. It succeeded at `00:11:07.484Z`; runtime readiness was
recorded at `00:11:12.805Z`. Only the router image, cache-permissions image and
`APP_RELEASE` changed. No update retry or automatic rollback was performed.
The retained predecessor is source
`357e3fa80db530a80266f7c53d480baf799ddafe`, release `20261114-fenton-linden`, image
`ghcr.io/javadevjt/routelisttotesla@sha256:740c9c069c21d34596d293778c2141b659f738670ea14bb5f65add4ef66be8b8`.

The configuration fingerprint changed from
`b57b0ef6690ea7988b5b40b53ed4451ac2aaa58a78dd96221aa5955f535f98f8` to
`b63111e8a17d7871b26cda6ee5824bf928fa2bacd59c0d4512bf9aef2855fc30`.
Normalizing those three allowed fields exactly reproduces the predecessor
fingerprint; this is a preservation comparison, not an atomic CAS or rollback.
The volume metadata fingerprint remains
`59d12fbcc26b7690669674a2c80d3e83747be3d3553faf8532f06eec6cf02b19`.
UID `10001:10001`, read-only root, 8 CPUs, 8 GiB, thread limits, the retained
route/cache/session volume, LAN port and private proxy network are preserved.
No Java agent, entrypoint or command override was introduced. Configuration was
compared in memory and fingerprinted; raw secret configuration and credential
values were not persisted. Verification did not read or delete route data contents.

At `2026-11-16T00:11:27.631Z`, native readback confirmed the exact new image and
`APP_RELEASE` with one running router. Container health was unreported; the
separate public `/health` returned 200 with exact source
`c421e632d7581933c1d3785fc1f6a2a343dbef72`. Login returned 200 and served theme
bytes matched the qualified image. At `00:11:28.971Z`, a 390 px live browser
confirmed the release marker, TAP sign-in link, source health, no script errors
and no horizontal overflow. No authenticated route or vehicle action was exercised.

The persistent service is [TeslaRouter](https://teslarouter.javadevjt.tech), with
no scheduled expiry. Norfolk remains unverified until the complete street or
exact building location is established; deployment does not remove that hold.
The deployed application source above remains distinct from the later docs-only
release-record commit.

Key private receipts are `release-binding.json`,
`release-ci-verification-43772735859-1.json`,
`release-registry-verification-43772735859-1.json`,
`release-image-verification-43772735859-1.json`, `native-completion.json`,
`browser-addresses.json`, `predeploy-reconciliation.json`,
`deployment-intent.jsonl`, `deployment.json`, `production-verification.json` and
`production-browser.json`. They retain exact source/image/hash links and
observation times. The finalization summary is unsigned owner evidence, not a
Quarterback enrolled publisher, generation or clearance receipt.
