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
