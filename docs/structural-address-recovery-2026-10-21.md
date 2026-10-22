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

Local validation passed 284 Java tests and 46 frontend tests, including 18 street
structure tests. The supplied cases first failed on the unchanged validator and
then passed with the new proof. Additional tests first reproduced unsupported
township ZIPs, proof-stage ZIP drift, malformed route names, duplicate geographic
components, contradictory long/short street names and order-dependent competing
place identities; each is now rejected safely.

An existing suffix token can itself be part of the name before the omitted
terminal suffix. This is handled by the same three proofs rather than a special
suffix allowlist. A regression covers that interpretation and rejection when the
unchanged constrained street points to a competing destination. No name edit
distance or arbitrary extra name words are accepted.

Geocoding policy is `street-address-v10`; OCR remains `ocr-consensus-v9`.
Current-policy saved/session/cache checks revalidate older location decisions.
Recovered rows show a positive verification message and Google's map address
without replacing the screenshot text. Independent correctness review found no
remaining material blocker. Immutable-image qualification and live deployment
verification are pending and will be recorded separately after they pass.
