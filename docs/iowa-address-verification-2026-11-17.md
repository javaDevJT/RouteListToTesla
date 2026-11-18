# Iowa address precision and C Drive OCR review

The supplied route contains `543 IOWA, BELLEVILLE, MI`. The image and review
agree on that text. There is no supplied street suffix to recover or invent.
Five bounded Google forward queries, including locality, postal and municipal
constraints, returned `543 Iowa, Belleville, MI 48111, USA` only as
`RANGE_INTERPOLATED`. Reverse lookup returned nearby rooftops at 535 and 533
Iowa, plus the estimated 543 point. None establishes the requested building.

Joshua approved an estimated road point as a viable substitute when no better
location is available. Policy `street-address-v20` therefore prefers verified
building recovery, then accepts a distinct `ESTIMATED_STREET` fallback for an
exact, unitless literal suffixless street. Two unchanged full-address queries—
the original and the locality-constrained form—must agree on the house, street,
primary city, state, country, ZIP, place ID and point within two metres. This
is matching provider evidence, not independent survey or rooftop evidence.
Conflicting, partial, ambiguous, unit-bearing or structurally different results
remain held. Nearby house rooftops are never substituted.

The accepted estimate retains the input text and OCR metadata, carries Google's
original point and place ID, and is explicitly not a verified building. The review
uses an amber “Estimated road location accepted” row and separates verified stops
from accepted estimates. Manual and automatic navigation freshly re-geocode on
the server; client-provided status and coordinates cannot bypass validation.
Older-policy rows are revalidated. An uncorroborated estimate remains unresolved.
The earlier Norfolk omitted-suffix hint remains held because its suggested Court
suffix is not present in the source.

The first source row visibly reads `14154 C DR, PLYMOUTH, MI`. Local Tesseract
PSM 6 and 11 join it as `CDR`; these are two settings of one engine. The source
glyphs are fully visible, so the low agreement cannot be attributed to a cut
through the address. The review shows 1/3 agreement and a confirmed current
reading. Human confirmation is preserved; this release does not manufacture
3/3 consensus or turn OCR confirmation into location verification. A fresh native
read of the deployed Laporte image reproduced all five correct primary texts.
C Drive remained 1/3 and required review: Tesseract read `CDR`, PaddleOCR read
`C DR`, and EasyOCR read `€ DR`. Iowa had 3/3 agreement.

The remaining source rows are `49337 STERLING DR, CANTON, MI`,
`46527 KILLARNEY CIR, CANTON, MI`, and `1828 HAMLET DR, YPSILANTI, MI`.
The review retains all five rows in image order. MI is default-state metadata,
not a state abbreviation visible in the source images.

Joshua approved up to 16 read-only Google lookups for Iowa; six responses are
retained for offline replay. No Tesla command is authorized or exercised by this
triage. The accepted Iowa road point is 42.1550986, -83.5321065; no
building or delivery-entrance precision is claimed.

The local evidence and release work are indexed in the private
`output/private/iowa-triage-20261117/RELEASE.md`. Native and deployment results
will be appended after qualification; local tests alone are not deployment proof.
