# Street structure recovery

The original supplied HEIC contains `6734 COTTONWOOD KNL` and `5537 SILVER POND`,
both in West Bloomfield. The screenshot text was transcribed correctly. Google
returns precise addresses for both: the first has a full route name ending in
Knoll Court; the second is a street named Silver Pond without a terminal road
suffix. The old validator required a recognized suffix before making a request
and could not corroborate an omitted terminal suffix.

Six authorized diagnostic requests covered the original text, the original street
constrained to its locality, and reverse lookup for each address. All three forms
agree on the same nonpartial rooftop place ID and coordinates for each address.
The remaining two approved requests have not been used. There are no vehicle
commands in this investigation.

The implementation covers these street structures without address-specific
aliases or guessed street names:

1. Let a house-numbered street with a parsed locality reach the provider even if
   its name has no catalog suffix. Require literal normalized name agreement.
2. Recognize one omitted terminal road suffix only when Google supplies it and
   every supplied name and direction token remains accounted for. A catalog
   abbreviation at the end of the supplied name can expand to its unambiguous
   full form; arbitrary spelling substitutions or additional name words cannot.
3. Require a unique full-address, nonpartial rooftop proposal, a separate query
   for the unmodified supplied street constrained to its locality, and reverse
   confirmation of the same rooftop place ID within two metres. Constraining a
   guessed replacement alone is insufficient.
4. Retain state/country, supplied ZIP, typed locality and postal-catalog checks;
   reject conflicting units and competing destinations in every proof stage.
   Retain the screenshot address and display the matched map address.
5. Revalidate saved and cached location decisions under the updated geocoding
   policy. Preserve OCR, ordering, overlap deduplication, TAP authorization,
   CSRF and uncertain-command holds.

Local validation passed 285 Java tests and 46 frontend tests, including 19 street
structure tests. The supplied cases first failed on the unchanged validator and
then passed with the new proof. Additional tests first reproduced unsupported
township ZIPs, proof-stage ZIP drift, malformed route names, duplicate geographic
components, contradictory long/short street names and order-dependent competing
place identities; each is now rejected safely.

An additional compatibility audit covers 27 distinct Google long/short route
pairs from 11 archived response files, including earlier reported addresses.
It caught a valid internal abbreviation such as Drive/Dr before deployment.
The provider-field comparison now uses unambiguous catalog abbreviations inside
Google's own names while retaining strict input-name matching and explicit
terminal-suffix identity. All 27 archived pairs pass; contradictory names still
fail. The regression also preserves the direct single-query path.

An existing suffix token can itself be part of the name before the omitted
terminal suffix. This is handled by the same three proofs rather than a special
suffix allowlist. A regression covers that interpretation and rejection when the
unchanged constrained street points to a competing destination. No name edit
distance or arbitrary extra name words are accepted.

The initial change used geocoding policy `street-address-v10`; OCR remains `ocr-consensus-v9`.
Current-policy saved/session/cache checks revalidate older location decisions.
Recovered rows show a positive verification message and Google's map address
without replacing the screenshot text. Independent correctness review found no
remaining material blocker. Immutable-image qualification and live deployment
verification are pending and will be recorded separately after they pass.

## October 22: suffix omitted by the provider

The next original HEIC also has correct OCR. Its failed rows are
`16909 SHREWSBURY, LIVONIA, MI`, `31331 HATHAWAY ST, LIVONIA, MI` and
`9115 RIVERDALE, REDFORD, MI`. Nine explicitly approved diagnostic requests
covered the original, unchanged locality-constrained street and reverse lookup
for each row. Each set agrees on one exact nonpartial rooftop/place ID.

The first and third rows have provider routes Shrewsbury Street and Riverdale
Avenue, which the initial missing-suffix recovery covers. The second reveals
the inverse shape: Google's long and short route names are both Hathaway,
without the supplied St. The initial change does not resolve that shape.

Policy `street-address-v11` additionally permits a proposal that differs only
by one known supplied terminal road suffix absent in the provider's route.
Every remaining name and direction token must match literally after the
existing direction normalization. The stronger proof still queries the complete
unchanged supplied street under its locality constraints, then requires reverse
confirmation of the same rooftop/place ID within two metres. House number,
locality/state/country, ZIP, unit conflicts and competing-destination checks
remain mandatory. The screenshot text is retained; no address mapping or guessed
street name is introduced. The existing separate corroboration for explicit
street-type differences is preserved.

The Hathaway-shaped positive regression fails on the initial change and passes
on the extension. Additional cases cover trailing directions, multiword names,
matched units, removal of other name/direction tokens, competing destinations,
ZIP drift and unexpected units at each proof stage. Current validation passes
293 Java tests (27 street-structure tests) and 46 frontend tests. Older cached
location decisions revalidate under v11. Final image qualification and deployment
are pending; the previous verified deployment remains recorded in the index.

Independent review also reproduced an edge where a later proof could reinterpret
a remaining name word as a suffix abbreviation. Positive proof acceptance now
rechecks the exact residual name/direction tokens against the supplied route,
in addition to the existing provider-field consistency and destination checks.

Replaying the complete saved reverse response also exposed an approximate
Riverdale road point 2.54 metres from the exact rooftop, with a different place
ID. The structural proof now counts only rooftop addresses as competing precise
destinations. An interpolated result cannot supply positive proof; the exact
original rooftop/place ID must still be present. Tests cover both proof stages
with exact-plus-interpolated results, failure when only an interpolated result
remains, and continued rejection of competing rooftop identities. The recorded
provider responses are preserved without filtering.
