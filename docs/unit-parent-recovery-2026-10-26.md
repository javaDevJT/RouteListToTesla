# Apartment and building identity verification

The supplied Cherrywood screenshot contains `5845 CHERRYWOOD APT 2601,
WEST BLOOMFIELD, MI`. OCR read it correctly. Google's original and
locality-constrained unchanged-street queries return the same precise apartment
place ID, unit 2601 and rooftop. Reverse geocoding returns the unitless parent
building at identical coordinates with a different place ID. The previous
street-structure proof interpreted that legitimate entity difference as two
competing destinations. This is a validation failure, not a corrupt place ID.

Policy `street-address-v13` adds a narrowly scoped parent-building proof:

- The original and locality-constrained unchanged street must first attest the
  exact supplied unit, the same unit place ID and rooftop within two metres.
- Only the reverse street-structure check can propose a different unitless
  parent ID. It must have one consistent house number, street, state, country,
  ZIP and typed locality, and the same rooftop within two metres. Partial or
  approximate results cannot establish the relationship.
- A fourth, building-only locality-constrained query must independently attest
  that parent ID and rooftop. This omits only the unit for corroboration; the
  original address and unit place ID remain the route destination. Two unit
  attestations and two parent attestations produce a verified unit.
- Conflicting units, multiple plausible parents or other destinations, changed
  place IDs in the forward proofs, wrong typed components and missing proof
  remain unresolved. Ordinary unitless addresses retain their existing identity
  requirements. Request budgets, deadlines and pacing are unchanged.

The screenshot also exposed a separate display bug: `STEELE` matched a permissive
`STE` marker and displayed a nonexistent “Suite ELE.” The review message now
uses the geocoder's unit-value grammar and a segment boundary. Compact and
separated unit forms remain supported; the address text is unchanged.

Saved location decisions from earlier policies revalidate under v13 without
clearing screenshots, routes or sessions. OCR remains `ocr-consensus-v9`; no
address-specific mappings or hosted OCR services are introduced.

The latest scheduled image build also exposed a High finding in `filelock`
4.0.6. The OCR dependency lock now selects 4.2.1, the patched release for
[GHSA-v4x8-3wr5-3wq8](https://github.com/advisories/GHSA-v4x8-3wr5-3wq8).
The existing vulnerability gate remains enforced without exclusions.

## Validation and release

The new positive regressions fail against the unchanged v12 source and pass
against v13. A clean package passes 324 Java tests, including seven unit/parent
tests with multiple positive and negative fixtures; 51 frontend tests pass.
Coverage includes compact units, a separate synthetic city, co-located competing
parents/units in both result orders, malformed subpremise results, formatted-only
unit claims, missing/changed address components, and drift beyond two metres.
Saved v12 decisions revalidate alongside the existing v11 migration cases.

The seven explicitly authorized Google requests were saved before qualification.
All subsequent replay used those responses and preserved the earlier 33 bodies;
no test route was sent to a vehicle.

## Verified release

- Application source `bfe06ecedb7a0b237508054336e5f4e343ccb0bb` on main.
  [CI 40356401515](https://github.com/javaDevJT/RouteListToTesla/actions/runs/40356401515),
  attempt 1, publish job 121689929273, passed build, security, storage, release
  promotion and cleanup. The retained report contains zero High/Critical findings.
- Immutable image:
  `ghcr.io/javadevjt/routelisttotesla@sha256:3b3256e0e2f719074cbdf09d25c67a045a0e99c7233d6b72f9bea59da7257024`.
  Registry source/configuration labels and packaged frontend, theme and postal
  catalog match the committed source. The actual image JAR was extracted and
  hashed before native qualification; application classes/resources loaded from
  `/app/app.jar`, with no source overlays.
- Native qualification passed 26 ordered-exact screenshots, retaining all 25
  earlier signatures. The supplied JPEG produced all five addresses in order,
  including apartment 2601 and the Halsted road suffix. All three OCR engines,
  36 Python tests and 19 address cases over 40 unique saved response fixtures
  passed. Prior address coordinates, place IDs, order and resolution metadata
  are unchanged apart from the expected policy version update. Actual filelock
  4.2.1 and dependency compatibility were verified. Peak memory was
  2,838,966,272 bytes. The isolated QA app and fixtures were cleaned up normally.
- TrueNAS job 15856 deployed release `20261026-cherrywood`. Only router image,
  permission-init image and `APP_RELEASE` changed. Credentials, cache/session
  volumes, eight CPUs/8 GiB, UID/GID 10001, read-only root and private LAN/proxy
  bindings were preserved, with the predecessor digest retained in the receipt.
- Fresh public health and runtime/configuration checks report the exact source
  and digest. Served release/theme identity, TAP redirect and protected-route/
  CSRF checks passed. Mobile Chrome at 390px rendered the live login without
  overflow or page errors. The mocked 19-row mobile review uses the exact native
  image's resolved data: Cherrywood is green and unit-verified, while Steele
  correctly states that no apartment or suite was supplied.

Reload TeslaRouter in Safari to load v13. Saved older location decisions
revalidate without clearing route or screenshot data.
