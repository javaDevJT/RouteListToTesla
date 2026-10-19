# Apartment lookup and OCR evidence fixes

The October 18 screenshots exposed two separate defects: a valid apartment was
rejected because Google used different display labels for the same unit, and a
package counter beside an address created a false OCR review warning.

## Google unit labels

The two user-approved Google lookups returned exact, non-partial rooftop street
addresses. In the apartment response, one `subpremise` component had long name
`Apartment 208` and short name `Apt 208`. The old code compared `APARTMENT208`
with `APT208`, returned `NO_MATCH / unit_conflict`, and correctly stopped further
queries under the genuine-conflict safeguard. The defect was in identifying the
conflict, rather than a missing address permutation.

Policy `street-address-v6` normalizes provider subpremise display labels before
comparing their values. It removes one recognized, explicitly separated label
after Unicode normalization: Apartment, Apt, Unit, Suite, Ste, Room or Rm.
Request-side apartment identifiers remain unchanged. Compact identifiers such as
`APT208`, unknown floor/building labels, different numbers, alphanumeric values
and leading zeros remain distinct. A genuine conflict still stops the lookup
without trying a less specific address.

Replaying the original recorded response reproduces the old rejection and now
returns `VERIFIED_UNIT / exact_unit_match` on the original query. The original
apartment text is retained. Replay uses a local HTTP fixture and makes no Google
requests. Street number, street/direction, locality, state, country, result type,
partial-match, coordinate and place-ID checks remain enforced, as do query
budgets and pacing.

## OCR evidence membership

All three engines agreed on the second address, including its apartment. A
far-right package counter overlapped it vertically and had 2/3 agreement. The old
Java parser included every short overlapping line as address evidence, lowered
the address agreement and forced review.

Extraction `ocr-consensus-v8` retains horizontal TSV bounds. Short fragments must
also be close to the address column before they become address evidence. Evidence
uses explicit line membership, so skipped counters cannot reappear inside a
contiguous range. Lookahead continues past side-column metadata while retaining
the last address fragment as its locality anchor. This also prevents a distant
counter from completing a street line ending in `APT`.

Nearby possible unit fragments and genuine engine disagreement remain reviewable.
Strict consensus packet validation and all three local OCR engines are preserved.
The UI now asks the user to compare engine readings without claiming that every
review requirement means there was no majority.

## Cache migration and validation

Image cache identity includes the new extraction and geocoding versions. Server
route checks and browser restoration use geocoding v6. Regression checks verify
that both successful and rejected v5 saved locations are resolved again before
the session is saved with current-policy locations; address text is preserved.

- Clean Maven package after syncing current main: 212 Java tests passed, with no
  failures, errors or skips.
- Frontend regression suite: 31 tests passed, including v5 session migration.
- Recorded provider response: old rejection reproduced; v6 verified the exact
  unit in one query, with no external calls.
- Native Linux OCR regression: all 19 original screenshots passed ordered-exact
  extraction with all three engines; 36 Python tests passed. Both new addresses
  had 3/3 agreement and no review requirement. No OCR jobs or temporary QA app or
  fixture directory remained after the run. The prior 18 cases retained identical
  address text, agreement counts and review decisions.
- Independent source review found no remaining blocker in spatial evidence,
  literal unit preservation, real-conflict handling or policy migration.

The native run used candidate parser classes overlaid on the previous immutable
image, rather than a newly published final image. It ran as UID 10001 with eight
CPUs, 8 GiB, a read-only root and no network; peak memory was 1,375,936,512 bytes,
with no memory-limit or OOM events. The production application was unchanged.

Original delivery screenshots, provider bodies and detailed diagnostic logs stay
in ignored private evidence. Only the two explicitly approved Google requests
were made. No vehicle commands or production deployment are part of this task;
existing uncertain-command holds remain in place.
