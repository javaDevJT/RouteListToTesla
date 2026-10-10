# October 10 container vulnerability repair

Scheduled run [38045728764](https://github.com/javaDevJT/RouteListToTesla/actions/runs/38045728764)
built source `4ab468b9e5b8c53015e905a24f169c65b28cba65`, then failed the
High/Critical gate. Release aliases were withheld. Its actual image SBOM and
Grype report identify ten blocking findings, all in Go `go1.26.7` embedded in
`/usr/bin/pebble`: GO-2026-6603, 6604, 6605, 6607, 6608, 6609, 6610, 6611,
6612 and 6613. Two are Critical and eight High. The reported fixed Go floor is
1.26.9 (some also list 1.27.2).

Pebble is a binary in the pinned Ubuntu 26.04 base layer, not a
declared application dependency or represented by a Debian package in this image's SBOM.
The base's Linux/amd64 configuration has no entrypoint and uses `/bin/bash` as
its default command. This application's final command directly launches Java;
its three local OCR engines do not use Pebble. The correction removes the unused
binary in `runtime-base` and verifies its absence after all final-stage copies.
The existing full-image scanner and release gates remain enforced.

[Canonical's Pebble documentation](https://ubuntu.com/docs/pebble/reference/cli-commands/)
describes its container service-manager role; the
[Go advisory](https://pkg.go.dev/vuln/GO-2026-6612) records one of the blocking
runtime vulnerabilities. Removal avoids retaining an unnecessary runtime merely
to update it.

The existing 40 GiB private runner lane and runtime-stage refresh policy are
preserved. Release acceptance requires successful image publication with zero
High/Critical findings, source-bound immutable image verification, native OCR
compatibility, and TrueNAS/public-runtime verification. Deployment preserves the
existing application data and configuration. No Tesla commands or real address
lookups are part of this release.

Local validation passed 146 Java tests, 15 frontend tests and seven OpenCV guard
tests, plus whitespace validation. Independent review approved the removal and
final absence guards; executable confirmation comes from the new image build,
full scanner and native OCR qualification.

## Verified release

Application source `5c0f7de7238f4bfe9f64fa257a32cb49263692f0` was committed and
pushed to `main`. [CI run 38047955977](https://github.com/javaDevJT/RouteListToTesla/actions/runs/38047955977)
passed build, scan and publication. The actual Grype report has zero High and zero
Critical findings; it still lists 238 Medium, 17 Low and one Negligible finding.
The SBOM no longer contains `/usr/bin/pebble`.

The source label, Linux/amd64 architecture, SHA tag and `latest` alias were
verified against this immutable image:

```text
ghcr.io/javadevjt/routelisttotesla@sha256:93db0724fec94bce3b39c91baa750653dbd112f661ebac4b092ab0ec6480c650
```

Native AMD qualification passed all eleven screenshots in exact order, including
the two original HEIC files, and 36 Python tests. All three OCR engines supplied
readings. The final runtime has no Pebble binary, runs as UID 10001, starts the
jlink Java application, and passes package and OpenCV backend checks. The test
used the immutable image with no source overlays; its disposable app and inputs
were removed successfully. Peak memory was 1,455,751,168 bytes.

TrueNAS update job **6034** succeeded for release `20261010-pebble-security`.
Only the router/cache-permissions image references and router release identifier
changed. Full configuration and runtime volume mount identities matched the
preserved baseline. The application runs the exact image above, with eight CPUs,
8 GiB, UID `10001:10001`, a read-only root filesystem and no host ports.

[TeslaRouter](https://teslarouter.javadevjt.tech/login) returned HTTP 200 with the
correct release identifier. TAP authorization redirects to the TAP host;
signed-out vehicle/session reads redirect to login and upload returns 403. The
mobile-width Chrome login smoke passed. Existing frontend retry/cache, apartment
preservation and overlap-deduplication checks passed with mocked authenticated
APIs; no personal-address Google lookups or vehicle commands were issued.

The workflow's storage steps succeeded. Their output states that storage
lifecycle is owned by runner hooks; no workflow storage artifact was emitted.
This release does not claim a new capacity calibration. The existing 40 GiB pool
admitted the job automatically and no infrastructure settings were changed.

Private evidence and operational adapters are retained under
`output/private/vuln-20261010/`: `ci-verification.json`,
`registry-verification.json`, `native.runner.json`, `native-completion.json`,
`deployment.json`, `production-verification.json`, and
`browser-verification.json`. The evidence binds source, CI, digest, native QA,
deployment, served identity and browser checks. This documentation follow-up
changes no application code or image recipe.
