# Modernization audit and current architecture

Updated September 26, 2026. This document preserves the audit findings and TAP route-arrival contracts. It is
not a release report; root owns current build, deployment, and live-service verification.

## Current architecture

The earlier OpenAI vision-extraction proposal is superseded. `AddressOcrService` now invokes the local Tesseract
CLI (`tesseract-cli-v1`) on the application server. Screenshot extraction has no OpenAI or other AI-model API
dependency. Google Maps geocoding remains a separate service for address candidates.

Vehicle discovery, telemetry, and navigation commands use TAP-V2 only. The application does not use a direct
Tesla Fleet or Teslemetry command client. TAP command acceptance and telemetry-confirmed arrival are separate
events.

The browser workflow selects the authorized Tesla VIN in Step 1, optionally sets a default state, uploads
screenshots, preserves their chosen order, and processes them. Step 2 reviews the extracted addresses; users may
edit or remove candidates. There is no Add Address from scratch and no manual text importer. A reviewed route is
sent by a **Send Route 1 to Tesla** group button, or the user starts **Use Automatic Navigation** with **Start
Automatic Navigation**. Routes respect the eight-waypoint limit.

On iPhone, use Safari’s native Photos/Files picker to select screenshots; RouteList receives only images you
choose and has no background camera-roll access. An iOS Shortcut or share-sheet importer is not implemented.

## Audit findings and preserved contracts

| Area | Finding | Contract to preserve |
| --- | --- | --- |
| Command acceptance | HTTP success or a matching word in a response is not proof that TAP accepted a command. | Require the explicit TAP command state and `accepted=true`; never treat transport success as vehicle completion. |
| Ambiguous command outcome | A lost response can leave the command's effect unknown. | Persist a stable command key before dispatch, do not blindly resend after ambiguity, and hold subsequent sends until the user checks the vehicle and clears the hold. |
| Arrival detection | Missing gear, stale location, or empty route fields can look like completion if treated as defaults. | Advance only from fresh, ordered telemetry with per-field source timestamps and evidence tied to the command/waypoint. Unknown gear, replay gaps, stale data, and ambiguous evidence fail closed. |
| Park and location evidence | A Park transition can precede a separate location packet. | Correlate the post-command drive-to-Park transition and fresh location to the expected waypoint; consume the pending arrival once. Discard it on departure, stale evidence, or restart. |
| Route completion | Dispatching the last batch does not mean its destinations were reached. | Mark stops complete only after their confirmed arrivals; complete the route only after the final stop is confirmed. |
| TAP ownership | Browser/session and background calls use the stable TAP subject and per-subject grant. | TAP entitlement and revocation govern browser and navigation access; email is unverified profile data. |
| Browser content and order | OCR text and file names are untrusted; sorting files differently from the visible list changes the route. | Render extracted text as text, use the user's explicit image order, preserve multipart stop order, and retain repeated visits for review. |
| Review bypass | An extract-and-send path can issue a command before a user reviews OCR. | Keep extraction and reviewed sending separate; the legacy `/route/places/{vin}` path is retired with HTTP 410. |
| Cache and geocoding | Default-state changes affect extracted addresses; provider failures can otherwise hide or drop stops. | Key new cache entries by extraction version, normalized default state, and image content; preserve legacy entries. Keep unresolved stops visible and block send until every reviewed stop is resolvable. |

## Capture workflow and future work

Use Safari’s browser picker to choose PNG/JPEG screenshots from Photos, arrange their order in the selected-file
list, process them, then review, edit, or remove extracted addresses. A Home Screen browser bookmark is optional
on iOS 26+; leave Open as Web App off to keep it in the browser. Standalone mode and physical-iPhone behavior
remain unverified.

Safari’s multi-photo picker and address review require no native app or Shortcut. Consider a native share
extension only if a measured user need warrants it. Do not put TAP credentials, screenshots, or addresses in a
Shortcut URL. A manual text importer is not implemented.

## Configuration boundaries

TAP configuration uses `TAP_BASE_URL` (including `/api/v1` ), `TAP_CLIENT_ID=routelist` , fixed
`TAP_REDIRECT_URI` , and server-only `TAP_CLIENT_KEY` for code exchange. Existing-data migration alone may use
the explicit `TAP_LEGACY_OWNER_EMAIL` + `TAP_LEGACY_OWNER_SUB` pair. Google Maps setting `google.api.key`
remains a separate geocoding configuration.

There is no OCR-provider API key or model setting in the current extraction path. Do not reintroduce an external
vision provider as a fallback for missing local Tesseract configuration.

## Verification boundary

This audit preserves design and failure-mode findings separately from current release evidence. The earlier
deployment snapshot below is historical; current TAP-auth and UI verification appears in the final section.

## Historical deployment snapshot (2026-09-26)

Historical pre-auth baseline: the TrueNAS app `routelisttotesla` ran immutable image
`ghcr.io/javadevjt/routelisttotesla@sha256:e324c56ccc271cba17546d277466e7f26b854a3fdd292ce6e9b07a7b9b57e328` ,
with persistent volume `ix-routelisttotesla_route-data` mounted at `/app/cache` and restart policy
`unless-stopped` . Job 205793 completed successfully. The public login page returned HTTP 200 with release
metadata `20260926-local-ocr` ; the unauthenticated vehicle route redirected to the HTTPS host. This snapshot
predates TAP auth jobs 206100 and 206108.

The earlier public-route checks did not establish TAP sign-in or an authenticated upload; those checks are
recorded below. No physical navigation acceptance, vehicle commands, or wakes were performed in that earlier
check. See the [deployment evidence index](README.md#verified-deployment-2026-09-26) for current release
evidence.


## TAP delegated login and current UI release status (2026-09-26)

The source login flow uses TAP authorization-code delegation with state and PKCE S256, the `routelist` scope,
and callback `/login/oauth2/code/tap` . TAP validates stable subject and entitlement; eight-hour grants have no
refresh token and are memory-only. TAP revocation blocks browser and navigation requests. A legacy data move
requires both explicit `TAP_LEGACY_OWNER_EMAIL` and `TAP_LEGACY_OWNER_SUB` ; unverified email is not identity.

**Status: READY (September 26, 2026).** TAP provider job **206100** and TeslaRouter auth job **206108**
succeeded. After restart, fresh public consent/callback and current-entitlement checks passed; three scoped
vehicle API reads and fixture screenshot geocoding (**4/4**) passed. TrueNAS job **206218** succeeded, and the
router runs pinned release `20260926-tap-auth-ui`
(`ghcr.io/javadevjt/routelisttotesla@sha256:023375e4c640da1689a1982e91347c7d6b4f0828fee2d3275263ec5faca8fccd`);
configuration readback matched, volume `ix-routelisttotesla_route-data` remains mounted at `/app/cache` , and
port **10088** is active. The Thymeleaf `[[...]]` fix and rendering regression pass in the clean package: **77
tests**, zero failures, errors, or skips; JAR SHA-256
`8b298d9a2d685449f8bc23a65f2b00519a9ff1008cfad7de17ee173c8a747229` . The 390x844 desktop Chromium smoke parsed
all served scripts and verified three vehicles/options, fixture upload HTTP 200, four visible review rows, and
cache-check HTTP 200. The browser forced only a cache miss and intercepted one session-save; the saved
fingerprint stayed unchanged. No auto-started `RUNNING` job, manual Add control, forbidden mutation, vehicle
command, or billing charge occurred. Browser/UI readiness is READY for this tested viewport; physical iPhone/car
and vehicle acceptance remain untested. Rotate the TAP credential before **2026-12-24T17:35:44Z**.
