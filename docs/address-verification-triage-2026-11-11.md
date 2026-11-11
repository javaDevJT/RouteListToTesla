# November 11 address verification triage

Two supplied screenshot pairs contain nine ordered stops, with three blocked
by location verification. The street letters were read correctly; one city
boundary was merged incorrectly during address extraction.
Preserve source images, address order, units, caches, the existing telemetry
changes, lookup budgets and pacing, and all vehicle-command holds.

## Acceptance and ownership

- Primary owns geocoding diagnosis, any production-code fixes, regression tests,
  policy/cache migration, documentation and final integration checks.
- `ocr-evidence` independently checks the two source images against extraction
  and reports ordered candidates; it owns only
  `output/private/address-triage-20261111/ocr-evidence.md` and supporting evidence
  in that directory. It must not edit application code or make external calls.
- `locality-feedback` owns only `src/main/resources/templates/index.html` and
  `src/test/js/index-consensus-ui.test.cjs`: display a server-reported
  `locality_mismatch` without enabling dispatch, and advance browser policy to
  v14. Primary owns the matching server contract and migration integration.
- `census-area` owns a bounded offline Census ZCTA area catalog, importer,
  source provenance and focused tests. It must not edit `GeocodingClient`, the
  existing GeoNames catalog or other shared files. Primary integrates the helper
  only into exact municipal proofs when existing ZIP-area evidence is absent.
- `identity-review` is read-only: independently check that punctuation and
  legacy city-boundary recovery cannot bypass destination, unit or city-conflict
  safeguards. It owns only a private review report; primary resolves findings.
- Reproduce the three failures from source/provider evidence before changing
  verification rules. Cover valid representations and conflicting destinations,
  preserve the other six stops, and run focused then appropriate broad checks.
- Live paid-provider queries and Tesla commands are separate authority scopes.
  No release or runtime result is implied by local tests.

## Delegation record

The live native catalog on 2026-11-11 lists one Luna family member:
`gpt-6-luna`, with maximum supported reasoning effort `max`. This resolves the
swarm skill's newest-callable Luna policy without release-order ambiguity.
Worker requested configuration: `gpt-6-luna` / `max`, no inherited history.
The same resolved configuration applies to `locality-feedback`.
It also applies to `census-area`.
`identity-review` uses the same resolved pair.
Runtime identity, outcome and review evidence will be recorded after completion;
unexposed backend controls are not independently verified.

## Results

The server restarted during investigation. The original `ocr_evidence` and
`locality_feedback` runs were canceled; partial OCR artifacts and UI edits
survived. The `census_area` dispatch left no source files or surviving native
agent. Replacement workers must inspect and reuse partial output before writing.
The primary audited native handles, working files and process ownership before
resuming. No probe or Maven process remained active. Ten Google requests are
accounted for, with nine full response bodies retained; one earlier overlapping
probe journal write lost a response body and is explicitly recorded as missing.
It still counts against the authorized maximum of 24. The private probe now
holds an exclusive lock for its complete journal/read/request/write operation.

New regression checks against unchanged production code failed in the expected
apostrophe, suffixless-city boundary and actionable city-mismatch cases. Logs:
`output/private/address-triage-20261111/baseline-regressions.log`.

## Confirmed causes and source fixes

- The possessive street name in the township screenshot differs only by an
  apostrophe from Google's route. Matching now ignores straight/curly apostrophe
  omission while retaining letters, word boundaries, suffix and direction
  checks. Its explicit municipal proof still requires three consistent,
  nonpartial rooftop/place-ID attestations within two metres.
- The same address uses ZIP 48382, which the GeoNames source grades at accuracy
  1 and the existing high-accuracy snapshot intentionally omits. A complete,
  independently sourced Census ZCTA representative-point catalog supplies
  geographic supporting evidence for exact municipal proofs. It does not
  provide postal-city aliases, municipal boundaries or navigation coordinates.
  GeoNames city-alias accuracy requirements remain unchanged.
- A suffixless street followed by `WHITE LAKE` was merged into one street:
  `LAKE` is also a recognized street suffix. Extraction now retains an exact
  catalog city/state boundary when another city line does not follow the wrap.
  Following city lines with an explicit state/ZIP are recognized too, while
  metadata such as age verification cannot supply a second city. Existing wrapped
  streets and units retain their continuation behavior. Saved joined text lacks
  the original line geometry and remains held: re-upload the image or explicitly
  edit `5337 WOODLAND WHITE LAKE, MI` to `5337 WOODLAND, WHITE LAKE, MI`.
- The Detroit-labelled directional-circle stop resolves to Dearborn in both
  original and locality-constrained Google lookups. It is range-interpolated,
  and the local postal data does not corroborate Detroit for that ZIP. The app
  retains `NO_MATCH`, zero coordinates and no place ID, and shows the returned
  address as an editing hint. An explicit city edit to Dearborn verifies through
  the normal matching path. The application never silently rewrites this city.

Current source versions are `ocr-consensus-v10` and `street-address-v14`.
Browser and server policies advance together; older saved location results
revalidate. Cache versions change without deleting existing screenshots, routes
or session data. New extraction keys prevent reuse of the merged old reading;
saved legacy address text remains intact during location revalidation.
Ambiguous old text is never automatically split into an inferred city.

## Validation and runtime boundary

- The address-only staged source, exported independently of the unrelated
  telemetry work, passes `./mvnw -B clean package`: 341 Java tests with zero
  failures, errors or skips. The combined working tree also passes its 346 Java
  tests, including five telemetry tests. `node --test
  src/test/js/index-consensus-ui.test.cjs` passes all 56 frontend tests.
- Actual supplied-image Tesseract TSV replay through the current extraction
  service produces all nine candidates in exact source order, retains apartment
  100, and separates `WOODLAND, WHITE LAKE`. The complete three-engine runtime is
  absent on this host, so no fresh consensus-image qualification is claimed.
- Offline replay of retained Google bodies passes five cases: the township
  address, newly extracted Woodland, the held saved joined row, the original
  Morrow city mismatch, and the explicit Dearborn edit. The first two verify via
  three exact rooftop proofs. Both ambiguous/mismatched rows retain no place ID
  and zero coordinates; the city edit verifies through the normal matching path. Thirteen
  local fixture requests made zero external calls; four uncaptured retry
  variants returned an explicit empty fixture rather than invented responses.
- Eleven of the authorized maximum 24 Google requests were used. Ten full bodies
  are retained; the missing response remains honestly accounted for. All further
  validation used local fixtures. No Tesla commands were issued.
- The Census importer reproduces all 33,791 national rows byte-for-byte, with
  source and resource hashes recorded in [Census provenance](../src/main/resources/postal/CENSUS.md).
  The existing GeoNames resource hash and accuracy threshold are unchanged.
  Missing, malformed, truncated, duplicate or oversized Census data fails closed.
- The first full suite exposed a pre-existing MVC-slice dependency gap in the
  worktree's telemetry changes. Four MVC tests now explicitly import a disabled
  telemetry test bean; exporters are not started and production telemetry source
  is unchanged. These test-only changes remain with the unrelated telemetry work
  and are excluded from the address commit. This follows Spring Boot's
  [test-only configuration pattern](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.detecting-configuration).
  Context7 lookup was unavailable because its monthly quota had been reached;
  the official Spring reference supplied this narrow documentation fallback.

Private evidence is under `output/private/address-triage-20261111/`, including
`ocr-evidence.md`, `ocr-replay-results.json`, `replay-results.json`,
`replay-requests.json`, `frontend-tests.log`, and
`package-reviewed.log` and `replay-reviewed.log`. The primary reviewed worker changes and
performed the final source checks. The subsequent native-image qualification and
production deployment are recorded below.
Publication checks against only the exact staged source are recorded in
`index-package.log`, `index-frontend-tests.log` and `index-replay.log`; all pass.
The latter replays the same nine source rows and five provider cases using the
exported staged application's classes, with no telemetry source overlay.

## Native qualification and production release

Deployed source is `47969db8179d569921402afe3d43ece5386902f8`, release
`20261111-addresses`. Exact push CI `43100047301`, attempt 1, completed with
successful publication and cleanup. Its retained security artifact was verified
against the GitHub metadata digest and reports zero High/Critical findings.
The immutable image is
`ghcr.io/javadevjt/routelisttotesla@sha256:6d410faa695bfaaa3d2a56af847291438d9e130acadba769eef7e56a7779d2bf`.
The extracted application JAR SHA-256 is
`1d4ca655cc61978688d17ebdeb3e64634749d969ec5b7291baca728f1ce595e5`.
Packaged template, theme, postal catalogs and version constants match the commit;
uncommitted application telemetry classes are absent.

Native x86 qualification used the immutable image with its application JAR and
OCR scripts, UID 10001, eight CPUs, 8 GiB, read-only filesystem and no external
network. All 28 ordered screenshot cases passed through the three-engine OCR
contract, including all nine supplied stops and apartment 100. The 36 Python
tests, runtime authentication/CSRF checks and two speed trials passed. Peak
memory was 2,562,355,200 bytes.

The combined QA harness failed in its test-only fixture metadata count after
OCR had passed. The original failed report remains intact. The corrected probe
checks the eleven captured IDs without counting inherited fixture IDs, accepts
the actual OCR report export and retains underlying Java diagnostics. A separate
provider-only run on the same image used hash-bound retained native OCR rows;
it did not rerun OCR or overlay application classes. All 19 prior address rows
matched, allowing only the intended policy-version change. All five new cases
passed their expected statuses and destination identities. It made 53 loopback
fixture requests: 49 matched retained bodies and four uncaptured variants
returned explicit empty responses. There were no external calls or Tesla
commands. `native-completion.json` records both evidence parts and the original
harness failure. Disposable QA apps and their owned staging files were removed.

The mobile browser check rendered the actual packaged template and assets with
the native provider results and mocked APIs. Carina and new Woodland verified;
Morrow displayed the Dearborn hint while manual and automatic dispatch stayed
blocked. Text, source order, 3/3 OCR agreement and the absence of horizontal
overflow were checked. This check issued no external calls or vehicle commands.

TrueNAS production update job `33264` succeeded on November 11 at 22:37 UTC.
The immediate readback was still `DEPLOYING`; read-only reconciliation observed
settled `RUNNING` on the same successful job. Exactly one production `app.update`
was submitted. Only the router image, permission-init image and `APP_RELEASE`
changed. Full configuration and runtime-volume fingerprints confirm preservation
of credentials, networks, resources, UID, read-only settings and `route-data`.
No command/entrypoint override or injected Java agent is present. The predecessor
image remains available and was not deleted.

Production verification at 22:38 UTC observed HTTP 200 health with the exact
source commit, reachable login and theme bytes matching the qualified image.
The live 390-pixel mobile login rendered the expected release metadata, TAP sign-in
link and no horizontal overflow or page errors. The signed-in address review was
verified with the packaged-template fixture above; no authenticated production
route send or new Google request was used as release evidence.

The persistent production URL is [TeslaRouter](https://teslarouter.javadevjt.tech),
with no preset expiry. Refresh the app to load v14. For a previously saved joined
Woodland row, re-upload its source image or explicitly insert the street/city
comma. Morrow remains held until the user checks and explicitly corrects the city;
the application never silently changes Detroit to Dearborn.

Release receipts, original failed attempts and screenshots remain under
`output/private/address-triage-20261111/`: `release-binding.json`, CI/registry/image
verification receipts, `native-completion.json`, `native-ocr.runner.json`,
`native-provider.runner.json`, `browser-addresses.json`, `deployment-intent.jsonl`,
`deployment.json`, `production-verification.json` and `production-browser.json`.
Documentation-only commits after the release are distinct from deployed source.

## Worker dispositions

- `ocr_evidence_resumed`: accepted and released by native completion; independent
  source/TSV ordering and boundary evidence used to confirm the parser failure.
- `locality_feedback_resumed`: accepted and released by native completion;
  primary reviewed display safety, dispatch holds and policy migration, corrected
  the v13 migration fixture to use the actual old `no_safe_match` reason, and
  independently reran all 56 frontend tests.
- `census_area_resumed`: accepted and released by native completion; primary
  inspected the helper, parser, full-data importer and source provenance, then
  integrated only the exact-municipality area fallback and reran the full suite
  after final worker handoff.
- `identity_review`: accepted and released by native completion. It found the
  city/state-line edge and ambiguous legacy-boundary inference; primary added
  state/ZIP-line regressions and removed the unsafe inference, then reran all
  346 Java tests and the supplied-case replay. Requested worker pair for
  every run was `gpt-6-luna` / `max`; native results expose task identities but
  do not independently expose the executed backend model/effort. Original
  interrupted runs were superseded after the restart audit, with surviving
  useful artifacts preserved and reused.
