# Quarterback receipt registry identity

The image publisher pushes to GHCR. Quarterback admits an image using the full
registry, repository and digest identity, such as
`ghcr.io/javadevjt/routelisttotesla@sha256:…`. The receipt producer now signs that
same full repository authority in `imageRepository`.

`QB_IMAGE_TARGETS_JSON` keeps its existing hostless `imageRepository` input and
publisher-job name. That input selects the trusted publisher job; the producer
adds `ghcr.io/` when constructing the signed payload. The full field is covered
by the Ed25519 signature and submitted unchanged. A path-only receipt occupies
a different store key and cannot satisfy the full registry lookup.

The shared regression suite exercises signatures and submitted bodies with
fake keys/transports, trusted source/check bindings and bounded idempotent
delivery:

```sh
node --test tests/ci/portable-build-receipt.test.mjs
```

The existing image workflow runs the suite immediately after source checkout,
before image verification or publication. Its `pull_request_target` definition
and existing same-repository/trusted-actor guard stay on the default branch;
this change preserves those permissions, checkouts and private runner pools.
The tests use mock inputs rather than signing secrets. The introducing PR uses
the local suite and existing default-branch image workflow; the new test step
also becomes part of future PR verification after merge.

After the correction is merged, a fresh successful default-branch build and the
existing signing/submission workflow must produce a receipt for that new head.
Keep existing signed records intact; editing their repository field would break
the signature. A clean scan or a successful publisher job does not replace
current receipt, target, owner, generation or CAS verification. This source
change does not deploy an image, change signing custody or clear a drift.
