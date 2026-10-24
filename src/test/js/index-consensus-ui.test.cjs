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

const locationCoreSource = [
    extractBetween('const locationResolutionStates = new WeakMap();', 'const MANUAL_COMMAND_HOLD_PREFIX'),
    extractBetween('function isLocated(address) {', 'function updateLocationResolutionNotice() {')
].join('\n');

const addressUiSource = [
    extractBetween('const locationResolutionStates = new WeakMap();', 'const MANUAL_COMMAND_HOLD_PREFIX'),
    extractBetween('function isLocated(address) {', 'function populateAddressList() {'),
    extractBetween('function createAddressItem(address, index) {', 'function formatNormalizedAddress('),
    extractBetween('function confirmOcrReading(index, row) {', 'function updateAddress('),
    extractBetween('function updateAddress(index, newText) {', 'function removeAddress(')
].join('\n');

const locationUiSource = [
    extractBetween('const locationResolutionStates = new WeakMap();', 'const MANUAL_COMMAND_HOLD_PREFIX'),
    extractBetween('function isLocated(address) {', 'function populateAddressList() {'),
    extractBetween('function updateAddress(index, newText) {', 'function removeAddress(')
].join('\n');

test('upload errors show the server reason and retain the selected files for retry', async () => {
    const source = extractBetween('const IMAGE_UPLOAD_CONCURRENCY = 2;', 'async function uploadImages(')
        + extractBetween('async function uploadImages(', '// File input handler');
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

test('image uploads stay at two in flight and apply out-of-order responses in selection order', async () => {
    const addresses = [], sent = [];
    class FormData {
        constructor() { this.names = []; }
        append(name, value, filename) { if (name === 'images') this.names.push(filename); }
    }
    const deferred = new Map();
    const source = extractBetween('function appendImageCandidates(', '// File input handler');
    const context = vm.createContext({
        FormData, extractedAddresses: addresses,
        apiFetch: (url, options) => {
            const name = options.body.names[0];
            sent.push(name);
            return new Promise(resolve => deferred.set(name, resolve));
        }
    });
    new vm.Script(source + '\nglobalThis.upload = uploadImages;').runInContext(context);
    const files = ['first.heic', 'second.heic', 'third.heic'].map(name => ({ file: {}, name }));
    const pending = context.upload(files, 'MI');
    for (let attempt = 0; attempt < 20 && sent.length < 2; attempt++) await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(sent, ['first.heic', 'second.heic']);

    const response = texts => ({ ok: true, json: async () => ({ imageCandidates: [texts.map(text => candidate({ text, normalized: text }))] }) });
    deferred.get('second.heic')(response(['202 SECOND RD', '303 THIRD RD']));
    for (let attempt = 0; attempt < 20 && sent.length < 3; attempt++) await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(sent, ['first.heic', 'second.heic', 'third.heic']);
    deferred.get('third.heic')(response(['404 FOURTH RD']));
    deferred.get('first.heic')(response(['101 FIRST RD', '202 SECOND RD']));
    await pending;
    assert.deepEqual(Array.from(addresses, row => row.text), [
        '101 FIRST RD', '202 SECOND RD', '303 THIRD RD', '404 FOURTH RD'
    ]);
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
        this.selectionStart = 0;
        this.selectionEnd = 0;
        this.style = {};
        this._textContent = '';
        this.attributes = {};
        this.disabled = false;
        this.classList = {
            contains: name => this.className.split(/\s+/).includes(name),
            add: (...names) => {
                const classes = new Set(this.className.split(/\s+/).filter(Boolean));
                names.forEach(name => classes.add(name));
                this.className = [...classes].join(' ');
            },
            remove: (...names) => {
                const classes = new Set(this.className.split(/\s+/).filter(Boolean));
                names.forEach(name => classes.delete(name));
                this.className = [...classes].join(' ');
            },
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
    extractBetween('function isLocated(address) {', 'function populateAddressList() {'),
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
        FormData: class {
            constructor() { this.entries = []; }
            append(...args) { this.entries.push(args); }
        },
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
            addresses: () => extractedAddresses,
            selectedFiles: () => selectedFiles
        };
    `).runInContext(context);
    return { context, elements, filePicker, statuses };
}

function loadUi(source, globals) {
    const baseDocument = {
        createElement: tagName => new FakeElement(tagName),
        getElementById: () => new FakeElement('div')
    };
    const context = vm.createContext({
        ...globals,
        document: { ...baseDocument, ...(globals.document || {}) },
        populateAddressList: globals.populateAddressList || (() => {})
    });
    new vm.Script(source + `
        globalThis.ui = {
            createAddressItem: typeof createAddressItem === 'function' ? createAddressItem : null,
            isLocated: typeof isLocated === 'function' ? isLocated : null,
            hasCurrentLocationResolution: typeof hasCurrentLocationResolution === 'function' ? hasCurrentLocationResolution : null,
            confirmOcrReading: typeof confirmOcrReading === 'function' ? confirmOcrReading : null,
            updateAddress: typeof updateAddress === 'function' ? updateAddress : null,
            resolveLocationCandidates: typeof resolveLocationCandidates === 'function' ? resolveLocationCandidates : null,
            getLocationResolutionState: typeof getLocationResolutionState === 'function' ? getLocationResolutionState : null,
            setLocationResolutionState: typeof setLocationResolutionState === 'function' ? setLocationResolutionState : null,
            updateLocationResolutionNotice: typeof updateLocationResolutionNotice === 'function' ? updateLocationResolutionNotice : null,
            refreshLocationPresentation: typeof refreshLocationPresentation === 'function' ? refreshLocationPresentation : null,
            showLocationBlockedSendStatus: typeof showLocationBlockedSendStatus === 'function' ? showLocationBlockedSendStatus : null,
            clearResolvedLocationBlockedSendError: typeof clearResolvedLocationBlockedSendError === 'function' ? clearResolvedLocationBlockedSendError : null
        };`)
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
        resolution: {
            status: 'VERIFIED_UNIT', strategy: 'original', reasonCode: 'street_match', attempts: 1,
            policyVersion: 'street-address-v12', retryAfterSeconds: null
        },
        ...overrides
    };
}

function unlocatedCandidate(overrides = {}) {
    return candidate({ lat: 0, lon: 0, pid: null, ocrReviewRequired: false, resolution: null, ...overrides });
}

function resolvedCandidate(address, placeId = 'resolved-place') {
    return {
        text: address.text, lat: 42.31, lon: -83.81, pid: placeId,
        resolution: {
            status: 'VERIFIED_UNIT', strategy: 'original', reasonCode: 'street_match', attempts: 1,
            policyVersion: 'street-address-v12', retryAfterSeconds: null
        }
    };
}

function createLocationHarness(addresses, apiFetch, overrides = {}) {
    const notice = new FakeElement('div');
    const document = {
        createElement: tagName => new FakeElement(tagName),
        getElementById: id => id === 'locationResolutionNotice' ? notice : new FakeElement('div'),
        activeElement: null,
        ...(overrides.document || {})
    };
    const context = loadUi(addressUiSource, {
        ...overrides,
        document,
        extractedAddresses: addresses,
        currentVin: overrides.currentVin || '',
        apiFetch,
        sendStatus: overrides.sendStatus || {},
        showStatus: overrides.showStatus || (() => {}),
        hideStatus: overrides.hideStatus || (() => {}),
        populateAddressList: overrides.populateAddressList || (() => {}),
        updateManualCommandHoldControls: overrides.updateManualCommandHoldControls || (() => {})
    });
    return { context, notice };
}

test('upload controls lock during processing and restore after success or error', async () => {
    for (const outcome of ['success', 'cache error', 'OCR error']) {
        let finishCacheRequest;
        let failCacheRequest;
        let finishOcrRequest;
        let cacheRequestCount = 0;
        const cachedResponse = () => ({
            ok: true,
            json: async () => ({ cached: true, candidates: [candidate({ text: '123 Main St' })] })
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

test('failed upload waits for active requests, launches no later file, and reuses successful cache results on retry', async () => {
    let attempt = 0;
    const initialUploads = [];
    const uploadedNames = [];
    const harness = createUploadHarness((url, options) => {
        if (url === '/route/cache/check') {
            const file = JSON.parse(options.body);
            if (attempt === 0 || file.filename !== 'first.png') {
                return Promise.resolve({ ok: true, json: async () => ({ cached: false }) });
            }
            return Promise.resolve({ ok: true, json: async () => ({
                cached: true,
                candidates: [candidate({ text: '101 Cached First Rd', normalized: '101 CACHED FIRST RD' })]
            }) });
        }

        const image = options.body.entries.find(entry => entry[0] === 'images');
        const name = image[2];
        uploadedNames.push(`${attempt}:${name}`);
        if (attempt === 0) {
            return new Promise(resolve => initialUploads.push({ name, resolve }));
        }
        return Promise.resolve({ ok: true, json: async () => ({
            imageCandidates: [[candidate({
                text: name === 'second.png' ? '202 Second Rd' : '303 Third Rd',
                normalized: name === 'second.png' ? '202 SECOND RD' : '303 THIRD RD'
            })]]
        }) });
    });
    const files = ['first.png', 'second.png', 'third.png'].map((name, index) => ({ file: {}, hash: `hash-${index}`, name }));
    harness.context.configureUpload(files);

    const firstAttempt = harness.context.uploadUi.submit({ preventDefault() {} });
    for (let spin = 0; spin < 30 && initialUploads.length < 2; spin++) await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(initialUploads.map(request => request.name), ['first.png', 'second.png']);
    assert.equal(harness.context.uploadUi.isBusy(), true);

    initialUploads[1].resolve({
        ok: false, status: 503, statusText: 'Unavailable',
        json: async () => ({ error: 'Second image failed' })
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(uploadedNames.length, 2);
    assert.equal(harness.context.uploadUi.isBusy(), true);

    initialUploads[0].resolve({
        ok: true, json: async () => ({ imageCandidates: [[candidate({ text: '101 Cached First Rd', normalized: '101 CACHED FIRST RD' })]] })
    });
    await firstAttempt;
    assert.equal(harness.context.uploadUi.isBusy(), false);
    assert.equal(harness.context.uploadUi.selectedFiles().length, 3);
    assert.equal(harness.context.uploadUi.addresses().length, 0);
    assert.deepEqual(uploadedNames, ['0:first.png', '0:second.png']);

    attempt = 1;
    await harness.context.uploadUi.submit({ preventDefault() {} });
    assert.deepEqual(uploadedNames, ['0:first.png', '0:second.png', '1:second.png', '1:third.png']);
    assert.deepEqual(Array.from(harness.context.uploadUi.addresses(), address => address.text), [
        '101 Cached First Rd', '202 Second Rd', '303 Third Rd'
    ]);
    assert.equal(harness.context.uploadUi.isBusy(), false);
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
        json: async () => ({ cached: true, candidates: [candidate({ text: '123 Main St' })] })
    });
    await first;
    assert.equal(harness.context.uploadUi.isBusy(), false);
    assert.equal(requestCount, 1);
});

test('new-policy upload shows screenshot and lookup progress without an immediate duplicate resolve pass', async () => {
    let finishUpload;
    let resolveCalls = 0;
    const address = candidate({ text: '120 Main St Apt 104, Ann Arbor MI' });
    const harness = createUploadHarness(url => {
        if (url === '/route/cache/check') return Promise.resolve({ ok: true, json: async () => ({ cached: false }) });
        if (url === '/route/places') return new Promise(resolve => { finishUpload = resolve; });
        if (url === '/route/resolve') resolveCalls++;
        throw new Error(`Unexpected request: ${url}`);
    });
    harness.context.configureUpload([{ file: {}, hash: 'fresh-hash', name: 'route.png' }]);

    const pending = harness.context.uploadUi.submit({ preventDefault() {} });
    for (let spin = 0; spin < 20 && !finishUpload; spin++) await new Promise(resolve => setImmediate(resolve));
    assert.equal(typeof finishUpload, 'function');
    const progress = harness.statuses.find(status => status.type === 'loading' && /Reading screenshots/.test(status.message));
    assert.ok(progress);
    assert.match(progress.message, /street addresses and tries safe alternatives/);

    finishUpload({ ok: true, json: async () => ({ candidates: [address], imageCandidates: [[address]] }) });
    await pending;
    assert.equal(resolveCalls, 0);
    assert.match(harness.statuses.at(-1).message, /1 of 1 stops verified/);
});

test('terminal upload outcomes do not trigger an automatic second lookup', async () => {
    for (const status of ['NO_MATCH', 'DEFERRED', 'LOOKUP_UNAVAILABLE']) {
        let resolveCalls = 0;
        const outcome = unlocatedCandidate({
            text: `120 Main St Apt 104, Ann Arbor MI ${status}`,
            resolution: {
                status, strategy: 'bounded_recovery', reasonCode: status.toLowerCase(), attempts: 8,
                policyVersion: 'street-address-v12', retryAfterSeconds: status === 'DEFERRED' ? 30 : null
            }
        });
        const harness = createUploadHarness(async url => {
            if (url === '/route/cache/check') return { ok: true, json: async () => ({ cached: true, candidates: [outcome] }) };
            if (url === '/route/resolve') resolveCalls++;
            throw new Error(`Unexpected request: ${url}`);
        });
        harness.context.configureUpload([{ file: {}, hash: `${status}-hash`, name: 'route.png' }]);
        await harness.context.uploadUi.submit({ preventDefault() {} });
        assert.equal(resolveCalls, 0, `${status} must remain terminal until manual retry`);
        assert.match(harness.statuses.at(-1).message, /0 of 1 stops verified/);
    }
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
    assert.equal(row.querySelector('.ocr-review-label').textContent,
        'Compare the engine readings and confirm or edit this address.');
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

test('editing an unresolved row clears its review requirement; legacy rows show no score', async () => {
    const address = candidate();
    const editedText = '123 corrected Main St, Ann Arbor MI';
    const harness = createLocationHarness([address], async (url, options) => {
        assert.equal(url, '/route/resolve');
        assert.equal(JSON.parse(options.body).candidates[0].text, editedText);
        return { ok: true, json: async () => ({ candidates: [resolvedCandidate({ text: editedText })] }) };
    });
    const context = harness.context;

    const row = context.ui.createAddressItem(address, 0);
    const input = row.querySelector('.address-input');
    input.value = editedText;
    await input.listeners.change();
    assert.equal(address.text, editedText);
    assert.equal(address.ocrReviewRequired, false);
    assert.equal(row.querySelector('.ocr-review'), null);

    const legacy = candidate({ ocrAgreement: 0, ocrReviewRequired: false, ocrAlternatives: [] });
    const legacyRow = context.ui.createAddressItem(legacy, 1);
    assert.equal(legacyRow.querySelector('.ocr-agreement'), null);
    assert.equal(legacyRow.querySelector('.ocr-review'), null);
});

test('location preflight skips verified rows and merges only the matching fresh location', async () => {
    const located = candidate({ ocrReviewRequired: false });
    const unresolved = unlocatedCandidate({ text: '120 Main St, Ann Arbor MI', ocrReviewRequired: true });
    let requestBody;
    const harness = createLocationHarness([located, unresolved], async (url, options) => {
        assert.equal(url, '/route/resolve');
        requestBody = JSON.parse(options.body);
        return { ok: true, json: async () => ({ candidates: [resolvedCandidate(unresolved)] }) };
    });

    const changed = await harness.context.ui.resolveLocationCandidates([located, unresolved]);
    assert.equal(changed, true);
    assert.equal(requestBody.candidates.length, 1);
    assert.equal(requestBody.candidates[0].text, unresolved.text);
    assert.equal(unresolved.pid, 'resolved-place');
    assert.equal(unresolved.ocrReviewRequired, true);
    assert.equal(harness.context.ui.getLocationResolutionState(unresolved).status, 'located');
    assert.equal(harness.notice.className, 'status success');
    assert.match(harness.notice.textContent, /2 of 2 stops verified/);
});

test('legacy coordinates are cleared and remain blocked until a current policy outcome arrives', async () => {
    const legacy = candidate({ text: '120 Main St Apt 104, Ann Arbor MI', resolution: null });
    let finishLookup;
    const harness = createLocationHarness([legacy], () => new Promise(resolve => { finishLookup = resolve; }));
    const row = harness.context.ui.createAddressItem(legacy, 0);
    assert.equal(harness.context.ui.isLocated(legacy), false);
    assert.match(row.querySelector('.location-status').textContent, /No current street-location verification/);

    const lookup = harness.context.ui.resolveLocationCandidates([legacy]);
    assert.equal(legacy.lat, 0);
    assert.equal(legacy.lon, 0);
    assert.equal(legacy.pid, null);
    assert.equal(row.querySelector('.location-retry-button').disabled, true);
    finishLookup({ ok: true, json: async () => ({ candidates: [{ ...resolvedCandidate(legacy), resolution: null }] }) });
    await lookup;

    assert.equal(harness.context.ui.isLocated(legacy), false);
    assert.equal(row.querySelector('.location-retry-button').disabled, false);
    assert.match(row.querySelector('.location-status').textContent, /No current street-location verification/);
    assert.match(harness.notice.textContent, /0 of 1 stops verified/);
});

test('another row resolving preserves a focused textarea draft, caret, and active element', async () => {
    const resolving = unlocatedCandidate({ text: '101 First Rd' });
    const editing = unlocatedCandidate({ text: '120 Main St, Ann Arbor MI' });
    let finishLookup;
    let listRebuilds = 0;
    const harness = createLocationHarness([resolving, editing], () => new Promise(resolve => { finishLookup = resolve; }), {
        populateAddressList: () => { listRebuilds++; }
    });
    const resolvingRow = harness.context.ui.createAddressItem(resolving, 0);
    const editingRow = harness.context.ui.createAddressItem(editing, 1);
    const input = editingRow.querySelector('.address-input');
    input.value = '120 Main St, Ann Arbor MI, Apt 331';
    input.selectionStart = 9;
    input.selectionEnd = 24;
    harness.context.document.activeElement = input;

    const lookup = harness.context.ui.resolveLocationCandidates([resolving]);
    assert.equal(resolvingRow.querySelector('.location-status').textContent.includes('Checking street address'), true);
    assert.equal(resolvingRow.querySelector('.location-retry-button').disabled, true);
    assert.equal(harness.context.document.activeElement, input);
    assert.equal(input.value, '120 Main St, Ann Arbor MI, Apt 331');
    assert.equal(input.selectionStart, 9);
    assert.equal(input.selectionEnd, 24);
    assert.equal(listRebuilds, 0);

    finishLookup({ ok: true, json: async () => ({ candidates: [resolvedCandidate(resolving)] }) });
    await lookup;
    assert.equal(resolvingRow.querySelector('.location-status').textContent.includes('verified'), true);
    assert.equal(resolvingRow.querySelector('.location-retry-button'), null);
    assert.equal(harness.context.document.activeElement, input);
    assert.equal(input.value, '120 Main St, Ann Arbor MI, Apt 331');
    assert.equal(input.selectionStart, 9);
    assert.equal(input.selectionEnd, 24);
    assert.equal(listRebuilds, 0);
});

test('failed location lookups can be retried from their row without creating a command hold', async () => {
    const unresolved = unlocatedCandidate({ text: '120 Main St Apt 330, Ann Arbor MI' });
    let attempts = 0;
    let holdWrites = 0;
    const harness = createLocationHarness([unresolved], async () => {
        attempts++;
        if (attempts === 1) {
            return { ok: false, status: 503, statusText: 'Unavailable', json: async () => ({ error: 'Geocoding unavailable' }) };
        }
        return { ok: true, json: async () => ({ candidates: [resolvedCandidate(unresolved, 'retry-place')] }) };
    }, { persistManualCommandHold: () => { holdWrites++; } });

    await harness.context.ui.resolveLocationCandidates([unresolved]);
    assert.equal(harness.context.ui.getLocationResolutionState(unresolved).status, 'unavailable');
    assert.equal(harness.notice.className, 'status error');

    const row = harness.context.ui.createAddressItem(unresolved, 0);
    const retry = row.querySelector('.location-retry-button');
    assert.equal(retry.disabled, false);
    assert.equal(retry.textContent, 'Retry location lookup');
    retry.listeners.click();
    assert.equal(harness.context.ui.getLocationResolutionState(unresolved).status, 'pending');
    const pendingRow = harness.context.ui.createAddressItem(unresolved, 0);
    assert.equal(pendingRow.querySelector('.location-retry-button').disabled, true);
    await new Promise(resolve => setImmediate(resolve));

    assert.equal(attempts, 2);
    assert.equal(harness.context.ui.getLocationResolutionState(unresolved).status, 'located');
    assert.equal(unresolved.pid, 'retry-place');
    assert.equal(holdWrites, 0);
    assert.match(pendingRow.querySelector('.location-status').textContent, /Apartment or suite verified/);
    assert.equal(pendingRow.querySelector('.location-status').className.includes('success'), true);
    assert.equal(pendingRow.querySelector('.location-status').getAttribute('aria-live'), 'polite');
});

test('every terminal location outcome stays visible and only verified unit/building rows remove retry', () => {
    const statuses = ['VERIFIED_UNIT', 'VERIFIED_BUILDING', 'NO_MATCH', 'AMBIGUOUS', 'DEFERRED', 'LOOKUP_UNAVAILABLE'];
    const addresses = statuses.map(status => {
        const resolution = {
            status, strategy: 'bounded_recovery', reasonCode: status.toLowerCase(), attempts: 4,
            policyVersion: 'street-address-v12', retryAfterSeconds: status === 'DEFERRED' ? 30 : null
        };
        if (status === 'VERIFIED_UNIT') return candidate({ text: '120 Main St Apt 104, Ann Arbor MI', resolution });
        if (status === 'VERIFIED_BUILDING') return candidate({ text: '120 Main St Apt 104, Ann Arbor MI', resolution });
        return unlocatedCandidate({ text: `120 Main St Apt 104, Ann Arbor MI ${status}`, resolution });
    });
    const harness = createLocationHarness(addresses, async () => { throw new Error('unexpected retry'); });
    const rows = addresses.map((address, index) => harness.context.ui.createAddressItem(address, index));
    const messages = rows.map(row => row.querySelector('.location-status').textContent);

    assert.match(messages[0], /Apartment or suite verified/);
    assert.match(messages[1], /Building verified — Apt 104 retained/);
    assert.match(messages[1], /unit was not verified/);
    assert.match(messages[2], /No safe street match after automatic attempts/);
    assert.match(messages[3], /Several street matches remain/);
    assert.match(messages[4], /try again in about 30 seconds/);
    assert.match(messages[5], /temporarily unavailable/);
    assert.equal(rows[0].querySelector('.location-retry-button'), null);
    assert.equal(rows[1].querySelector('.location-retry-button'), null);
    for (const row of rows.slice(2)) {
        const status = row.querySelector('.location-status');
        const retry = row.querySelector('.location-retry-button');
        assert.equal(status.getAttribute('role'), 'status');
        assert.equal(status.getAttribute('aria-live'), 'polite');
        assert.equal(retry.disabled, false);
    }

    harness.context.ui.updateLocationResolutionNotice();
    assert.match(harness.notice.textContent, /2 of 6 stops verified/);
    assert.match(harness.notice.textContent, /1 no safe match, 1 ambiguous, 1 deferred, 1 temporarily unavailable/);
});

test('recovered locations show the Google address and retained screenshot text without changing verification claims', () => {
    const locality = candidate({
        text: '123 Main St, Requested City MI',
        resolution: {
            status: 'VERIFIED_UNIT', strategy: 'corroborated_locality', reasonCode: 'recovered_locality', attempts: 3,
            policyVersion: 'street-address-v12', matchedAddress: '123 Main St, Alternate City, MI 48104, USA'
        }
    });
    const streetRepresentation = candidate({
        text: '456 North Oak St, Example City MI',
        resolution: {
            status: 'VERIFIED_UNIT', strategy: 'corroborated_street_representation',
            reasonCode: 'recovered_street_representation', attempts: 3,
            policyVersion: 'street-address-v12', matchedAddress: '456 North Oak Ave, Example City, MI 48104, USA'
        }
    });
    const buildingOnly = candidate({
        text: '789 Pine Rd Apt 104, Example City MI',
        resolution: {
            status: 'VERIFIED_BUILDING', strategy: 'corroborated_locality',
            reasonCode: 'unit_not_confirmed', attempts: 3,
            policyVersion: 'street-address-v12', matchedAddress: '789 Pine Rd, Alternate City, MI 48104, USA'
        }
    });
    const ordinary = candidate({
        resolution: {
            status: 'VERIFIED_UNIT', strategy: 'original', reasonCode: 'exact_unit_match', attempts: 1,
            policyVersion: 'street-address-v12', matchedAddress: '123 Main St, Map City, MI 48104, USA'
        }
    });
    const addresses = [locality, streetRepresentation, buildingOnly, ordinary];
    const harness = createLocationHarness(addresses, async () => { throw new Error('unexpected retry'); });
    const messages = addresses.map((address, index) =>
        harness.context.ui.createAddressItem(address, index).querySelector('.location-status').textContent);

    assert.match(messages[0], /Google lists this street location under a different city name\. Map address: 123 Main St, Alternate City, MI 48104, USA\./);
    assert.match(messages[0], /Your address text is retained\./);
    assert.match(messages[1], /Google uses a different street spelling or type\. Map address: 456 North Oak Ave, Example City, MI 48104, USA\./);
    assert.match(messages[1], /Your address text is retained\./);
    assert.doesNotMatch(messages[0], /123 Main St, Requested City MI/);
    assert.doesNotMatch(messages[1], /456 North Oak St, Example City MI/);
    assert.doesNotMatch(messages[1], /exact OCR match/i);
    assert.match(messages[2], /Building verified — Apt 104 retained; the unit was not verified/);
    assert.match(messages[2], /Google lists this street location under a different city name/);
    assert.doesNotMatch(messages[2], /Apartment or suite verified/);
    assert.equal(messages[3], 'Apartment or suite verified.');
});

test('postal recovery explains the city difference and keeps the supplied address', () => {
    const text = '878 NOTTINGHAM RD, GROSSE POINTE, MI';
    const address = candidate({text, resolution: {
        status: 'VERIFIED_BUILDING', strategy: 'corroborated_postal_locality', reasonCode: 'exact_street_match',
        attempts: 2, policyVersion: 'street-address-v12',
        matchedAddress: '878 Nottingham Rd, Grosse Pointe Park, MI 48230, USA'
    }});
    const harness = createLocationHarness([address], async () => { throw new Error('unexpected retry'); });
    const row = harness.context.ui.createAddressItem(address, 0);
    const message = row.querySelector('.location-status').textContent;
    assert.match(message, /Postal city verified/);
    assert.match(message, /Map address: 878 Nottingham Rd, Grosse Pointe Park, MI 48230, USA/);
    assert.match(message, /Your address text is retained/);
    assert.doesNotMatch(message, /Apartment or suite verified/);
    assert.equal(address.text, text);
    assert.equal(row.querySelector('.location-retry-button'), null);
});

test('street structure recovery shows the verified map address without changing the supplied street', () => {
    const text = '6734 COTTONWOOD KNL, WEST BLOOMFIELD, MI';
    const address = candidate({text, resolution: {
        status: 'VERIFIED_BUILDING', strategy: 'corroborated_street_structure', reasonCode: 'exact_street_match',
        attempts: 3, policyVersion: 'street-address-v12',
        matchedAddress: '6734 Cottonwood Knoll Ct, West Bloomfield Township, MI 48322, USA'
    }});
    const harness = createLocationHarness([address], async () => { throw new Error('unexpected retry'); });
    const row = harness.context.ui.createAddressItem(address, 0);
    const message = row.querySelector('.location-status').textContent;
    assert.match(message, /Street name verified with additional location checks/);
    assert.match(message, /Map address: 6734 Cottonwood Knoll Ct/);
    assert.match(message, /Your address text is retained/);
    assert.equal(address.text, text);
    assert.equal(row.querySelector('.location-retry-button'), null);
});

test('recovered verification safely falls back when matchedAddress is absent or malformed', () => {
    const originalText = '321 Cedar Rd, Original City MI';
    for (const matchedAddress of [undefined, null, {}, 17, '', 'x'.repeat(513)]) {
        const resolution = {
            status: 'VERIFIED_UNIT', strategy: 'corroborated_street_representation',
            reasonCode: 'recovered_street_representation', attempts: 3, policyVersion: 'street-address-v12'
        };
        if (matchedAddress !== undefined) resolution.matchedAddress = matchedAddress;
        const address = candidate({ text: originalText, resolution });
        const harness = createLocationHarness([address], async () => { throw new Error('unexpected retry'); });
        const message = harness.context.ui.createAddressItem(address, 0).querySelector('.location-status').textContent;

        assert.match(message, /Apartment or suite verified\./);
        assert.match(message, /Google uses a different street spelling or type\. Your address text is retained\./);
        assert.doesNotMatch(message, /321 Cedar Rd, Original City MI/);
        assert.doesNotMatch(message, /\[object Object\]/);
        if (typeof matchedAddress === 'string' && matchedAddress.length > 512) assert.doesNotMatch(message, /x{513}/);
    }
});

test('provider matchedAddress is rendered literally as text', () => {
    const untrustedAddress = '<img src=x onerror=alert(1)> 555 Birch St, Map City MI';
    const address = candidate({
        text: '555 Birch St, Original City MI',
        resolution: {
            status: 'VERIFIED_UNIT', strategy: 'corroborated_locality', reasonCode: 'recovered_locality', attempts: 3,
            policyVersion: 'street-address-v12', matchedAddress: untrustedAddress
        }
    });
    const harness = createLocationHarness([address], async () => { throw new Error('unexpected retry'); });
    const status = harness.context.ui.createAddressItem(address, 0).querySelector('.location-status');

    assert.match(status.textContent, /Map address: <img src=x onerror=alert\(1\)> 555 Birch St, Map City MI\./);
    assert.equal(status.querySelector('img'), null);
    assert.equal(status.children.length, 0);
});

test('retry keeps focus on the active retry row after success and does not steal another row focus', async () => {
    const address = unlocatedCandidate({ text: '120 Main St Apt 104, Ann Arbor MI' });
    let finishLookup;
    const harness = createLocationHarness([address], () => new Promise(resolve => { finishLookup = resolve; }));
    const row = harness.context.ui.createAddressItem(address, 0);
    const retry = row.querySelector('.location-retry-button');
    const status = row.querySelector('.location-status');
    status.focus = () => { harness.context.document.activeElement = status; };
    harness.context.document.activeElement = retry;

    retry.listeners.click();
    assert.equal(harness.context.document.activeElement, retry);
    assert.equal(retry.disabled, true);
    finishLookup({ ok: true, json: async () => ({ candidates: [resolvedCandidate(address)] }) });
    await new Promise(resolve => setImmediate(resolve));

    assert.equal(row.querySelector('.location-retry-button'), null);
    assert.equal(harness.context.document.activeElement, status);
    assert.match(status.textContent, /verified/);
});

test('resolving stops clears only its marked location-blocked send error and leaves unrelated errors or holds visible', async () => {
    const locationBlocked = unlocatedCandidate({ text: '120 Main St, Ann Arbor MI' });
    const sendStatus = new FakeElement('div');
    const harness = createLocationHarness([locationBlocked], async () => ({
        ok: true, json: async () => ({ candidates: [resolvedCandidate(locationBlocked)] })
    }), {
        sendStatus,
        getManualCommandHold: () => null,
        showStatus: (message, type, target) => {
            target.textContent = message;
            target.className = `status ${type}`;
            target.classList.remove('hidden');
        },
        hideStatus: target => target.classList.add('hidden')
    });
    harness.context.ui.createAddressItem(locationBlocked, 0);
    harness.context.ui.showLocationBlockedSendStatus('Resolve a verified location first.', [locationBlocked]);
    const editedLookup = harness.context.ui.updateAddress(0, '121 Main St, Ann Arbor MI');
    await editedLookup;
    assert.equal(sendStatus.classList.contains('hidden'), true);
    assert.equal(sendStatus.dataset.locationBlocked, undefined);
    assert.equal(sendStatus.textContent, '');
    assert.equal(sendStatus.classList.contains('error'), false);

    const unrelated = unlocatedCandidate({ text: '200 Oak St, Ann Arbor MI' });
    const unrelatedStatus = new FakeElement('div');
    const unrelatedHarness = createLocationHarness([unrelated], async () => ({
        ok: true, json: async () => ({ candidates: [resolvedCandidate(unrelated)] })
    }), {
        sendStatus: unrelatedStatus,
        getManualCommandHold: () => null,
        showStatus: (message, type, target) => { target.textContent = message; target.className = `status ${type}`; },
        hideStatus: target => target.classList.add('hidden')
    });
    unrelatedStatus.textContent = 'TAP authorization expired.';
    unrelatedStatus.className = 'status error';
    await unrelatedHarness.context.ui.resolveLocationCandidates([unrelated]);
    assert.equal(unrelatedStatus.textContent, 'TAP authorization expired.');

    const held = unlocatedCandidate({ text: '300 Pine St, Ann Arbor MI' });
    const holdStatus = new FakeElement('div');
    const hold = { vin: 'VIN-TEST', state: 'PENDING', idempotencyKey: 'kept' };
    const holdHarness = createLocationHarness([held], async () => ({
        ok: true, json: async () => ({ candidates: [resolvedCandidate(held)] })
    }), {
        sendStatus: holdStatus,
        getManualCommandHold: () => hold,
        showStatus: (message, type, target) => { target.textContent = message; target.className = `status ${type}`; },
        hideStatus: target => target.classList.add('hidden')
    });
    holdStatus.textContent = 'Vehicle route command outcome is unconfirmed.';
    holdStatus.className = 'status error';
    holdStatus.dataset.manualCommandHold = 'true';
    await holdHarness.context.ui.resolveLocationCandidates([held]);
    assert.equal(holdStatus.textContent, 'Vehicle route command outcome is unconfirmed.');
    assert.equal(holdStatus.dataset.manualCommandHold, 'true');
});

test('late location responses cannot overwrite edited text and reordered or removed rows stay identity-bound', async () => {
    const edited = unlocatedCandidate({ text: '123 Old Rd, Ann Arbor MI' });
    const addresses = [edited];
    const pending = [];
    const harness = createLocationHarness(addresses, (url, options) => new Promise(resolve => {
        pending.push({ body: JSON.parse(options.body), resolve });
    }));

    const oldLookup = harness.context.ui.resolveLocationCandidates([edited]);
    const editedLookup = harness.context.ui.updateAddress(0, '456 New Ave, Ann Arbor MI');
    assert.equal(edited.normalized, '456 New Ave, Ann Arbor MI');
    assert.equal(pending.length, 2);

    pending[0].resolve({ ok: true, json: async () => ({ candidates: [resolvedCandidate({ text: '123 Old Rd, Ann Arbor MI' }, 'stale-place')] }) });
    await oldLookup;
    assert.equal(edited.pid, null);
    assert.equal(harness.context.ui.getLocationResolutionState(edited).version, 1);

    pending[1].resolve({ ok: true, json: async () => ({ candidates: [resolvedCandidate(edited, 'edited-place')] }) });
    await editedLookup;
    assert.equal(edited.pid, 'edited-place');
    assert.equal(edited.text, '456 New Ave, Ann Arbor MI');

    const first = unlocatedCandidate({ text: '101 First Rd' });
    const second = unlocatedCandidate({ text: '202 Second Rd' });
    const removed = unlocatedCandidate({ text: '303 Removed Rd' });
    const ordered = [first, second, removed];
    const reorderHarness = createLocationHarness(ordered, (url, options) => new Promise(resolve => {
        pending.push({ body: JSON.parse(options.body), resolve });
    }));
    const reorderLookup = reorderHarness.context.ui.resolveLocationCandidates(ordered);
    ordered.splice(0, 3, second, first);
    pending[2].resolve({ ok: true, json: async () => ({ candidates: [
        resolvedCandidate(first, 'first-place'), resolvedCandidate(second, 'second-place'), resolvedCandidate(removed, 'removed-place')
    ] }) });
    await reorderLookup;
    assert.equal(first.pid, 'first-place');
    assert.equal(second.pid, 'second-place');
    assert.equal(removed.pid, null);
});

for (const [savedState, resolution] of [
    ['legacy coordinates', null],
    ['v5 verified unit', { status: 'VERIFIED_UNIT', strategy: 'original', reasonCode: 'exact_unit_match', attempts: 1, policyVersion: 'street-address-v5' }],
    ['v5 unit conflict', { status: 'NO_MATCH', strategy: 'original', reasonCode: 'unit_conflict', attempts: 1, policyVersion: 'street-address-v5' }],
    ['v6 verified unit', { status: 'VERIFIED_UNIT', strategy: 'original', reasonCode: 'exact_unit_match', attempts: 1, policyVersion: 'street-address-v6' }],
    ['v6 rejected no match', { status: 'NO_MATCH', strategy: 'original', reasonCode: 'no_safe_match', attempts: 8, policyVersion: 'street-address-v6' }],
    ['v7 verified building', { status: 'VERIFIED_BUILDING', strategy: 'original', reasonCode: 'exact_street_match', attempts: 1, policyVersion: 'street-address-v7' }],
    ['v7 rejected postal city', { status: 'NO_MATCH', strategy: 'recovery_exhausted', reasonCode: 'no_safe_match', attempts: 4, policyVersion: 'street-address-v7' }],
    ['v8 verified postal city', { status: 'VERIFIED_BUILDING', strategy: 'corroborated_postal_locality', reasonCode: 'exact_street_match', attempts: 2, policyVersion: 'street-address-v8' }],
    ['v8 rejected municipality', { status: 'NO_MATCH', strategy: 'recovery_exhausted', reasonCode: 'no_safe_match', attempts: 8, policyVersion: 'street-address-v8' }],
    ['v8 unit conflict', { status: 'NO_MATCH', strategy: 'original', reasonCode: 'unit_conflict', attempts: 1, policyVersion: 'street-address-v8' }],
    ['v9 verified municipality', { status: 'VERIFIED_BUILDING', strategy: 'corroborated_postal_locality', reasonCode: 'exact_street_match', attempts: 2, policyVersion: 'street-address-v9' }],
    ['v9 rejected street structure', { status: 'NO_MATCH', strategy: 'recovery_exhausted', reasonCode: 'no_safe_match', attempts: 6, policyVersion: 'street-address-v9' }],
    ['v9 unit conflict', { status: 'NO_MATCH', strategy: 'original', reasonCode: 'unit_conflict', attempts: 1, policyVersion: 'street-address-v9' }],
    ['v11 verified street structure', { status: 'VERIFIED_BUILDING', strategy: 'corroborated_street_structure', reasonCode: 'exact_street_match', attempts: 3, policyVersion: 'street-address-v11' }],
    ['v11 rejected explicit township', { status: 'NO_MATCH', strategy: 'recovery_exhausted', reasonCode: 'no_safe_match', attempts: 8, policyVersion: 'street-address-v11' }]
]) {
test(`restoring a session re-resolves ${savedState} before saving current-policy locations`, async () => {
    const unresolved = candidate({ text: '120 Main St Apt 330, Ann Arbor MI', resolution });
    const saved = [];
    let resolveCalls = 0;
    const elements = { vin: new FakeElement('select'), defaultState: new FakeElement('select') };
    const context = vm.createContext({
        ...({ extractedAddresses: [], imageHashes: [], currentVin: '', autoNavSessionId: null }),
        document: { getElementById: id => elements[id] || new FakeElement('div') },
        fetch: async url => {
            assert.equal(url, '/route/session/load');
            return { ok: true, json: async () => ({ valid: true, candidates: [unresolved], imageHashes: ['hash'], vin: 'VIN-TEST', defaultState: 'MI' }) };
        },
        apiFetch: async (url, options) => {
            resolveCalls++;
            assert.equal(url, '/route/resolve');
            assert.deepEqual(JSON.parse(options.body).candidates.map(row => row.text), [unresolved.text]);
            return { ok: true, json: async () => ({ candidates: [resolvedCandidate(unresolved, 'restored-place')] }) };
        },
        sendStatus: {}, updateManualCommandHoldControls() {},
        processStatus: {}, showStatus() {}, dismissSessionRecovery() {}, setCookie() {}, showStep2() {},
        saveCurrentSession: async () => { saved.push(unresolved.pid); }
    });
    new vm.Script(locationUiSource + '\n' + extractBetween('async function loadPreviousSession() {', 'function dismissSessionRecovery() {')
        + '\nglobalThis.restore = loadPreviousSession;').runInContext(context);

    await context.restore();
    assert.equal(resolveCalls, 1);
    assert.equal(context.extractedAddresses[0], unresolved);
    assert.equal(unresolved.pid, 'restored-place');
    assert.equal(unresolved.text, '120 Main St Apt 330, Ann Arbor MI');
    assert.equal(unresolved.resolution.policyVersion, 'street-address-v12');
    assert.deepEqual(saved, ['restored-place']);
});
}

test('pre-send validation stays retryable while uncertain outcomes remain held', async () => {
  const source = locationCoreSource
    + extractBetween('async function sendGroupToTesla(', 'function showStatus(');
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
  const address = unlocatedCandidate({ lat: 0, lon: 0, pid: null });
  const context = loadUi(addressUiSource, {
    document: { createElement: tagName => new FakeElement(tagName) }, extractedAddresses: [address],
    sendStatus: {}, showStatus() {}
  });
  const row = context.ui.createAddressItem(address, 0);
  assert.match(row.querySelector('.location-status').textContent, /No current street-location verification/);
  assert.doesNotMatch(row.querySelector('.address-meta').textContent, /0\.0000/);
});

test('manual and automatic route sends stop before API dispatch when any route row needs review', async () => {
    const locationCheck = locationCoreSource;
    const sendSource = locationCheck + extractBetween('async function sendGroupToTesla(', 'function showStatus(');
    const autoSource = locationCheck + extractBetween('async function startAutoNavigation(', 'function startAutoNavPolling(');
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

test('manual and automatic sends stay blocked on unresolved locations without dispatch or command holds', async () => {
    const locationCheck = locationCoreSource;
    const locationStatusHelpers = extractBetween('function locationTarget(address) {', 'function updateAddressLocationPresentation(address) {');
    const sendSource = locationCheck + locationStatusHelpers + extractBetween('async function sendGroupToTesla(', 'function showStatus(');
    const autoSource = locationCheck + locationStatusHelpers + extractBetween('async function startAutoNavigation(', 'function startAutoNavPolling(');
    const statuses = [];
    let apiCalls = 0;
    let holdWrites = 0;
    const globals = {
        currentVin: 'VIN-TEST',
        extractedAddresses: [unlocatedCandidate({ text: '120 Main St Apt 330, Ann Arbor MI' })],
        getManualCommandHold: () => null,
        manualCommandInFlight: new Set(),
        availableVehicles: [{ vin: 'VIN-TEST', command: true }],
        sendStatus: {},
        apiFetch: async () => { apiCalls++; throw new Error('unexpected command dispatch'); },
        persistManualCommandHold: () => { holdWrites++; },
        showStatus: (message, type) => statuses.push({ message, type }),
        updateManualCommandHoldControls: () => {}
    };
    const manualContext = vm.createContext({ ...globals });
    new vm.Script(sendSource + '\nglobalThis.send = sendGroupToTesla;').runInContext(manualContext);
    await manualContext.send(0, 0, 1);
    assert.equal(apiCalls, 0);
    assert.equal(holdWrites, 0);
    assert.match(statuses.at(-1).message, /verified location/);

    const autoContext = vm.createContext({ ...globals });
    new vm.Script(autoSource + '\nglobalThis.start = startAutoNavigation;').runInContext(autoContext);
    await autoContext.start();
    assert.equal(apiCalls, 0);
    assert.equal(holdWrites, 0);
    assert.match(statuses.at(-1).message, /verified location/);
});

test('clearing a command hold does not reenable location-blocked route buttons', () => {
    const source = locationCoreSource
        + extractBetween('function updateManualCommandHoldControls(vin, statusElement) {', 'async function loadVehicles(');
    const groupButton = new FakeElement('button');
    groupButton.disabled = true;
    groupButton.dataset.commandBlocked = 'true';
    groupButton.dataset.defaultLabel = 'Send Route 1 to Tesla';
    groupButton.dataset.addressStart = '0';
    groupButton.dataset.addressCount = '1';
    groupButton.textContent = 'Vehicle sends paused — reconcile first';
    const autoButton = new FakeElement('button');
    autoButton.disabled = true;
    autoButton.dataset.commandBlocked = 'true';
    const addresses = [unlocatedCandidate()];
    const context = vm.createContext({
        currentVin: 'VIN-TEST', extractedAddresses: addresses,
        manualCommandInFlight: new Set(), getManualCommandHold: () => null,
        document: {
            querySelectorAll: selector => selector === '.group-send-btn' ? [groupButton] : [autoButton]
        }
    });
    new vm.Script(source + '\nglobalThis.refresh = updateManualCommandHoldControls;').runInContext(context);

    context.refresh('VIN-TEST');
    assert.equal(groupButton.disabled, true);
    assert.equal(groupButton.textContent, 'Send Route 1 to Tesla');
    assert.equal(groupButton.dataset.commandBlocked, undefined);
    assert.equal(autoButton.disabled, true);
    assert.equal(autoButton.title, 'Resolve every stop to a verified location before starting.');

    Object.assign(addresses[0], {
        lat: 42.31, lon: -83.81, pid: 'resolved-place', resolution: candidate().resolution
    });
    context.refresh('VIN-TEST');
    assert.equal(groupButton.disabled, false);
    assert.equal(autoButton.disabled, false);
    assert.equal(autoButton.title, '');
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
    const row = text => ({
        text, normalized: text, pid: text, lat: 42.31, lon: -83.81,
        resolution: candidate().resolution, ocrAgreement: 3, ocrReviewRequired: false
    });
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
    const row = text => ({
        text, normalized: text, ocrReviewRequired: false, ocrAgreement: 3,
        lat: 42.31, lon: -83.81, pid: text, resolution: candidate().resolution
    });
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
