const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const templatePath = path.resolve(__dirname, '../../main/resources/templates/index.html');
const template = fs.readFileSync(templatePath, 'utf8');

function extractBetween(startMarker, endMarker) {
    const start = template.indexOf(startMarker);
    const end = template.indexOf(endMarker, start);
    assert.notEqual(start, -1, 'template contains ' + startMarker);
    assert.notEqual(end, -1, 'template contains ' + endMarker);
    return template.slice(start, end);
}

const addressUiSource = [
    extractBetween('function createAddressItem(address, index) {', 'function formatNormalizedAddress('),
    extractBetween('function confirmOcrReading(index, row) {', 'function updateAddress('),
    extractBetween('function updateAddress(index, newText) {', 'function removeAddress(')
].join('\n');

test('upload errors show the server reason and retain the selected files for retry', async () => {
    const source = extractBetween('async function uploadImages(', '// File input handler');
    for (const problem of [{ error: 'Geocoding service is unavailable' }, null]) {
        const addresses = [];
        const context = vm.createContext({
            FormData: class { append() {} },
            extractedAddresses: addresses,
            apiFetch: async () => ({
                ok: false, status: 503, statusText: 'Service Unavailable',
                json: async () => { if (problem) return problem; throw new Error('HTML response'); }
            })
        });
        new vm.Script(source + '\nglobalThis.upload = uploadImages;').runInContext(context);
        const selected = [{ file: {}, name: 'route.png' }];
        await assert.rejects(context.upload(selected, 'MI'),
            { message: problem?.error || 'HTTP 503: Service Unavailable' });
        assert.equal(selected.length, 1);
        assert.equal(addresses.length, 0);
    }
});

test('each image uses its own sequential request and seams stay deduplicated', async () => {
    const addresses = [], sent = [];
    class FormData {
        constructor() { this.names = []; }
        append(name, value, filename) { if (name === 'images') this.names.push(filename); }
    }
    const rows = [
        [candidate({ text: '101 FIRST RD', normalized: '101 FIRST RD' }), candidate({ text: '202 SECOND RD', normalized: '202 SECOND RD' })],
        [candidate({ text: '202 SECOND RD', normalized: '202 SECOND RD' }), candidate({ text: '303 THIRD RD', normalized: '303 THIRD RD' })]
    ];
    const source = extractBetween('function appendImageCandidates(', '// File input handler');
    const context = vm.createContext({
        FormData, extractedAddresses: addresses,
        apiFetch: async (url, options) => {
            const index = sent.length;
            sent.push(options.body.names);
            return { ok: true, json: async () => ({ imageCandidates: [rows[index]] }) };
        }
    });
    new vm.Script(source + '\nglobalThis.upload = uploadImages;').runInContext(context);
    await context.upload([{ file: {}, name: 'first.heic' }, { file: {}, name: 'second.heic' }], 'MI');
    assert.deepEqual(sent, [['first.heic'], ['second.heic']]);
    assert.deepEqual(Array.from(addresses, row => row.text), ['101 FIRST RD', '202 SECOND RD', '303 THIRD RD']);
});

class FakeElement {
    constructor(tagName) {
        this.tagName = tagName.toLowerCase();
        this.children = [];
        this.parentElement = null;
        this.className = '';
        this.dataset = {};
        this.listeners = {};
        this.value = '';
        this.style = {};
        this._textContent = '';
        this.attributes = {};
        this.disabled = false;
        this.classList = {
            contains: name => this.className.split(/\s+/).includes(name),
            toggle: (name, force) => {
                const shouldAdd = force === undefined ? !this.classList.contains(name) : force;
                const classes = new Set(this.className.split(/\s+/).filter(Boolean));
                if (shouldAdd) classes.add(name);
                else classes.delete(name);
                this.className = [...classes].join(' ');
                return shouldAdd;
            }
        };
    }

    set textContent(value) {
        this._textContent = String(value ?? '');
        this.children.forEach(child => { child.parentElement = null; });
        this.children = [];
    }

    get textContent() {
        return this._textContent + this.children.map(child => child.textContent).join('');
    }

    append(...children) {
        children.forEach(child => this.appendChild(child));
    }

    appendChild(child) {
        child.parentElement = this;
        this.children.push(child);
        return child;
    }

    addEventListener(name, callback) {
        this.listeners[name] = callback;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    getAttribute(name) {
        return this.attributes[name] ?? null;
    }

    replaceChildren(...children) {
        this.children.forEach(child => { child.parentElement = null; });
        this.children = [];
        this.append(...children);
    }

    matches(selector) {
        if (selector.startsWith('.')) {
            return this.className.split(/\s+/).includes(selector.slice(1));
        }
        return this.tagName === selector.toLowerCase();
    }

    querySelector(selector) {
        for (const child of this.children) {
            if (child.matches(selector)) return child;
            const nested = child.querySelector(selector);
            if (nested) return nested;
        }
        return null;
    }

    querySelectorAll(selector) {
        return this.children.flatMap(child => [
            ...(child.matches(selector) ? [child] : []),
            ...child.querySelectorAll(selector)
        ]);
    }

    remove() {
        if (!this.parentElement) return;
        this.parentElement.children = this.parentElement.children.filter(child => child !== this);
        this.parentElement = null;
    }
}

const uploadUiSource = [
    extractBetween('let uploadBusy = false;', 'const MANUAL_COMMAND_HOLD_PREFIX'),
    extractBetween('const uploadForm = document.getElementById', '// SHA-256 of file bytes'),
    extractBetween('function renderSelectedFiles() {', 'function moveSelectedFile('),
    extractBetween('function moveSelectedFile(', 'function setUploadBusy('),
    extractBetween('function setUploadBusy(busy) {', 'function showImageProcessingStatus('),
    extractBetween('function showImageProcessingStatus(imageCount) {', 'async function uploadImages('),
    extractBetween('async function uploadImages(', '// File input handler'),
    extractBetween("uploadForm.addEventListener('submit', async function(e) {", 'function populateAddressList() {')
].join('\n');

function createUploadHarness(apiFetch) {
    const elements = Object.fromEntries([
        'uploadForm', 'processBtn', 'sendBtn', 'processStatus', 'sendStatus', 'images',
        'fileList', 'step1', 'step2', 'addressGroups', 'vin', 'defaultState'
    ].map(id => [id, new FakeElement(id === 'uploadForm' ? 'form' : 'div')]));
    elements.processBtn.textContent = 'Read addresses';
    elements.vin.tagName = 'select';
    elements.vin.value = 'VIN-TEST';
    elements.defaultState.tagName = 'select';
    elements.defaultState.value = 'MI';
    const filePicker = new FakeElement('label');
    filePicker.className = 'file-input';
    const statuses = [];
    const context = vm.createContext({
        FormData: class { append() {} },
        document: {
            getElementById: id => elements[id] || new FakeElement('div'),
            querySelector: selector => selector === '.file-input' ? filePicker : null,
            createElement: tagName => new FakeElement(tagName)
        },
        apiFetch,
        console: { error() {}, log() {} },
        showStatus: (message, type) => statuses.push({ message, type }),
        updateManualCommandHoldControls: () => {},
        populateAddressList: () => {},
        saveCurrentSession: () => {},
        setTimeout: () => 0
    });
    new vm.Script(uploadUiSource + `
        globalThis.configureUpload = files => {
            selectedFiles = files;
            availableVehicles = [{ vin: 'VIN-TEST' }];
            renderSelectedFiles();
        };
        globalThis.uploadUi = {
            submit: uploadForm.listeners.submit,
            setBusy: setUploadBusy,
            isBusy: () => uploadBusy,
            addresses: () => extractedAddresses
        };
    `).runInContext(context);
    return { context, elements, filePicker, statuses };
}

function loadUi(source, globals) {
    const context = vm.createContext(globals);
    new vm.Script(source + '\nglobalThis.ui = { createAddressItem, confirmOcrReading, updateAddress };')
        .runInContext(context);
    return context;
}

function candidate(overrides = {}) {
    return {
        text: '123 Main St, Ann Arbor MI',
        normalized: '123 MAIN ST ANN ARBOR MI',
        sourceImage: 'route.png',
        lineIndex: 0,
        lat: 42.28,
        lon: -83.74,
        pid: 'place-id',
        ocrAgreement: 2,
        ocrReviewRequired: true,
        ocrAlternatives: [
            'Tesseract CLI: 123 Main St, Ann Arbor MI',
            'Vision engine: 123 Main St, Ann Arbor MI'
        ],
        ...overrides
    };
}

test('upload controls lock during processing and restore after success or error', async () => {
    for (const outcome of ['success', 'cache error', 'OCR error']) {
        let finishCacheRequest;
        let failCacheRequest;
        let finishOcrRequest;
        let cacheRequestCount = 0;
        const cachedResponse = () => ({
            ok: true,
            json: async () => ({ cached: true, candidates: [{ text: '123 Main St' }] })
        });
        const harness = createUploadHarness(url => {
            if (url === '/route/cache/check') {
                cacheRequestCount++;
                if (cacheRequestCount > 1) return Promise.resolve(cachedResponse());
                return new Promise((resolve, reject) => {
                    finishCacheRequest = resolve;
                    failCacheRequest = reject;
                });
            }
            return new Promise(resolve => { finishOcrRequest = resolve; });
        });
        harness.context.configureUpload([
            { file: {}, hash: 'first-hash', name: 'first.png' },
            { file: {}, hash: 'second-hash', name: 'second.png' }
        ]);

        const pending = harness.context.uploadUi.submit({ preventDefault() {} });
        assert.equal(cacheRequestCount, 1);
        assert.equal(harness.context.uploadUi.isBusy(), true);
        assert.equal(harness.elements.uploadForm.getAttribute('aria-busy'), 'true');
        assert.equal(harness.elements.images.disabled, true);
        assert.equal(harness.elements.vin.disabled, true);
        assert.equal(harness.elements.defaultState.disabled, true);
        assert.equal(harness.filePicker.getAttribute('aria-disabled'), 'true');
        assert.equal(harness.filePicker.classList.contains('is-busy'), true);
        assert.equal(harness.elements.fileList.querySelectorAll('button').every(button => button.disabled), true);

        if (outcome === 'success') {
            finishCacheRequest(cachedResponse());
        } else if (outcome === 'cache error') {
            failCacheRequest(new Error('Cache unavailable'));
        } else {
            finishCacheRequest({ ok: true, json: async () => ({ cached: false }) });
            for (let attempt = 0; attempt < 20 && !finishOcrRequest; attempt++) {
                await new Promise(resolve => setImmediate(resolve));
            }
            assert.equal(typeof finishOcrRequest, 'function');
            assert.equal(harness.context.uploadUi.isBusy(), true);
            finishOcrRequest({
                ok: false,
                status: 503,
                statusText: 'Service Unavailable',
                json: async () => ({ error: 'OCR service unavailable' })
            });
        }
        await pending;

        assert.equal(harness.context.uploadUi.isBusy(), false);
        assert.equal(harness.elements.uploadForm.getAttribute('aria-busy'), 'false');
        assert.equal(harness.elements.images.disabled, false);
        assert.equal(harness.elements.vin.disabled, false);
        assert.equal(harness.elements.defaultState.disabled, false);
        assert.equal(harness.filePicker.getAttribute('aria-disabled'), 'false');
        assert.equal(harness.filePicker.classList.contains('is-busy'), false);
        const orderButtons = harness.elements.fileList.querySelectorAll('button');
        assert.deepEqual(orderButtons.map(button => button.disabled), [true, false, false, true]);
        assert.equal(harness.elements.processBtn.textContent, 'Read addresses');
        assert.equal(harness.elements.processBtn.disabled, false);
        assert.equal(harness.statuses.at(-1).type, outcome === 'success' ? 'success' : 'error');
    }
});

test('keeps a vehicle selector disabled when it was already disabled', async () => {
    let apiCalls = 0;
    const harness = createUploadHarness(async () => {
        apiCalls++;
        return { ok: true, json: async () => ({ cached: true, candidates: [] }) };
    });
    harness.context.configureUpload([{ file: {}, hash: 'hash', name: 'route.png' }]);
    harness.elements.vin.disabled = true;

    await harness.context.uploadUi.submit({ preventDefault() {} });
    assert.equal(apiCalls, 0);
    assert.equal(harness.elements.vin.disabled, true);
    assert.equal(harness.context.uploadUi.isBusy(), false);
    assert.match(harness.statuses.at(-1).message, /select a VIN/);

    harness.context.uploadUi.setBusy(true);
    harness.context.uploadUi.setBusy(false);
    assert.equal(harness.elements.vin.disabled, true);
});

test('ignores a second upload submission while the first cache check is pending', async () => {
    let finishRequest;
    let requestCount = 0;
    const harness = createUploadHarness(() => {
        requestCount++;
        return new Promise(resolve => { finishRequest = resolve; });
    });
    harness.context.configureUpload([{ file: {}, hash: 'hash', name: 'route.png' }]);

    const first = harness.context.uploadUi.submit({ preventDefault() {} });
    await harness.context.uploadUi.submit({ preventDefault() {} });
    assert.equal(requestCount, 1);
    assert.equal(harness.context.uploadUi.isBusy(), true);

    finishRequest({
        ok: true,
        json: async () => ({ cached: true, candidates: [{ text: '123 Main St' }] })
    });
    await first;
    assert.equal(harness.context.uploadUi.isBusy(), false);
    assert.equal(requestCount, 1);
});

test('shows OCR support and literal engine readings, then confirms the current reading', () => {
    const unsafeReading = '<img src=x onerror=alert(1)>';
    const address = candidate({
        ocrAlternatives: ['Tesseract CLI: 123 Main St', 'Vision engine: ' + unsafeReading]
    });
    const statuses = [];
    const context = loadUi(addressUiSource, {
        document: { createElement: tagName => new FakeElement(tagName) },
        extractedAddresses: [address],
        sendStatus: {},
        showStatus: (message, type) => statuses.push({ message, type })
    });

    const row = context.ui.createAddressItem(address, 0);
    assert.equal(row.querySelector('.ocr-agreement').textContent, 'OCR agreement: 2/3 engines');
    assert.equal(row.querySelector('.ocr-alternatives').children[1].textContent, 'Vision engine: ' + unsafeReading);
    assert.equal(row.querySelector('img'), null);

    const confirmButton = row.querySelector('.ocr-review').querySelector('button');
    assert.equal(confirmButton.dataset.action, 'confirmOcrReading');
    assert.equal(confirmButton.textContent, 'Confirm current reading');
    confirmButton.listeners.click();

    assert.equal(address.ocrReviewRequired, false);
    assert.equal(row.querySelector('.ocr-review'), null);
    assert.match(statuses[0].message, /confirmed/);
});

test('editing an unresolved row clears its review requirement; legacy rows show no score', () => {
    const address = candidate();
    const context = loadUi(addressUiSource, {
        document: { createElement: tagName => new FakeElement(tagName) },
        extractedAddresses: [address],
        sendStatus: {},
        showStatus: () => {}
    });

    const row = context.ui.createAddressItem(address, 0);
    const input = row.querySelector('.address-input');
    input.value = '123 corrected Main St, Ann Arbor MI';
    input.listeners.change();
    assert.equal(address.text, '123 corrected Main St, Ann Arbor MI');
    assert.equal(address.ocrReviewRequired, false);
    assert.equal(row.querySelector('.ocr-review'), null);

    const legacy = candidate({ ocrAgreement: 0, ocrReviewRequired: false, ocrAlternatives: [] });
    const legacyRow = context.ui.createAddressItem(legacy, 1);
    assert.equal(legacyRow.querySelector('.ocr-agreement'), null);
    assert.equal(legacyRow.querySelector('.ocr-review'), null);
});

test('pre-send validation stays retryable while uncertain outcomes remain held', async () => {
  const source = extractBetween('async function sendGroupToTesla(', 'function showStatus(');
  for (const [ok, status, body, held] of [
    [false, 400, { state: 'NOT_SENT', accepted: false, message: 'Stop 1 needs review.' }, false],
    [false, 503, { state: 'NOT_SENT', accepted: false, message: 'Geocoding unavailable' }, false],
    [false, 409, { state: 'NOT_SENT', accepted: false, message: 'Stop the active navigation session first' }, false],
    [false, 503, { error: 'TAP outcome unconfirmed' }, true],
    [false, 400, { error: 'Unclassified error' }, true],
    [true, 202, { state: 'PENDING', accepted: null }, true],
    [true, 200, null, true]
  ]) {
    const button = new FakeElement('button');
    button.textContent = 'Send Route 1';
    const holds = [], statuses = [];
    const context = vm.createContext({
      currentVin: 'VIN-TEST', availableVehicles: [{ vin: 'VIN-TEST', command: true }],
      extractedAddresses: [candidate({ ocrReviewRequired: false })], manualCommandInFlight: new Set(),
      getManualCommandHold: () => null, updateManualCommandHoldControls() {},
      persistManualCommandHold: (vin, hold) => holds.push(hold),
      apiFetch: async () => ({ ok, status, json: async () => { if (!body) throw new Error('Malformed response'); return body; } }),
      crypto: { randomUUID: () => 'fixed-idempotency-key' }, document: { querySelector: () => button },
      console: { error() {} }, showStatus: (message, type) => statuses.push({ message, type }), sendStatus: {}
    });
    new vm.Script(source + '\nglobalThis.send = sendGroupToTesla;').runInContext(context);
    await context.send(0, 0, 1);
    assert.equal(holds.length > 0, held);
    assert.equal(button.disabled, held);
    if (!held) {
      assert.match(statuses.at(-1).message, /Route not sent:/);
      assert.equal(button.textContent, 'Send Route 1');
    }
  }
});

test('clear-hold button removes the stored pause and permits a retry', () => {
  const source = extractBetween('function manualCommandHoldStorageKey(vin) {', 'function updateManualCommandHoldControls(');
  const stored = new Map(), statuses = [];
  const status = new FakeElement('div');
  status.classList.remove = () => {};
  let controlsUpdated = 0;
  const context = vm.createContext({
    MANUAL_COMMAND_HOLD_PREFIX: 'hold:', manualCommandHolds: new Map(), currentVin: 'VIN-TEST',
    sessionStorage: {
      getItem: key => stored.get(key) ?? null,
      setItem: (key, value) => stored.set(key, value),
      removeItem: key => stored.delete(key)
    },
    document: { createElement: tag => new FakeElement(tag), querySelectorAll: () => [status] },
    updateManualCommandHoldControls: () => { controlsUpdated++; },
    showStatus: message => statuses.push(message)
  });
  new vm.Script(source + '\nglobalThis.holds = { persistManualCommandHold, getManualCommandHold, showManualCommandHold };').runInContext(context);
  const hold = context.holds.persistManualCommandHold('VIN-TEST', { state: 'HTTP_400', idempotencyKey: 'old-key' });
  context.holds.showManualCommandHold('VIN-TEST', hold, status);
  assert.ok(context.holds.getManualCommandHold('VIN-TEST'));
  status.querySelector('button').listeners.click();
  assert.equal(context.holds.getManualCommandHold('VIN-TEST'), null);
  assert.equal(stored.size, 0);
  assert.equal(controlsUpdated, 1);
  assert.equal(status.dataset.manualCommandHold, undefined);
  assert.match(statuses.at(-1), /cleared/);
});

test('unlocated addresses explain the problem instead of showing zero coordinates', () => {
  const address = candidate({ lat: 0, lon: 0, pid: null });
  const context = loadUi(addressUiSource, {
    document: { createElement: tagName => new FakeElement(tagName) }, extractedAddresses: [address],
    sendStatus: {}, showStatus() {}
  });
  const row = context.ui.createAddressItem(address, 0);
  assert.match(row.querySelector('.address-meta').textContent, /Location not found/);
  assert.doesNotMatch(row.querySelector('.address-meta').textContent, /0\.0000/);
});

test('manual and automatic route sends stop before API dispatch when any route row needs review', async () => {
    const sendSource = extractBetween('async function sendGroupToTesla(', 'function showStatus(');
    const autoSource = extractBetween('async function startAutoNavigation(', 'function startAutoNavPolling(');
    const pending = candidate();
    let apiCalls = 0;
    const statuses = [];
    const globals = {
        currentVin: 'VIN-TEST',
        extractedAddresses: [pending],
        getManualCommandHold: () => null,
        manualCommandInFlight: new Set(),
        availableVehicles: [{ vin: 'VIN-TEST', command: true }],
        sendStatus: {},
        apiFetch: async () => { apiCalls += 1; throw new Error('unexpected API dispatch'); },
        showStatus: (message, type) => statuses.push({ message, type }),
        updateManualCommandHoldControls: () => {}
    };

    const manualContext = vm.createContext({ ...globals });
    new vm.Script(sendSource + '\nglobalThis.send = sendGroupToTesla;').runInContext(manualContext);
    await manualContext.send(0, 0, 1);
    assert.equal(apiCalls, 0);
    assert.match(statuses.at(-1).message, /unresolved OCR reading/);

    const autoContext = vm.createContext({ ...globals });
    new vm.Script(autoSource + '\nglobalThis.start = startAutoNavigation;').runInContext(autoContext);
    await autoContext.start();
    assert.equal(apiCalls, 0);
    assert.match(statuses.at(-1).message, /unresolved OCR reading/);
});

test('merges screenshot seams and contained captures while preserving meaningful repeats', () => {
    const source = extractBetween('function appendImageCandidates(', 'async function uploadImages(');
    const cases = [
        [[['101 First Rd', '202 Second Rd', '303 Third Rd'], ['202 Second Rd', '303 Third Rd', '404 Fourth Rd'], ['404 Fourth Rd', '505 Fifth Rd']],
            ['101 First Rd', '202 Second Rd', '303 Third Rd', '404 Fourth Rd', '505 Fifth Rd']],
        [[['101 First Rd', '202 Second Rd', '303 Third Rd', '404 Fourth Rd'], ['202 Second Rd', '303 Third Rd']],
            ['101 First Rd', '202 Second Rd', '303 Third Rd', '404 Fourth Rd']],
        [[['101 First Rd', '202 Second Rd', '101 First Rd'], ['101 First Rd', '303 Third Rd']],
            ['101 First Rd', '202 Second Rd', '101 First Rd', '303 Third Rd']],
        [[['120 Main St Apt 330'], ['120 Main St Apt 331']], ['120 Main St Apt 330', '120 Main St Apt 331']],
        [[['12 1/2 Main St'], ['12 Main St']], ['12 1/2 Main St', '12 Main St']],
        [[['12.5 Main St'], ['12 5 Main St']], ['12.5 Main St', '12 5 Main St']],
        [[['101 Main St'], ['101A Main St']], ['101 Main St', '101A Main St']],
        [[['120 MAIN ST, APT. 330 MI'], ['120 main st apt 330, mi']], ['120 MAIN ST, APT. 330 MI']],
        [[[''], ['']], ['', '']]
    ];
    for (const [images, expected] of cases) {
        const addresses = [];
        const context = vm.createContext({ extractedAddresses: addresses });
        new vm.Script(source + '\nglobalThis.append = appendImageCandidates;').runInContext(context);
        images.forEach((texts, image) => context.append(texts.map(text => ({
            text, normalized: text, sourceImage: `${image}.png`, pid: 'same-building',
            ocrAgreement: 3, ocrReviewRequired: false
        }))));
        assert.deepEqual(addresses.map(address => address.text), expected);
    }
});

test('cached captures followed by a multi-image upload retain each image boundary', async () => {
    const row = text => ({ text, normalized: text, pid: text, ocrAgreement: 3, ocrReviewRequired: false });
    const first = ['101 First Rd', '202 Second Rd', '303 Third Rd', '404 Fourth Rd'];
    const images = [['202 Second Rd', '303 Third Rd'], ['303 Third Rd', '404 Fourth Rd', '505 Fifth Rd']];
    const harness = createUploadHarness(async (url, options) => ({
        ok: true,
        json: async () => url === '/route/cache/check'
            ? { cached: JSON.parse(options.body).filename === 'first.png', candidates: first.map(row) }
            : { candidates: ['202 Second Rd', '303 Third Rd', '404 Fourth Rd', '505 Fifth Rd'].map(row),
                imageCandidates: images.map(image => image.map(row)) }
    }));
    harness.context.configureUpload(['first.png', 'image.png', 'image.png'].map((name, index) => ({
        file: {}, name, hash: String(index)
    })));
    await harness.context.uploadUi.submit({ preventDefault() {} });
    assert.deepEqual(Array.from(harness.context.uploadUi.addresses(), address => address.text),
        [...first, '505 Fifth Rd']);
    assert.equal(harness.statuses.at(-1).type, 'success');
});

test('an overlapping complete reading replaces the partial copy and retains its evidence', () => {
    const source = extractBetween('function appendImageCandidates(', 'async function uploadImages(');
    const addresses = [candidate({ ocrReviewRequired: true, sourceImage: 'cropped.png' })];
    const complete = candidate({ ocrReviewRequired: false, ocrAgreement: 3, sourceImage: 'complete.png' });
    const context = vm.createContext({ extractedAddresses: addresses });
    new vm.Script(source + '\nglobalThis.append = appendImageCandidates;').runInContext(context);
    context.append([complete]);
    assert.equal(addresses.length, 1);
    assert.equal(addresses[0], complete);
    assert.equal(addresses[0].ocrReviewRequired, false);
});

test('overlap removal spans cached and uploaded screenshot groups in selection order', async () => {
    const row = text => ({ text, normalized: text, ocrReviewRequired: false, ocrAgreement: 3 });
    const first = ['101 First Rd', '202 Second Rd', '303 Third Rd'];
    const fresh = ['202 Second Rd', '303 Third Rd', '404 Fourth Rd'];
    const last = ['404 Fourth Rd', '505 Fifth Rd'];
    const harness = createUploadHarness(async (url, options) => ({
        ok: true,
        json: async () => {
            if (url === '/route/cache/check') {
                const filename = JSON.parse(options.body).filename;
                return filename === 'new.png' ? { cached: false } : {
                    cached: true, candidates: (filename === 'first.png' ? first : last).map(row)
                };
            }
            return { candidates: fresh.map(row) };
        }
    }));
    harness.context.configureUpload(['first.png', 'new.png', 'last.png'].map(name => ({
        file: {}, name, hash: name
    })));
    await harness.context.uploadUi.submit({ preventDefault() {} });
    assert.deepEqual(Array.from(harness.context.uploadUi.addresses(), address => address.text),
        ['101 First Rd', '202 Second Rd', '303 Third Rd', '404 Fourth Rd', '505 Fifth Rd']);
    assert.equal(harness.statuses.at(-1).type, 'success');
});
