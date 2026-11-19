# Numbered street verification

The supplied screenshot reads `22506 W 11 MILE RD, SOUTHFIELD, MI`.
Google returns the same nonpartial rooftop as `22506 W Eleven Mile Rd`;
policy v20 rejected the differing number representation. Five approved diagnostic
requests agree on that building. Local Tesseract settings support the source
reading; those settings are one engine, not independent consensus.

Policy `street-address-v21` recognizes whole English cardinal and ordinal number
expressions throughout street names: `Eleven` / `11`, `Twenty First` / `21st`,
including hundreds, thousands and millions up to 999,999,999. Cardinal and ordinal
forms remain distinct. It preserves the supplied address and OCR metadata.

Conversion requires the original unchanged address, unchanged-street constrained
forward query and reverse lookup to corroborate the same nonpartial rooftop,
house number, ZIP, place ID and coordinates within two metres. Directions,
terminal street types, units, state/country and competing destinations retain
their safeguards. This does not substitute OCR lookalike letters in house or
unit numbers. Existing labeled estimated-road fallback behavior is preserved.

Review explains the provider's number spelling and shows its map address while
retaining the source text. Policy invalidation causes stale saved geocoding
results to be checked again without deleting source images or saved routes.
No extraction-version change is needed.

The isolated source build passes 392 Java tests and 73 browser-state tests.
The regression covers the recorded Eleven Mile rooftop, bidirectional general
number expressions, original/constrained/reverse failures, competing buildings,
unit conflicts and unchanged OCR metadata. The exact CI image also passed 40
ordered three-engine screenshot cases and all 32 saved provider cases. The new
case resolves as `VERIFIED_BUILDING` using `corroborated_number_word_street` in
three requests. Source text, line order, OCR agreement and alternatives remain
unchanged. Native replay made no external provider or vehicle calls.

The first candidate CI run, `44515207365/1`, correctly blocked publication on
two High OpenJDK findings in the previous pinned Java 25.0.4.1 runtime:
`CVE-2026-114938` and `CVE-2026-91415`. The build now uses the official Temurin
25.0.5.1+2 Linux x64 binary, pinned by its release SHA-256, for both compilation
and jlink. Temurin's Docker tag still supplies 25.0.5+8; the checksum-pinned
binary closes that publication gap. The runtime base, compiler-free jlink
packaging, security gate, UID and read-only deployment contract are retained.
See [Temurin's security guidance](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/).

The following run, `44524118168/1`, failed before application compilation when
the Ubuntu HTTP mirrors were unreachable. Build stages now use the same signed
Ubuntu repositories over HTTPS, three bounded download retries and strict index
update failure handling. The pinned Temurin image supplies the bootstrap CA
bundle because the minimal Ubuntu base does not include one; Ubuntu then installs
its own certificate package. The minimal base also lacks OpenSSL's default
trust-file links. After `44527051063/1` exposed that bootstrap failure, APT is
configured with the explicit CA bundle path and peer/hostname verification
enabled. Package signature verification remains enabled.

## Deployed release

The Route owner deployed the release with one journaled TrueNAS update. This
was the Route release workflow, not an autonomous Quarterback deployment.

- Application source: `4a327e319c3504835c378f98c8d3bc09011b97bb`.
- CI: [44529344157, attempt 1](https://github.com/javaDevJT/RouteListToTesla/actions/runs/44529344157/attempts/1), push event; build, zero High/Critical findings, storage, publication and cleanup gates passed.
- Immutable image: `ghcr.io/javadevjt/routelisttotesla@sha256:f53d269bf2d05a4c3bdacfb73f87c1a0cdb647ae8137f4394a347dc82a1690b5`.
- Image application JAR SHA-256: `5923e59ae171d14400a80008036c97846c32d93ab18fa4f0f8c65e0ffbb9b78a`.
- `APP_RELEASE`: `20261119-west11mile`; policy `street-address-v21`; extraction remains `ocr-consensus-v10`.
- Native qualification: create job `57117`, attempt 2; 40 screenshots, 32 provider cases, patched Java runtime build `25.0.5.1+2-LTS`, UID 10001, read-only root, 8 CPU/8 GiB, network none, no application overlays; QA and fixtures cleaned up.
- Production update: `57153`, successful and verified at `2026-11-19T14:26:34.496Z`.
- Public source/asset verification: `2026-11-19T14:26:51.997Z`; mobile browser verification: `2026-11-19T14:27:16.256Z`.
- Live service: [TeslaRouter](https://teslarouter.javadevjt.tech/login), ongoing production lifecycle with no scheduled expiry. Health returns HTTP 200 and the exact application source above.

The first candidate native attempt completed all 40 screenshot checks, then
stopped on a harness-only assumption: jlink's reduced release file does not
contain `JAVA_RUNTIME_VERSION`. Its failed outputs were retained and hashed;
read-only reconciliation confirmed QA/fixture cleanup and unchanged production
before recovery. Attempt 2 checked the full runtime's actual three-line
`java --version` output before OCR and repeated the complete functional suite.
The final record preserves both attempts and their reconciliation. The public
browser verifier also stopped before launch on a stale predecessor label;
correcting that private check required no additional deployment.

The production update changed only the two service image references and
`APP_RELEASE`. The predecessor configuration fingerprint was
`e2614c3fc17c252b230a1ef38094c72d99ad6c29f1b9ff95c2c4ee9cedad4e70`;
the deployed fingerprint is
`0eaad9f647cc9eaef9163a48d2571871a13fbf8fa9ea42d1a074ff07db0b2ca2`.
Restoring those three allowed fields in a copy reproduces the predecessor
fingerprint. Runtime volume fingerprint
`59d12fbcc26b7690669674a2c80d3e83747be3d3553faf8532f06eec6cf02b19`,
UID/GID `10001:10001`, read-only root, resource limits and networking are retained.
The mobile review shows the verified building, the provider's number spelling
and the retained numeric input. Send and automatic-start controls were enabled
but left unclicked.

Private operational evidence is indexed in
`output/private/west11mile-triage-20261119/RELEASE.md`. The five of 24 approved
Google requests are retained; the remaining budget is closed. No Tesla commands
were issued.
