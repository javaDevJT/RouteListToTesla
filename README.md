# RouteListToTesla

RouteListToTesla turns ordered address screenshots into reviewed Tesla routes. The browser uploads selected
images to the application server, where Tesseract, PaddleOCR, and EasyOCR independently read them and
produce a majority transcription with literal alternatives for review.
Google Maps geocoding is a separate service.

The container builds from source in separate Java and OCR stages. Production uses
a stripped Java 25 `jlink` runtime as non-root UID/GID 10001, with no JDK or Maven
in the final image. Build and validation commands are in the
[development reference](docs/development-reference.md).

## Current source workflow

1. In TAP, open **Other Tools → TeslaRouter**, or open [TeslaRouter](https://teslarouter.javadevjt.tech)
   directly, then tap **Continue with TAP**.
2. Sign in with TAP and approve RouteList access in TAP. TAP controls identity, billing, the `routelist`
   entitlement, and per-subject vehicle grants.
3. In **Import your stops**, select an authorized **Vehicle**. Set **Default state**
   only when a screenshot omits its state.
4. Tap **Choose route screenshots**. On iPhone, choose **Photo Library** and select multiple screenshots. Reorder
   them in the selected-file list before tapping **Read addresses**; their order determines route-stop order.
5. In **Review & send**, check the OCR candidates, correct text, and remove unwanted
   addresses. Confirm or edit flagged readings before sending. The page does not support creating a new
   address by typing or importing a text list.
6. Send a route group with **Send Route 1 to Tesla**; the number changes for each group. Tesla routes are
   grouped to respect the eight-waypoint limit. Automatic navigation is optional: select **Use Automatic
   Navigation**, then **Start Automatic Navigation**.

TAP grants last up to eight hours, have no refresh token, and are held in memory. Expiry or an application
restart requires signing in with TAP again. TAP checks current entitlements and vehicle grants; revoked access
blocks browser and navigation requests. RouteList uses the stable TAP subject (`sub`) as identity. TAP email is
unverified profile data and is not an authorization key.

## iPhone

TeslaRouter shares TAP's design and links back to **TAP Dashboard** and **Other Tools**.
On narrow screens, open **Menu** to find these links. See the
[design and deployment record](docs/tap-design-integration-2026-09-27.md).

Use Safari and its native Photos picker as the primary flow; the site does not read the Photos library in the
background. For an iOS 26+ Home Screen shortcut that opens in the browser, leave **Open as Web App** off.
Standalone mode remains optional and unverified on a physical iPhone. See
[iPhone setup guide](docs/ios-setup.md) for the steps. Shortcuts, share-sheet import, and a native iPhone app
remain proposals and are not implemented.

## OCR and data flow

Image cache checks are owner-scoped, use SHA-256, and preserve selected-image ordering. All three OCR
engines run concurrently and locally with models installed at build time; no OpenAI key or hosted OCR is used. Review and editing remain
user-controlled. Overlapping screenshots merge matching consecutive address rows, preserving distinct
units and repeats within a screenshot. Google Maps geocoding stays separately configured with the existing `google.api.key` setting.

## Run locally

Use Java 25 and the Maven wrapper. Real OCR requires the three-engine wrapper and its offline models;
the Dockerfile installs them. Plain Tesseract TSV is accepted only by explicit legacy tests. Configure TAP delegated login and
existing geocoding settings as described in [TAP setup](OAUTH_SETUP.md) and the
[development reference](docs/development-reference.md) .

```sh
./mvnw spring-boot:run
./mvnw test
```

## Deployment

The parallel OCR release uses four CPUs and 4 GiB RAM. Its native qualification
reports and implementation checks are recorded in the development reference.
The runtime and benchmark details below describe the September 28 release.

Image builds and publication are automated on pushes to `main` using the private
TrueNAS runner. GHCR receives `latest` and `sha-<full-commit-sha>` tags. See
[GitHub Actions publishing](docs/github-actions.md) for the runner and build setup.

**Verified September 28, 2026.** TeslaRouter runs `20260928-jlink`, TrueNAS job
**212741**, from source commit `0fbb774d0cb2913e7e8151e09f4ed01c04dc861e` and image
`sha256:a62e3e8ff5390d2665030bd3306b7608c42bb0cde49acaad505e6bfd70df34bd`.
The multi-stage image uses a Java 25 jlink runtime and runs the application as
UID/GID 10001, with 4 GiB memory, two CPUs, and a read-only root filesystem. The
existing route-data volume is retained and no host port is published. The JRE is
52% smaller; OCR dependencies and offline models still dominate image size.

**iPhone HEIC/HEIF uploads convert automatically on the server.** Select images
from Safari's Photo Library or Files picker; no manual conversion or Shortcut is
needed. Local conversion handles orientation before three local OCR engines run.
The 12 MiB / 20 megapixel limits, TAP authorization, and CSRF protection remain in
place. OCR subprocess environments exclude application credentials.

Native AMD TrueNAS qualification passed all nine synthetic cases (20 exact
ordered rows) and both original HEICs (eight exact ordered rows). Every original
row had majority support without unresolved disagreements. Peak memory was
1.12 GiB, with no OOM events or leftover jobs. Emulated amd64 timings from the
Apple Silicon development machine are not used as production evidence.

The deployed browser flow at iPhone width passed a two-file uncached raw-HEIC
upload (HTTP 200, 63.5 seconds), malformed-HEIC rejection (HTTP 400), busy-control
restoration, duplicate-submit protection, and saved-session preservation. TAP
consent loaded three authorized vehicles. No personal addresses were geocoded or
vehicle commands sent. Physical iPhone hardware remains untested.

See [release evidence](docs/container-modernization-2026-09-28.md) and
[iPhone setup](docs/ios-setup.md).

## Documentation

- [Documentation index](docs/README.md)
- [TAP delegated login setup](OAUTH_SETUP.md)
- [Development reference](docs/development-reference.md)
- [iPhone setup](docs/ios-setup.md)
- [Modernization audit](docs/modernization-2026-09-26.md)
