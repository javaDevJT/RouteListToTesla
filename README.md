# RouteListToTesla

RouteListToTesla converts photographed route lists into Tesla-ready navigation waypoints. The Spring Boot 3.5.5 service ingests 1..N annotated screenshots, extracts and normalizes street addresses with Tesseract 5, geocodes the candidates through Google Maps, lets drivers review or edit the list in groups of eight, and finally publishes the confirmed waypoints to Tesla Fleet / Teslemetry APIs.

## Feature Highlights
- **High-accuracy OCR pipeline** – Upscaling, grayscale, unsharp masking, and heuristics repair common street-spacing issues before parsing.
- **Default-state enrichment** – When dispatch sheets omit the state, the caller can supply a default state code that is appended during extraction.
- **SHA-256 image cache** – Frontend computes the hash, backend stores resolved candidates under `cache/` so duplicate screenshots never re-run OCR/geocoding.
- **Editable review UI** – Users can add, remove, or edit addresses in an eight-address grid before transmitting them to the vehicle.
- **Tesla Fleet integration** – Supports both the legacy `commandNavigationGpsRequest` and the newer batch waypoint API with retry-aware HTTP client.
- **Secured by Google OAuth2** – Only allow-listed Google accounts can access the UI; OAuth tokens are never stored server-side.

## End-to-End Flow
1. User drags 1..N route screenshots into the browser.
2. Frontend computes SHA-256 hashes and calls `/route/cache/check` to skip known images.
3. Only cache misses are uploaded to `/route/places` with an optional `defaultState` parameter.
4. Tesseract OCR emits line candidates; `AddressExtractor` merges overlapping lines and normalizes tokens.
5. Google Geocoding API resolves each unique candidate to `lat/lon/place_id` (US-biased results by default).
6. Cached or freshly resolved candidates are merged and grouped by eight for user review.
7. Users edit addresses inline; edited rows are re-geocoded before send.
8. `/route/send/{vin}` pushes the final, ordered waypoint list to Tesla Fleet / Teslemetry.
9. The backend stores successful geocoded blobs per image hash for subsequent replays.

## Architecture at a Glance
- **Controller layer** (`RouteController`) – REST endpoints for OCR, Tesla dispatch, and cache checks.
- **OCR layer** (`AddressOcrService`) – ByteDeco Tesseract bindings with preprocessing filters and address repair logic.
- **Address parsing utilities** (`AddressExtractor`) – US-focused heuristics that normalize spacing, merge HOCR overlaps, and append default states.
- **Geocoding client** (`GeocodingClient`) – Apache HttpClient 5 + Jackson for robust Google API calls with pacing.
- **Cache service** (`ImageCacheService`) – File-based store that maps SHA-256 hashes to JSON payloads under `cache/images/`.
- **Tesla client** (`FleetApi`) – Consolidates Fleet API + Teslemetry calls including waypoint submission.

## Technology
- Java 21, Spring Boot 3.5.5, Maven wrapper
- Spring MVC, Validation, Security, OAuth2 Client, Thymeleaf
- ByteDeco Tesseract 5.5.1 + Leptonica 1.85.0
- Apache HttpClient 5, Jackson, Lombok (optional)
- Tesla Fleet API / Teslemetry HTTP integrations

## Prerequisites
- Java 21+ and Maven 3.6+ (wrapper included)
- Google Cloud project with OAuth2 Client + Geocoding API enabled
- Tesla Fleet / Teslemetry API key with access to the target VIN
- `eng.traineddata` already ships under `src/main/resources`

## Required Environment
```bash
export google.oauth.client-id="your-google-client-id"
export google.oauth.client-secret="your-google-client-secret"
export google.api.key="your-google-maps-api-key"
export allowed.user1="authorized-user@example.com"   # allow-list entry (email)
export teslemetry.api.key="teslemetry-or-tesla-fleet-token"
```

## Local Development
```bash
# Install dependencies & run API locally on :10088
./mvnw spring-boot:run

# Package an executable jar
./mvnw clean package
```
The UI lives at `http://localhost:10088/` and automatically redirects to Google OAuth. When testing locally, configure the Google callback to `http://localhost:10088/login/oauth2/code/google`.

## Quality & Tests
```bash
./mvnw test
```
Unit and integration tests live under `src/test/java` and cover the OCR pipeline (`AddressOcrServiceTest`), address extraction utilities, and cache integration.

## API Surface
| Method | Path | Description |
| ------ | ---- | ----------- |
| `POST` | `/route/places` | Accepts multipart images (and optional `defaultState`) and returns unique, geocoded candidates. |
| `POST` | `/route/places/{vin}` | Legacy endpoint that processes images and immediately sends the resulting route to the Tesla VIN. |
| `POST` | `/route/send/{vin}` | Accepts edited JSON payload of candidates and sends validated waypoints to the Tesla VIN. |
| `POST` | `/route/cache/check` | Accepts `{imageHash, filename}` and returns cached candidates (if present). |

### `/route/places`
```bash
curl -X POST "http://localhost:10088/route/places" \
  -H "Authorization: Bearer <oauth-cookie>" \
  -F defaultState=MI \
  -F images=@/path/to/route1.png \
  -F images=@/path/to/route2.png
```
Response snippet:
```json
{
  "candidates": [
    {
      "text": "123 Main St, Ann Arbor MI",
      "normalized": "123 main st ann arbor mi",
      "sourceImage": "route1.png",
      "lineIndex": 0,
      "lat": 42.2808,
      "lon": -83.7430,
      "pid": "ChIJ..."
    }
  ]
}
```

### `/route/send/{vin}`
```bash
curl -X POST "http://localhost:10088/route/send/5YJ3E1EA7JF000000" \
  -H "Content-Type: application/json" \
  -d '{
        "candidates": [
          {"text":"123 Main St, Ann Arbor MI", "normalized":"123 main st ann arbor mi", "lat":42.2808, "lon":-83.7430, "pid":"ChIJ..."}
        ]
      }'
```

### `/route/cache/check`
```bash
curl -X POST "http://localhost:10088/route/cache/check" \
  -H "Content-Type: application/json" \
  -d '{"imageHash":"a1b2...","filename":"route1.png"}'
```

## Image Cache & Data Retention
- Cached responses are stored under `cache/image_cache_index.json` plus `cache/images/<hash>.json`.
- Each cache entry contains the geocoded candidates for a single screenshot and can be safely deleted when rolling deployments.
- The `cache/` directory is ignored by Git; scrub it before publishing if you previously processed customer data.

## Security
- Google OAuth2 login (Spring Security) protects every endpoint except `/route/cache/check`.
- Requests are restricted to `allowed.user1` (add more env vars for additional accounts).
- Tesla/Teslemetry access tokens are injected via environment variables and never persisted to disk.

## Project Layout
```
src/main/java/com/jtdev/routelisttotesla/
├── controller/RouteController.java
├── service/
│   ├── AddressOcrService.java
│   ├── GeocodingClient.java
│   └── ImageCacheService.java
├── model/
│   ├── FleetApi.java
│   ├── PlaceCandidate.java
│   └── PlaceCandidatesResponse.java
├── util/AddressExtractor.java
└── config/
    ├── SecurityConfig.java
    └── FleetApiClientConfig.java
```

## Container Image
```bash
./mvnw clean package
podman build --arch amd64 -t routelisttotesla:amd64-latest .
podman build --arch arm64 -t routelisttotesla:arm64-latest .
podman manifest create routelisttotesla:latest
podman manifest add routelisttotesla:latest routelisttotesla:amd64-latest
podman manifest add routelisttotesla:latest routelisttotesla:arm64-latest
podman manifest push routelisttotesla:latest
```

## Planned Enhancements
- Multi-language OCR packs and deskew/denoise preprocessing.
- Batch geocoding via Google Maps Advanced Routes for even tighter QPS. 
- Redis-based cache for distributed deployments.
- Mobile-ready UI with live Tesla route progress.