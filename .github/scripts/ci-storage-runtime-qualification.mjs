import {
  constants,
  existsSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statfsSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { open as openAsync } from 'node:fs/promises';
import { randomBytes, createHash } from 'node:crypto';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

export const TARGETS = Object.freeze({
  statementparser: Object.freeze({
    id: 'statementparser',
    repository: 'javadevjt/statementparser',
    quotaBytes: '9663676416',
    pool: 'statementparser-storage-9g',
    runnerLabel: 'truenas-statementparser-storage-9g',
  }),
  routelisttotesla: Object.freeze({
    id: 'routelisttotesla',
    repository: 'javadevjt/routelisttotesla',
    quotaBytes: '34359738368',
    pool: 'routelisttotesla-storage-32g',
    runnerLabel: 'truenas-routelisttotesla-storage-32g',
  }),
});

export const PHASES = Object.freeze(['cache-miss', 'cache-hit', 'hold', 'physical-enospc']);
const CACHE_ROOT = '/home/runner/_work/.ci-cache';
const IMPORT_ROOT = '/ci-cache-import';
const WORKSPACE_ROOT = '/home/runner/_work';
const PRESSURE_ROOT = '/tmp';
const COLLECTOR_MODULE = '/opt/ci-storage/ci-storage-efficiency.mjs';
const BUILD_CHUNK_BYTES = 4 * 1024 * 1024;
const OCI_INDEX_MAX_BYTES = 4 * 1024 * 1024;
const PRODUCER_METADATA_MAX_BYTES = 16 * 1024;
const MAX_BUILD_LOG_BYTES = 2 * 1024 * 1024;
const MAX_LOCAL_PRESSURE_SAMPLES = 4096;
const PRESSURE_SAMPLE_POLL_MS = 100;
const PRESSURE_SAMPLE_MAX_GAP_MS = 300;

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  throw error;
}

function unsigned(value, name) {
  const text = String(value ?? '');
  if (!/^(0|[1-9][0-9]*)$/.test(text)) fail('INVALID_CONTEXT', 'Invalid ' + name);
  return BigInt(text);
}

export function validateRequest(phase, cacheKey, holdSeconds) {
  if (!PHASES.includes(phase)) fail('INVALID_INPUT', 'Unsupported fixture phase');
  if (typeof cacheKey !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$/.test(cacheKey)
      || cacheKey.includes('..')) {
    fail('INVALID_INPUT', 'Cache key must be 1-48 safe characters and cannot contain traversal');
  }
  const secondsText = String(holdSeconds ?? '');
  if (!/^(?:[1-9]|[1-9][0-9]|[1-2][0-9]{2}|300)$/.test(secondsText)) {
    fail('INVALID_INPUT', 'Hold duration must be an integer from 1 through 300 seconds');
  }
  return { phase, cacheKey, holdSeconds: Number(secondsText) };
}

export function assertPathWithin(root, candidate, { allowRoot = false } = {}) {
  if (typeof root !== 'string' || typeof candidate !== 'string'
      || !isAbsolute(root) || !isAbsolute(candidate)) {
    fail('UNSAFE_PATH', 'Paths must be absolute');
  }
  const absoluteRoot = resolve(root);
  const absoluteCandidate = resolve(candidate);
  const rel = relative(absoluteRoot, absoluteCandidate);
  if ((!allowRoot && rel === '') || rel === '..' || rel.startsWith('..' + sep) || isAbsolute(rel)) {
    fail('UNSAFE_PATH', 'Path escapes its assigned root');
  }
  return absoluteCandidate;
}

export function assertNoSymlinkPath(root, candidate, { allowMissing = false, allowRoot = false } = {}) {
  const absoluteRoot = resolve(root);
  const absoluteCandidate = assertPathWithin(absoluteRoot, candidate, { allowRoot });
  let current = absoluteRoot;
  const rootInfo = lstatSync(current);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) fail('UNSAFE_PATH', 'Path root is not a real directory');
  const rel = relative(absoluteRoot, absoluteCandidate);
  for (const component of rel.split(sep)) {
    if (!component || component === '.') continue;
    current = join(current, component);
    try {
      const info = lstatSync(current);
      if (info.isSymbolicLink()) fail('UNSAFE_PATH', 'Symlink path component rejected');
    } catch (error) {
      if (error?.code === 'ENOENT' && allowMissing) return absoluteCandidate;
      throw error;
    }
  }
  return absoluteCandidate;
}

function unescapeMountInfoPath(value) {
  return value.replace(/\\([0-7]{3})/g, (_all, octal) => String.fromCharCode(parseInt(octal, 8)));
}

export function mountForPath(candidate, mountInfo) {
  const target = resolve(candidate);
  const matches = [];
  for (const line of String(mountInfo).split('\n')) {
    const fields = line.trim().split(' ');
    const separator = fields.indexOf('-');
    if (separator < 6 || fields.length <= separator + 2) continue;
    const mountPoint = resolve(unescapeMountInfoPath(fields[4]));
    if (target === mountPoint || target.startsWith(mountPoint + sep)) {
      matches.push({
        mountPoint,
        options: fields[5].split(','),
        superOptions: fields[separator + 3]?.split(',') ?? [],
      });
    }
  }
  matches.sort((a, b) => b.mountPoint.length - a.mountPoint.length);
  return matches[0] ?? null;
}

export function isMountReadOnlyAt(candidate, mountInfo) {
  const mount = mountForPath(candidate, mountInfo);
  return Boolean(mount && mount.options.includes('ro'));
}

function requireReadOnlyImportRoot(importRoot, mountInfo = readFileSync('/proc/self/mountinfo', 'utf8')) {
  if (importRoot !== IMPORT_ROOT || !isMountReadOnlyAt(importRoot, mountInfo)) {
    fail('IMPORT_NOT_READ_ONLY', 'Trusted cache import root is not mounted read-only');
  }
  const info = lstatSync(importRoot);
  if (!info.isDirectory() || info.isSymbolicLink()) fail('UNSAFE_PATH', 'Trusted cache import root is not a real directory');
}

export function validateOciLayout(layoutRoot) {
  const root = resolve(layoutRoot);
  const info = lstatSync(root);
  if (!info.isDirectory() || info.isSymbolicLink()) fail('INVALID_OCI_LAYOUT', 'Cache layout is not a real directory');
  const indexPath = join(root, 'index.json');
  assertNoSymlinkPath(root, indexPath);
  const indexInfo = lstatSync(indexPath);
  if (!indexInfo.isFile() || indexInfo.isSymbolicLink() || indexInfo.size > OCI_INDEX_MAX_BYTES) {
    fail('INVALID_OCI_LAYOUT', 'Cache index is missing, unsafe, or too large');
  }
  const index = JSON.parse(readFileSync(indexPath, 'utf8'));
  if (index?.schemaVersion !== 2 || !Array.isArray(index.manifests) || index.manifests.length < 1
      || index.manifests.length > 4096) {
    fail('INVALID_OCI_LAYOUT', 'Cache index has no bounded OCI manifest list');
  }
  const digests = [];
  for (const descriptor of index.manifests) {
    const digest = descriptor?.digest;
    if (typeof digest !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(digest)) {
      fail('INVALID_OCI_LAYOUT', 'Cache manifest digest is invalid');
    }
    const blobPath = join(root, 'blobs', 'sha256', digest.slice(7));
    assertNoSymlinkPath(root, blobPath);
    const blobInfo = lstatSync(blobPath);
    if (!blobInfo.isFile() || blobInfo.isSymbolicLink()) fail('INVALID_OCI_LAYOUT', 'Cache manifest blob is missing or unsafe');
    digests.push(digest);
  }
  return {
    index_sha256: createHash('sha256').update(readFileSync(indexPath)).digest('hex'),
    manifest_count: index.manifests.length,
    manifest_digests: digests,
  };
}

export function cacheHelperInvocation(scope, outputPath, runnerTemp = '/tmp') {
  if (!/^[A-Za-z0-9_.-]{1,120}$/.test(scope) || scope === '.' || scope === '..') {
    fail('UNSAFE_SCOPE', 'Cache helper scope is unsafe');
  }
  if (typeof outputPath !== 'string' || !isAbsolute(outputPath)) fail('UNSAFE_PATH', 'Cache helper output path is invalid');
  assertPathWithin(runnerTemp, outputPath);
  return { command: 'ci-cache', args: [scope], shell: false, outputPath };
}

export function buildxBuildArgs({ phase, contextPath, dockerfilePath, cacheFrom, cacheTo }) {
  if (!['cache-miss', 'cache-hit'].includes(phase)) fail('INVALID_INPUT', 'BuildKit cache phase is required');
  if (typeof contextPath !== 'string' || !isAbsolute(contextPath)
      || typeof dockerfilePath !== 'string' || !isAbsolute(dockerfilePath)
      || typeof cacheTo !== 'string' || !isAbsolute(cacheTo)) {
    fail('UNSAFE_PATH', 'BuildKit paths must be absolute');
  }
  assertPathWithin(WORKSPACE_ROOT, contextPath);
  assertPathWithin(contextPath, dockerfilePath);
  assertPathWithin(CACHE_ROOT, cacheTo);
  if (/[,=\r\n]/.test(cacheTo)) fail('UNSAFE_PATH', 'Cache export path contains an exporter delimiter');
  const args = [
    'buildx', 'build',
    '--progress=plain',
    '--platform=linux/amd64',
    '--file', dockerfilePath,
    '--output=type=cacheonly',
    '--cache-to', 'type=local,dest=' + cacheTo + ',mode=max',
  ];
  if (phase === 'cache-miss') {
    if (cacheFrom !== null) fail('INVALID_INPUT', 'Cache miss cannot import a cache source');
    args.push('--no-cache');
  } else {
    if (typeof cacheFrom !== 'string' || !isAbsolute(cacheFrom)) fail('UNSAFE_PATH', 'Cache hit needs an absolute imported cache');
    assertPathWithin(IMPORT_ROOT, cacheFrom);
    if (/[,=\r\n]/.test(cacheFrom)) fail('UNSAFE_PATH', 'Cache import path contains an exporter delimiter');
    args.push('--cache-from', 'type=local,src=' + cacheFrom);
  }
  args.push(contextPath);
  return args;
}

export function markerCopyWasCached(buildLog) {
  const clean = String(buildLog).replace(/\u001b\[[0-9;]*m/g, '');
  const commandByStep = new Map();
  const cachedSteps = new Set();
  for (const line of clean.split('\n')) {
    const step = line.match(/^#([0-9]+)\s+(.*)$/);
    if (!step) continue;
    const id = step[1];
    const detail = step[2];
    if (/COPY\s+marker\.txt\s+\/marker\.txt(?:\s|$)/.test(detail)) commandByStep.set(id, true);
    if (/^(?:\[.*?\]\s*)?CACHED(?:\s|$)/.test(detail)) cachedSteps.add(id);
  }
  for (const id of commandByStep.keys()) if (cachedSteps.has(id)) return true;
  return false;
}

export function pressurePathFor({ targetId, runId, attempt, nonce }) {
  if (!TARGETS[targetId] || !/^[1-9][0-9]{0,19}$/.test(String(runId))
      || !/^[1-9][0-9]{0,3}$/.test(String(attempt)) || !/^[a-f0-9]{8,32}$/.test(String(nonce))) {
    fail('UNSAFE_PATH', 'Pressure file identity is invalid');
  }
  return join(PRESSURE_ROOT, '.ci-storage-runtime-' + targetId + '-' + runId + '-' + attempt + '-' + nonce);
}

export function cleanupOwnedFile({ root = PRESSURE_ROOT, path, dev, ino }) {
  const candidate = assertPathWithin(root, path);
  if (dirname(candidate) !== resolve(root)) fail('UNSAFE_PATH', 'Pressure file must be a direct child of its root');
  if (dev === null || dev === undefined || ino === null || ino === undefined) {
    fail('OWNERSHIP_MISMATCH', 'Pressure file identity is unavailable');
  }
  let info;
  try {
    info = lstatSync(candidate, { bigint: true });
  } catch (error) {
    if (error?.code === 'ENOENT') return { removed: false, already_absent: true };
    throw error;
  }
  if (!info.isFile() || info.isSymbolicLink()
      || info.dev !== BigInt(dev) || info.ino !== BigInt(ino) || info.nlink !== 1n) {
    fail('OWNERSHIP_MISMATCH', 'Refusing to remove a file not owned by this fixture');
  }
  unlinkSync(candidate);
  return { removed: true, already_absent: false };
}

export async function registerOpenedPressureFile(owner, path, handle) {
  owner.handle = handle;
  owner.file = { path, dev: null, ino: null, logicalBytes: 0n };
  let identity;
  try {
    identity = await handle.stat({ bigint: true });
  } catch (primaryError) {
    try {
      identity = fstatSync(handle.fd, { bigint: true });
    } catch {
      throw primaryError;
    }
    if (!identity.isFile() || identity.nlink !== 1n) {
      fail('OWNERSHIP_MISMATCH', 'Opened pressure file identity could not be established safely');
    }
    owner.file.dev = identity.dev.toString();
    owner.file.ino = identity.ino.toString();
    throw primaryError;
  }
  if (!identity.isFile() || identity.nlink !== 1n) {
    fail('OWNERSHIP_MISMATCH', 'Opened pressure file is not a singly linked regular file');
  }
  owner.file.dev = identity.dev.toString();
  owner.file.ino = identity.ino.toString();
  return owner.file;
}

export async function attemptBoundaryEnospcProbe({ remainingQuotaByFile, remainingQuotaByFilesystem, availableBytes, writeOneByte }) {
  const bounds = [remainingQuotaByFile, remainingQuotaByFilesystem, availableBytes].map(value => BigInt(value));
  if (bounds.some(value => value < 0n) || bounds[1] > 0n && bounds[2] > 0n
      || typeof writeOneByte !== 'function') {
    fail('INVALID_ENOSPC_PROBE', 'Boundary probe requires exhausted filesystem quota or available blocks and a write adapter');
  }
  try {
    const result = await writeOneByte(Buffer.from([0]));
    if (!result || result.bytesWritten !== 1) fail('WRITE_STALLED', 'One-byte ENOSPC probe made no progress');
    return { observed: false, request_bytes: 1, bytes_written: 1 };
  } catch (error) {
    if (error?.code === 'ENOSPC') return { observed: true, request_bytes: 1, bytes_written: 0 };
    throw error;
  }
}

export function validateProtectedIdentity(env, targetId) {
  const target = TARGETS[targetId];
  if (!target) fail('INVALID_TARGET', 'Fixture target is not configured');
  if (String(env.GITHUB_REPOSITORY ?? '').toLowerCase() !== target.repository) fail('WRONG_REPOSITORY', 'Workflow ran in the wrong repository');
  if (env.CI_STORAGE_REQUESTED_BYTES !== target.quotaBytes
      || env.CI_STORAGE_POOL !== target.pool) fail('WRONG_QUOTA', 'Injected quota does not match the protected target');
  if (!/^[A-Za-z0-9-]{1,128}$/.test(env.CI_STORAGE_WORKER ?? '')
      || env.CI_STORAGE_WORKER !== env.RUNNER_NAME
      || env.CI_CACHE_WORKER !== env.CI_STORAGE_WORKER) {
    fail('WRONG_WORKER', 'Runner, storage, and cache worker identities do not agree');
  }
  if (env.CI_CACHE_ROOT !== CACHE_ROOT || env.CI_CACHE_IMPORT_ROOT !== IMPORT_ROOT) {
    fail('WRONG_CACHE_ROOT', 'Injected cache roots do not match the controller contract');
  }
  if (!/^[a-f0-9]{40}$/.test(env.GITHUB_SHA ?? '')
      || !/^[1-9][0-9]{0,19}$/.test(env.GITHUB_RUN_ID ?? '')
      || !/^[1-9][0-9]{0,3}$/.test(env.GITHUB_RUN_ATTEMPT ?? '')
      || !/^[A-Za-z0-9_.-]{1,128}$/.test(env.GITHUB_JOB ?? '')) {
    fail('INVALID_CONTEXT', 'GitHub source or run identity is invalid');
  }
  for (const [name, path] of [['RUNNER_TEMP', env.RUNNER_TEMP], ['GITHUB_WORKSPACE', env.GITHUB_WORKSPACE]]) {
    if (typeof path !== 'string' || !isAbsolute(path)) fail('INVALID_CONTEXT', 'Missing absolute ' + name);
    assertPathWithin(WORKSPACE_ROOT, path, { allowRoot: name === 'GITHUB_WORKSPACE' });
  }
  return target;
}

export function validateStorageContext(env, targetId) {
  const target = validateProtectedIdentity(env, targetId);
  if (!existsSync(WORKSPACE_ROOT) || !existsSync(PRESSURE_ROOT)) fail('INVALID_CONTEXT', 'Expected job storage mount is missing');
  assertNoSymlinkPath(WORKSPACE_ROOT, env.RUNNER_TEMP);
  assertNoSymlinkPath(WORKSPACE_ROOT, env.GITHUB_WORKSPACE, { allowRoot: env.GITHUB_WORKSPACE === WORKSPACE_ROOT });
  const pressureRoot = lstatSync(PRESSURE_ROOT);
  if (!pressureRoot.isDirectory() || pressureRoot.isSymbolicLink()) fail('WRONG_VOLUME', 'Pressure path is not a real mounted directory');
  return target;
}

function statfsBytes(location) {
  const stat = statfsSync(location, { bigint: true });
  return {
    total: stat.blocks * stat.bsize,
    used: (stat.blocks - stat.bfree) * stat.bsize,
    available: stat.bavail * stat.bsize,
  };
}

function validateSharedStorageVolume(target, cacheRoot = CACHE_ROOT) {
  const workspaceStat = lstatSync(WORKSPACE_ROOT, { bigint: true });
  const tempStat = lstatSync(PRESSURE_ROOT, { bigint: true });
  if (!workspaceStat.isDirectory() || workspaceStat.isSymbolicLink()
      || !tempStat.isDirectory() || tempStat.isSymbolicLink()) {
    fail('WRONG_VOLUME', 'Workspace and pressure paths must be real directories');
  }
  if (workspaceStat.dev !== tempStat.dev) fail('WRONG_VOLUME', 'Workspace and pressure path are on different filesystems');
  const workspaceFs = statfsBytes(WORKSPACE_ROOT);
  const tempFs = statfsBytes(PRESSURE_ROOT);
  if (workspaceFs.total !== BigInt(target.quotaBytes) || tempFs.total !== workspaceFs.total
      || workspaceFs.used > workspaceFs.total || tempFs.used > tempFs.total) {
    fail('WRONG_VOLUME', 'Actual job volume total or usage differs across protected mounts');
  }
  if (existsSync(cacheRoot)) {
    const cacheStat = lstatSync(cacheRoot, { bigint: true });
    const cacheFs = statfsBytes(cacheRoot);
    if (!cacheStat.isDirectory() || cacheStat.isSymbolicLink()
        || cacheStat.dev !== workspaceStat.dev || cacheFs.total !== workspaceFs.total) {
      fail('WRONG_VOLUME', 'Writable cache export root is not on the protected job volume');
    }
  }
  return { total_bytes: workspaceFs.total.toString(), initial_used_bytes: workspaceFs.used.toString() };
}

function cacheScope(target, key) {
  return 'runtime-qualification-' + target.id + '-' + key;
}

function runChecked(command, args, { env, timeout = 120000, maxBuffer = MAX_BUILD_LOG_BYTES } = {}) {
  const result = spawnSync(command, args, {
    env,
    shell: false,
    encoding: 'utf8',
    timeout,
    maxBuffer,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.error) {
    const error = new Error('External command failed');
    error.code = result.error.code ?? 'COMMAND_FAILED';
    throw error;
  }
  if (result.status !== 0) {
    const error = new Error('External command exited nonzero');
    error.code = 'COMMAND_EXIT_' + String(result.status ?? 'UNKNOWN');
    throw error;
  }
  return { stdout: result.stdout ?? '', stderr: result.stderr ?? '' };
}

function parseCacheHelperOutput(text) {
  const fields = new Map();
  for (const line of String(text).split(/\r?\n/)) {
    if (!line) continue;
    const split = line.indexOf('=');
    if (split < 1) fail('CACHE_HELPER_OUTPUT', 'Cache helper returned malformed output');
    const name = line.slice(0, split);
    if (!['from', 'to'].includes(name) || fields.has(name)) fail('CACHE_HELPER_OUTPUT', 'Cache helper returned unexpected output');
    fields.set(name, line.slice(split + 1));
  }
  if (!fields.has('from') || !fields.has('to')) fail('CACHE_HELPER_OUTPUT', 'Cache helper omitted required paths');
  return { from: fields.get('from'), to: fields.get('to') };
}

function runCacheHelper(scope, env) {
  const tempDir = mkdtempSync(join(env.RUNNER_TEMP, 'runtime-cache-helper-'));
  const outputPath = join(tempDir, 'outputs');
  try {
    writeFileSync(outputPath, '', { flag: 'wx', mode: 0o600 });
    const invocation = cacheHelperInvocation(scope, outputPath, env.RUNNER_TEMP);
    const commandEnv = { ...env, GITHUB_OUTPUT: invocation.outputPath };
    runChecked(invocation.command, invocation.args, { env: commandEnv, timeout: 30000, maxBuffer: 64 * 1024 });
    return parseCacheHelperOutput(readFileSync(outputPath, 'utf8'));
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

function getImportedSnapshot(from, scope, context) {
  const root = resolve(IMPORT_ROOT);
  const generation = assertNoSymlinkPath(root, from);
  const rel = relative(root, generation).split(sep);
  if (rel.length !== 4 || rel[1] !== 'buildkit' || rel[2] !== scope
      || !/^[A-Za-z0-9_.-]{1,128}$/.test(rel[0]) || !rel[3].startsWith(rel[0] + '-')) {
    fail('INVALID_CACHE_SOURCE', 'Imported generation path does not match the expected scope');
  }
  if (rel[0] === context.worker) fail('SAME_CACHE_WORKER', 'Cache hit must import from a different worker');
  const snapshotRoot = join(root, rel[0]);
  const marker = join(snapshotRoot, '.complete');
  assertNoSymlinkPath(snapshotRoot, marker);
  const markerInfo = lstatSync(marker);
  if (!markerInfo.isFile() || markerInfo.isSymbolicLink()
      || readFileSync(marker, 'utf8').trim() !== 'complete') {
    fail('UNTRUSTED_CACHE_SOURCE', 'Controller completion marker is missing or invalid');
  }
  const layout = validateOciLayout(generation);
  const metadataPath = join(generation, 'runtime-qualification', 'fixture.json');
  assertNoSymlinkPath(generation, metadataPath);
  const metadataInfo = lstatSync(metadataPath);
  if (!metadataInfo.isFile() || metadataInfo.isSymbolicLink() || metadataInfo.size > PRODUCER_METADATA_MAX_BYTES) {
    fail('MISSING_PRODUCER_METADATA', 'Imported source fixture metadata is missing or unsafe');
  }
  const metadata = JSON.parse(readFileSync(metadataPath, 'utf8'));
  if (metadata.schema_version !== 1 || metadata.target !== context.target.id
      || metadata.phase !== 'cache-miss' || metadata.cache_scope !== scope
      || metadata.cache_key !== context.cacheKey || metadata.source_revision !== context.sourceRevision
      || metadata.repository !== context.repository || metadata.worker !== rel[0]
      || metadata.source_worker !== rel[0] || metadata.pool !== context.target.pool
      || metadata.requested_bytes !== context.target.quotaBytes
      || metadata.total_bytes !== context.target.quotaBytes
      || !/^[1-9][0-9]{0,19}$/.test(String(metadata.run_id ?? ''))
      || !/^[1-9][0-9]{0,3}$/.test(String(metadata.run_attempt ?? ''))) {
    fail('SOURCE_MISMATCH', 'Imported cache producer metadata does not match this fixture');
  }
  return {
    source_worker: rel[0],
    source_generation: relative(root, generation),
    source_run_id: metadata.run_id,
    source_run_attempt: metadata.run_attempt,
    source_revision: metadata.source_revision,
    layout,
  };
}

function writeMetadata(cacheExportRoot, metadata) {
  assertPathWithin(CACHE_ROOT, cacheExportRoot);
  assertNoSymlinkPath(CACHE_ROOT, cacheExportRoot);
  const directory = join(cacheExportRoot, 'runtime-qualification');
  const relativeDir = relative(cacheExportRoot, directory).split(sep);
  let current = cacheExportRoot;
  const rootInfo = lstatSync(cacheExportRoot);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) fail('UNSAFE_PATH', 'Writable cache root is not a real directory');
  for (const component of relativeDir) {
    if (!component || component === '.' || component === '..') fail('UNSAFE_PATH', 'Metadata path is unsafe');
    current = join(current, component);
    try {
      const info = lstatSync(current);
      if (info.isSymbolicLink() || !info.isDirectory()) fail('UNSAFE_PATH', 'Metadata directory is not a real directory');
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error;
      mkdirSync(current, { mode: 0o700 });
    }
  }
  const path = join(directory, 'fixture.json');
  writeFileSync(path, JSON.stringify(metadata, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  return path;
}

function verifyBuildKitTLS(env) {
  const expected = {
    BUILDKIT_TLS_CA_CERT: '/run/buildkit/tls/ca.pem',
    BUILDKIT_TLS_CLIENT_CERT: '/run/buildkit/tls/client.pem',
    BUILDKIT_TLS_CLIENT_KEY: '/run/buildkit/tls/client.key',
  };
  for (const [name, path] of Object.entries(expected)) {
    if (env[name] !== path) fail('TLS_PATH_MISMATCH', 'Remote BuildKit TLS path is not protected');
    const info = lstatSync(path);
    if (!info.isFile() || info.isSymbolicLink()) fail('TLS_PATH_MISMATCH', 'Remote BuildKit TLS file is missing or unsafe');
  }
  const inspect = runChecked('docker', ['buildx', 'inspect'], { env, timeout: 30000, maxBuffer: 128 * 1024 });
  const output = inspect.stdout + '\n' + inspect.stderr;
  if (!/Driver:\s*remote\b/i.test(output) || !/Endpoint:\s*tcp:\/\/buildkit:1234\b/i.test(output)) {
    fail('WRONG_BUILDER', 'Selected Buildx builder is not the authenticated remote endpoint');
  }
}

function markerText(target, key, sourceRevision) {
  return 'runtime-cache-marker-v1:' + target.id + ':' + key + ':' + sourceRevision + '\n';
}

function createBuildContext(runnerTemp, target, key, sourceRevision) {
  const directory = mkdtempSync(join(runnerTemp, 'runtime-cache-context-'));
  const dockerfile = join(directory, 'Dockerfile');
  const marker = join(directory, 'marker.txt');
  writeFileSync(dockerfile, 'FROM scratch\nCOPY marker.txt /marker.txt\n', { flag: 'wx', mode: 0o600 });
  writeFileSync(marker, markerText(target, key, sourceRevision), { flag: 'wx', mode: 0o600 });
  return { directory, dockerfile, marker };
}

function storageRequest(env, target, phase, cacheKey, scope) {
  return {
    schema_version: 1,
    fixture: 'storage-runtime-qualification',
    target: target.id,
    phase,
    cache_key: cacheKey,
    cache_scope: scope,
    repository: env.GITHUB_REPOSITORY,
    run_id: env.GITHUB_RUN_ID,
    run_attempt: env.GITHUB_RUN_ATTEMPT,
    job: env.GITHUB_JOB,
    source_revision: env.GITHUB_SHA,
    worker: env.CI_STORAGE_WORKER,
    pool: target.pool,
    requested_bytes: target.quotaBytes,
    total_bytes: target.quotaBytes,
  };
}

export function validateCollectorSnapshot(snapshot, env, target, now = Date.now()) {
  if (!snapshot || snapshot.valid !== true || snapshot.error
      || snapshot.worker !== env.CI_STORAGE_WORKER || snapshot.pool !== target.pool
      || unsigned(snapshot.requested_bytes, 'collector request') !== BigInt(target.quotaBytes)
      || unsigned(snapshot.total_bytes, 'collector total') !== BigInt(target.quotaBytes)
      || unsigned(snapshot.sample_interval_ms, 'collector interval') !== 100n
      || unsigned(snapshot.samples, 'collector samples') < 1n) {
    fail('COLLECTOR_MISMATCH', 'Actual authenticated storage collector identity or quota is invalid');
  }
  const started = Date.parse(snapshot.started_at);
  const sampled = Date.parse(snapshot.sampled_at);
  if (!Number.isFinite(started) || !Number.isFinite(sampled) || started > sampled
      || now - sampled > 5000 || sampled - now > 1000) {
    fail('COLLECTOR_STALE', 'Actual storage collector snapshot is stale or malformed');
  }
  const current = unsigned(snapshot.current_used_bytes, 'collector current usage');
  const peak = unsigned(snapshot.peak_used_bytes, 'collector peak usage');
  if (current > peak || peak > BigInt(target.quotaBytes)) {
    fail('COLLECTOR_MISMATCH', 'Collector usage exceeds the protected quota or peak');
  }
  return snapshot;
}

export async function fetchCollector(env, target, collector) {
  if (env.BUILDKIT_HOST !== 'tcp://buildkit:1234'
      || env.CI_STORAGE_METRICS_URL !== 'https://buildkit:1235/v1/usage') {
    fail('COLLECTOR_ENDPOINT_MISMATCH', 'Unexpected authenticated builder or collector endpoint');
  }
  const expected = collector.expectedFromEnv(env);
  const response = await collector.fetchUsage(expected);
  if (response.statusCode !== 200) fail('COLLECTOR_HTTP', 'Authenticated collector rejected the request');
  const snapshot = JSON.parse(response.body);
  collector.validateUsageSnapshot(snapshot, expected);
  return validateCollectorSnapshot(snapshot, env, target);
}

export function countHighSamples(samples, startAt, endAt, quotaBytes) {
  const start = Date.parse(startAt);
  const end = Date.parse(endAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || !Array.isArray(samples)) {
    fail('COLLECTOR_SERIES_INVALID', 'Locally collected pressure interval is invalid');
  }
  const threshold = (BigInt(quotaBytes) * 4n) / 5n;
  const selected = [];
  let previous = 0;
  for (const sample of samples) {
    const at = Date.parse(sample?.at);
    const used = unsigned(sample?.used_bytes, 'collector sample usage');
    if (!Number.isFinite(at) || used > BigInt(quotaBytes)) fail('COLLECTOR_SERIES_INVALID', 'Collector series contains an invalid sample');
    if (at < start || at > end) continue;
    if (at <= previous) fail('COLLECTOR_SERIES_INVALID', 'Local collector snapshot timestamps are not strictly increasing');
    previous = at;
    selected.push({ at, used });
  }
  if (selected.length < 2 || selected[0].at - start > 300 || end - selected.at(-1).at > 300) {
    fail('COLLECTOR_SERIES_GAP', 'Local collector snapshots do not cover the complete pressure interval');
  }
  for (let index = 1; index < selected.length; index += 1) {
    if (selected[index].at - selected[index - 1].at > PRESSURE_SAMPLE_MAX_GAP_MS) {
      fail('COLLECTOR_SERIES_GAP', 'Local collector snapshots have an excessive sampling gap');
    }
  }
  const min = selected.reduce((value, sample) => sample.used < value ? sample.used : value, selected[0].used);
  if (min <= threshold) fail('PRESSURE_NOT_OBSERVED', 'Actual collector samples did not stay above 80 percent');
  return {
    sample_interval_ms: 100,
    sample_count: selected.length,
    first_sample_at: new Date(selected[0].at).toISOString(),
    last_sample_at: new Date(selected.at(-1).at).toISOString(),
    minimum_used_bytes: min.toString(),
    maximum_used_bytes: selected.reduce((value, sample) => sample.used > value ? sample.used : value, 0n).toString(),
    all_above_80_percent: true,
  };
}

function sleep(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds));
}

async function writeChunk(handle, owner, position, length) {
  const buffer = randomBytes(length);
  let offset = 0;
  while (offset < length) {
    if (owner.interrupted) fail('INTERRUPTED', 'Fixture was interrupted during pressure allocation');
    const result = await handle.write(buffer, offset, length - offset, position + offset);
    if (!result.bytesWritten) fail('WRITE_STALLED', 'Pressure file write made no progress');
    offset += result.bytesWritten;
    owner.logicalBytes += BigInt(result.bytesWritten);
  }
}

async function allocatePressure({ owner, target, durationMs, physicalEnospc, collector }) {
  const before = statfsBytes(PRESSURE_ROOT);
  if (before.total !== BigInt(target.quotaBytes)) fail('WRONG_VOLUME', 'Pressure mount no longer has the protected quota');
  if (typeof constants.O_SYNC !== 'number') fail('UNSUPPORTED_SYNC_WRITE', 'Synchronous pressure writes are required for a real ENOSPC result');
  const maxLogicalBytes = BigInt(target.quotaBytes) - before.used;
  if (maxLogicalBytes < 1n && !physicalEnospc) fail('QUOTA_BOUND_REACHED', 'No protected quota bytes remain for the pressure hold');
  const nonce = randomBytes(8).toString('hex');
  const filePath = pressurePathFor({
    targetId: target.id,
    runId: owner.env.GITHUB_RUN_ID,
    attempt: owner.env.GITHUB_RUN_ATTEMPT,
    nonce,
  });
  assertPathWithin(PRESSURE_ROOT, filePath);
  const openFlags = constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_SYNC;
  const handle = await openAsync(filePath, openFlags, 0o600);
  await registerOpenedPressureFile(owner, filePath, handle);
  const threshold = (BigInt(target.quotaBytes) * 4n) / 5n;
  let writeEnospc = false;
  let requestedBytes = 0n;
  let maxUsed = before.used;

  if (physicalEnospc) {
      while (true) {
        const current = statfsBytes(PRESSURE_ROOT);
        if (current.total !== BigInt(target.quotaBytes) || current.used > BigInt(target.quotaBytes)) {
          fail('WRONG_VOLUME', 'Pressure exceeded the protected filesystem bounds');
      }
      const remainingQuotaByFile = maxLogicalBytes - owner.file.logicalBytes;
      const remainingQuotaByFilesystem = BigInt(target.quotaBytes) - current.used;
      if (remainingQuotaByFile <= 0n && remainingQuotaByFilesystem > 0n && current.available > 0n) {
        fail('ENOSPC_NOT_OBSERVED', 'Per-file quota bound was reached while filesystem space remained');
      }
      if (remainingQuotaByFilesystem <= 0n || current.available <= 0n) {
        const probe = await attemptBoundaryEnospcProbe({
            remainingQuotaByFile: remainingQuotaByFile < 0n ? 0n : remainingQuotaByFile,
            remainingQuotaByFilesystem,
            availableBytes: current.available,
            writeOneByte: buffer => handle.write(buffer, 0, 1, Number(owner.file.logicalBytes)),
          });
          requestedBytes += 1n;
          if (probe.observed) {
            writeEnospc = true;
            break;
          }
          owner.file.logicalBytes += 1n;
          const afterProbe = statfsBytes(PRESSURE_ROOT);
          if (afterProbe.total !== BigInt(target.quotaBytes) || afterProbe.used > BigInt(target.quotaBytes)) {
            fail('WRONG_VOLUME', 'The bounded one-byte ENOSPC probe exceeded the protected volume');
          }
          fail('ENOSPC_NOT_OBSERVED', 'The bounded one-byte write succeeded at the protected quota boundary');
        }
        const request = Number([BigInt(BUILD_CHUNK_BYTES), remainingQuotaByFile, remainingQuotaByFilesystem, current.available].reduce((a, b) => a < b ? a : b));
        if (request < 1) fail('ENOSPC_NOT_OBSERVED', 'No bounded write remained to observe physical ENOSPC');
        requestedBytes += BigInt(request);
        try {
          await writeChunk(handle, owner.file, owner.file.logicalBytes, request);
        } catch (error) {
          if (error?.code === 'ENOSPC') {
            writeEnospc = true;
            break;
          }
          throw error;
        }
        const after = statfsBytes(PRESSURE_ROOT);
        if (after.used > maxUsed) maxUsed = after.used;
        if (after.total !== BigInt(target.quotaBytes) || after.used > BigInt(target.quotaBytes)) {
          fail('WRONG_VOLUME', 'Pressure exceeded the protected filesystem bounds');
        }
      }
      if (!writeEnospc) fail('ENOSPC_NOT_OBSERVED', 'Protected quota bound was reached without write() returning ENOSPC');
    } else {
      const targetUsed = threshold + 1n;
      let actual = statfsBytes(PRESSURE_ROOT);
      let firstAllocation = true;
      while (actual.used <= threshold || firstAllocation) {
        const remainingQuotaByFile = maxLogicalBytes - owner.file.logicalBytes;
        const remainingQuotaByFilesystem = BigInt(target.quotaBytes) - actual.used;
        const remainingQuota = remainingQuotaByFile < remainingQuotaByFilesystem ? remainingQuotaByFile : remainingQuotaByFilesystem;
        const limitBytes = actual.available < remainingQuota ? actual.available : remainingQuota;
        if (limitBytes < 1n) {
          if (actual.used > threshold && owner.file.logicalBytes === 0n) break;
          fail('PRESSURE_CAPACITY', 'Insufficient free bytes to reach the requested utilization safely');
        }
        const gap = targetUsed > actual.used ? targetUsed - actual.used : 1n;
        const needed = owner.file.logicalBytes === 0n ? (gap > 4096n ? gap : 4096n) : gap;
        const amount = Number([BigInt(BUILD_CHUNK_BYTES), limitBytes, needed].reduce((a, b) => a < b ? a : b));
        if (amount < 1) fail('PRESSURE_CAPACITY', 'No bounded write remains for pressure allocation');
        requestedBytes += BigInt(amount);
        try {
          await writeChunk(handle, owner.file, owner.file.logicalBytes, amount);
        } catch (error) {
          if (error?.code === 'ENOSPC') fail('PRESSURE_CAPACITY', 'Filesystem returned ENOSPC before the 80 percent target');
          throw error;
        }
        firstAllocation = false;
        actual = statfsBytes(PRESSURE_ROOT);
        if (actual.total !== BigInt(target.quotaBytes) || actual.used > BigInt(target.quotaBytes)) {
          fail('WRONG_VOLUME', 'Pressure exceeded the protected filesystem bounds');
        }
        if (actual.used > maxUsed) maxUsed = actual.used;
      }
      if (actual.used <= threshold) fail('PRESSURE_NOT_OBSERVED', 'Actual statfs usage did not exceed 80 percent');
    }
    const allocated = statfsBytes(PRESSURE_ROOT);
    if (allocated.used > maxUsed) maxUsed = allocated.used;
    if (allocated.used <= threshold) fail('PRESSURE_NOT_OBSERVED', 'Actual statfs usage did not exceed 80 percent');
    const localSamples = [];
    const startupDeadline = process.hrtime.bigint() + 5_000_000_000n;
    let measuredStart = null;
    let measuredEnd = null;
    let wallDeadline = null;
    while (measuredEnd === null || measuredEnd - measuredStart < durationMs) {
      if (owner.interrupted) fail('INTERRUPTED', 'Fixture was interrupted during the measured pressure interval');
      const snapshot = await fetchCollector(owner.env, target, collector);
      const sampledAt = Date.parse(snapshot.sampled_at);
      const used = unsigned(snapshot.current_used_bytes, 'collector pressure usage');
      const thresholdBytes = (BigInt(target.quotaBytes) * 4n) / 5n;
      if (measuredStart === null) {
        if (used > thresholdBytes) {
          measuredStart = sampledAt;
          wallDeadline = process.hrtime.bigint() + BigInt(durationMs + 10_000) * 1_000_000n;
          localSamples.push({ at: snapshot.sampled_at, used_bytes: used.toString() });
          measuredEnd = sampledAt;
        } else if (process.hrtime.bigint() > startupDeadline) {
          fail('PRESSURE_NOT_OBSERVED', 'Authenticated collector did not sample usage above 80 percent');
        }
      } else if (sampledAt > measuredEnd) {
        if (used <= thresholdBytes) fail('PRESSURE_NOT_OBSERVED', 'Authenticated collector sampled usage at or below 80 percent during the hold');
        if (localSamples.length >= MAX_LOCAL_PRESSURE_SAMPLES) {
          fail('COLLECTOR_SERIES_LIMIT', 'Local pressure evidence exceeded its bounded sample count');
        }
        localSamples.push({ at: snapshot.sampled_at, used_bytes: used.toString() });
        measuredEnd = sampledAt;
      } else if (sampledAt < measuredEnd) {
        fail('COLLECTOR_SERIES_INVALID', 'Authenticated collector snapshot timestamps moved backward');
      }
      if (wallDeadline !== null && process.hrtime.bigint() > wallDeadline) {
        fail('COLLECTOR_SERIES_GAP', 'Authenticated collector did not cover the complete requested pressure interval');
      }
      const current = statfsBytes(PRESSURE_ROOT);
      if (current.total !== BigInt(target.quotaBytes) || current.used <= threshold) {
        fail('PRESSURE_NOT_OBSERVED', 'Actual statfs usage fell below 80 percent during the measured interval');
      }
      if (current.used > maxUsed) maxUsed = current.used;
      if (measuredEnd === null || measuredEnd - measuredStart < durationMs) await sleep(PRESSURE_SAMPLE_POLL_MS);
    }
    const intervalStart = localSamples[0].at;
    const intervalEnd = localSamples.at(-1).at;
    const summary = countHighSamples(localSamples, intervalStart, intervalEnd, target.quotaBytes);
    return {
      file_path: filePath,
      logical_bytes_written: owner.file.logicalBytes.toString(),
      write_enospc_observed: writeEnospc,
      write_bytes_requested: requestedBytes.toString(),
      statfs_used_at_start_bytes: before.used.toString(),
      statfs_used_during_bytes: maxUsed.toString(),
      quota_bytes: target.quotaBytes,
      pressure_start_at: intervalStart,
      pressure_end_at: intervalEnd,
      requested_hold_ms: durationMs,
      collector_samples: localSamples,
      collector_sample_interval_ms: summary.sample_interval_ms,
    };
}

function summarizeFailure(error, stage) {
  const candidate = String(error?.code ?? '');
  const code = /^[A-Z0-9_]{2,64}$/.test(candidate) ? candidate : 'QUALIFICATION_FAILED';
  return { stage, code };
}

async function main() {
  const evidencePath = resolve(process.env.RUNNER_TEMP ?? '/tmp', 'storage-runtime-qualification.json');
  let stage = 'input_validation';
  let target = null;
  let request = null;
  let context = null;
  let collector = null;
  let owner = { env: process.env, interrupted: false, file: null, handle: null };
  let buildContext = null;
  let pressure = null;
  let pressureSeries = null;
  let finalSnapshot = null;
  let cacheEvidence = null;
  let buildEvidence = null;
  let errorEvidence = null;
  const signalHandler = signal => { owner.interrupted = signal; };
  process.once('SIGTERM', signalHandler);
  process.once('SIGINT', signalHandler);
  try {
    const env = process.env;
    const targetId = env.RUNTIME_QUALIFICATION_TARGET;
    const phaseInput = validateRequest(env.RUNTIME_QUALIFICATION_PHASE, env.RUNTIME_QUALIFICATION_CACHE_KEY, env.RUNTIME_QUALIFICATION_HOLD_SECONDS);
    target = validateStorageContext(env, targetId);
    request = storageRequest(env, target, phaseInput.phase, phaseInput.cacheKey, cacheScope(target, phaseInput.cacheKey));
    context = {
      target,
      worker: env.CI_STORAGE_WORKER,
      repository: env.GITHUB_REPOSITORY,
      sourceRevision: env.GITHUB_SHA,
      cacheKey: phaseInput.cacheKey,
    };
    owner.env = env;
    stage = 'volume_validation';
    const sharedVolume = validateSharedStorageVolume(target);
    stage = 'collector_import';
    collector = await import(COLLECTOR_MODULE);
    stage = 'collector_initial_read';
    const initialSnapshot = await fetchCollector(env, target, collector);
    const initialCollectorUsed = unsigned(initialSnapshot.current_used_bytes, 'collector usage');
    if (initialCollectorUsed > BigInt(target.quotaBytes)) fail('COLLECTOR_MISMATCH', 'Collector usage exceeds the protected quota');

    if (phaseInput.phase === 'cache-miss' || phaseInput.phase === 'cache-hit') {
      stage = 'cache_mount_validation';
      requireReadOnlyImportRoot(IMPORT_ROOT);
      stage = 'buildkit_tls_validation';
      verifyBuildKitTLS(env);
      const scope = request.cache_scope;
      stage = 'cache_helper';
      const outputs = runCacheHelper(scope, env);
      const expectedTo = join(CACHE_ROOT, 'buildkit', scope, env.CI_CACHE_WORKER + '-' + env.GITHUB_RUN_ATTEMPT);
      if (resolve(outputs.to) !== expectedTo || !assertPathWithin(CACHE_ROOT, outputs.to)
          || outputs.from !== IMPORT_ROOT + '/missing' && !assertPathWithin(IMPORT_ROOT, outputs.from)) {
        fail('CACHE_PATH_MISMATCH', 'Cache helper returned a path outside the protected cache roots');
      }
      const cacheRootInfo = lstatSync(CACHE_ROOT);
      if (!cacheRootInfo.isDirectory() || cacheRootInfo.isSymbolicLink()) fail('UNSAFE_PATH', 'Writable cache root is not a real directory');
      assertNoSymlinkPath(CACHE_ROOT, outputs.to);
      if (outputs.from === IMPORT_ROOT + '/missing') {
        if (phaseInput.phase === 'cache-hit') fail('CACHE_SOURCE_MISSING', 'Cache hit did not find a controller-completed source');
      } else {
        requireReadOnlyImportRoot(IMPORT_ROOT);
        if (phaseInput.phase === 'cache-miss') fail('CACHE_SOURCE_ALREADY_EXISTS', 'Cache miss requires a fresh cache key with no imported generation');
      }
      let source = null;
      if (phaseInput.phase === 'cache-hit') {
        stage = 'cache_source_validation';
        source = getImportedSnapshot(outputs.from, scope, context);
      }
      const metadata = {
        ...request,
        phase: phaseInput.phase,
        source_worker: env.CI_STORAGE_WORKER,
        source_generation: null,
        created_at: new Date().toISOString(),
        source_is_controller_trusted: false,
      };
      stage = 'build_context';
      buildContext = createBuildContext(env.RUNNER_TEMP, target, phaseInput.cacheKey, env.GITHUB_SHA);
      stage = 'buildx_cache_build';
      const args = buildxBuildArgs({
        phase: phaseInput.phase,
        contextPath: buildContext.directory,
        dockerfilePath: buildContext.dockerfile,
        cacheFrom: phaseInput.phase === 'cache-hit' ? outputs.from : null,
        cacheTo: outputs.to,
      });
      const result = runChecked('docker', args, { env, timeout: 900000, maxBuffer: MAX_BUILD_LOG_BYTES });
      const log = result.stdout + '\n' + result.stderr;
      const markerCached = markerCopyWasCached(log);
      if (phaseInput.phase === 'cache-hit' && !markerCached) fail('MARKER_CACHE_MISS', 'BuildKit did not report the unique marker COPY as CACHED');
      if (phaseInput.phase === 'cache-miss' && markerCached) fail('UNEXPECTED_CACHE_HIT', 'Cache miss unexpectedly reused the unique marker COPY');
      stage = 'oci_export_validation';
      const layout = validateOciLayout(outputs.to);
      writeMetadata(outputs.to, metadata);
      if (existsSync(join(outputs.to, '.complete'))) fail('UNTRUSTED_CACHE_SOURCE', 'Fixture must not create a controller completion marker');
      cacheEvidence = {
        scope,
        phase: phaseInput.phase,
        import_root_read_only: true,
        imported_generation: source?.source_generation ?? null,
        imported_worker: source?.source_worker ?? null,
        imported_source_run_id: source?.source_run_id ?? null,
        imported_source_run_attempt: source?.source_run_attempt ?? null,
        producer_metadata_is_not_trust_anchor: true,
        actual_marker_copy_cached: markerCached,
        export_relative_path: relative(CACHE_ROOT, outputs.to),
        imported_layout: source?.layout ?? null,
        exported_layout: layout,
        controller_complete_marker_created_by_fixture: false,
      };
      buildEvidence = { buildx_driver: 'remote', buildx_endpoint: 'tcp://buildkit:1234', output: 'cacheonly', registry_or_image_published: false };
    }

    if (buildContext) {
      const contextRelative = assertPathWithin(env.RUNNER_TEMP, buildContext.directory);
      rmSync(contextRelative, { recursive: true, force: true });
      buildContext = null;
    }

    stage = 'pressure_allocation';
    const pressureDurationMs = phaseInput.holdSeconds * 1000;
    pressure = await allocatePressure({
      owner,
      target,
      durationMs: pressureDurationMs,
      physicalEnospc: phaseInput.phase === 'physical-enospc',
      collector,
    });
    stage = 'collector_pressure_validation';
    pressureSeries = countHighSamples(
      pressure.collector_samples,
      pressure.pressure_start_at,
      pressure.pressure_end_at,
      target.quotaBytes,
    );
    const currentStats = statfsBytes(PRESSURE_ROOT);
    if (currentStats.total !== BigInt(target.quotaBytes) || currentStats.used > BigInt(target.quotaBytes)
        || currentStats.used <= (BigInt(target.quotaBytes) * 4n) / 5n) {
      fail('PRESSURE_NOT_OBSERVED', 'Final actual statfs did not confirm the protected high-utilization interval');
    }
    pressure.statfs_used_at_observation_bytes = currentStats.used.toString();
    pressure.local_collector_snapshot_summary = pressureSeries;
    pressure.local_collector_sample_count = String(pressure.collector_samples.length);
    pressure.same_volume_device_checked = true;
    pressure.shared_volume_initial = sharedVolume;
  } catch (error) {
    errorEvidence = summarizeFailure(error, stage);
  } finally {
    stage = 'owned_file_cleanup';
    if (owner.handle) {
      try { await owner.handle.close(); } catch { errorEvidence ??= { stage, code: 'PRESSURE_CLOSE_FAILED' }; }
      owner.handle = null;
    }
    let cleanup = { removed: false, already_absent: false };
    if (owner.file) {
      try { cleanup = cleanupOwnedFile({ path: owner.file.path, dev: owner.file.dev, ino: owner.file.ino }); }
      catch (error) { errorEvidence ??= summarizeFailure(error, stage); }
    }
    if (buildContext) {
      try {
        assertPathWithin(process.env.RUNNER_TEMP, buildContext.directory);
        rmSync(buildContext.directory, { recursive: true, force: true });
      } catch (error) { errorEvidence ??= summarizeFailure(error, 'build_context_cleanup'); }
    }
    if (collector && target) {
      try {
        finalSnapshot = await fetchCollector(process.env, target, collector);
      } catch (error) { errorEvidence ??= summarizeFailure(error, 'collector_final_read'); }
    }
    const result = {
      schema_version: 1,
      fixture: 'storage-runtime-qualification',
      status: errorEvidence ? 'failed' : 'completed',
      request,
      target: target ? { id: target.id, pool: target.pool, requested_bytes: target.quotaBytes } : null,
      cache: cacheEvidence,
      build: buildEvidence,
      pressure,
      local_collector_snapshot_interval: pressureSeries,
      after_cleanup: finalSnapshot ? {
        current_used_bytes: String(finalSnapshot.current_used_bytes),
        peak_used_bytes: String(finalSnapshot.peak_used_bytes),
        samples: String(finalSnapshot.samples),
        sampled_at: finalSnapshot.sampled_at,
      } : null,
      cleanup: {
        pressure_file_removed: cleanup.removed,
        pressure_file_already_absent: cleanup.already_absent,
        pressure_file_logical_bytes: owner.file?.logicalBytes?.toString() ?? '0',
        cache_export_tree_untouched_by_pressure_file: true,
        cleanup_completed_before_storage_finish: !owner.file || cleanup.removed || cleanup.already_absent,
      },
      failure: errorEvidence,
      claims: {
        application_image_built: false,
        application_image_published: false,
        cold_reference_enrolled: false,
        sdk_interval_overlap_proven: false,
        cache_import_trusted_only_by_controller_marker: true,
      },
    };
    try {
      writeFileSync(evidencePath, JSON.stringify(result, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    } catch (error) {
      errorEvidence ??= summarizeFailure(error, 'evidence_write');
    }
    process.removeListener('SIGTERM', signalHandler);
    process.removeListener('SIGINT', signalHandler);
  }
  if (errorEvidence) {
    console.error('Runtime qualification fixture failed; protected storage and publication gates remain active.', JSON.stringify(errorEvidence));
    process.exitCode = 1;
  } else {
    console.log('Runtime qualification evidence recorded; application publication and reference enrollment were not attempted.');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    const code = /^[A-Z0-9_]{2,64}$/.test(String(error?.code ?? '')) ? error.code : 'QUALIFICATION_FAILED';
    console.error('Runtime qualification fixture failed before evidence completion.', JSON.stringify({ code }));
    process.exitCode = 1;
  });
}
