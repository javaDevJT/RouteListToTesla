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
`output/private/iowa-triage-20261117/RELEASE.md`. The final receipts distinguish
the deployed application source from the later documentation-only commit.

## Verified release

Application source `1874e8d8222e417d4ccddb76dcea8f97bb5f7906` was committed and pushed to main.
[CI 44225999452, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/44225999452)
passed build/test/publication, storage enforcement, the zero High/Critical
vulnerability gate, and transient artifact cleanup. The immutable image is
`ghcr.io/javadevjt/routelisttotesla@sha256:2f6afee5d7424a138f84764a8db0e7ba0732cf05123a774b806fa84ebd9d015b`; its application JAR SHA-256 is
`49e1986f100bdd44c55f16fd03f19af436b58eb632cc985756adf092a01556c6`.
The packaged review assets match the committed source. Exact bytecode instruction,
signature and constant comparisons bind the tested source to the image; raw class
hashes are retained separately because the local and CI compiler versions differ.

Local checks passed 384 Java tests and 71 browser-logic tests. Native image
qualification passed all 38 ordered screenshot cases and 31 provider cases,
including all 30 prior outcomes unchanged apart from policy version. Iowa used
only its two saved forward responses and retained the real native OCR metadata.
The original full harness exceeded its output timeout and was cleaned up without
changing production. Its failed evidence is retained. The reconciled second
attempt kept all functional cases, captured raw three-engine output for the two
new screenshots, and did not repeat unchanged performance profiling. Its QA
creation job was `52800`; temporary app and fixtures were removed.
No application classes or OCR sources were overlaid.

A 390-pixel packaged-image browser check rendered the actual native Iowa result
in amber as “estimated road location accepted,” with zero verified buildings and
both manual and automatic controls enabled. Neither send nor start was clicked.
No live Google lookup or vehicle command was exercised by qualification.

Production update job `52833` succeeded once. The app is `RUNNING` with
`APP_RELEASE=20261117-iowa`, UID `10001:10001`, read-only root, eight CPUs and
8 GiB memory. Only the router image, cache-permission image and `APP_RELEASE`
changed. Predecessor configuration fingerprint
`9d6438cff8c58104bf4cfb5cd0321ba52b9b53dad6f0c002a301e46d6c9688dc` became
`e2614c3fc17c252b230a1ef38094c72d99ad6c29f1b9ff95c2c4ee9cedad4e70`; restoring those three allowed fields reproduces
the predecessor fingerprint. Persistent volume fingerprint
`59d12fbcc26b7690669674a2c80d3e83747be3d3553faf8532f06eec6cf02b19` and existing mounts, credentials and settings were
preserved. All 15 unrelated dirty tracked files and six untracked groups were
preserved.

Public health returned HTTP 200 with the exact application source at
`2026-11-18T04:27:57.688Z`. The live mobile login/source/assets check passed at
`2026-11-18T04:28:32.668Z` without page errors or horizontal overflow. The service
is available at [TeslaRouter](https://teslarouter.javadevjt.tech/login) with no
scheduled expiry. Google usage closed at six of 16 approved diagnostic requests;
ten remained unused. No Tesla commands were sent.
