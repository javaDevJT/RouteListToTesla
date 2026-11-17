# Laporte building label verification

The November 17 screenshot contains `8429 LAPORTE DR BLDG 24, STERLING HEIGHTS,
MI`. Extraction and the review retained that text correctly. Google's original
response returned an exact rooftop with a structured `bldg 24` subpremise, but
the matcher compared that entire label with the input's bare secondary number
`24`. This produced a unit conflict and the generic no-safe-match review state.

Policy `street-address-v19` treats an explicit `BLDG` or `BUILDING` label as a
typed identity. It does not turn that label into an apartment or floor alias.
The original building label stays in the constrained query and in the displayed
address. Both provider subpremise names and the formatted building label must
agree with the supplied number. Leading zeros, distinct hyphenated identities, different secondary types,
multiple secondary labels and competing destinations remain held.

For this case, original and constrained building queries attest the same
nonpartial rooftop/place ID. Reverse lookup attests a distinct unitless parent
at that rooftop; a fourth unchanged-street parent query must confirm its place
ID, coordinates, house, route, locality and ZIP. Existing exact-rooftop guards
remain in effect. The labeled destination is retained after corroboration.
Review says **Building 24 verified**, with Google's map address and retained
input text; it does not claim that an apartment was verified.

Four read-only Google requests were retained under the user's case-specific
24-request authorization. Qualification replays those saved responses offline.
No Tesla command is authorized or issued by this triage. The supplied source
image has two ordered rows; the other three review rows refer to IMG_0757,
which was not supplied and is not represented as newly qualified source pixels.

Focused validation passed 98 Java checks and 67 frontend checks. The isolated
indexed-source clean package passed all 370 Java checks. The immutable image also passed 36 ordered screenshot cases across EasyOCR,
PaddleOCR and Tesseract, plus all 30 saved-provider cases (29 prior and Laporte).
The mobile image-template check renders Building 24 verified in green, retains
the source text, and leaves send/start controls unclicked.


## Qualified release

Release `20261117-laporte` deploys application source
`d1078af14b2707e0ff5073d94ae7e9738e03af06` from exact push CI
[44181027449, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/44181027449).
Build, High/Critical security enforcement (zero findings), storage enforcement,
publication and cleanup all passed. The image is
`ghcr.io/javadevjt/routelisttotesla@sha256:de131f0dcabe87d20c9a079621a8d8283a5abb9fadfa788ea8db029cd9ebfe39`;
its application JAR SHA-256 is
`db1f7b71774a7a9dfbc43f5070722a9f8f69eba5e98bb1cbf4def44e0818c3ae`.
Packaged assets match the commit. Exact instruction/signature/constant comparisons
match 26 geocoder classes to the tested source and 20 OCR/grammar/model classes
to the predecessor; raw class hashes are retained separately from compiler metadata.

Native qualification `native-44181027449-1` used one isolated QA app (create job
`52046`), no networking, UID 10001, read-only root, and the actual image JAR.
It completed successfully and cleaned up its app and fixtures. Production was
unchanged during qualification. All 71 replay fixture requests were local;
qualification made zero external provider calls and zero Tesla commands.

One guarded production update, job `52082`, succeeded. It changed only the
router image, cache-permissions image and `APP_RELEASE`. Production `/health`
returned HTTP 200 with source `d1078af14b2707e0ff5073d94ae7e9738e03af06` at
2026-11-17T22:40:10.712Z. The live 390px login view verified the release marker,
TAP link, source health and no horizontal overflow at 22:40:20.026Z. The service
is [TeslaRouter](https://teslarouter.javadevjt.tech), with no scheduled expiry.
Refresh an already-open page to load policy v19 and revalidate older saved rows.

The predecessor source was `c421e632d7581933c1d3785fc1f6a2a343dbef72`, release
`20261115-norfolk`, image digest
`d95966351b9073296f471067bd3754efa1519927acf93d037f0193b9dc57751e`.
Its configuration fingerprint was
`b63111e8a17d7871b26cda6ee5824bf928fa2bacd59c0d4512bf9aef2855fc30`;
the new fingerprint is
`9d6438cff8c58104bf4cfb5cd0321ba52b9b53dad6f0c002a301e46d6c9688dc`.
Restoring the three authorized fields in memory reproduces the predecessor
fingerprint exactly. The route-data volume fingerprint remains
`59d12fbcc26b7690669674a2c80d3e83747be3d3553faf8532f06eec6cf02b19`.
Credentials, mounts, networking, UID 10001, read-only root, CPU/memory limits and
unrelated dirty work were preserved. Norfolk and Crystal remain held as before.

Sanitized owner receipts, retained responses, proofs and mobile screenshots are
indexed in `output/private/laporte-triage-20261117/RELEASE.md`. Four of the 24
approved Google lookups were used; 20 remained unused and the pass is closed.
No Tesla command was sent. These owner release records do not change Quarterback
incident or generation state.
