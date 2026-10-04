# October 4 screenshot processing reliability

User reports two images taking three attempts: an OCR timeout, a Google lookup error, then success. Baseline: main `37f9ce8`. Capture live evidence before attributing either failure. Preserve three-engine consensus, address/unit specificity, screenshot ordering and overlap dedupe, TAP authorization, CSRF, route data, and vehicle-command safeguards. No vehicle commands or new real Google address lookups are authorized by this diagnosis.

## Ownership and acceptance

- Primary owns production inspection, controller/cache/browser retry flow, integration checks, and main-branch publication/deployment.
- OCR worker owns `AddressOcrService`, `ocr/consensus.py`, and the related focused deadline/cleanup tests.
- Geocoding worker owns `GeocodingClient` and its retry/quota tests.
- Acceptance: a failed stage does not discard valid completed work; transient lookup failures are handled within a bounded budget; OCR deadlines are coherent and qualified on native Linux; invalid and city-level results remain rejected; privacy-safe diagnostics identify the failing stage.

The October 4 native spawn catalog lists one callable Luna member, `gpt-6-luna`, with maximum supported effort `max`. This is the centralized swarm policy's resolved worker pair. Model/effort requests will be explicit; provider internals are unverified. Workers complete naturally after handoff. Maven target writes must be serialized.

## Evidence and decisions

Production was running revision `37f9ce831471e596643df73f6bfe6780f682251f`, image digest `sha256:e9df20c848947cd011aa5a94e74f00094c57ce9014d1499f5dd5c1a4dd4be739`, with eight CPUs, 8 GiB, and two threads per engine. Logs show an engine timeout at `2026-10-04T13:35:20.241Z` after 38,812 ms, then a geocoding failure at `2026-10-04T13:36:40.779Z` after 10,085 ms. The latter generic log does not establish an exact Google transport or status cause.

The shared Python deadline was 37 seconds; the Java deadline was 45 seconds. The candidate change allows 60 seconds inside Python and 75 seconds outside it, retaining bounded cleanup and complete three-engine consensus. Timeout diagnostics allow only known scopes and engine names.

Completed extraction is persisted in the existing owner/state/version-separated image cache using an `.ocr` suffix. Only completely geocoded images become browser cache hits. Atomic replacement protects cache readers; serialized index writes protect concurrent updates. The browser processes images in sequential requests, preserving selection order and screenshot-boundary deduplication.

Google retries are restricted to transient transport failures, HTTP 408/500/502/503/504, and `UNKNOWN_ERROR`. At most three application-owned attempts use 100/200 ms backoff, the existing pacing, and a fresh owner usage reservation before each extra attempt. Permanent failures and definitive address results are not retried. Unit-specific lookup and validated unit-omission fallback remain unchanged. Java's HTTP client can recover an idempotent GET internally after a connection failure; application attempt accounting is therefore not an exact wire or billing meter. Its global retry properties are unchanged to avoid modifying TAP client behavior.

No new partial-address cache or asynchronous job system is introduced. Earlier successful Google lookups inside a failed image may repeat; completed images and OCR extraction are preserved.

Private operational evidence is under `output/private/reliability-20261004/` and is excluded from Git. Native QA uses a disposable isolated TrueNAS app with no production mounts, ports, or network. Local amd64 Docker emulation is not used.

## Worker ledger and checks

- `/root/ocr_reliability`: requested `gpt-6-luna / max`, owns the OCR wrapper, deadline handling, and related tests. Primary accepted the reviewed production changes and test-only correction; native completion released the worker. The new protocol test selects the final engine and isolates signalling because macOS rejected signalling completed process groups; the existing descendant cleanup test and native runtime still exercise real cleanup. Worker reran five focused tests successfully. No backend model/provider metadata is exposed.
- `/root/geocoding_reliability`: requested `gpt-6-luna / max`, owns `GeocodingClient` and its tests. Primary accepted the reviewed retry logic and deterministic mocked transport tests after correcting one Mockito stubbing-order error. Native completion released the worker. Primary owns Maven verification.
- `/root/reliability_review`: requested `gpt-6-luna / max`, read-only independent review of cache/controller/browser behavior and safe retry boundaries at baseline `37f9ce8`. Consumer: primary integration acceptance. Review handoff pending; release is concise findings followed by natural completion.

The full `mvn clean package` check passes 146 tests with no skips, failures, or errors. Browser tests pass 15 checks. They cover sequential single-image requests, screenshot seam merging, and existing upload recovery controls. Cache/controller checks include restart persistence, owner/state separation, OCR failure followed by lookup failure and recovery, filename rebinding, and preservation of unit/review evidence.

An isolated native Linux source-overlay qualification passes all 11 ordered fixture comparisons, including `IMG_0539.HEIC` and `IMG_0540.HEIC`, and all 29 Python checks. OCR took 19.0–26.8 seconds per fixture in this run, with zero leftover OCR job directories and peak cgroup memory of 1,375,440,896 bytes. The QA app and its temporary fixture mount were removed; production configuration remained unchanged. This is one qualification run, not a guarantee against provider outages or host overload.

Registry, immutable-image qualification, production, and 390×844 mobile browser reports are written under the private evidence directory by `verify-release.mjs`, `native-ocr-qa.mjs`, `verify-production.mjs`, and `browser-check.mjs`. Release identifier: `20261004-reliability`. Publication, deployment, and final rendered verification remain pending at source preparation.
