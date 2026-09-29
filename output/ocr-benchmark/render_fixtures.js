#!/usr/bin/env node
// Deterministic screenshot text. Truth is stored separately and is never inferred from OCR.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
function loadPlaywright() {
  if (process.env.PLAYWRIGHT_MODULE_PATH) return require(process.env.PLAYWRIGHT_MODULE_PATH);
  try { return require('playwright'); } catch {}
  const cache = path.join(os.homedir(), '.npm', '_npx');
  for (const entry of fs.existsSync(cache) ? fs.readdirSync(cache).sort() : []) {
    const module = path.join(cache, entry, 'node_modules', 'playwright');
    if (fs.existsSync(path.join(module, 'package.json'))) return require(module);
  }
  throw new Error('Install Playwright or set PLAYWRIGHT_MODULE_PATH to an installed module.');
}
const args = process.argv.slice(2);
if (args.length !== 4 || args[0] !== '--spec' || args[2] !== '--outdir') {
  throw new Error('Usage: node render_fixtures.js --spec INPUT.json --outdir IMAGE_DIRECTORY');
}
const spec = JSON.parse(fs.readFileSync(args[1], 'utf8'));
if (spec.schemaVersion !== 1 || !Array.isArray(spec.cases)) throw new Error('Expected schemaVersion 1 cases');
const output = path.resolve(args[3]);
fs.mkdirSync(output, { recursive: true });
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const browser = await loadPlaywright().chromium.launch({ headless: true, channel: 'chrome' });
try {
  for (const fixture of spec.cases) {
    if (!/^[a-z0-9-]+$/.test(fixture.id) || !Array.isArray(fixture.rows)) throw new Error('Invalid fixture');
    const p = fixture.profile ?? {};
    const width = p.width ?? 600, height = p.height ?? 1000;
    const fontScale = p.fontScale ?? 1, scroll = p.scrollTop ?? 0;
    if (width < 320 || width > 1600 || height < 300 || height > 3000 || fontScale < 0.6 || fontScale > 1.6 || scroll < 0 || scroll > 2000) {
      throw new Error('Fixture dimensions outside supported range');
    }
    const scale = width / 600;
    const light = p.theme === 'light';
    const rows = fixture.rows.map((row, i) => `<section class="stop"><div class="number">${i+2}</div><div class="content">
      ${i === 0 ? '<div class="next">Next Stop:</div>' : ''}
      <div class="schedule"><span class="clock">◷</span> # B.L16.OV · Scheduled 6:00 - 11:00 AM</div>
      <div class="street">${escape(row.street ?? row.distractor)}</div>
      ${row.city ? `<div class="city">${escape(row.city)}${row.postal ? ' ' + escape(row.postal) : ''}</div>` : ''}
      <div class="delivery">${escape(row.delivery ?? `Deliver ${row.packages ?? 1} package${(row.packages ?? 1) === 1 ? '' : 's'}`)}</div>
      </div></section>`).join('');
    const page = await browser.newPage({ viewport: {width, height}, deviceScaleFactor: 1 });
    try {
      await page.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>
        *{box-sizing:border-box}body{margin:0;background:${light?'#f4f5f5':'#050d12'};color:${light?'#111':'#fff'};font-family:Arial,sans-serif;font-weight:700}
        .status{height:${45*scale}px;background:${light?'#ddd':'#181818'};padding:${10*scale}px;font-size:${18*scale}px;display:flex;justify-content:space-between}
        .toolbar{height:${63*scale}px;background:${light?'#e2e7ea':'#202c33'};display:flex;align-items:center;justify-content:space-between;padding:0 ${23*scale}px;font-size:${22*scale}px}
        .tabs{height:${67*scale}px;background:${light?'#ddd':'#2b2929'};display:flex;align-items:center;justify-content:space-around;font-size:${20*scale}px}
        .viewport{height:${height-175*scale}px;overflow:hidden}.list{transform:translateY(-${scroll}px);margin-left:${56*scale}px;border-left:1px solid #788288}
        .stop{position:relative;margin-left:${30*scale}px}.content{padding:${17*scale}px ${17*scale}px ${10*scale}px;border-bottom:1px solid #788288;font-size:${20*scale*fontScale}px;line-height:${31*scale*fontScale}px;min-height:${143*scale}px;overflow-wrap:normal}
        .number{position:absolute;left:${-44*scale}px;top:${31*scale}px;border:1px solid #b9c1c5;border-radius:50%;width:${28*scale}px;height:${28*scale}px;text-align:center;line-height:${26*scale}px;font-size:${18*scale}px;background:${light?'#ddd':'#232929'}}
        .stop:first-child .number{background:#267119;color:white}.schedule,.delivery{font-size:${18*scale*fontScale}px}.clock{color:#00bded}.street,.city{text-transform:${p.textTransform==='none'?'none':'uppercase'}}
        .next,.street,.city,.schedule,.delivery{text-shadow:${light?'none':'-0.6px -0.6px #000, 0.6px 0.6px #000'}}
      </style></head><body><div class="status"><span>6:08 ✉ ◇</span><span>◉ ▰ 96%</span></div>
      <div class="toolbar"><span>☰</span><span>ITINERARY</span><span>▢ ⓘ</span></div>
      <div class="tabs"><span>LIST</span><span>MAP</span><span>SUMMARY</span></div>
      <div class="viewport"><div class="list">${rows}</div></div></body></html>`);
      await page.screenshot({ path: path.join(output, fixture.id + '.png') });
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
console.log(JSON.stringify({rendered: spec.cases.length, output}));
