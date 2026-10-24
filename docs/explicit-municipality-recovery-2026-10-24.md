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
verification remain pending and will be recorded here when complete. Tests use
saved Google responses and send no routes to a vehicle.
