import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildReleaseConfig,
  buildTapBackendAuthConfig,
  RELEASE_AUTH_ENV,
  TAP_BACKEND_AUTH_ENV,
  TAP_BACKEND_SERVICE
} from './truenas-release-config.mjs';

const image = `ghcr.io/javadevjt/routelisttotesla@sha256:${'a'.repeat(64)}`;
const release = '20260927-tap-design';
const proxyNetwork = 'ix-nginx-proxy-manager_default';
const authEnvironment = {
  TAP_BASE_URL: 'https://tap.example.test/api/v1',
  TAP_CLIENT_KEY: 'test-client-key',
  TAP_CLIENT_ID: 'test-client-id',
  TAP_REDIRECT_URI: 'https://teslarouter.example.test/login/oauth2/code/tap'
};

function appConfig() {
  return {
    services: {
      router: {
        image: `ghcr.io/javadevjt/routelisttotesla@sha256:${'b'.repeat(64)}`,
        environment: {
          GOOGLE_API_KEY: 'preserve-geocoding-key',
          GOOGLE_OAUTH_CLIENT_ID: 'remove-old-client-id',
          GOOGLE_OAUTH_CLIENT_SECRET: 'remove-old-client-secret',
          ALLOWED_USER1: 'remove-old-static-allowlist',
          TAP_BASE_URL: 'https://tap.example.test/api/v1',
          TAP_CLIENT_KEY: 'old-client-key',
          TAP_OWNER_EMAIL: 'remove-old-owner',
          OPENAI_API_KEY: 'obsolete-test-only-value',
          OCR_TESSERACT_EXECUTABLE: 'tesseract',
          APP_RELEASE: '20260926-old-release',
          CUSTOM_SETTING: 'preserve-this'
        },
        ports: ['10088:10088'],
        mem_limit: '2g',
        volumes: ['route-data:/app/cache'],
        depends_on: { telemetry: { condition: 'service_started' } },
        restart: 'unless-stopped'
      }
    },
    volumes: { 'route-data': {} },
    networks: { existing: { external: true } }
  };
}

test('release hardens the router and joins only the existing NPM bridge', () => {
  const current = appConfig();
  const original = structuredClone(current);
  const { config, previousImage } = buildReleaseConfig(current, { image, release, authEnvironment, proxyNetwork });

  assert.equal(previousImage, original.services.router.image);
  assert.equal(config.services.router.image, image);
  assert.equal(Object.hasOwn(config.services.router, 'ports'), false);
  assert.equal(config.services.router.user, '10001:10001');
    assert.equal(config.services.router.mem_limit, '4g');
    assert.equal(config.services.router.cpus, 4);
  assert.equal(config.services.router.read_only, true);
  assert.deepEqual(config.services.router.tmpfs, ['/tmp:rw,nosuid,nodev,size=256m,mode=1777']);
  assert.deepEqual(config.services.router.cap_drop, ['ALL']);
  assert.deepEqual(config.services.router.security_opt, ['no-new-privileges:true']);
  assert.deepEqual(config.services.router.networks.default, {});
  assert.deepEqual(config.services.router.networks['npm-proxy'], { aliases: ['teslarouter'] });
  assert.deepEqual(config.networks['npm-proxy'], { external: true, name: proxyNetwork });
  assert.deepEqual(config.services.router.depends_on, {
    telemetry: { condition: 'service_started' },
    'cache-permissions': { condition: 'service_completed_successfully' }
  });
  assert.deepEqual(config.services['cache-permissions'], {
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
  });
  assert.equal(config.services.router.environment.APP_RELEASE, release);
  assert.equal(config.services.router.environment.OCR_TESSERACT_EXECUTABLE, '/app/ocr/run');
  assert.equal(config.services.router.environment.OCR_MODEL_MANIFEST, '/app/ocr/models.json');
  assert.equal(Object.hasOwn(config.services.router.environment, 'OPENAI_API_KEY'), false);
  for (const name of RELEASE_AUTH_ENV) assert.equal(config.services.router.environment[name], authEnvironment[name]);
  for (const name of ['GOOGLE_OAUTH_CLIENT_ID', 'GOOGLE_OAUTH_CLIENT_SECRET', 'ALLOWED_USER1', 'TAP_OWNER_EMAIL']) {
    assert.equal(Object.hasOwn(config.services.router.environment, name), false);
  }
  assert.equal(config.services.router.environment.GOOGLE_API_KEY, original.services.router.environment.GOOGLE_API_KEY);
  assert.equal(config.services.router.environment.CUSTOM_SETTING, original.services.router.environment.CUSTOM_SETTING);
  assert.equal(config.services.router.restart, original.services.router.restart);
  assert.deepEqual(config.services.router.volumes, original.services.router.volumes);
  assert.deepEqual(config.volumes, original.volumes);
  assert.deepEqual(config.networks.existing, original.networks.existing);
  assert.deepEqual(current, original, 'the retrieved config must not be mutated');

  const nextImage = `ghcr.io/javadevjt/routelisttotesla@sha256:${'e'.repeat(64)}`;
  const next = buildReleaseConfig(config, {
    image: nextImage,
    release: '20260926-follow-up',
    authEnvironment,
    proxyNetwork
  }).config;
  assert.equal(next.services.router.image, nextImage);
  assert.equal(next.services['cache-permissions'].image, nextImage);
  assert.deepEqual(next.services.router.networks['npm-proxy'], { aliases: ['teslarouter'] });
  assert.deepEqual(next.services.router.depends_on['cache-permissions'], {
    condition: 'service_completed_successfully'
  });
});

test('release rejects missing delegated-auth inputs and unexpected config shape', () => {
  const incomplete = { ...authEnvironment, TAP_CLIENT_ID: '' };
  assert.throws(
    () => buildReleaseConfig(appConfig(), { image, release, authEnvironment: incomplete, proxyNetwork }),
    /TAP_CLIENT_ID missing/
  );

  const malformed = appConfig();
  malformed.services.router.environment = ['GOOGLE_API_KEY=preserve'];
  assert.throws(
    () => buildReleaseConfig(malformed, { image, release, authEnvironment, proxyNetwork }),
    /Expected router environment map/
  );

  assert.throws(
    () => buildReleaseConfig(appConfig(), {
      image,
      release,
      authEnvironment,
      proxyNetwork,
      legacyEnvironment: { TAP_LEGACY_OWNER_EMAIL: 'binding-without-sub' }
    }),
    /Both TAP legacy owner binding values must be provided together/
  );

  assert.throws(
    () => buildReleaseConfig(appConfig(), { image, release, authEnvironment }),
    /Observed NPM bridge network required/
  );

  const conflict = appConfig();
  conflict.services['cache-permissions'] = { image };
  assert.throws(
    () => buildReleaseConfig(conflict, { image, release, authEnvironment, proxyNetwork }),
    /Unexpected existing cache-permissions service/
  );
});

test('TAP auth config changes only the two backend environment values', () => {
  const current = {
    services: {
      backend: {
        image: `ghcr.io/javadevjt/tapv2@sha256:${'c'.repeat(64)}`,
        environment: {
          SERVER_PORT: '8080',
          APP_ROUTELIST_REDIRECT_URI: 'https://old.example.test/callback',
          APP_ROUTELIST_SERVICE_CLIENT_ID: 'old-client-id'
        },
        volumes: ['tap-data:/data']
      },
      'tesla-command-proxy': {
        image: `ghcr.io/javadevjt/tapv2-command-proxy@sha256:${'d'.repeat(64)}`,
        environment: { PROXY_SETTING: 'preserve' },
        volumes: ['proxy-data:/data']
      }
    },
    volumes: { 'tap-data': {}, 'proxy-data': {} }
  };
  const original = structuredClone(current);
  const { config, previousImage } = buildTapBackendAuthConfig(current);

  assert.equal(previousImage, original.services.backend.image);
  assert.equal(config.services.backend.image, original.services.backend.image);
  assert.deepEqual(config.services.backend.environment, {
    ...original.services.backend.environment,
    ...TAP_BACKEND_AUTH_ENV
  });
  assert.deepEqual(config.services['tesla-command-proxy'], original.services['tesla-command-proxy']);
  assert.deepEqual(config.volumes, original.volumes);
  assert.deepEqual(current, original, 'the retrieved config must not be mutated');
  assert.equal(TAP_BACKEND_SERVICE, 'backend');
});
