# Security audit — September 26, 2026

**Current status: S1–S4 remediated and deployed September 27, 2026.** See the
[security/OCR release record](security-ocr-release-2026-09-26.md) and
[final evidence](../output/security-ocr-final-verification.json) for the immutable image and verification.
The original audit sections below describe the superseded September 26 baseline.

| Finding | Closure evidence |
| --- | --- |
| S1: replacement grants revive older authority | Grant fingerprint binding, synchronized dispatch/replacement, and conditional logout pass regression tests; repaired classes are in the deployed image. Fresh TAP login passed after restart. |
| S2: geocoding before vehicle authorization | Navigation authorization now precedes geocoding, with owner/global budgets and preserved pacing. Regression tests pass; live OCR verification used negative fixtures and no geocoding quota. |
| S3: forwarded-host redirect poisoning | Real Tomcat regression reproduced the mocked-test gap. Relative servlet redirects are enabled; all four final public spoof-header probes return relative `/login`, including `X-Forwarded-Host: audit.invalid`. |
| S4: Tomcat dependency advisory | Final JAR includes Tomcat 10.1.60, Spring Boot 3.5.16, and Jackson 2.21.7. |

Additional deployed protections include owner-scoped OCR cache, CSRF, CSP nonces, bounded JSON/OCR work,
a nonroot read-only runtime, dropped capabilities, and private NPM connectivity. Host port 10088 is removed
and refused a connection. A harmless authenticated POST without CSRF returned 403.
Public login and OAuth entry/callback endpoints necessarily remain available; protected data and command
routes require TAP authentication and applicable grants.

The local TrueNAS management helper retains the explicitly approved self-signed TLS exception for
192.168.0.2:8443; that management connection does not certificate-verify server identity. Public TeslaRouter
HTTPS is separate. No vehicle commands, wakes, billing changes, or destructive probes were used.

## Historical audit scope

Scope: the current RouteListToTesla worktree and its deployed TrueNAS service,
including the TAP delegation boundary. This is an authorized source review and bounded, read-only
deployment verification. Vehicle commands, billing changes, destructive probes, and credential
disclosure are excluded.

Base revision: `56bb3ccf701d0541e8ca4d708c2441c7223a4156`, with existing modernization changes preserved.
Deployed baseline: `20260926-tap-auth-ui`; live configuration and served identity matched during this audit.
The running image remains
`ghcr.io/javadevjt/routelisttotesla@sha256:023375e4c640da1689a1982e91347c7d6b4f0828fee2d3275263ec5faca8fccd`.

## Acceptance checks

- Inventory every application endpoint and document the necessary public login/callback surface.
- Verify that anonymous requests cannot read route, image, vehicle, telemetry, or session data, or mutate state.
- Verify delegated token, CSRF, session, entitlement, object ownership, and background authority boundaries.
- Review input handling, cache paths, browser rendering, credential packaging/logging, and dependencies.
- Check deployed headers, cookies, redirects, protected endpoints, and network exposure without vehicle actions.
- Record confirmed findings, fixes, evidence, limitations, and deployment status separately.

## Work ownership

Workers use `gpt-6-luna` at `max`, resolved from the live native spawn catalog on September 26, 2026.
The catalog identifies the other callable Luna version as older. This follows the swarm skill's
central model policy. The native surface exposes no separate speed control.

| Owner | Scope | Consumer and check | State |
| --- | --- | --- | --- |
| Primary | Endpoint inventory, deployment checks, integration, final report and all unassigned writes | Final security assessment; deployed anonymous-access evidence | Complete |
| `/root/security_auth` | Auth/session review; one separately owned audit reproducer | Primary; grant replacement confirmed with real service and local TAP stub | Accepted |
| `/root/security_data` | Route/data review; one separately owned audit reproducer | Primary; actual controller geocoding call count confirmed with mocked collaborators | Accepted |
| `/root/security_exposure` | Build/container/configuration, secret locations, dependency inventory; read only | Primary; redacted packaging and advisory applicability review | Accepted |

Workers did not redelegate or make external changes. All worker findings were reviewed by the primary.
The first geocoding demonstration duplicated implementation logic and was rejected; its replacement invokes
the production controller. Both accepted reproductions were independently compiled and run with Java 21.

## Findings

### S1 — Medium: a new login can reauthorize an older session or running route

`TapAccessService.requireAccess` and `tokenFor` look up the OAuth client using only the registration and
TAP subject. A second browser logging into the same account replaces that account's stored client.
The old browser session contains the same subject and therefore uses the new browser's grant.

Concrete sequence: browser A holds a valid local session; TAP revokes A's grant; browser B obtains a new
grant for the same subject before A next makes a protected request. A is then checked against B's grant
and remains authorized. This requires an existing session and a subsequent same-account login; it is not
an anonymous login bypass or a cross-account access bug. If A's denied request happens first, the current
filter invalidates A's session. Logging out A locally also invalidates that local session.

The current behavior is account-wide delegation. If that policy is intentional, revoking one TAP grant
must not be presented as revoking every associated local session. The finding is a medium revocation risk
under the expected rule that a revoked grant stops the browser/background authority established with it.

The same owner-based lookup is used by background navigation. If B is stored before the next poll observes
A's revocation, an already-running route can continue using B. This consequence is supported by the call
graph; the audit did not exercise live background vehicle commands.

Evidence: [TapAccessService.java](../src/main/java/com/jtdev/routelisttotesla/service/TapAccessService.java),
lines 63, 72–101; [SecurityConfig.java](../src/main/java/com/jtdev/routelisttotesla/config/SecurityConfig.java),
lines 100–108; [AutoNavigationService.java](../src/main/java/com/jtdev/routelisttotesla/service/AutoNavigationService.java),
owner-based telemetry/command calls; and
[SessionGrantRevivalAudit.java](../output/security-audit-repros/SessionGrantRevivalAudit.java).
The reproduction uses the real authorized-client store and `TapAccessService` with a loopback fake TAP;
it confirmed that A's next introspection uses B's token.

Remediation: bind browser and running-navigation authority to the grant that established it, while keeping
the stable TAP subject as the data owner. Replacing an account-level client must not silently transfer
authority to old sessions or revive background work. Add a two-browser revocation regression and an
equivalent background-navigation check. This matters particularly when users revoke a compromised session.

### S2 — Medium: authenticated requests can spend geocoding quota before vehicle authorization

`POST /route/auto-navigate/{vin}` checks account entitlement and VIN syntax, then geocodes as many as 500
text-only candidates before creating and starting a navigation session. Actual access to that VIN is
checked later by TAP during scheduled telemetry polling. A syntactically valid but ungranted VIN therefore
does not prevent the initial geocoding work. Repeated or concurrent entitled requests multiply the cost.
The 60 ms pacing is not a per-account budget or concurrency limit.

Evidence: [RouteController.java](../src/main/java/com/jtdev/routelisttotesla/controller/RouteController.java),
lines 113–124 and 223–239; [GeocodingClient.java](../src/main/java/com/jtdev/routelisttotesla/service/GeocodingClient.java),
lines 36–77; and [GeocodeAuthorizationAudit.java](../output/security-audit-repros/GeocodeAuthorizationAudit.java).
The real controller made 500 calls to the mocked geocoder and returned a real session containing all 500
addresses. The harness stops at the mocked navigation service; it does not claim runtime proof of the
later telemetry rejection. No Google API or vehicle was contacted.

Remediation: check the selected vehicle's grant before billable work, add per-user/global geocoding and
concurrency budgets, and bound JSON bodies at the ingress layer. Google-side quotas were not inspected.
This requires an authenticated, entitled account; anonymous requests were blocked before service calls.

### S3 — Low: untrusted forwarding headers control login redirects

The public deployment accepts both `Forwarded: proto=http;host=audit.invalid` and
`X-Forwarded-Host: audit.invalid`. Each changed the host in the `/route/vehicles` login redirect. The first
also downgraded the generated URL to HTTP and suppressed HSTS on that response. `X-Forwarded-Proto` alone
was overwritten by the proxy. Normal traffic uses HTTPS, HSTS, and Secure cookies.

Evidence: [application.yml](../src/main/resources/application.yml), line 3;
[SecurityConfig.java](../src/main/java/com/jtdev/routelisttotesla/config/SecurityConfig.java), line 66; and
the four isolated header probes in [live results](../output/security-audit-live-20260926.json).
The configured OAuth callback remains fixed. No authorization-code theft, cookie theft, cache poisoning,
or browser-exploitable redirect chain was demonstrated; an ordinary link cannot set these request headers.

Remediation: strip externally supplied `Forwarded` and `X-Forwarded-*` values at the trusted proxy, then
set only the required authoritative values. Restrict origin access to that proxy and prefer canonical or
relative application redirects. Spring explicitly documents the need for this proxy trust boundary in
its [ForwardedHeaderFilter documentation](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/ForwardedHeaderFilter.html).

### S4 — Low, version-confirmed: Tomcat has a relevant denial-of-service advisory

The deployed JAR contains Tomcat 10.1.55. Apache's September 23, 2026 advisory
**CVE-2026-77756** covers HTTP/1.0 requests with `Transfer-Encoding` behind a reverse proxy; affected
Tomcat versions can cause another user's request to fail. The fixed version is 10.1.60. This application's
version and proxy topology match, but the malformed-request path was not exercised against production.
No request-smuggling or denial-of-service traffic was sent.

Remediation: upgrade to a compatible patched Tomcat release through the dependency management, then
rerun packaging, security, and deployed proxy checks. Source: Apache's
[Tomcat 10 security advisories](https://tomcat.apache.org/security-10.html).

## Other exposure and privacy observations

- TrueNAS publishes `10088:10088` on all host interfaces; direct LAN HTTP is reachable. Those requests
  still require authentication and receive Secure cookies. This is not a demonstrated authentication
  bypass. Prefer a private proxy-to-application network with no broad host publication. Internet NAT and
  firewall rules were not inspected, so this report does not claim that port 10088 is publicly reachable.
- The container has no explicit user, read-only filesystem, capability-drop list, or `no-new-privileges`
  setting. The Dockerfile does not set `USER`. Improve isolation and keep only the cache/temp paths
  writable. No container escape or code-execution vulnerability was demonstrated.
- The OCR cache is shared between accounts. An entitled user who already knows an exact image SHA-256
  and default state can learn whether it is cached and receive data extracted from that same image.
  The response substitutes the requester's filename and does not disclose the original uploader.
  No arbitrary cross-account private read was demonstrated. Scope cache keys by owner if processing
  history itself must be private; a hash is not an authorization mechanism.
- Public static/OAuth wildcard allowances are broader than the current application needs. No protected
  controller currently uses them, but exact entry-point allowances would reduce future exposure risk.
- No CSP, Referrer-Policy, or Permissions-Policy was returned by the sampled public login response.
  The existing headers include `nosniff`, frame denial, no-store, and HSTS. These missing headers are
  defense-in-depth opportunities, not proof of XSS or data disclosure.

## Credentials, packaging, and dependencies

Gitleaks 8.30.1 was downloaded from its official release into a temporary directory, checked against the
pinned release archive SHA-256, and run offline with complete value redaction. No source was uploaded.
The six locally reachable Git commits produced no findings. The current tracked/nonignored text-file
scan covered 76 files and flagged two generic keys; both were verified to be test **Idempotency-Key**
values in `RouteControllerTest.java:96` and `TapVehicleClientTest.java:33`, not authentication credentials.
See [scanner metadata and redacted findings](../output/security-audit-gitleaks-20260926.json).
The report retains the scanner's temporary mirror paths for those two findings.

An additional bounded source/config/helper review found no confirmed production credential leak.
The ignored TAP credential file is mode 0600 and excluded from the Docker context. Its values were not
printed or included in this report. The ignored IDE credential file was not opened; `.idea` is excluded
from the Docker context. The packaged-startup helper's apparent credential is a local test fixture.

The inspected JAR's SHA-256 is
`8b298d9a2d685449f8bc23a65f2b00519a9ff1008cfad7de17ee173c8a747229`, matching the deployed release evidence.
It contains 229 ZIP entries and 54 embedded dependency JARs. Private configuration paths, `.env`,
`workspace.xml`, and `application.properties` were absent. The worker's bounded signature review of JAR
entries found no high-confidence private-key/token patterns; this is not a proof that arbitrary bytecode
can never contain a secret.

Resolved runtime versions include Boot 3.5.16, Spring Framework 6.2.19, Spring Security 6.5.11,
Tomcat 10.1.55, and Jackson Databind 2.21.4. The Maven runtime tree contains 57 artifacts; that is a
different count from embedded JAR files.

Several current advisories version-match dependencies but require features not found in this code:

- Jackson [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54)
  requires name-based polymorphic fallback, and
  [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24)
  requires identity-enabled forward references. Both were published September 22, 2026 and have fixes
  in 2.21.7. The production DTOs/configuration use neither feature.
- Jackson [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7)
  requires binding untrusted JSON to XML duration/calendar types; those types were not found.
- Spring Security [CVE-2026-41707](https://spring.io/security/cve-2026-41707/) concerns DPoP proof replay.
  This app uses OAuth client login, has no resource-server dependency, and does not configure DPoP.
- Other reviewed Spring/Tomcat notices require binding/rendering, SSE, HTTP/2, AJP, or WebSocket features
  not found in the current application configuration. They were not promoted to demonstrated app findings.

Keep dependency patching current, but do not equate a version match with demonstrated exploitability.
No comprehensive OS/container vulnerability scanner was run; this was a targeted primary-advisory review.

## Findings and verification

The deployed immutable image still matches the previous release, and TrueNAS reports it running.
Forty-seven bounded read-only HTTP checks completed without network errors. Public and LAN route/session/vehicle
reads redirected anonymous clients to login; TAP vehicle and delegation-session reads returned 401.
Public HTTP redirects to HTTPS. Session cookies are Secure, HttpOnly, and SameSite=Lax.

The full Maven suite passed **80 tests, zero failures/errors/skips**, with 15 fresh Surefire reports.
The new endpoint regression passes all three tests: every route endpoint rejects an anonymous
request even when it carries a valid CSRF token; every route mutation rejects an authenticated request without
CSRF; denied or unavailable TAP entitlement checks cannot reach application services.

Both audit reproductions compiled and passed on OpenJDK 21.0.12.1. They exercise actual application classes
with mocked collaborators or a loopback TAP stub, not the real TAP account or vehicle. Reproduction
commands and results are recorded alongside the sources.

Evidence:

- [Live HTTP results](../output/security-audit-live-20260926.json), with cookies redacted.
- [Container configuration summary](../output/security-audit-infra-20260926.json), without credential values.
- [Read-only HTTP probe](../output/security-audit-probe.py).
- [Read-only deployment inspector](../output/security-audit-infra.mjs).
- [Endpoint regression](../src/test/java/com/jtdev/routelisttotesla/config/EndpointSecurityAuditTest.java).
- [Audit reproduction instructions](../output/security-audit-repros/README.md).
- [Audit reproduction results](../output/security-audit-repros/results-20260926.json).
- [Secret scan runner](../output/security-audit-secret-scan.py).

No production configuration or application code has been changed by this audit.
The original September 26 audit added regression coverage and evidence only; S1–S4 remained open at that point.
Their subsequent September 27 remediation and deployed closure are recorded above.

This review does not cover all TAP billing/provider internals, router/NAT rules, every transitive or OS
advisory, exhaustive parser fuzzing, or every possible concurrency interleaving. No vehicle commands,
wakes, billing mutations, live geocoding requests, load tests, or destructive security probes were used.

## Endpoint inventory

Every endpoint in the following table requires a TAP-authenticated local session and a successful current
TAP entitlement check. All non-GET endpoints additionally require CSRF. The local regression enumerates
the actual Spring route mappings rather than maintaining a separate list of test URLs.

| Method | Path | Data or action |
| --- | --- | --- |
| GET | `/route/vehicles` | Vehicles authorized by TAP |
| POST | `/route/places` | Image OCR and geocoding |
| POST | `/route/places/{vin}` | Retired direct-send endpoint; returns 410 after authentication |
| POST | `/route/send/{vin}` | Reviewed navigation command |
| POST | `/route/cache/check` | Content-addressed OCR cache lookup |
| POST | `/route/auto-navigate/{vin}` | Create and start automatic navigation |
| GET | `/route/auto-navigate/status/{sessionId}` | Owner-checked navigation status |
| GET | `/route/auto-navigate/active` | Current owner's active navigation |
| POST | `/route/auto-navigate/stop/{sessionId}` | Owner-checked stop |
| POST | `/route/session/save` | Save current owner's reviewed route |
| GET | `/route/session/load` | Load current owner's reviewed route |
| GET | `/route/session/check` | Current owner's saved-route availability |
| DELETE | `/route/session` | Delete current owner's saved route |

The `/` application page is protected too. Necessary public entry points are `/login`,
`/oauth2/authorization/tap`, and `/login/oauth2/code/tap`. The callback requires the matching local session,
state, and PKCE exchange; a fabricated code/state only redirects to the generic login error page.
The configured authorization rule also permits `/error` and the broader OAuth/static prefixes. Direct
`/error` exposed only status/error/timestamp metadata; nonexistent static resources returned generic 404s.
There are no actual public static assets in the current resource tree. Narrowing these wildcard allowances
is a useful way to prevent future endpoints accidentally inheriting public access.

Anonymous GET `/logout` redirected to login. State-changing logout uses Spring Security's CSRF protection.
No protected data was returned by the tested `.env`, Git, actuator, OpenAPI, or Swagger paths.
