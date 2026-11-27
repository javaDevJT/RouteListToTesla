import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { deflateRawSync } from 'node:zlib';
import test from 'node:test';
import { canonicalJSON, extractPublisherDigest, produceBuildReceipts } from '../../scripts/portable-build-receipt.mjs';

const repository = 'javaDevJT/Tesla-Automatic-Preconditioning-V2';
const owner = 'javaDevJT';
const repositoryId = 341;
const runId = 12345;
const runAttempt = 2;
const workflowId = 77;
const suiteId = 654;
const sourceCommit = '891ce6d411e183b57247bbb3dd2573d5aedff6b0';
const apiOrigin = 'https://api.github.com';
const backendDigest = `sha256:${'a'.repeat(64)}`;
const frontendDigest = `sha256:${'b'.repeat(64)}`;

const targets = [
  { imageRepository: 'javadevjt/tapv2-backend', publisherJobName: 'Publish backend image (TrueNAS)' },
  { imageRepository: 'javadevjt/tapv2-frontend', publisherJobName: 'Publish frontend image (TrueNAS)' },
];

const signedImageRepository = (target) => `ghcr.io/${target.imageRepository}`;

function makeResponse(body, status = 200, headers = {}) {
  const payload = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  return new Response(payload, { status, headers });
}

function rateLimitedResponse(retryAfter = '1', body = { error: 'GitHub is temporarily rate limited.', code: 'github_rate_limited' }) {
  const headers = { 'content-type': 'application/json' };
  if (retryAfter !== null) headers['retry-after'] = retryAfter;
  return makeResponse(body, 503, headers);
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function zipLog(text) {
  const name = Buffer.from('publisher.txt');
  const data = Buffer.from(text, 'utf8');
  const compressed = deflateRawSync(data);
  const crc = crc32(data);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(8, 8);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(compressed.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(name.length, 26);
  const localRecord = Buffer.concat([local, name, compressed]);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE((3 << 8) | 20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(8, 10);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(compressed.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(name.length, 28);
  central.writeUInt32LE((0o100644 * 65536) >>> 0, 38);
  const centralRecord = Buffer.concat([central, name]);

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(1, 8);
  eocd.writeUInt16LE(1, 10);
  eocd.writeUInt32LE(centralRecord.length, 12);
  eocd.writeUInt32LE(localRecord.length, 16);
  return Buffer.concat([localRecord, centralRecord, eocd]);
}

function marker(digest, { sha = sourceCommit, id = runId, attempt = runAttempt } = {}) {
  return `QB_BUILD_RECEIPT_V1 source_sha=${sha} run_id=${id} run_attempt=${attempt} digest=${digest}`;
}

function githubLogLine(body) {
  return `2026-10-19T00:00:00.0000000Z ${body}`;
}

function mockFetch({
  sourceBranch = 'main',
  runEvent = 'push',
  runConclusion = 'success',
  runHeadSha = sourceCommit,
  headRepositoryId = repositoryId,
  jobConclusion = 'success',
  checkConclusion = 'success',
  backendLog = `${githubLogLine('Publisher pushed image')}\n${githubLogLine(marker(backendDigest))}\n`,
  frontendLog = zipLog(`${githubLogLine('Publisher pushed image')}\n${githubLogLine(marker(frontendDigest))}\n`),
  backendMarkerId = runId,
} = {}) {
  const posts = [];
  const postBodies = [];
  const calls = [];
  const jobs = [
    {
      id: 701,
      run_id: runId,
      run_attempt: runAttempt,
      name: targets[0].publisherJobName,
      status: 'completed',
      conclusion: jobConclusion,
      head_sha: runHeadSha,
      check_run_url: `${apiOrigin}/repos/${encodeURIComponent(owner)}/${encodeURIComponent('Tesla-Automatic-Preconditioning-V2')}/check-runs/801`,
    },
    {
      id: 702,
      run_id: runId,
      run_attempt: runAttempt,
      name: targets[1].publisherJobName,
      status: 'completed',
      conclusion: 'success',
      head_sha: runHeadSha,
      check_run_url: `${apiOrigin}/repos/${encodeURIComponent(owner)}/${encodeURIComponent('Tesla-Automatic-Preconditioning-V2')}/check-runs/802`,
    },
    {
      id: 703,
      run_id: runId,
      run_attempt: runAttempt,
      name: 'Build and test',
      status: 'completed',
      conclusion: 'success',
      head_sha: runHeadSha,
      check_run_url: `${apiOrigin}/repos/${encodeURIComponent(owner)}/${encodeURIComponent('Tesla-Automatic-Preconditioning-V2')}/check-runs/803`,
    },
  ];
  const checkRuns = jobs.map((job, index) => ({
    id: 801 + index,
    status: 'completed',
    conclusion: index === 0 ? checkConclusion : 'success',
    head_sha: runHeadSha,
    check_suite: { id: suiteId },
    app: { id: 15368 },
    name: job.name,
  }));

  const fetchImpl = async (input, init = {}) => {
    const url = new URL(input);
    calls.push({ url: url.href, method: init.method ?? 'GET', authorization: init.headers?.authorization, signal: init.signal });
    if (url.origin === 'https://qb.example' && url.pathname === '/api/v1/build-receipts' && init.method === 'POST') {
      postBodies.push(String(init.body));
      const receipt = JSON.parse(String(init.body));
      posts.push(receipt);
      const baseKey = `${receipt.imageRepository}@${receipt.imageDigest}`.toLowerCase();
      const key = posts.length % 2 === 0 ? `${baseKey}@${receipt.sourceCommit}`.toLowerCase() : baseKey;
      return makeResponse({ accepted: true, key, receipt }, posts.length % 2 === 0 ? 200 : 201);
    }
    if (url.hostname === 'signed-logs.example') return makeResponse(Buffer.alloc(0));
    if (url.origin !== apiOrigin) throw new Error(`Unexpected request origin: ${url.origin}`);
    const path = url.pathname;
    if (path === '/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2') {
      return makeResponse({ id: repositoryId, full_name: repository, default_branch: 'main' });
    }
    if (path === `/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/actions/runs/${runId}/attempts/${runAttempt}`) {
      return makeResponse({
        id: runId,
        run_attempt: runAttempt,
        workflow_id: workflowId,
        status: 'completed',
        conclusion: runConclusion,
        event: runEvent,
        head_branch: sourceBranch,
        head_sha: runHeadSha,
        repository: { id: repositoryId },
        head_repository: { id: headRepositoryId },
        path: '.github/workflows/ci.yml@refs/heads/main',
        check_suite_id: suiteId,
      });
    }
    if (path === `/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/actions/workflows/${workflowId}`) {
      return makeResponse({ id: workflowId, path: '.github/workflows/ci.yml', state: 'active' });
    }
    if (path === `/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/check-suites/${suiteId}`) {
      return makeResponse({ id: suiteId, head_sha: runHeadSha });
    }
    if (path === `/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/actions/runs/${runId}/attempts/${runAttempt}/jobs`) {
      return makeResponse({ total_count: jobs.length, jobs });
    }
    if (path === `/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/check-suites/${suiteId}/check-runs`) {
      return makeResponse({ total_count: checkRuns.length, check_runs: checkRuns });
    }
    if (path === '/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/actions/jobs/701/logs') {
      const altered = backendMarkerId === runId ? backendLog : String(backendLog).replace(`run_id=${runId}`, `run_id=${backendMarkerId}`);
      return makeResponse(altered);
    }
    if (path === '/repos/javaDevJT/Tesla-Automatic-Preconditioning-V2/actions/jobs/702/logs') return makeResponse(frontendLog);
    throw new Error(`Unexpected request: ${init.method ?? 'GET'} ${url.href}`);
  };
  return { fetchImpl, posts, postBodies, calls };
}

const { privateKey, publicKey } = generateKeyPairSync('ed25519');
const privateKeyPem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();

function options(overrides = {}) {
  return {
    githubApiUrl: `${apiOrigin}/`,
    githubToken: 'github-read-token-fixture',
    repository,
    githubRepositoryId: repositoryId,
    sourceRunId: runId,
    sourceRunAttempt: runAttempt,
    trustedWorkflowId: workflowId,
    trustedWorkflowPath: '.github/workflows/ci.yml',
    repositoryId: '123e4567-e89b-42d3-a456-426614174000',
    publisherIdentity: 'github-actions:javaDevJT/Tesla-Automatic-Preconditioning-V2',
    imageTargets: targets,
    signingKeyPem: privateKeyPem,
    instanceUrl: 'https://qb.example',
    receiptToken: 'receipt-post-token-fixture',
    fetchImpl: mockFetch().fetchImpl,
    ...overrides,
  };
}

test('publishes separately signed receipts from two exact publisher jobs on one successful run', async () => {
  const mock = mockFetch();
  const receipts = await produceBuildReceipts(options({ fetchImpl: mock.fetchImpl }));

  assert.equal(receipts.length, 2);
  assert.equal(mock.posts.length, 2);
  assert.deepEqual(receipts.map((receipt) => receipt.imageRepository), targets.map(signedImageRepository));
  assert.deepEqual(mock.posts.map((receipt) => receipt.imageRepository), targets.map(signedImageRepository));
  assert.deepEqual(receipts.map((receipt) => receipt.imageDigest), [backendDigest, frontendDigest]);
  for (const [index, receipt] of receipts.entries()) {
    assert.equal(receipt.imageRepository, signedImageRepository(targets[index]));
    assert.equal(receipt.attestation.payload.imageRepository, signedImageRepository(targets[index]));
    assert.equal(receipt.sourceCommit, sourceCommit);
    assert.equal(receipt.runId, runId);
    assert.equal(receipt.runAttempt, runAttempt);
    assert.equal(receipt.checkRuns.length, 3);
    const { attestation, ...unsigned } = receipt;
    assert.equal(attestation.digest, receipt.imageDigest);
    assert.equal(attestation.issuer, 'quarterback-ci');
    assert.equal(attestation.subject, receipt.publisherIdentity);
    assert.equal(canonicalJSON(attestation.payload), canonicalJSON(unsigned));
    assert.equal(verify(null, Buffer.from(canonicalJSON(attestation.payload)), publicKey, Buffer.from(attestation.signature, 'base64')), true);
    const posted = mock.posts[index];
    assert.deepEqual(posted, receipt);
    const { attestation: postedAttestation, ...postedUnsigned } = posted;
    assert.equal(canonicalJSON(postedAttestation.payload), canonicalJSON(postedUnsigned));
    assert.equal(verify(null, Buffer.from(canonicalJSON(postedAttestation.payload)), publicKey, Buffer.from(postedAttestation.signature, 'base64')), true);
  }
  assert.equal(mock.calls.some((call) => call.method === 'POST' && call.authorization === 'Bearer receipt-post-token-fixture'), true);
  assert.equal(mock.calls.every((call) => call.signal instanceof AbortSignal), true);
});

for (const [label, makeSuccessResponse] of [
  ['an HTML success page', () => makeResponse('<html>private endpoint URL and token=secret</html>', 200, { 'content-type': 'text/html' })],
  ['missing acknowledgement fields', () => makeResponse({ accepted: true }, 200)],
  ['a mismatched signed receipt', (receipt) => makeResponse({
    accepted: true,
    key: `${receipt.imageRepository}@${receipt.imageDigest}`.toLowerCase(),
    receipt: { ...receipt, imageDigest: frontendDigest },
  }, 200)],
]) {
  test(`treats ${label} as indeterminate without retrying`, async () => {
    const mock = mockFetch();
    let postAttempts = 0;
    const waits = [];
    const fetchImpl = async (input, init = {}) => {
      const url = new URL(input);
      if (url.origin === 'https://qb.example' && init.method === 'POST') {
        postAttempts++;
        return makeSuccessResponse(JSON.parse(String(init.body)));
      }
      return mock.fetchImpl(input, init);
    };

    await assert.rejects(produceBuildReceipts(options({
      fetchImpl,
      sleep: async (milliseconds) => { waits.push(milliseconds); },
    })), (error) => {
      assert.equal(error.message, 'Quarterback receipt submission returned an indeterminate success response; check the persisted receipt before replaying.');
      assert.doesNotMatch(error.message, /private endpoint|token=secret|https?:\/\//);
      return true;
    });
    assert.equal(postAttempts, 1);
    assert.deepEqual(waits, []);
  });
}

test('publishes separately signed receipts from a scheduled trusted default-branch run', async () => {
  const mock = mockFetch({ runEvent: 'schedule' });
  const receipts = await produceBuildReceipts(options({ fetchImpl: mock.fetchImpl }));

  assert.equal(receipts.length, 2);
  assert.equal(mock.posts.length, 2);
  assert.ok(receipts.every((receipt) => receipt.sourceCommit === sourceCommit && receipt.runId === runId && receipt.runAttempt === runAttempt));
});

test('rejects a run that is not from the default branch', async () => {
  const mock = mockFetch({ sourceBranch: 'release/test' });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /successful trusted default-branch run/);
  assert.equal(mock.posts.length, 0);
});

test('preserves trusted manual and chained producer events', async () => {
  for (const runEvent of ['workflow_dispatch', 'workflow_run']) {
    const mock = mockFetch({ runEvent });
    assert.equal((await produceBuildReceipts(options({ fetchImpl: mock.fetchImpl }))).length, 2);
    assert.equal(mock.posts.length, 2);
  }
});

test('rejects PR, unsupported, and malformed source events before signing', async () => {
  for (const runEvent of ['pull_request', 'repository_dispatch', '', null]) {
    const mock = mockFetch({ runEvent });
    await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /unsupported source event/);
    assert.equal(mock.calls.length, 2);
    assert.equal(mock.posts.length, 0);
  }
});

test('rejects a forked source run before signing', async () => {
  const mock = mockFetch({ headRepositoryId: repositoryId + 1 });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /successful trusted default-branch run/);
  assert.equal(mock.posts.length, 0);
});

test('rejects a malformed source commit before signing', async () => {
  const mock = mockFetch({ runHeadSha: 'not-a-commit' });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /successful trusted default-branch run/);
  assert.equal(mock.posts.length, 0);
});

test('rejects a malformed publisher digest marker', async () => {
  const mock = mockFetch({ backendLog: githubLogLine(`QB_BUILD_RECEIPT_V1 source_sha=${sourceCommit} run_id=${runId} run_attempt=${runAttempt} digest=sha256:bad`) });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /malformed build receipt marker/);
  assert.equal(mock.posts.length, 0);
});

test('rejects a marker with an invalid GitHub timestamp', () => {
  const log = '2026-02-30T00:00:00.0000000Z ' + marker(backendDigest);
  assert.throws(() => extractPublisherDigest(log, sourceCommit, runId, runAttempt), /invalid GitHub timestamp/);
});

test('rejects a source job check that did not pass', async () => {
  const mock = mockFetch({ jobConclusion: 'failure' });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /job check that did not pass/);
  assert.equal(mock.posts.length, 0);
});

test('rejects a validly shaped marker copied from another workflow run', async () => {
  const mock = mockFetch({ backendMarkerId: runId + 1 });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /does not match the verified source run identity/);
  assert.equal(mock.posts.length, 0);
});

test('ignores a marker in the GitHub Run command echo and accepts canonical output', async () => {
  const echoed = "\u001b[36;1mprintf 'QB_BUILD_RECEIPT_V1 source_sha=%s run_id=%s run_attempt=%s digest=%s\\n' \"$GITHUB_SHA\" \"$GITHUB_RUN_ID\" \"$GITHUB_RUN_ATTEMPT\" \"$IMAGE_DIGEST\"\u001b[0m";
  const backendLog = [
    githubLogLine(`##[group]Run ${echoed}`),
    githubLogLine(echoed),
    githubLogLine('##[endgroup]'),
    githubLogLine('\u001b[32m' + marker(backendDigest) + '\u001b[0m'),
  ].join('\n');
  const mock = mockFetch({ backendLog });
  const receipts = await produceBuildReceipts(options({ fetchImpl: mock.fetchImpl }));
  assert.equal(receipts.length, 2);
  assert.equal(receipts[0].imageDigest, backendDigest);
});

test('rejects an actual marker with the wrong source SHA', async () => {
  const backendLog = githubLogLine(marker(backendDigest, { sha: 'f'.repeat(40) }));
  const mock = mockFetch({ backendLog });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /does not match the verified source run identity/);
  assert.equal(mock.posts.length, 0);
});

test('rejects duplicate canonical output markers', async () => {
  const backendLog = [githubLogLine(marker(backendDigest)), githubLogLine(marker(backendDigest))].join('\n');
  const mock = mockFetch({ backendLog });
  await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl })), /exactly one build receipt marker/);
  assert.equal(mock.posts.length, 0);
});

test('rejects foreign, registry-qualified, tagged, and digest-qualified target inputs before upload', async () => {
  const invalidRepositories = [
    'other-owner/app',
    'ghcr.io/javadevjt/tapv2-backend',
    'docker.io/javadevjt/tapv2-backend',
    'javadevjt/tapv2-backend:latest',
    `javadevjt/tapv2-backend@${backendDigest}`,
  ];
  for (const imageRepository of invalidRepositories) {
    const mock = mockFetch();
    const invalidTargets = [{ imageRepository, publisherJobName: 'Publish backend image (TrueNAS)' }];
    await assert.rejects(produceBuildReceipts(options({ fetchImpl: mock.fetchImpl, imageTargets: invalidTargets })), /hostless lowercase GHCR repository/, imageRepository);
    assert.equal(mock.posts.length, 0, imageRepository);
  }
});

test('extracts the existing marker from a bounded ZIP log archive', () => {
  const expected = marker(backendDigest);
  assert.equal(extractPublisherDigest(zipLog(githubLogLine(expected)), sourceCommit, runId, runAttempt), backendDigest);
});

for (const seconds of [900, 901, 1500, 3900]) test(`honors ${seconds}s Retry-After and resends the exact signed receipt body`, async () => {
  const mock = mockFetch();
  const postAttempts = [];
  const waits = [];
  let fakeNow = 0;
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts.push({
        body: String(init.body),
        authorization: new Headers(init.headers).get('authorization'),
        redirect: init.redirect,
        url: new URL(input).href,
      });
      if (postAttempts.length === 1) return rateLimitedResponse(String(seconds));
    }
    return mock.fetchImpl(input, init);
  };

  const receipts = await produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  }));

  assert.equal(receipts.length, 2);
  assert.equal(postAttempts.length, 3);
  assert.equal(postAttempts[0].body, postAttempts[1].body);
  assert.deepEqual(waits, [seconds * 1000]);
  assert.ok(postAttempts.every((attempt) => attempt.url === 'https://qb.example/api/v1/build-receipts'));
  assert.ok(postAttempts.every((attempt) => attempt.authorization === 'Bearer receipt-post-token-fixture'));
  assert.ok(postAttempts.every((attempt) => attempt.redirect === 'manual'));
  assert.equal(mock.posts.length, 2);
  assert.deepEqual(mock.posts.map((receipt) => receipt.imageRepository), targets.map(signedImageRepository));
});

test('retries idempotently persisted receipts after losing both first responses', async () => {
  const mock = mockFetch();
  const persisted = new Map();
  const attempts = [];
  const waits = [];
  let fakeNow = 0;
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      const url = new URL(input).href;
      const body = String(init.body);
      const receipt = JSON.parse(body);
      attempts.push({
        imageRepository: receipt.imageRepository,
        body,
        authorization: new Headers(init.headers).get('authorization'),
        redirect: init.redirect,
        url,
      });
      const previous = persisted.get(receipt.imageRepository);
      if (previous === undefined) {
        persisted.set(receipt.imageRepository, body);
        throw Object.assign(new Error('response lost after persistence; token=private'), { code: 'ECONNRESET' });
      }
      assert.equal(previous, body, 'the idempotent consumer must see the exact persisted receipt bytes');
      return mock.fetchImpl(input, init);
    }
    return mock.fetchImpl(input, init);
  };

  const receipts = await produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  }));

  assert.equal(receipts.length, 2);
  assert.equal(persisted.size, 2);
  assert.equal(attempts.length, 4);
  for (let index = 0; index < attempts.length; index += 2) {
    assert.equal(attempts[index].body, attempts[index + 1].body);
  }
  assert.ok(attempts.every((attempt) => attempt.url === 'https://qb.example/api/v1/build-receipts'));
  assert.ok(attempts.every((attempt) => attempt.authorization === 'Bearer receipt-post-token-fixture'));
  assert.ok(attempts.every((attempt) => attempt.redirect === 'manual'));
  assert.deepEqual(waits, [1000, 1000]);
  assert.equal(mock.posts.length, 2);
});

test('recovers from a receipt upload timeout with the first bounded transport backoff', async () => {
  const mock = mockFetch();
  const waits = [];
  let postAttempts = 0;
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      if (postAttempts === 1) throw Object.assign(new Error('sensitive timeout at private endpoint'), { name: 'TimeoutError' });
    }
    return mock.fetchImpl(input, init);
  };

  const receipts = await produceBuildReceipts(options({
    fetchImpl,
    sleep: async (milliseconds) => { waits.push(milliseconds); },
  }));

  assert.equal(receipts.length, 2);
  assert.equal(postAttempts, 3);
  assert.deepEqual(waits, [1000]);
  assert.equal(mock.posts.length, 2);
});

test('shares the sixty-five-minute wait cap across a 503 and transport failure on different targets', async () => {
  const mock = mockFetch();
  const attemptsByImage = new Map();
  const waits = [];
  let fakeNow = 0;
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      const receipt = JSON.parse(String(init.body));
      const attempts = attemptsByImage.get(receipt.imageRepository) ?? [];
      attempts.push(String(init.body));
      attemptsByImage.set(receipt.imageRepository, attempts);
      if (receipt.imageRepository === signedImageRepository(targets[0]) && attempts.length === 1) return rateLimitedResponse('3900');
      if (receipt.imageRepository === signedImageRepository(targets[1])) throw new Error('response reset at second target');
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  })), /transport retry delay of 1s would exceed the 65 minute cumulative retry wait cap/);
  assert.equal(attemptsByImage.get(signedImageRepository(targets[0]))?.length, 2);
  assert.equal(attemptsByImage.get(signedImageRepository(targets[0]))?.[0], attemptsByImage.get(signedImageRepository(targets[0]))?.[1]);
  assert.equal(attemptsByImage.get(signedImageRepository(targets[1]))?.length, 1);
  assert.deepEqual(waits, [3_900_000]);
  assert.equal(mock.posts.length, 1);
});

test('stops after three transport failures with a safe message and the final cause', async () => {
  const mock = mockFetch();
  const causes = [
    new Error('first private endpoint failure'),
    new Error('second private endpoint failure'),
    new Error('https://private.example/path?token=secret'),
  ];
  const bodies = [];
  const waits = [];
  let postAttempts = 0;
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      bodies.push(String(init.body));
      throw causes[postAttempts - 1];
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({
    fetchImpl,
    sleep: async (milliseconds) => { waits.push(milliseconds); },
  })), (error) => {
    assert.match(error.message, /submission transport failed after 3 attempts/);
    assert.doesNotMatch(error.message, /private\.example|token=secret/);
    assert.strictEqual(error.cause, causes[2]);
    return true;
  });
  assert.equal(postAttempts, 3);
  assert.equal(bodies[0], bodies[1]);
  assert.equal(bodies[1], bodies[2]);
  assert.deepEqual(waits, [1000, 2000]);
  assert.equal(mock.posts.length, 0);
});

test('does not send again when transport backoff reaches the shared retry deadline', async () => {
  const mock = mockFetch();
  const deadline = 65 * 60_000 + targets.length * 3 * 30_000 + 1_000;
  let fakeNow = 0;
  let postAttempts = 0;
  const waits = [];
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      fakeNow = deadline - 1_000;
      throw new Error('response timeout');
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  })), /retry deadline expired before the next transport attempt/);
  assert.equal(postAttempts, 1);
  assert.deepEqual(waits, [1000]);
  assert.equal(mock.posts.length, 0);
});

test('shares the cooldown budget across image targets and refuses 3800 plus 101 seconds', async () => {
  const mock = mockFetch();
  const attemptsByImage = new Map();
  let fakeNow = 0;
  const waits = [];
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      const body = String(init.body);
      const receipt = JSON.parse(body);
      const attempts = attemptsByImage.get(receipt.imageRepository) ?? [];
      attempts.push(body);
      attemptsByImage.set(receipt.imageRepository, attempts);
      if (attempts.length === 1) return rateLimitedResponse(receipt.imageRepository === signedImageRepository(targets[0]) ? '3800' : '101');
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  })), /Retry-After 101s would exceed the 65 minute cumulative retry wait cap/);
  assert.equal(attemptsByImage.get(signedImageRepository(targets[0]))?.length, 2);
  assert.equal(attemptsByImage.get(signedImageRepository(targets[0]))?.[0], attemptsByImage.get(signedImageRepository(targets[0]))?.[1]);
  assert.equal(attemptsByImage.get(signedImageRepository(targets[1]))?.length, 1);
  assert.deepEqual(waits, [3_800_000]);
  assert.equal(mock.posts.length, 1);
  assert.deepEqual(mock.posts.map((receipt) => receipt.imageRepository), [signedImageRepository(targets[0])]);
});

test('allows 800 plus 100 seconds across image targets and preserves each signed body', async () => {
  const mock = mockFetch();
  const attemptsByImage = new Map();
  let fakeNow = 0;
  const waits = [];
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      const body = String(init.body);
      const receipt = JSON.parse(body);
      const attempts = attemptsByImage.get(receipt.imageRepository) ?? [];
      attempts.push(body);
      attemptsByImage.set(receipt.imageRepository, attempts);
      if (attempts.length === 1) return rateLimitedResponse(receipt.imageRepository === signedImageRepository(targets[0]) ? '800' : '100');
    }
    return mock.fetchImpl(input, init);
  };

  const receipts = await produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  }));

  assert.equal(receipts.length, 2);
  assert.deepEqual(waits, [800_000, 100_000]);
  for (const target of targets) {
    const attempts = attemptsByImage.get(signedImageRepository(target));
    assert.equal(attempts?.length, 2);
    assert.equal(attempts?.[0], attempts?.[1], `every retry for ${target.imageRepository} must resend the identical signed JSON bytes`);
  }
  assert.equal(mock.posts.length, 2);
});

test('reports an over-cap Retry-After without exposing the response body', async () => {
  const mock = mockFetch();
  let postAttempts = 0;
  const waits = [];
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      return rateLimitedResponse('3901');
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({ fetchImpl, sleep: async (milliseconds) => { waits.push(milliseconds); } })), (error) => {
    assert.match(error.message, /Retry-After at least 3901s would exceed the 65 minute cumulative retry wait cap/);
    assert.doesNotMatch(error.message, /GitHub is temporarily rate limited/);
    return true;
  });
  assert.equal(postAttempts, 1);
  assert.deepEqual(waits, []);
  assert.equal(mock.posts.length, 0);
});

test('stops after three explicit GitHub rate-limit upload attempts', async () => {
  const mock = mockFetch();
  let postAttempts = 0;
  let fakeNow = 0;
  const waits = [];
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      return rateLimitedResponse('1');
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow += milliseconds; },
  })), /retry attempt limit of 3 was reached/);
  assert.equal(postAttempts, 3);
  assert.deepEqual(waits, [1000, 1000]);
});

test('does not submit after the bounded retry deadline expires', async () => {
  const mock = mockFetch();
  let postAttempts = 0;
  let fakeNow = 0;
  const waits = [];
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      return rateLimitedResponse('1');
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({
    fetchImpl,
    now: () => fakeNow,
    sleep: async (milliseconds) => { waits.push(milliseconds); fakeNow = 5_000_000; },
  })), /retry deadline expired/);
  assert.equal(postAttempts, 1);
  assert.deepEqual(waits, [1000]);
});

for (const [label, makeFailure] of [
  ['missing Retry-After', () => rateLimitedResponse(null)],
  ['malformed Retry-After', () => rateLimitedResponse('1e2')],
  ['wrong response code', () => rateLimitedResponse('1', { error: 'rate-limited', code: 'rate_limit' })],
  ['malformed response JSON', () => makeResponse('{"code":', 503, { 'retry-after': '1' })],
]) {
  test(`does not retry a 503 with ${label}`, async () => {
    const mock = mockFetch();
    let postAttempts = 0;
    const waits = [];
    const fetchImpl = async (input, init = {}) => {
      if (init.method === 'POST') {
        postAttempts++;
        return makeFailure();
      }
      return mock.fetchImpl(input, init);
    };

    await assert.rejects(produceBuildReceipts(options({ fetchImpl, sleep: async (milliseconds) => { waits.push(milliseconds); } })), /HTTP 503/);
    assert.equal(postAttempts, 1);
    assert.deepEqual(waits, []);
    assert.equal(mock.posts.length, 0);
  });
}

test('bounds the 503 response body before deciding whether it can retry', async () => {
  const mock = mockFetch();
  let postAttempts = 0;
  const fetchImpl = async (input, init = {}) => {
    if (init.method === 'POST') {
      postAttempts++;
      return makeResponse(Buffer.alloc(2 * 1024 * 1024 + 1, 0x61), 503, { 'retry-after': '1' });
    }
    return mock.fetchImpl(input, init);
  };

  await assert.rejects(produceBuildReceipts(options({ fetchImpl })), /response exceeded the size limit/);
  assert.equal(postAttempts, 1);
  assert.equal(mock.posts.length, 0);
});

for (const [label, makeFailure, expectedError] of [
  ['HTTP 401 authentication failure', () => makeResponse({ message: 'unauthorized' }, 401), /HTTP 401/],
  ['HTTP 403 authorization failure', () => makeResponse({ message: 'forbidden' }, 403), /HTTP 403/],
  ['HTTP 500', () => makeResponse({ message: 'server failure' }, 500), /HTTP 500/],
  ['HTTP 502', () => makeResponse({ message: 'upstream failure' }, 502), /HTTP 502/],
  ['HTTP redirect', () => makeResponse({ message: 'redirect' }, 302, { location: 'https://attacker.test/receipt' }), /HTTP 302/],
  ['stale trust HTTP 409', () => makeResponse({ message: 'source trust is stale' }, 409), /HTTP 409/],
]) {
  test(`does not retry a ${label} during receipt upload`, async () => {
    const mock = mockFetch();
    let postAttempts = 0;
    const waits = [];
    const fetchImpl = async (input, init = {}) => {
      if (init.method === 'POST') {
        postAttempts++;
        return makeFailure();
      }
      return mock.fetchImpl(input, init);
    };

    await assert.rejects(produceBuildReceipts(options({ fetchImpl, sleep: async (milliseconds) => { waits.push(milliseconds); } })), expectedError);
    assert.equal(postAttempts, 1);
    assert.deepEqual(waits, []);
    assert.equal(mock.posts.length, 0);
  });
}
