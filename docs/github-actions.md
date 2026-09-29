# GitHub Actions image publishing

Objective: build this repository on the owner's private runner and publish the
container to GHCR on every push to `main`. GitHub stores workflows under
`.github/workflows/`. This publishing workflow does not deploy to TrueNAS.

The workflow uses `truenas-routelisttotesla`, the repository-specific scale set
already present in the private runner controller's target list. Each job gets an
ephemeral runner and a private remote BuildKit instance at `tcp://buildkit:1234`,
authenticated with its mounted per-job TLS certificates. Builds target native
`linux/amd64` and use a GitHub Actions cache shared across this repository's runs.

Successful builds publish `ghcr.io/javadevjt/routelisttotesla:latest` and
`ghcr.io/javadevjt/routelisttotesla:sha-<full-commit-sha>`. The image revision label
records that same source commit. The Dockerfile runs Maven tests and checks the
Python dependency/model setup as part of the build. Publishing uses the job's
short-lived `GITHUB_TOKEN` with `packages: write`; no personal token is configured.

Pushes to `main` trigger the build without path filters. Manual dispatch is also
available on `main`; other branches cannot publish through this workflow.
Publishing jobs are serialized. Third-party actions are pinned to commit SHAs,
and checkout does not leave its token in the repository configuration.

## Implementation record — September 29, 2026

The existing private GHCR package grants this repository **Write** under
**Manage Actions access** in its package settings. This grant is required for
the workflow's `GITHUB_TOKEN`; registry login alone does not prove package
access. Run `36563117001` completed image assembly but exposed the missing
grant, which has now been configured without adding a personal-token secret.

The private runner infrastructure now provides a 32 GiB memory-backed BuildKit
filesystem per job, with three concurrent worker reservations globally. The
rootless engine has a 128 GiB aggregate memory limit and zero swap; 32 GiB is
build filesystem capacity, not a per-runner RSS limit. These limits are owned
by the runner infrastructure, not configured by this workflow.

The initial push run `36560101649` passed the Java tests (117 reported, zero
failures/errors, one skip), Python dependency checks, and OCR model verification,
then exhausted the former 16 GiB BuildKit filesystem during image assembly.
The infrastructure agent deployed source hash `473476747ee95995` through TrueNAS
job `213919` and verified a multi-stage build using 27.04 GiB, the three-worker
cap, resumed admissions, and complete ephemeral-worker cleanup.

- Follow-up storage investigator: read-only ownership of the private builder's
  disk limits and safe configuration options after run `36560101649` exhausted
  storage. Primary consumes the recommendation and owns any change. Selection:
  `gpt-6-luna` / `max`, verified against the live native spawn catalog. Base:
  `3c2bdbe`. Release after a source-backed handoff; no infrastructure mutation
  or unrelated cleanup is authorized for this worker.
- `/root/ci_builder_storage` completed its read-only handoff and was released
  through normal completion. It identified the 16 GiB per-job BuildKit tmpfs
  limit and the global four-runner cap. The primary sent the findings and the
  user's request for approximately 32 GiB and three concurrent runners to the
  existing GitHub Actions infrastructure task, which owns the deployment fix.

- Primary owns workflow, documentation, validation, commit, and push to `main`.
- Runner-pattern investigator owns a read-only comparison with the owner's other
  repositories and runner configuration. Its handoff supplies the runner labels,
  Docker/build contract, and any access prerequisite for the primary to verify.
- `/root/private_runner_contract` completed the read-only handoff. Primary checked
  the existing transient build example and controller target list; the worker is
  released through normal completion.
- Worker selection: `gpt-6-luna` at `max`, resolved from the live native spawn
  catalog (one callable Luna version) and the swarm skill's model configuration.
- Acceptance: a push to `main` starts the workflow, uses the private runner,
  passes the image build checks, and publishes a commit-identifiable GHCR image.
- Before publication: actionlint 1.7.12 passed, all four action versions were
  resolved to immutable official repository commits, and the deployed TrueNAS
  controller was confirmed running with this repository in its target list.
