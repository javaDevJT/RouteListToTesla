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
indexed-source clean package passed all 370 Java checks. Full immutable-image,
native OCR, mobile rendering and deployment results are recorded after release
qualification in the private `output/private/laporte-triage-20261117` receipts.
