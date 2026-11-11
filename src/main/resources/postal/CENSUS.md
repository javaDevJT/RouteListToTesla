# U.S. Census ZCTA representative-point evidence

`us-zcta-areas.tsv` contains the ZIP Code Tabulation Area identifiers and
representative latitude/longitude coordinates from the U.S. Census Bureau's
[2026 Gazetteer Files](https://www.census.gov/geographies/reference-files/time-series/geo/gazetteer-files.2026.html).
The national ZCTA file is [2026_Gaz_zcta_national.zip](https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2026_Gazetteer/2026_Gaz_zcta_national.zip).
The Census Bureau describes the file as ZCTAs based on 2020 Census blocks and
its Gazetteer records as including representative coordinates.

The snapshot contains all 33,791 source records, sorted by ZIP, latitude, and
longitude. The TSV schema is `ZIP`, `INTPTLAT`, `INTPTLONG`. It preserves ZIPs
as five-character strings, including leading zeroes. Source ZIP SHA-256:
`f1e9046b91f6e60686a99343cb2752834c02b0764939403b331c78c017e2c54c` (952,131
bytes). Source text member `2026_Gaz_zcta_national.txt` SHA-256:
`e85e5d6a3df1d4f0655dae4851383b0e8f68579fb0f3f62ef0f11928b70bbda4` (2,369,699
bytes). Derived TSV SHA-256: `d51c1f5e925d80e957746db20217f9bda090b8053773f7d2b135d55507ef3ebb`.

`scripts/census-postal-areas.py` validates the pinned archive, member, schema,
coordinates, uniqueness, and national record count, then writes the TSV to
stdout without network access or filesystem writes. Reproduce the snapshot
with:

```sh
python3 scripts/census-postal-areas.py output/private/address-triage-20261111/census-zcta-2026.zip > src/main/resources/postal/us-zcta-areas.tsv
```

This data is supporting evidence only. A typed five-digit ZIP is supported when
the independently established location is within 10 km of its Census ZCTA
representative point. The result does not establish a city alias, state, street
address, rooftop, or navigation destination, and the representative point is
never returned as a geocoding result. Missing, oversized, malformed, duplicate,
or invalid-UTF-8 catalog data disables this evidence path. The existing
GeoNames locality snapshot remains unchanged.
