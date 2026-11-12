# Miller Avenue apartment verification

Release `20261111-miller` is deployed at
[TeslaRouter](https://teslarouter.javadevjt.tech), with application source
`92594ee7706fe091205e88615a0b55a9a05c084b`. Public health and mobile sign-in
checks passed on November 11, 2026, at 21:58 America/Detroit (02:58 UTC on
November 12). This is the persistent production deployment with no preset expiry.

## Problem and correction

The screenshot contains Wellington Circle, Miller Avenue apartment 109, and
Royal Berkshire Lane in that order. The first and third were already verified.
OCR correctly extracted `414 MILLER AVE APT 109, ROCHESTER HILLS, MI`, but
verification held the apartment because Google labels its exact rooftop as
`Rochester`. The postal catalog does not list Rochester Hills for that ZIP.
A corroborating parent also carries a bare `subpremise` type tag despite having
no actual apartment component.

Policy `street-address-v15` adds a bounded recovery. It retains the original
text, apartment, unit place ID and rooftop coordinates. It requires:

- Exact, nonpartial unit rooftops matching the unchanged house, street, unit,
  state and country; original and constrained queries must share unit ID/point.
- One unambiguous reverse postal result for the exact ZIP/state/country, with
  both city names in explicit `postcode_localities`, valid bounds containing
  the point, and a finite centre inside those bounds and within 10 km.
- Matching reverse and forward parent rooftops when parent and unit IDs differ.
  Only this corroborated unitless-parent role tolerates the bare type tag.
  Structured units and formatted unit labels must agree.
- No competing rooftop identity or conflicting supplied-city result, including
  a rival in another ZIP and either provider response order.

The approximate postal geometry never supplies navigation coordinates. Long or
irregular ZIP areas can decline this optional recovery under the conservative
10 km bound. Existing successful locality and parent proofs remain unchanged.
Google documents the field as localities contained in a multi-locality postal
code: [Geocoding responses](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding).

The review row explains the postal-city difference and retains the user's
address. Browser/server policy constants both use v15; restored v14 address
results are re-resolved. OCR remains `ocr-consensus-v10`. Image order, source
metadata, cache/session data and the eight-waypoint limit are preserved.

## Authority and verification

The user approved up to twelve read-only Google requests for this new case using
the existing key. Five were used and all five bodies retained. They establish
the unit-109 rooftop, the explicit two-city ZIP list and the matching parent
ID/point. Release checks replay those bodies with zero additional Google calls.
No Tesla commands were sent. Earlier case authorization/accounting is separate.

Private evidence, authorization, request journal and operational receipts remain
in `output/private/miller-triage-20261111/`. No credentials are included in
source, command arguments, logs or this document.

- Exact staged source excludes unrelated telemetry: clean package with 347 Java
  tests, no failures/errors/skips; 58 frontend tests passed.
- Negative tests cover postal metadata/bounds, wrong house/street/city/state/
  country/ZIP, formatted and structured unit conflicts, competing rooftops and
  cross-ZIP rivals in both orders. Independent review identified the formatted
  unit gap, which root corrected; final competitor review found no further issue.
- Native immutable-image OCR passed all 29 ordered screenshots using Tesseract,
  PaddleOCR and EasyOCR. Miller preserves all three rows, apartment 109,
  zero-based line index 1 and three-engine agreement. Runtime, Python dependency/
  tests, CSRF, denied unauthenticated reads and speed checks also completed.
- Native provider replay preserves all 24 prior results; Miller is
  `VERIFIED_UNIT` through four saved requests. The complete replay makes 57
  loopback requests: 53 retained responses and four explicit empty fallback
  variants. External and vehicle calls are zero.
- Qualification combines retained full OCR with corrected provider-only replay
  on the same image/JAR. The first harness failed after OCR and prior replay by
  reading `sourceImage` on a row instead of its enclosing image case. Its failed
  receipt is preserved. Cleanup and unchanged production were reconciled before
  the provider-only run; no OCR result or failed record was rewritten. Both QA
  apps and staging files were removed.
- Mobile review uses the actual native Miller result and packaged image assets.
  Original Rochester Hills/APT 109 text and postal-city feedback are visible;
  manual/automatic controls are enabled with no overflow. APIs are isolated
  fixtures; neither send nor start was clicked.

## Deployed release

[CI run 43133312391, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/43133312391)
passed build, zero High/Critical findings, storage, publication and cleanup gates.
The earlier `7b2d852` build was canceled as obsolete after a final cross-ZIP
negative test exposed a gap; it was never deployed. Both fixes are pushed.

| Identity | Verified value |
| --- | --- |
| Application source | `92594ee7706fe091205e88615a0b55a9a05c084b` |
| Release | `20261111-miller` |
| Immutable image | `ghcr.io/javadevjt/routelisttotesla@sha256:62bc36381ee7a1b2bbe52be85f45791d325d3227e5b93e6c7aa3804a4d9bac42` |
| JAR SHA-256 | `dba5306e79404ce13ded93a4789608baabc93972e8e93bbbc8d82cde0dd40583` |
| Native run | `native-43133312391-1` |
| Production update | TrueNAS job `34180`, one submission, success, no retry |
| Public health | HTTP 200, matching application source |
| Mobile sign-in | HTTP 200, TAP link visible, no overflow/page errors |

Registry digest, OCI/source revision, revision tag, packaged template/theme and
both postal resources match committed source. Runtime retains UID
`10001:10001`, read-only filesystem, eight CPUs, eight GiB, production network
and persistent volume. Only both service image references and `APP_RELEASE`
changed. No Java agent or entrypoint override was introduced. The predecessor
source `47969db` / release `20261111-addresses` remains recoverable. Unrelated
dirty telemetry and untracked AGENTS.md were preserved.

Root owned integration, Git, native execution and deployment. Bounded workers
provided accepted OCR evidence, proof review, adapters and competitor review.
Private helper schema corrections preserve frozen release-binding bytes and
check the actual JAR against its image receipt and SHA-256.
