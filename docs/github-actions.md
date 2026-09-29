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
