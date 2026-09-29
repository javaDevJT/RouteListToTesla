# TAP delegated login setup

RouteList uses TAP for browser sign-in, consent, identity, billing entitlement, and per-subject vehicle access.
It does not use an independent Google OAuth client, Google-account allowlist, or static owner email for normal
login.

## Authorization flow

1. RouteList's login page offers **Continue with TAP**, which starts `/oauth2/authorization/tap`.
2. RouteList begins an authorization-code flow with state and PKCE S256; TAP handles user sign-in and consent
   for the `routelist` scope.
3. TAP redirects to the fixed RouteList callback: `https://teslarouter.javadevjt.tech/login/oauth2/code/tap`.
4. The server exchanges the one-use authorization code and PKCE verifier at TAP's form-encoded
   `/api/v1/delegation/token` endpoint. The confidential client key is sent only in this server-side exchange.
5. RouteList validates the delegated token through `/api/v1/delegation/session` and requires a stable `sub` plus
   the `routelist` entitlement.
6. TAP checks the delegated subject's current entitlement and vehicle grants on each hub operation. The app uses
   the delegated token in `X-TAP-Client-Key` ; route navigation is the granted scope, not arbitrary vehicle
   commands. Revoking access blocks browser and navigation requests.

## Server configuration

- `TAP_BASE_URL` is TAP's API base and includes `/api/v1` (the configured default is
  `https://tap.javadevjt.tech/api/v1` ).
- `TAP_CLIENT_ID` is the fixed application client identifier `routelist`.
- `TAP_CLIENT_KEY` is a confidential server-side key used only for exchanging an authorization code with TAP.
  Store it in the approved secret mechanism; never put it in source, browser code, command arguments, logs, or
  docs.
- `TAP_REDIRECT_URI` is fixed to `https://teslarouter.javadevjt.tech/login/oauth2/code/tap` and must match the
  URI registered with TAP exactly.

Keep TAP login configuration separate from Google Maps geocoding. The existing `google.api.key` setting remains
for address geocoding and is not used for identity or authorization.

## Session, identity, and legacy data

Delegated grants last eight hours, have no refresh token, and are kept in memory. Expiry or an application
restart requires the user to sign in again before further vehicle work. The stable TAP `sub` is the identity
key. TAP email is unverified profile data and must not authorize a user or select an owner record.

TAP owns the `routelist` entitlement, billing state, and vehicle grants. Do not restore a Google account
allowlist or an email-based static owner. If existing RouteList data needs an explicit one-time ownership
migration, bind it with both `TAP_LEGACY_OWNER_EMAIL` and `TAP_LEGACY_OWNER_SUB` ; email alone is never
sufficient.

## Deployment status

**Status: READY (September 26, 2026).** TAP provider job **206100** and TeslaRouter auth job **206108**
succeeded; fresh public consent/callback after restart, current-entitlement checks, and three scoped vehicle API
reads passed. TrueNAS job **206218** now serves pinned image `20260926-tap-auth-ui`
(`sha256:023375e4c640da1689a1982e91347c7d6b4f0828fee2d3275263ec5faca8fccd`). The corrected UI passed desktop
Chromium smoke at 390x844; physical iPhone/car and vehicle acceptance remain untested, and no billing charge
occurred. Rotate the TAP credential before **2026-12-24T17:35:44Z**. See the
[verification index](docs/README.md#verified-deployment-2026-09-26) .
