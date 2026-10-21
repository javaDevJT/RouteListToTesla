# Municipal postal recovery

The supplied Reymont screenshot was transcribed correctly. Google's forward and
reverse responses both identify a precise street address in Waterford Township,
with Elizabeth Lake as its neighborhood and no primary city field. The previous
postal-city proof required a primary city and therefore rejected this otherwise
corroborated address. Two authorized Google requests reproduced the issue; their
responses are retained privately for offline regression tests.

Geocoding policy `street-address-v9` supports this general response shape without
mapping individual addresses or municipalities. If all primary city fields are
absent, one consistent administrative-level-3 municipality can provide the
canonical identity for the existing postal proof. A present malformed or
conflicting primary city blocks fallback. Neighborhood, county and state fields
cannot provide a substitute city. Township label variations normalize only
within the typed municipal field, including across forward and reverse results.

Acceptance still requires the requested US city/state to be supported by the
returned ZIP in the bundled postal catalog, a precise nonpartial full address,
the correct house number and street, and the same rooftop place ID within two
metres in reverse evidence. Supplied ZIP and ZIP+4 constraints remain binding.
Another qualifying rooftop in the requested postal area blocks acceptance even
when its municipality differs. Conflicting apartment numbers remain terminal,
including on a duplicate result with a different municipal label. No catalog
coordinate becomes a destination. The original address text is retained and the
review displays Google's verified map address.

The browser, image cache and saved-result checks share the new policy so older
failed or verified results are revalidated. Extraction remains `ocr-consensus-v9`;
all three local OCR engines, ordering, overlap deduplication, eight-stop grouping,
TAP authorization, CSRF and vehicle-command holds remain in place.

Local checks passed 266 Java tests and 42 frontend tests, including 12 focused
municipal cases. Regression coverage includes
the recorded Waterford case, another catalog-backed township, equivalent labels,
missing/contradictory components, incorrect streets, ZIP constraints, competing
rooftops, apartment conflicts and older-policy browser migration.

The preceding scheduled image build also reported eight High/Critical findings
in Pillow and certifi. Their pins advance to Pillow `13.0.0` and certifi
`2026.10.19`, the advisory fixes. A native ARM64 smoke test with the current
`pillow-heif==1.8.0` passed HEIC decoding, orientation, lossless PNG round-trip
and resizing for the supplied image. The resulting immutable Linux image also
passed the vulnerability gate and complete native OCR qualification below. See the
[Pillow release](https://pypi.org/project/Pillow/13.0.0/) and
[certifi advisory](https://github.com/advisories/GHSA-6q48-6c7w-9gw2).

## Verified release

[CI run 39617731110, attempt 2](https://github.com/javaDevJT/RouteListToTesla/actions/runs/39617731110/attempts/2)
built application source `38d28d00ccf1e583ca2657f0e2b99397365c3d47` and passed
the security, storage and publication gates with zero High/Critical findings.
TrueNAS job **99** successfully deployed release **20261021-municipal-postal**
on October 21, 2026, using immutable image:

```text
ghcr.io/javadevjt/routelisttotesla@sha256:a9892609a03b2ea7501ce247d4d8aa3f99ca96aa610203da1aca4b44ba24abae
```

Native amd64 qualification used that exact image without source-class overlays:

- All 22 screenshot cases passed ordered-exact extraction with Tesseract,
  PaddleOCR and EasyOCR. The prior 21 cases retained their text, agreement and
  review decisions; the supplied raw Reymont HEIC produced three correct rows
  with 3/3 agreement and no false review. The unavailable original Oak Point
  image was not substituted into the image corpus; its recorded geocoding case
  remains covered separately.
- All 36 Python tests passed. Nine recorded address cases passed against the
  packaged Java geocoder using 15 loopback fixture requests, including the
  previous apartment, street-spelling and postal-city failures. This replay
  contacted neither Google nor Tesla.
- Peak memory was 2,335,748,096 bytes (2.18 GiB), with no memory-limit or OOM
  events and no leftover OCR jobs. Application startup, non-root runtime,
  authentication/CSRF checks and pinned dependency checks passed.
- One paired-image trial took 38.35 seconds sequentially and 20.59 seconds at
  image concurrency two, with identical text and review decisions. This measures
  OCR only, excludes Google lookup, and does not establish a guaranteed latency.
  The existing parallel-processing configuration is retained.

The first qualification completed its tests but failed cleanup when shared
TrueNAS management reported too many open files; that failed invocation is
retained as a failure. After management recovered, a fresh full qualification
exited successfully and completed cleanup. This task issued no management-service
restart or privilege change.

Production readback confirmed the selected digest, source identity at `/health`,
release on the live login page and the packaged stylesheet. A real Chrome check
at 390-pixel width passed with no page errors or horizontal overflow. The
digest-proven review template also passed a mocked nine-address mobile review,
including visible recovery details and send availability, without dispatching.
Signed-out route reads redirect to login, unauthenticated state changes fail CSRF,
and the TAP authorization redirect points to the expected TAP hostname.

Only the router/init images and release label changed. Existing data mounts,
eight CPUs/8 GiB, two engine compute threads, UID/GID 10001, read-only root and
network settings were preserved, including the existing private LAN binding
`192.168.0.2:30080` to container port `10088` and the proxy network alias.
TAP grants remain in memory, so the application restart requires signing in again.
The [public app](https://teslarouter.javadevjt.tech) serves this verified release.
Diagnosis used exactly the two approved Google requests described above; release
qualification made no additional Google requests. No vehicle commands were sent.
