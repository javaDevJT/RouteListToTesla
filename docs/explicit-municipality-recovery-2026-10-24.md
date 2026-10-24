# Explicit township address recovery

The original IMG_0690 HEIC reads `3156 GATEWAY LEDGE, COMMERCE TOWNSHIP, MI`.
OCR transcribed it correctly. Three approved Google requests—the original full
address, unchanged street constrained to the supplied locality, and reverse
verification—return the same nonpartial rooftop address, place ID and coordinates.
Google supplies Commerce Township as `administrative_area_level_3`, without a
primary city field. The reverse response also contains other addresses and
approximate results; those responses remain unmodified in qualification.

The previous validator required a postal-city mapping whenever Google supplied
only a township. The catalog associates ZIP 48390 with Walled Lake, so it could
not corroborate the explicitly supplied municipal label Commerce Township.
This was a validation failure, not OCR, a missing Google address, or a corrupt ID.

Policy `street-address-v12` adds a general explicit-township proof:

- The input must contain a recognized Township/Twp designation, including
  Charter Township and Township of forms. The provider must supply one typed
  municipality with consistent long/short names and the same literal base name.
- A township match cannot override a primary city, postal town or sublocality.
  Neighborhoods remain separate geographic levels and do not substitute for
  municipal identity. An actual primary city with the exact supplied full label
  retains its own validation path.
- ZIP/state evidence still requires a catalog coordinate with accuracy at least
  four within 10 km. This is a geographic plausibility bound, not a ZIP polygon
  or municipal boundary. The municipal name is proved independently; no postal
  alias is invented and catalog coordinates never become route destinations.
- The original request, unchanged locality-constrained street and reverse lookup
  must confirm the same precise rooftop/place ID within two metres. House number,
  street-name/direction tokens, state/country, ZIP and supplied ZIP+4 constraints,
  units, partial results and competing rooftop destinations remain guarded.
  This proof is required even when the supplied street has a normal road suffix.
- Bare postal-city hints retain their existing catalog-backed behavior. Original
  screenshot text, ordering, deduplication, OCR engines, TAP/CSRF authorization and
  vehicle command safeguards are preserved. Older location decisions revalidate
  under v12; OCR extraction remains `ocr-consensus-v9`.

Baseline regressions reproduced the failed municipality examples before the
change. Review also reproduced and closed an exact-label fallback that could
mistake a sublocality for a primary city. The fallback now accepts only an actual
`locality`; postal towns and sublocality-only results cannot establish the
explicit municipality. The full clean package passed 317 Java tests, including
20 explicit-municipality and four ZIP-area tests; 48 frontend tests passed,
including revalidation of saved v11 successes and failures. Independent source
safety review passed. Immutable-image qualification and live deployment
verification passed as recorded below. Tests use saved Google responses and
send no routes to a vehicle.

## Verified release

- Application source: `22ab299cd945c836622ad97e70e1aaee02a1984a` on main.
- [CI run 40094618455](https://github.com/javaDevJT/RouteListToTesla/actions/runs/40094618455),
  attempt 1, publish job 120874158438: build, security, storage, release promotion
  and cleanup succeeded. The retained report has zero High/Critical findings.
- Immutable image:
  `ghcr.io/javadevjt/routelisttotesla@sha256:4e839e4cf1dff5131977a747921c092fd2d2dc8444bd6af7828a6272864cf5cb`.
  Registry labels/configuration bind this digest to the source; the packaged
  frontend, theme and postal catalog match that committed source.
- Native qualification passed all 25 ordered-exact screenshots, including the
  original supplied HEIC and unchanged signatures for all 24 earlier cases.
  All three local OCR engines, 36 Python tests, 15 address cases and all 33 unique
  saved provider-response requests passed. Application classes loaded from
  `/app/app.jar`; no source overlays or hosted OCR were used. Dependency,
  authentication/CSRF, nonroot, resource and parallel-image quality gates passed.
  The isolated QA app and fixtures were cleaned up normally.
- TrueNAS job 9493 succeeded, deploying release `20261024-gateway`. Only router
  image, permission-init image and `APP_RELEASE` changed. Data mounts, 8 CPUs /
  8 GiB, UID/GID 10001, read-only root and private LAN/proxy bindings were preserved.
- Public `/health` reports the exact source. The served release marker/theme,
  TAP redirect and protected-route/CSRF checks passed. Mobile Chrome at 390px
  rendered the live login without overflow or page errors. A mocked 15-row mobile
  review retained original text, showed green Gateway verification and allowed
  route actions without dispatch; its data hash matches native qualification.

Exactly the three approved Google requests diagnosed this case. All subsequent
qualification used the recorded responses and issued no external Google requests
or vehicle commands. Reload and sign in through TAP to load the new policy;
saved older location decisions are revalidated without clearing route data.
