# TAP delegated authentication

Status: deployed and browser-verified on September 26, 2026. TAP controls identity, RouteList entitlement, and vehicle grants. Independent Google login and the static RouteList owner are removed; Google geocoding remains.

## Ownership and verification

- Primary: RouteList authentication, API integration, release, final verification.
- TAP task `019ceda5-f4ca-7e31-96bf-2696d5835ebc`: delegation contract, TAP endpoints, entitlement policy, backend deployment.
- Existing `integration_review` worker: narrowly extend the existing deployment helper for an image/auth configuration rollout; preserve unrelated app settings and storage. Consumer: primary deployment. Check: reviewed diff plus offline validation, no deployment by worker.
- Existing `navigation_audit` worker: traced persisted ownership, then implemented authorization-denial handling, exact subject matching, and explicit owner migration in the navigation service/tests. Check: every background vehicle call and persisted identity path; 36 focused tests passed.
- `auth_checks` worker (`gpt-6-luna`, `max`, native catalog verified September 26): authentication and delegated-client regression tests only. Consumer: primary review/release. Base: current dirty worktree; released after reviewed test handoff. No deployment or vehicle actions.
- `auth_review` worker (same model/effort): independent read-only review of delegated identity, authorization, token storage, revocation, and the TAP contract. Consumer: primary fixes before deployment; released after findings are handled.

Workers retain the existing verified `gpt-6-luna` / `max` configuration from this run. The current callable catalog lists GPT-6 Luna as the current Luna family, with `max` its highest effort. No provider speed control is exposed.

Acceptance: TAP-authenticated login and denied/revoked access tests; authorization-code state/replay checks; user-scoped vehicle calls including background navigation; existing OCR/navigation tests; preserved TrueNAS volume/configuration; public login and read-only vehicle smoke. No vehicle commands or billing charges are part of release verification.

Final browser review completed: `client_audit` corrected the template rendering failure that left the vehicle picker on its loading placeholder despite a successful authenticated API read. Primary rebuilt, redeployed, and verified both populated vehicle options and the actual upload/review controls.

The UI correction was accepted after a verified failing-then-passing rendering regression and primary review; the full package passed 77 tests. `client_audit` owns the final README, documentation index, iPhone guide, OAuth setup, and modernization audit updates. Primary retains this release ledger, machine-readable evidence, deployment, and final live verification.

## Agreed contract

- Client `routelist`, fixed callback `https://teslarouter.javadevjt.tech/login/oauth2/code/tap`.
- TAP `/auth/delegate`: authorization code, scope `routelist`, state, PKCE S256; TAP handles sign-in and consent.
- Form-encoded `/api/v1/delegation/token`: one-use code, redirect URI and verifier, with confidential `X-TAP-Client-Key`. Standard `access_token`, `token_type=Bearer`, `expires_in`, `scope` response.
- Bearer GET/DELETE `/api/v1/delegation/session`: current stable `sub`, profile and `entitlements: ["routelist"]`; DELETE revokes the grant.
- Hub requests use the delegated token in `X-TAP-Client-Key`. TAP dynamically checks entitlement and vehicle access; navigation only, no arbitrary command scope.
- Eight-hour grant, no refresh token. Tokens remain in memory; restart or expiry requires login before further vehicle work.
- TAP email is not currently verified. Identity is the stable subject, never email. Legacy data requires an explicit operator-bound migration, not trust in an email claim.

## Release evidence

- TAP configuration job **205946** succeeded. Only `backend.environment.APP_ROUTELIST_REDIRECT_URI` and `APP_ROUTELIST_SERVICE_CLIENT_ID` changed. Full configuration readback matched; existing image and storage were preserved; TAP/backend returned RUNNING. TAP task owns the subsequent endpoint image release.
- Deployment-helper worker accepted after primary review and independent run of three passing config-transformation tests. Its native worker completed; no worker-side deployment occurred.
- Independent auth review found no concrete identity, callback, CSRF, token-leakage, or redirect flaw. Its saved-route migration race was fixed by serializing saves and migration on the same service lock; the existing-target preservation regression remains part of release checks.
- Browser verification must not issue vehicle commands. Restored RUNNING sessions become PAUSED or ERROR before login; identity migration preserves that status and never starts navigation. The browser smoke is limited to login, account/vehicle reads, and screenshot extraction/review.
- Navigation worker accepted after primary inspection of the startup/migration/denial paths and its 36 passing tests. Auth-review and documentation workers completed their bounded reviews. Native completion released each worker; final integration tests and release verification remain primary-owned.
- Auth-test worker completed 14 passing checks, including the actual Spring authorization-code flow, state/PKCE/replay, subject isolation, revocation/expiry, and CSRF. Primary `clean package` then passed all **76 tests**; packaged startup verified login 200, TAP/PKCE redirect 302, unauthenticated vehicle access 302, and missing-CSRF POST 403. Container JAR SHA-256 matched the tested artifact.

## Final corrected release

- TAP provider job **206100** succeeded. Its owner reported 638 backend, 278 browser, 14 SDK, and seven public smoke tests passing. The real TeslaRouter consent/callback was subsequently verified against that deployment.
- The first delegated-login image deployed in job **206108** passed authentication/API checks but failed full page rendering: Thymeleaf interpreted a nested JavaScript array as an inline expression at `index.html:884`. Server logs established the cause; replacing the array entries with objects fixed it. An authenticated template regression was verified failing before and passing after the change.
- Corrected release **`20260926-tap-auth-ui`** is running on TrueNAS app `routelisttotesla` after successful job **206218**. Its immutable image is `ghcr.io/javadevjt/routelisttotesla@sha256:023375e4c640da1689a1982e91347c7d6b4f0828fee2d3275263ec5faca8fccd`. Complete configuration readback matched; port 10088, restart policy, and `ix-routelisttotesla_route-data:/app/cache` were preserved.
- The final clean package passed **77 tests**, with no failures, errors, or skips. Tested JAR and container JAR SHA-256 match: `8b298d9a2d685449f8bc23a65f2b00519a9ff1008cfad7de17ee173c8a747229`.
- Fresh public TAP consent returned to authenticated TeslaRouter. Served JavaScript parsed successfully; all **three** scoped vehicles appeared in the actual picker. The screenshot API returned four candidates, all geocoded.
- At a **390 × 844 Chromium viewport**, selecting the fixture and clicking **Process Images** produced **four visible review rows**. The cache endpoint returned 200; only its browser response was changed to a cache miss to exercise multipart upload. One session-save request was intercepted to preserve existing state. The saved-route fingerprint remained unchanged; browser storage was restored and the page reloaded afterward.
- No manual Add Address button, auto-started navigation, vehicle commands, vehicle wakes, or billing mutations occurred. Physical iPhone and vehicle-navigation acceptance remain untested. Existing tabs opened before the correction need a refresh.

The machine-readable record is [deployment verification](../output/deployment-verification-20260926-tap-auth.json). Browser evidence: [populated mobile picker](../output/playwright/tap-auth-mobile.png) and [mobile address review](../output/playwright/tap-auth-review-mobile.png).
