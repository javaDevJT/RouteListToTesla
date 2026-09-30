import assert from 'node:assert/strict';
import { isDeepStrictEqual } from 'node:util';

export const RELEASE_AUTH_ENV = Object.freeze([
  'TAP_BASE_URL',
  'TAP_CLIENT_KEY',
  'TAP_CLIENT_ID',
  'TAP_REDIRECT_URI'
]);

export const LEGACY_OWNER_ENV = Object.freeze([
  'TAP_LEGACY_OWNER_EMAIL',
  'TAP_LEGACY_OWNER_SUB'
]);

export const TAP_BACKEND_SERVICE = 'backend';
export const TAP_BACKEND_AUTH_ENV = Object.freeze({
  APP_ROUTELIST_REDIRECT_URI: 'https://teslarouter.javadevjt.tech/login/oauth2/code/tap',
  APP_ROUTELIST_SERVICE_CLIENT_ID: '825174ef-d0d5-41c8-bc82-a0b67235bec6'
});

const REMOVED_ENV = Object.freeze([
  'GOOGLE_OAUTH_CLIENT_ID',
  'GOOGLE_OAUTH_CLIENT_SECRET',
  'ALLOWED_USER1',
  'TAP_OWNER_EMAIL',
  'OPENAI_API_KEY',
  'SPRING_AI_OPENAI_API_KEY'
]);
const OCR_ENV = Object.freeze({
  OCR_TESSERACT_EXECUTABLE: '/app/ocr/run',
  OCR_MODEL_MANIFEST: '/app/ocr/models.json',
  OCR_LANGUAGE: 'eng',
  OMP_THREAD_LIMIT: '2',
  OMP_NUM_THREADS: '2',
  OPENBLAS_NUM_THREADS: '2',
  MKL_NUM_THREADS: '2'
});
const CACHE_PERMISSION_SERVICE = 'cache-permissions';
const CACHE_PERMISSION_DEPENDENCY = Object.freeze({ condition: 'service_completed_successfully' });

function cachePermissionsService(image) {
  return {
    image,
    user: '0:0',
    network_mode: 'none',
    read_only: true,
    cap_drop: ['ALL'],
    cap_add: ['CHOWN', 'FOWNER', 'DAC_OVERRIDE'],
    security_opt: ['no-new-privileges:true'],
    entrypoint: ['find'],
    command: ['-P', '/app/cache', '-xdev', '-exec', 'chown', '-h', '10001:10001', '{}', '+'],
    volumes: ['route-data:/app/cache'],
    restart: 'no'
  };
}

export function buildReleaseConfig(currentConfig, { image, release, authEnvironment, legacyEnvironment = {}, proxyNetwork }) {
  assert.match(image, /^ghcr\.io\/javadevjt\/routelisttotesla@sha256:[a-f0-9]{64}$/,
    'Expected immutable RouteList image');
  assert.match(release, /^2026[0-9]{4}-[a-z0-9-]+$/, 'Release identifier required');
  assert(currentConfig && typeof currentConfig === 'object', 'Retrieved app config required');

  const currentRouter = currentConfig.services?.router;
  assert(currentRouter && typeof currentRouter === 'object', 'Expected router service configuration');
  assert.match(proxyNetwork, /^[a-z0-9][a-z0-9_.-]*$/i, 'Observed NPM bridge network required');
  const currentEnvironment = currentRouter.environment;
  assert(currentEnvironment && typeof currentEnvironment === 'object' && !Array.isArray(currentEnvironment),
    'Expected router environment map');
  const currentNetworks = currentConfig.networks ?? {};
  assert(currentNetworks && typeof currentNetworks === 'object' && !Array.isArray(currentNetworks),
    'Expected Compose network map');
  const currentRouterNetworks = currentRouter.networks ?? {};
  assert(currentRouterNetworks && typeof currentRouterNetworks === 'object' && !Array.isArray(currentRouterNetworks),
    'Expected router network map');
  const expectedProxyNetwork = { external: true, name: proxyNetwork };
  const expectedRouterProxyNetwork = { aliases: ['teslarouter'] };
  if (Object.hasOwn(currentNetworks, 'npm-proxy')) {
    assert.deepEqual(currentNetworks['npm-proxy'], expectedProxyNetwork, 'Unexpected npm-proxy network entry');
  }
  if (Object.hasOwn(currentRouterNetworks, 'npm-proxy')) {
    assert.deepEqual(currentRouterNetworks['npm-proxy'], expectedRouterProxyNetwork,
      'Unexpected router npm-proxy network entry');
  }
  const currentDependencies = currentRouter.depends_on ?? {};
  assert(currentDependencies && typeof currentDependencies === 'object' && !Array.isArray(currentDependencies),
    'Expected router dependency map');
  const existingPermissionService = currentConfig.services[CACHE_PERMISSION_SERVICE];
  if (Object.hasOwn(currentConfig.services, CACHE_PERMISSION_SERVICE)) {
    assert.deepEqual(existingPermissionService, cachePermissionsService(currentRouter.image),
      `Unexpected existing ${CACHE_PERMISSION_SERVICE} service`);
    assert.deepEqual(currentDependencies[CACHE_PERMISSION_SERVICE], CACHE_PERMISSION_DEPENDENCY,
      `Unexpected ${CACHE_PERMISSION_SERVICE} dependency`);
  } else {
    assert(!Object.hasOwn(currentDependencies, CACHE_PERMISSION_SERVICE),
      `Unexpected ${CACHE_PERMISSION_SERVICE} dependency`);
  }
  for (const name of RELEASE_AUTH_ENV) {
    assert(typeof authEnvironment?.[name] === 'string' && authEnvironment[name].trim(),
      `${name} missing from delegated-auth configuration`);
  }
  const hasLegacyEmail = Object.hasOwn(legacyEnvironment, LEGACY_OWNER_ENV[0]);
  const hasLegacySub = Object.hasOwn(legacyEnvironment, LEGACY_OWNER_ENV[1]);
  assert.equal(hasLegacyEmail, hasLegacySub, 'Both TAP legacy owner binding values must be provided together');
  if (hasLegacyEmail) {
    for (const name of LEGACY_OWNER_ENV) {
      assert(typeof legacyEnvironment[name] === 'string' && legacyEnvironment[name].trim(),
        `${name} missing from delegated-auth configuration`);
    }
  }

  const config = structuredClone(currentConfig);
  config.services.router.image = image;
  delete config.services.router.ports;
  config.services.router.user = '10001:10001';
  config.services.router.mem_limit = '8g';
  config.services.router.cpus = 8;
  config.services.router.read_only = true;
  config.services.router.tmpfs = ['/tmp:rw,nosuid,nodev,size=256m,mode=1777'];
  config.services.router.cap_drop = ['ALL'];
  config.services.router.security_opt = ['no-new-privileges:true'];
  config.services.router.networks = {
    ...currentRouterNetworks,
    default: currentRouterNetworks.default ?? {},
    'npm-proxy': { aliases: ['teslarouter'] }
  };
  config.networks = {
    ...currentNetworks,
    default: currentNetworks.default ?? {},
    'npm-proxy': { external: true, name: proxyNetwork }
  };
  config.services[CACHE_PERMISSION_SERVICE] = cachePermissionsService(image);
  config.services.router.depends_on = {
    ...currentDependencies,
    [CACHE_PERMISSION_SERVICE]: CACHE_PERMISSION_DEPENDENCY
  };
  for (const name of REMOVED_ENV) delete config.services.router.environment[name];
  for (const name of RELEASE_AUTH_ENV) {
    config.services.router.environment[name] = authEnvironment[name].trim();
  }
  if (hasLegacyEmail) {
    for (const name of LEGACY_OWNER_ENV) config.services.router.environment[name] = legacyEnvironment[name].trim();
  }
  config.services.router.environment.APP_RELEASE = release;
  Object.assign(config.services.router.environment, OCR_ENV);

  const comparison = structuredClone(config);
  comparison.services.router.image = currentRouter.image;
    for (const name of ['ports', 'user', 'mem_limit', 'cpus', 'read_only', 'tmpfs', 'cap_drop', 'security_opt', 'networks', 'depends_on']) {
    if (Object.hasOwn(currentRouter, name)) comparison.services.router[name] = currentRouter[name];
    else delete comparison.services.router[name];
  }
  if (Object.hasOwn(currentConfig.services, CACHE_PERMISSION_SERVICE)) {
    comparison.services[CACHE_PERMISSION_SERVICE] = existingPermissionService;
  } else delete comparison.services[CACHE_PERMISSION_SERVICE];
  for (const name of ['default', 'npm-proxy']) {
    if (Object.hasOwn(currentNetworks, name)) comparison.networks[name] = currentNetworks[name];
    else delete comparison.networks[name];
  }
  if (!Object.hasOwn(currentConfig, 'networks')) delete comparison.networks;
  const comparisonEnvironment = comparison.services.router.environment;
  const permittedEnvironment = new Set([...REMOVED_ENV, ...RELEASE_AUTH_ENV, ...LEGACY_OWNER_ENV, ...Object.keys(OCR_ENV), 'APP_RELEASE']);
  for (const name of permittedEnvironment) {
    if (Object.hasOwn(currentEnvironment, name)) comparisonEnvironment[name] = currentEnvironment[name];
    else delete comparisonEnvironment[name];
  }
  assert(isDeepStrictEqual(comparison, currentConfig),
    'Release config changed a field outside router image and delegated-auth/release environment');

  return { config, previousImage: currentRouter.image };
}

export function buildTapBackendAuthConfig(currentConfig) {
  const currentBackend = currentConfig?.services?.[TAP_BACKEND_SERVICE];
  assert(currentBackend && typeof currentBackend === 'object',
    `Expected ${TAP_BACKEND_SERVICE} service configuration`);
  const currentEnvironment = currentBackend.environment;
  assert(currentEnvironment && typeof currentEnvironment === 'object' && !Array.isArray(currentEnvironment),
    `Expected ${TAP_BACKEND_SERVICE} environment map`);
  assert(typeof currentBackend.image === 'string' && currentBackend.image,
    `Expected existing ${TAP_BACKEND_SERVICE} image`);

  const config = structuredClone(currentConfig);
  Object.assign(config.services[TAP_BACKEND_SERVICE].environment, TAP_BACKEND_AUTH_ENV);

  const comparison = structuredClone(config);
  const comparisonEnvironment = comparison.services[TAP_BACKEND_SERVICE].environment;
  for (const name of Object.keys(TAP_BACKEND_AUTH_ENV)) {
    if (Object.hasOwn(currentEnvironment, name)) comparisonEnvironment[name] = currentEnvironment[name];
    else delete comparisonEnvironment[name];
  }
  assert(isDeepStrictEqual(comparison, currentConfig),
    `TAP auth update changed a field outside ${TAP_BACKEND_SERVICE} delegated-auth environment`);

  return { config, previousImage: currentBackend.image };
}
