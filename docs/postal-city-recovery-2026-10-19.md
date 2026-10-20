# Postal city recovery

## Problem and behavior

The supplied Nottingham screenshot contains the correct house and street under
the postal city `Grosse Pointe`. Google returns the same precise street address
under `Grosse Pointe Park`, ZIP 48230. Four approved diagnostic requests agreed
on the same rooftop coordinate and place ID. The previous v7 policy could only
recover a differing locality if reverse address evidence repeated the original
city name, so it rejected this case.

Geocoding policy `street-address-v8` adds offline postal-city evidence. It has no
address-specific replacements and makes no new external service requests. The
normal exact-match and existing reverse locality/street-representation recovery
paths remain. Saved results from older policies are revalidated before use.
When a response already contains a valid original-city interpolated proof, it
uses that existing path before postal fallback. A second representation of the
same address must not create false ambiguity; genuinely competing original-city
proofs and unit conflicts still block recovery.

## Acceptance contract

Only the original query's unique nonpartial rooftop proposal can enter recovery.
House number, street spelling/type/directions, state and country must already
match; this path cannot repair both a street and a locality. Real unit conflicts
remain terminal. Postal evidence additionally requires:

1. One unambiguous five-digit ZIP in the proposal, with any supplied ZIP preserved.
   A supplied ZIP+4 suffix must also be confirmed by both proposal and proof.
2. The original city/state and returned ZIP in the bundled US postal catalog,
   with a qualified reference point within 10 km of the proposed rooftop.
3. Reverse geocoding at that rooftop must return the same full house/street,
   canonical locality/state/country and ZIP, the original place ID and a rooftop
   coordinate within 2 m. Interpolated locations cannot prove a postal alias.
4. A competing rooftop or conflicting unit blocks acceptance regardless of
   response order. Missing, malformed, partial or broad-region results never
   supply a destination.

The catalog's reference coordinates are supporting locality evidence only.
Navigation retains Google's original verified rooftop coordinate and place ID.
The provider's full street-address results establish the rooftop's ZIP; the
catalog associates the requested postal city with that ZIP. This validates a
postal name, not legal municipal membership. The 10 km bound adds a plausibility
check and does not represent a ZIP polygon or a municipal boundary.
An apartment is verified only when both the proposal and proof confirm it;
otherwise the building can be verified while retaining the apartment text.

The interface shows the original screenshot address and explains the verified
postal-city difference, including Google's map address. Success removes the retry
control. Unresolved rows continue to block vehicle sends.

## Data and maintenance

The [GeoNames postal dump](https://download.geonames.org/export/zip/) is bundled
as a deterministic, qualified US snapshot: 39,848 rows, accuracy 4, with original
city/state/ZIP and reference coordinates. It is licensed under CC BY 4.0; see
[provenance, hashes and attribution](../src/main/resources/postal/README.md).
`scripts/postal-catalog.py` emits the derived rows without network or filesystem
writes. Dataset refreshes are reviewed source changes, not automatic runtime
downloads. Production has no new API account, credential or paid dependency.

Missing or malformed data disables this additional proof path. The catalog is
not complete and rural ZIPs can span more than the 10 km evidence bound, so some
otherwise valid addresses can remain unresolved. City prefixes or repeated
structured query results alone never establish an alias: Google's
[component-filter contract](https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding#ComponentFiltering)
does not enforce locality or administrative-area filters.

## Verification

Clean Maven package passed 254 Java tests. The focused postal cases cover
multiple cities/states, leading-zero ZIPs, numeric and alphanumeric apartments,
missing evidence, mismatched ZIP/city/state, competing rooftops, wrong streets,
units and partial/imprecise results. All 39 frontend tests passed, including v7
saved-result revalidation and postal recovery feedback. Native image and release
qualification are pending; no new vehicle commands or Google requests are part
of these implementation tests.
