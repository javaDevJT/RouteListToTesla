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
engines run locally with models installed at build time; no OpenAI key or hosted OCR is used. Review and editing remain
user-controlled. Google Maps geocoding stays separately configured with the existing `google.api.key` setting.

## Run locally

Use Java 25 and the Maven wrapper. Real OCR requires the three-engine wrapper and its offline models;
the Dockerfile installs them. Plain Tesseract TSV is accepted only by explicit legacy tests. Configure TAP delegated login and
existing geocoding settings as described in [TAP setup](OAUTH_SETUP.md) and the
[development reference](docs/development-reference.md) .

```sh
./mvnw spring-boot:run
./mvnw test
```

## Current deployment

**Verified September 27, 2026.** TeslaRouter runs `20260927-heic`, TrueNAS job
**209049**, immutable image `sha256:191f711504cbb02290aa666493f6bfacb54f6a76a576b3059b6982e226ef414c`.
The existing route-data volume is retained and no host port is published.

**iPhone HEIC/HEIF uploads convert automatically on the server.** Select images
from Safari's Photo Library or Files picker; no manual conversion or Shortcut is
needed. Conversion stays local, handles orientation, and runs before the three
OCR engines. The 12 MiB / 20 megapixel limits, authenticated upload, and CSRF
protection remain in place. OCR subprocess environments exclude application
credentials.

Both original supplied HEIC files passed the final container's Java-to-OCR path:
eight exact ordered address rows, all three engines agreeing, in 36.3 and 31.4
seconds. Network access was disabled; peak memory was 1.593 GB under the 2 GiB
limit, with no leftover job directories. Nine strict screenshot regressions
passed with 20 correct rows and no missing or extra rows. Checks passed:
116 Java tests, 24 Python tests, and seven JavaScript/deployment tests.

Fresh TAP consent loaded three vehicles. A public authenticated upload of two
uncached raw HEIC fixtures returned HTTP 200 in 69.9 seconds; malformed HEIC
returned HTTP 400. The saved session was unchanged during the smoke test.
No personal addresses were geocoded and no vehicle command was sent.
Physical iPhone hardware remains untested; both original iPhone files and the
public raw-HEIC multipart upload path were verified.

See [release evidence](docs/tap-design-integration-2026-09-27.md),
[machine-readable verification](output/heic-release-verification.json), and
[iPhone setup](docs/ios-setup.md).

## Documentation

- [Documentation index](docs/README.md)
- [TAP delegated login setup](OAUTH_SETUP.md)
- [Development reference](docs/development-reference.md)
- [iPhone setup](docs/ios-setup.md)
- [Modernization audit](docs/modernization-2026-09-26.md)
