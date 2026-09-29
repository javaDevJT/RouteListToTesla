# Container, dependency, and frontend modernization

## Objective and boundaries

Build the application inside a multi-stage Dockerfile, link a minimal supported
Java runtime with jlink, run the final image as a non-root user, update dependencies,
make focused iPhone upload improvements, then verify, commit, push, and deploy.
Preserve existing dirty work, credentials, saved routes, TAP authorization/CSRF,
three-engine local OCR, HEIC support, and vehicle-command safeguards. No vehicle
commands or personal-address geocoding are authorized for verification.

## Ownership and acceptance

| Work | Owner | Scope | Acceptance |
|---|---|---|---|
| Java dependencies | Java worker | Maven, wrapper, required Java compatibility changes; exclude OCR/frontend files | Current stable compatible versions, full Java tests |
| OCR dependencies | OCR worker | `ocr/` only | Current compatible CPU dependencies, decoder/unit tests; no accuracy loss in final-image qualification |
| Frontend | Frontend worker | Templates, static CSS, JS tests only | iPhone upload/review usability, accessibility, existing auth and route safeguards retained |
| Container and release | Primary | Dockerfile, ignore rules, build/release checks, docs, Git and deployment | Multi-stage build, jlink modules, non-root runtime, clean build, final-image OCR and authenticated public smoke |

Workers use the live native catalog's only Luna model, `gpt-6-luna`, at its
highest exposed effort, `max` (verified September 28, 2026). The installed swarm
model configuration is authoritative. Spawn fields establish requested settings;
provider/backend execution settings are not independently exposed.

## Baseline

Worktree: `feature/automatic-queue-new-routes`, one commit ahead of origin, with
the prior TAP/OCR/HEIC implementation still dirty. The last verified deployment
is `20260927-heic`, job 209049. Live configuration and immutable image will be
checked again for this release. Private artifacts and original screenshots must
not be accidentally included in the commit.

## Progress

- Worker handles: `/root/modern_java`, `/root/modern_ocr`, and
  `/root/modern_frontend`, each requested as `gpt-6-luna` / `max` with fresh
  bounded context. Native completion releases capacity; no extra worker sessions
  or external wrappers were created.
- Selected Spring Boot 4.1.1 and Java 25 LTS (Spring Boot supports Java 17–26;
  Java 27 is non-LTS). Java builder: Temurin 25.0.4.1, immutable image
  `sha256:5b14970485a676b41faa08f4a7bc8716cc20915daa1d581d7a75f37a8ebaf9a8`.
- Runtime base: Ubuntu 26.04,
  `sha256:da6fc2be547864451aa253836dd926da33623312df4a9a243e35dc877c378a78`.
  Runtime packages are updated within that supported distribution. Its package
  catalog provides Python 3.14 and Tesseract 5.5.0. CP314 wheels were confirmed
  for the native OCR dependencies; full-image qualification remains required.
- Previous image size: 3,339,993,805 bytes. Do not assume jlink alone dominates
  savings: the image also contains three OCR engines and their offline models.
- The GitHub repository is public. Raw supplied screenshots, private runtime
  data, credentials, and transient build/browser output are excluded from Git.

## Worker lifecycle

- `/root/modern_ocr`: accepted dependency audit. Updated `filelock` 4.0.4 →
  4.0.6 and `ImageIO` 2.37.4 → 2.38.0; moved pinned Pillow-HEIF 1.8.0 into
  the requirements lock. Updated CPython 3.14 target and CPU-wheel provenance;
  OCR model checksums stayed unchanged. All 42 packages resolved for CP314.
  The primary built the actual Ubuntu 26.04 OCR stage, passed `pip check`, and
  ran all 24 Python checks successfully in that image with networking disabled.
  Native completion is the worker release path.
- Two Python pins remain at their newest compatible versions: OmegaConf 2.3.1
  requires antlr4 runtime `==4.9.*`, so 4.9.3 is retained; SymPy 1.14.0 requires
  mpmath `<1.4`, so 1.3.0 is retained. Blind upgrades would break dependency
  resolution. The remaining pins are current stable releases from PyPI or the
  official PyTorch CPU index.
- `/root/modern_frontend`: accepted. Upload progress explains cache checks and
  processing waits; controls are locked while processing and their prior disabled
  state is restored on success or failure. Seven JavaScript regressions pass.
- `/root/modern_java`: accepted after a fresh successful package run: 117 tests
  in 17 classes, zero failures/errors. Spring Boot 4.1.1 and Jackson 3 preserve
  previous API defaults through `spring.jackson.use-jackson2-defaults` and use
  compatible persistence mappers. A literal legacy JSON regression checks dates,
  absent OCR metadata, nullable fields, and mutable saved-session lists. Maven
  3.9.16/wrapper 3.3.4 replace the old wrapper. Local tests used JDK 27 with
  `--release 25`; the Docker build additionally runs the suite on actual Java 25.
- `/root/modern_review`: independent read-only review found no confirmed
  persistence/auth/module blocker. Its legacy JSON test gap was addressed.
- `/root/publication_review`: the nine committed qualification images are
  deterministic synthetic inputs; no address overlaps with private original
  ground truth were found. Original photos, original ground truth, and runtime
  data remain excluded. Gitleaks found only reviewed synthetic test literals;
  all existing Git refs passed the secret scan.

The primary owns Git and deployment actions and retains final review responsibility.

## Qualification and memory budget

Performance qualification runs on the native AMD EPYC TrueNAS host with 4 GiB
and two CPUs. Linux/amd64 execution on the Apple Silicon development machine
uses emulation, so its timings are not production performance evidence. The
unrelated local MCP containers remain running, as requested.

- Local package: 117 tests, zero failures/errors/skips. The Java 25 Docker build
  also passed: 116 executed tests and one optional standalone-Tesseract skip.
- Frontend/release checks: 10 JavaScript tests pass, including the explicitly
  authorized change from 2 GiB to 4 GiB in the retained production configuration.
- The packaged application starts as UID 10001 with a read-only filesystem,
  denies unauthenticated business requests, and rejects POST without CSRF.
  The runtime has no Java compiler or Maven; Python dependency checks pass.
- JRE files total 79,317,551 bytes versus the previous 165,248,675 bytes. The
  initial candidate image totals 3,301,955,512 bytes versus 3,339,993,805 bytes.
  OCR engines and model weights remain the dominant part of the image.
- The first 2 GiB OCR run was inconclusive: subprocess exits/timeouts occurred
  while the local 8 GiB Docker VM was crowded with dozens of unrelated duplicate
  MCP tool containers. Its measured OCR cgroup peak was only 1,365,520,384 bytes;
  this does not establish a 2 GiB cgroup OOM. Docker status queries subsequently
  timed out. No OCR/model change was made on the basis of that run.
- The user explicitly approved 4 GiB for production and qualification; the CPU
  limit remains two and the subprocess deadline remains 45 seconds.

## Native qualification completed

The immutable candidate
`ghcr.io/javadevjt/routelisttotesla@sha256:7e2f48623482f280cf52b9a8805376ee11d7f3eca9e9b86141e6105b080c653e`
passed on the AMD EPYC 7301 TrueNAS host at 4 GiB and two CPUs. Each temporary
container ran as UID 10001 with networking disabled, a read-only root filesystem,
and no production data mounted.

- Nine synthetic cases: all 20 address rows exact and ordered; unit numbers,
  duplicate stops, cutoff review, and three negative images passed the strict
  acceptance gate. All three OCR engines supplied evidence. Image times ranged
  from 23.85 to 34.48 seconds.
- Two untouched private HEICs: all eight rows exact and ordered, including the
  three repeats across images. Every row had majority support, with no unresolved
  disagreement. Image times were 33.14 and 34.92 seconds.
- Maximum cgroup memory peak: 1,201,364,992 bytes (1.12 GiB); no OOM events or
  leftover OCR jobs. Each suite also passed Java startup, non-root/JRE packaging,
  Python dependency, unauthenticated-access, and CSRF checks.
- Temporary QA apps and uploaded fixtures were removed after every suite;
  production configuration stayed unchanged throughout qualification. Private
  original images, extracted rows, and operational adapters remain excluded from
  the public repository.

Local evidence is retained in the ignored
`output/ocr-benchmark/results/20260928-jlink-native-*.runner.json` files. The final
release image will be rebuilt with its Git revision and checked against the
qualified candidate's filesystem layers before deployment.
