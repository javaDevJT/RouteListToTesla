# Numbered street verification

The supplied screenshot reads `22506 W 11 MILE RD, SOUTHFIELD, MI`.
Google returns the same nonpartial rooftop as `22506 W Eleven Mile Rd`;
policy v20 rejected the differing number representation. Five approved diagnostic
requests agree on that building. Local Tesseract settings support the source
reading; those settings are one engine, not independent consensus.

Policy `street-address-v21` recognizes whole English cardinal and ordinal number
expressions throughout street names: `Eleven` / `11`, `Twenty First` / `21st`,
including hundreds, thousands and millions up to 999,999,999. Cardinal and ordinal
forms remain distinct. It preserves the supplied address and OCR metadata.

Conversion requires the original unchanged address, unchanged-street constrained
forward query and reverse lookup to corroborate the same nonpartial rooftop,
house number, ZIP, place ID and coordinates within two metres. Directions,
terminal street types, units, state/country and competing destinations retain
their safeguards. This does not substitute OCR lookalike letters in house or
unit numbers. Existing labeled estimated-road fallback behavior is preserved.

Review explains the provider's number spelling and shows its map address while
retaining the source text. Policy invalidation causes stale saved geocoding
results to be checked again without deleting source images or saved routes.
No extraction-version change is needed.

The isolated source build passes 392 Java tests and 73 browser-state tests.
The regression covers the recorded Eleven Mile rooftop, bidirectional general
number expressions, original/constrained/reverse failures, competing buildings,
unit conflicts and unchanged OCR metadata. Release qualification and deployment
are pending; a local package is not deployment evidence.

Private operational evidence is indexed in
`output/private/west11mile-triage-20261119/RELEASE.md`. The five of 24 approved
Google requests are retained; the remaining budget is closed. No Tesla commands
were issued.
