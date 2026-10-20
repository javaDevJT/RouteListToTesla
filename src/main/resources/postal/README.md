# US postal locality evidence

`us-localities.tsv` is derived from the [GeoNames US postal code dump](https://download.geonames.org/export/zip/US.zip),
retrieved October 19, 2026. Data by [GeoNames](https://www.geonames.org/),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Columns are ZIP, state,
place name, latitude, longitude, and GeoNames accuracy. Tabs separate columns.

The complete US input has 41,490 rows. The derived snapshot retains 39,848 sorted,
unique rows with a five-digit ZIP, two-letter state, city name, valid coordinates
and source accuracy 4. Low-accuracy or missing-state records are excluded.
`scripts/postal-catalog.py` reproduces this transformation without network or
filesystem writes. Production and CI read the vendored resource without downloads.

SHA-256:

```text
Source US.zip: 5bcc560547a4998c4d9c05a7f8fb3260d4cd0b6e410000f5cd803f5bafc65440
Source US.txt: 88495471922bbafb1e982c01aafd1e38038d08dde578be2ec5bbc0f16bf9dc9e
Derived TSV:  d4bde326697f50264137016827e3760fe6c70cbd39607d747c424b42815b4880
```

The data is supplied as-is and can be incomplete or outdated. It is supporting
evidence for a postal city only, never a street-address lookup or source of
navigation coordinates. Missing evidence disables this recovery path. Exact
street/house/state/country checks, independent reverse rooftop proof, ZIP and
unit safeguards remain necessary.
