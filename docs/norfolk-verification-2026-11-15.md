# Norfolk street estimate review

The two supplied source screenshots contain six addresses in the correct order.
The review held only `13342 S NORFOLK, DETROIT, MI`; its OCR agreement was 2/3.
Visual review and local Tesseract PSM 6/11 both preserve the house number,
street letters and supplied South direction. No OCR implementation change is
needed. Native three-engine qualification remains a separate release gate.

## Provider evidence and the hold

The user approved up to 12 read-only Google geocoding requests for this case.
Five requests were made and retained: original full text, unchanged street under
locality/state/country constraints, provider-canonical Court with ZIP, reverse
lookup of the returned point, and expanded South Norfolk Court. No further paid
calls are planned; remaining qualification uses saved responses. No Tesla
commands were authorized or issued.

All four forward forms return `13342 S Norfolk Ct, Detroit, MI 48235, USA`,
house 13342 and structured `South Norfolk Court` / `S Norfolk Ct`, at
42.4430414,-83.179406. The provider labels that point `RANGE_INTERPOLATED`, not
`ROOFTOP`. Reverse lookup returns the same estimated address with a different
generated place ID and nearby rooftops for other house numbers. None of these
responses establishes a rooftop for house 13342. Neighboring rooftops cannot
replace the requested stop.

The supplied street omits Court. Existing street-structure recovery deliberately
requires exact rooftop/place-ID proof before inferring a missing road suffix.
The provider's estimated point does not satisfy that contract. The old generic
message hid this distinction and encouraged retrying the unchanged address.
Confirmation of the complete street from the delivery details or an exact
building map pin has been requested from the user. The original stop remains
unverified while that evidence is missing.

## Shared correction

Policy `street-address-v18` reports a narrowly validated interpolation as
`NO_MATCH/recovery_exhausted/street_location_estimated` with an unverified
editing hint in `matchedAddress`. It preserves the input text/source/OCR
metadata, leaves place ID null and coordinates zero, and does not substitute the
suggested suffix automatically. The review explains that Google returned an
estimated road location and asks the driver to check the complete street name
against the delivery details before editing and retrying. Manual and automatic
send remain held.

Hints are restricted to original, nonpartial, unitless address responses with
the exact house, preserved name/direction, a single consistent route, typed
locality/state/country and valid constrained postal information. Only a missing
terminal suffix is suggested. Missing or malformed point/ID/components,
unexpected units, wrong names/directions/localities and competing destinations
do not select a hint. Two distinct identities or points suppress the hint even
when their display addresses are equal. Positive recovery retains its existing
`ROOFTOP` guard and independent corroboration requirements.

Frontend hints are literal text, bounded to 512 characters, and never promote a
held status. Existing v17 sessions are revalidated without deleting stored data.
Extraction stays `ocr-consensus-v10`; order, source filenames, default-state
handling and the eight-waypoint limit are unchanged.

## Checks and release evidence

The initial regression reproduced the generic no-safe-match result. The corrected
source passes 106 focused Java methods and 64 frontend checks. The independent
backend matrix covers directional streets, identity/address/component validity,
typed ZIP mismatches, units, partial/approximate responses, competing estimates
and metadata/coordinate preservation. Existing rooftop and prior-recovery tests
remain in place. Local OCR is not native image qualification.

Root owns source integration, Git and all remote effects. Existing dirty
telemetry, AGENTS.md, credentials and route/cache/session data are preserved.
Private records are under `output/private/norfolk-triage-20261115/`, indexed by
its `RELEASE.md`. Exact source, CI/image, native and production identities will
be added as their gates complete. This record does not claim that Norfolk's
building location has been verified.
