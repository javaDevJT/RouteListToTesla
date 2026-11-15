# Fenton and Linden verification

The three supplied screenshots contain twelve correctly extracted addresses.
The review holds `9440 CRYSTAL TARN, FENTON, MI` and
`16402 SEYMOUR RD, LINDEN, MI`. The implementation is being qualified for release.

## Seymour Road

Google returns `16402 S Seymour Rd, Linden, MI 48451, USA`, exact house16402,
rooftop42.7849967,-83.8480367 and place ID `ChIJx3hrlwJsI4gReI5U7sjbaMM`.
The original full address, unchanged street-only constrained query and reverse
response agree. v16 rejects the extra leading South direction even when all
three proofs agree.

Policy `street-address-v17` permits this specific representation difference
through the shared strict street-structure proof: an input with a known suffix
and no supplied direction may propose exactly one provider-added leading
direction. The residual street name and suffix must match, and the original,
unchanged-street forward and reverse proofs must retain the exact house,
typed city, state/country, ZIP, rooftop and place ID. A requested direction
cannot disappear or change. Competing directional rooftops, added interior or
trailing words, unit conflicts, partial results and missing proofs remain held.
The original address text and source metadata are retained.

An independent test worker supplied real-case and N/S/E/W vectors, all-stage
identity/point/ZIP/unit/locality drift and directional competition in both
orders. Its matrix exposed an original-proposal direction-plus-locality repair;
the implementation now requires the original locality to match for this path.
The focused 36 street-structure methods and 64 other geocoding methods pass.
All61frontend methods pass, including v16 session revalidation and the new
provider-coverage message. OCR/extraction version remains `ocr-consensus-v10`.
The exact staged export excludes unrelated telemetry and passes a clean package
with356Java methods and no failures, errors or skips. The tested frontend bytes
match that export. Immutable-image/native/runtime checks remain release gates.

## Crystal Tarn coverage limit

The screenshot says house9440. The user supplied an approximate hint near
9439 W Crystal Tarn Drive. Google resolves that different house, but every
captured forward form for9440—including the full hinted street name—returns
only partial, approximate city/township areas. Reverse lookup of the neighbor
does not establish a9440 rooftop. The neighbor or a city center cannot verify
this delivery stop.

The [Livingston County Road Commission access-name inventory](https://livingstonroads.org/wp-content/uploads/2024/04/AccessNameInventory2024.pdf)
lists Crystal Tarn as a private access in Tyrone Woods MHC. That street record
provides context, not house9440 coordinates. No street or house whitelist, guessed
point, or neighbor-location substitution is introduced.

When all returned results are broad areas rather than an address, v17 preserves
`NO_MATCH` with reason `provider_area_only`. The review now explains that Google
returned only a city or area and asks for the complete street address; send/start
remain paused. A verified exact building location or corrected target address
is still needed for9440. This is an unresolved provider-data dependency,
not a successful location verification. Existing editing/removal remains available.

## Authority and release evidence

The user approved up to16 read-only Google requests for these two cases. All16
were retained and the budget is closed; all further qualification uses offline
replay. No Tesla commands were authorized or issued. Local Tesseract PSM6/11
preserved all twelve addresses in image order; native three-engine qualification
is a separate release gate.

Root owns integration, Git and every remote effect. Unrelated dirty telemetry,
AGENTS.md, credentials, route/cache/session data, source image/address order and
the eight-waypoint limit are preserved. Private evidence and release receipts
are under `output/private/fenton-linden-triage-20261114/`. Exact source, CI/image,
native and production identities will be recorded after qualification.
