# Municipal postal recovery

The supplied Reymont screenshot was transcribed correctly. Google's forward and
reverse responses both identify a precise street address in Waterford Township,
with Elizabeth Lake as its neighborhood and no primary city field. The previous
postal-city proof required a primary city and therefore rejected this otherwise
corroborated address. Two authorized Google requests reproduced the issue; their
responses are retained privately for offline regression tests.

Geocoding policy `street-address-v9` supports this general response shape without
mapping individual addresses or municipalities. If all primary city fields are
absent, one consistent administrative-level-3 municipality can provide the
canonical identity for the existing postal proof. A present malformed or
conflicting primary city blocks fallback. Neighborhood, county and state fields
cannot provide a substitute city. Township label variations normalize only
within the typed municipal field, including across forward and reverse results.

Acceptance still requires the requested US city/state to be supported by the
returned ZIP in the bundled postal catalog, a precise nonpartial full address,
the correct house number and street, and the same rooftop place ID within two
metres in reverse evidence. Supplied ZIP and ZIP+4 constraints remain binding.
Another qualifying rooftop in the requested postal area blocks acceptance even
when its municipality differs. Conflicting apartment numbers remain terminal,
including on a duplicate result with a different municipal label. No catalog
coordinate becomes a destination. The original address text is retained and the
review displays Google's verified map address.

The browser, image cache and saved-result checks share the new policy so older
failed or verified results are revalidated. Extraction remains `ocr-consensus-v9`;
all three local OCR engines, ordering, overlap deduplication, eight-stop grouping,
TAP authorization, CSRF and vehicle-command holds remain in place.

Local checks pass 266 Java tests and 42 frontend tests, including 12 focused
municipal cases. Validation and deployment are in progress. Regression coverage includes
the recorded Waterford case, another catalog-backed township, equivalent labels,
missing/contradictory components, incorrect streets, ZIP constraints, competing
rooftops, apartment conflicts and older-policy browser migration. Final immutable
image, native screenshot qualification and production readback will be recorded
after release; a local test pass alone is not deployment evidence.

The preceding scheduled image build also reported eight High/Critical findings
in Pillow and certifi. Their pins advance to Pillow `13.0.0` and certifi
`2026.10.19`, the advisory fixes. A native ARM64 smoke test with the current
`pillow-heif==1.8.0` passed HEIC decoding, orientation, lossless PNG round-trip
and resizing for the supplied image. This is not a Linux runtime qualification:
the existing vulnerability gate and complete native OCR suite must still pass
against the resulting immutable image before deployment. See the
[Pillow release](https://pypi.org/project/Pillow/13.0.0/) and
[certifi advisory](https://github.com/advisories/GHSA-6q48-6c7w-9gw2).
