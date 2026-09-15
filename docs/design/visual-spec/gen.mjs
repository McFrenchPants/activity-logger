// Generates the Activity Ledger visual-spec artboards (.dc.html) + canvas.json.
import { writeFileSync } from 'node:fs';

const OUT = new URL('./', import.meta.url);
const w = (name, s) => writeFileSync(new URL(name, OUT), s);

// ---------- tokens ----------
const SANS = "'Instrument Sans', 'Segoe UI', system-ui, sans-serif";
const SERIF = "'Source Serif 4', Georgia, 'Times New Roman', serif";
const L = {
  name: 'light',
  bg: '#F7F4EE', surface: '#FFFDF9', container: '#EFEAE1', containerHigh: '#E7E1D6', outline: '#D6CEBF', outlineStrong: '#9C9486',
  ink: '#22201C', muted: '#6B655B',
  primary: '#2F6B55', onPrimary: '#FFFFFF', primaryContainer: '#D5E8DD', onPrimaryContainer: '#0F3527',
  review: '#855610', reviewContainer: '#F3E4C4', onReviewContainer: '#3D2804',
  error: '#A33B2C', errorContainer: '#F6D9D3', onErrorContainer: '#44120A',
  navIndicator: '#D5E8DD',
};
const D = {
  name: 'dark',
  bg: '#161513', surface: '#1E1C19', container: '#282521', containerHigh: '#322E29', outline: '#403B35', outlineStrong: '#7A7368',
  ink: '#EDE8DF', muted: '#A69F93',
  primary: '#8FC9AE', onPrimary: '#0B2A1E', primaryContainer: '#1E4838', onPrimaryContainer: '#CDEBDC',
  review: '#E4BA6C', reviewContainer: '#3B2E16', onReviewContainer: '#F6E3BD',
  error: '#F0A193', errorContainer: '#4B221B', onErrorContainer: '#FAD9D2',
  navIndicator: '#1E4838',
};

// ---------- icons (24 grid, stroke) ----------
const P = {
  mic: '<rect x="9" y="3" width="6" height="11" rx="3"></rect><path d="M5 11a7 7 0 0 0 14 0M12 18v3"></path>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor"></rect>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"></path>',
  saved: '<circle cx="12" cy="12" r="9"></circle><path d="M8 12.5l2.8 2.8L16.5 9.5"></path>',
  review: '<circle cx="12" cy="12" r="9"></circle><path d="M9.6 9.3a2.5 2.5 0 1 1 3.4 2.3c-.6.3-1 .8-1 1.5v.4"></path><path d="M12 16.8v.1"></path>',
  pending: '<circle cx="12" cy="12" r="9" stroke-dasharray="2.6 2.6"></circle><path d="M12 8v4.2l2.6 1.6"></path>',
  alert: '<circle cx="12" cy="12" r="9"></circle><path d="M12 7.5v5.5M12 16.3v.1"></path>',
  progress: '<circle cx="12" cy="12" r="9"></circle><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor"></path>',
  queued: '<path d="M12 15V5M7.5 9.5L12 5l4.5 4.5"></path><path d="M5 19h14"></path>',
  watch: '<rect x="7" y="6" width="10" height="12" rx="3"></rect><path d="M9 6l.6-3h4.8L15 6M9 18l.6 3h4.8L15 18"></path>',
  phone: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"></rect><path d="M11 18.5h2"></path>',
  history: '<path d="M3.5 12a8.5 8.5 0 1 0 2.5-6"></path><path d="M3.5 4.5V8H7"></path><path d="M12 7.5V12l3 2"></path>',
  ask: '<path d="M20 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.2A8 8 0 1 1 20 12z"></path>',
  settings: '<path d="M4 7h9M18 7h2M4 17h4M13 17h7"></path><circle cx="15.5" cy="7" r="2.3"></circle><circle cx="10.5" cy="17" r="2.3"></circle>',
  undo: '<path d="M9 14L4 9l5-5"></path><path d="M4 9h10a6 6 0 0 1 0 12h-3"></path>',
  chev: '<path d="M9.5 6l6 6-6 6"></path>',
  back: '<path d="M19 12H5M11 6l-6 6 6 6"></path>',
  close: '<path d="M6 6l12 12M18 6L6 18"></path>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"></path><path d="M13.5 6.5l4 4"></path>',
  keyboard: '<rect x="2.5" y="6" width="19" height="12" rx="2"></rect><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M8 14h8"></path>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"></rect><path d="M8 11V8a4 4 0 0 1 8 0v3"></path>',
  plus: '<path d="M12 5v14M5 12h14"></path>',
  retry: '<path d="M20 11a8 8 0 1 0-2.3 5.7"></path><path d="M20 4v7h-7"></path>',
  send: '<path d="M4.5 12L20 4.5 15 20l-3-6.5z"></path>',
  down: '<path d="M7 10l5 5 5-5"></path>',
  search: '<circle cx="11" cy="11" r="6.5"></circle><path d="M16 16l4.5 4.5"></path>',
  hide: '<path d="M3 3l18 18"></path><path d="M10.6 5.1A10 10 0 0 1 12 5c5 0 9 5 9 7a9.7 9.7 0 0 1-2.4 3.4M6.6 6.6C4.5 8 3 10.3 3 12c0 2 4 7 9 7 1.6 0 3-.4 4.3-1.1"></path>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"></path>',
  more: '<circle cx="12" cy="5.5" r="1" fill="currentColor"></circle><circle cx="12" cy="12" r="1" fill="currentColor"></circle><circle cx="12" cy="18.5" r="1" fill="currentColor"></circle>',
  activity: '<path d="M5 5h14v14H5z"></path><path d="M9 9h6M9 12h6M9 15h3"></path>',
  shield: '<path d="M12 3l7 3v5c0 5-3.5 8.5-7 10-3.5-1.5-7-5-7-10V6z"></path>',
  chip: '<rect x="6" y="6" width="12" height="12" rx="2"></rect><path d="M9 2.5V6M15 2.5V6M9 18v3.5M15 18v3.5M2.5 9H6M2.5 15H6M18 9h3.5M18 15h3.5"></path>',
};
const ic = (name, size = 24, color = 'currentColor', sw = 1.75, extra = '') =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0; display: block;"${extra}>${P[name]}</svg>`;

// ---------- document shell ----------
const doc = (body, bg = L.bg, extraCss = '') => `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Instrument+Sans:wght@400;500;600;700&amp;family=Source+Serif+4:ital,opsz,wght@0,8..60,400;1,8..60,400;1,8..60,500&amp;display=swap">
  <style>
    html, body { margin: 0; background: ${bg}; }
    body { font-family: ${SANS}; -webkit-font-smoothing: antialiased; font-variant-numeric: tabular-nums; }
    a { color: ${L.primary}; } a:hover { color: ${L.onPrimaryContainer}; }
    p, h1, h2, h3, h4 { margin: 0; }
    .ev { font-family: ${SERIF}; font-style: italic; font-weight: 400; }
    ${extraCss}
  </style>
</helmet>
${body}
</x-dc>
</body>
</html>
`;

// ---------- phone parts ----------
const PW = 412, PH = 915;
const iconBtn = (t, name, label, color) =>
  `<div role="button" aria-label="${label}" style="width: 48px; height: 48px; border-radius: 24px; display: flex; align-items: center; justify-content: center; color: ${color || t.ink};">${ic(name, 24)}</div>`;

const topBar = (t, { title, lead, actions = '', big = false }) => `
  <div style="height: 64px; display: flex; align-items: center; gap: 4px; padding: 0 4px 0 ${lead ? 4 : 16}px; flex-shrink: 0;">
    ${lead ? iconBtn(t, lead, lead === 'back' ? 'Navigate up' : 'Close') : ''}
    <div style="flex-grow: 1; font-size: ${big ? 22 : 22}px; line-height: 28px; font-weight: 600; color: ${t.ink}; letter-spacing: -0.01em; padding-left: ${lead ? 4 : 0}px;">${title}</div>
    <div style="display: flex; align-items: center;">${actions}</div>
  </div>`;

const navBar = (t, active) => {
  const item = (key, icon, label) => {
    const on = key === active;
    return `<div role="tab" aria-selected="${on}" style="flex: 1 1 0; display: flex; flex-direction: column; align-items: center; gap: 4px; padding-top: 12px;">
      <div style="width: 64px; height: 32px; border-radius: 16px; display: flex; align-items: center; justify-content: center; background: ${on ? t.navIndicator : 'transparent'}; color: ${on ? t.onPrimaryContainer : t.muted};">${ic(icon, 24)}</div>
      <div style="font-size: 12px; line-height: 16px; font-weight: ${on ? 700 : 500}; color: ${on ? t.ink : t.muted};">${label}</div>
    </div>`;
  };
  return `<div role="tablist" style="height: 80px; display: flex; background: ${t.container}; flex-shrink: 0;">${item('log', 'mic', 'Log')}${item('history', 'history', 'History')}${item('ask', 'ask', 'Ask')}</div>
  <div style="height: 24px; background: ${t.container}; flex-shrink: 0;"></div>`;
};

const phone = (t, { top, body, nav, bottom = '' }) => `
<div style="width: ${PW}px; height: ${PH}px; background: ${t.bg}; display: flex; flex-direction: column; overflow: hidden; position: relative; color: ${t.ink};">
  <div style="height: 36px; flex-shrink: 0;"></div>
  ${top}
  <div style="flex-grow: 1; display: flex; flex-direction: column; overflow: hidden; position: relative;">${body}</div>
  ${bottom}
  ${nav ? navBar(t, nav) : `<div style="height: 24px; flex-shrink: 0;"></div>`}
</div>`;

const quote = (t, text, size = 14, color) =>
  `<p class="ev" style="font-size: ${size}px; line-height: ${Math.round(size * 1.42)}px; color: ${color || t.muted}; text-wrap: pretty;">“${text}”</p>`;

const stateTag = (t, kind) => {
  const map = {
    review: [t.review, 'review', 'Needs review'],
    pending: [t.muted, 'pending', 'Not categorized yet'],
    progress: [t.primary, 'progress', 'In progress'],
    queued: [t.muted, 'queued', 'Queued on watch'],
  };
  const [c, icon, label] = map[kind];
  return `<div style="display: inline-flex; align-items: center; gap: 6px; color: ${c}; font-size: 13px; line-height: 16px; font-weight: 600;">${ic(icon, 16, c, 2)}<span>${label}</span></div>`;
};

const deviceIcon = (t, dev) =>
  `<div aria-label="Logged from ${dev}" style="width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; color: ${t.muted};">${ic(dev, 18, t.muted, 1.6)}</div>`;

// History/recent row
const row = (t, r) => {
  const unresolved = r.kind === 'review' || r.kind === 'pending';
  const head = unresolved
    ? `${stateTag(t, r.kind)}${quote(t, r.quote, 17, t.ink)}`
    : `<div style="font-size: 17px; line-height: 24px; font-weight: 600; color: ${t.ink};">${r.name}</div>`;
  const meta = `<div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;"><span style="font-size: 14px; line-height: 20px; color: ${t.muted};">${r.when}</span>${r.kind === 'progress' ? stateTag(t, 'progress') : ''}</div>`;
  return `<div role="button" style="display: flex; gap: 12px; padding: 12px 16px 12px 20px; min-height: 72px; box-sizing: border-box; border-bottom: 1px solid ${t.outline}; align-items: flex-start; background: ${r.hl ? t.container : 'transparent'};">
    <div style="flex-grow: 1; display: flex; flex-direction: column; gap: 3px; min-width: 0;">
      ${head}
      ${meta}
      ${unresolved ? '' : quote(t, r.quote)}
    </div>
    ${deviceIcon(t, r.dev)}
  </div>`;
};

const btn = (t, label, { kind = 'filled', icon, color, full = false } = {}) => {
  const styles = {
    filled: `background: ${t.primary}; color: ${t.onPrimary};`,
    tonal: `background: ${t.container}; color: ${t.ink};`,
    outline: `background: transparent; color: ${t.ink}; border: 1px solid ${t.outlineStrong};`,
    text: `background: transparent; color: ${color || t.primary};`,
  }[kind];
  return `<div role="button" style="height: 48px; padding: 0 ${kind === 'text' ? 12 : 20}px; border-radius: 24px; display: ${full ? 'flex' : 'inline-flex'}; align-items: center; justify-content: center; gap: 8px; font-size: 15px; line-height: 20px; font-weight: 600; box-sizing: border-box; ${styles}">${icon ? ic(icon, 20) : ''}<span>${label}</span></div>`;
};

const micButton = (t, { size = 112, listening = false, dim = false } = {}) => {
  const core = `<div role="button" aria-label="${listening ? 'Stop listening' : 'Log by voice'}" style="width: ${size}px; height: ${size}px; border-radius: ${size / 2}px; background: ${t.primary}; color: ${t.onPrimary}; display: flex; align-items: center; justify-content: center; box-shadow: 0 6px 18px ${t.name === 'light' ? 'rgba(47,107,85,0.28)' : 'rgba(0,0,0,0.4)'}; opacity: ${dim ? 0.5 : 1};">${ic(listening ? 'stop' : 'mic', Math.round(size * 0.4), 'currentColor', 1.9)}</div>`;
  if (!listening) return core;
  return `<div style="width: 220px; height: 220px; border-radius: 110px; background: ${t.primaryContainer}55; display: flex; align-items: center; justify-content: center;">
    <div style="width: 168px; height: 168px; border-radius: 84px; background: ${t.primaryContainer}; display: flex; align-items: center; justify-content: center;">${core}</div></div>`;
};

const sectionHead = (t, label, action) => `<div style="display: flex; align-items: center; justify-content: space-between; padding: 8px 8px 4px 20px; min-height: 48px; box-sizing: border-box;">
  <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${t.muted}; letter-spacing: 0.02em;">${label}</div>${action || ''}</div>`;

// ---------- sample data ----------
const R = {
  mow: { name: 'Mow lawn', when: 'Today, afternoon', quote: 'I cut the grass this afternoon', dev: 'phone' },
  mowMorning: { name: 'Mow lawn', when: 'Today, morning', quote: 'I cut the grass this morning', dev: 'phone' },
  furnaceReview: { kind: 'review', when: 'Today, 11:05 AM', quote: 'I did the thing with the furnace', dev: 'phone' },
  pool: { name: 'Clean pool', kind: 'progress', when: 'Today, 10:20 AM', quote: 'I’m cleaning the pool', dev: 'watch' },
  filter: { name: 'Replace furnace filter', when: 'Sep 12, 9:40 AM', quote: 'Changed the HVAC filter', dev: 'phone' },
  gutters: { name: 'Clean gutters', when: 'Sat, Sep 12', quote: 'I cleaned the gutters Saturday', dev: 'watch' },
  oil: { name: 'Change vehicle oil', when: 'Sep 8, 5:30 PM', quote: 'Oil change on the truck', dev: 'phone' },
  mow2: { name: 'Mow lawn', when: 'Sep 6, 6:15 PM', quote: 'Mowed the lawn', dev: 'watch' },
};

const homeTop = (t, extra = '') => topBar(t, { title: 'Activity Ledger', actions: iconBtn(t, 'settings', 'Settings and diagnostics') + extra });
const allHistory = (t) => btn(t, 'All history', { kind: 'text' });

// ============ PHONE ARTBOARDS ============

// Main — Home, ready
w('Main.dc.html', doc(phone(L, {
  top: homeTop(L), nav: 'log',
  body: `
  <div style="display: flex; flex-direction: column; align-items: center; gap: 20px; padding: 28px 24px 28px; flex-shrink: 0;">
    <p style="font-size: 26px; line-height: 34px; font-weight: 500; color: ${L.ink}; text-align: center; letter-spacing: -0.015em;">Say what you just did.</p>
    ${micButton(L)}
    <div style="display: flex; flex-direction: column; align-items: center; gap: 2px;">
      <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted};">Tap to speak</div>
      ${btn(L, 'Type instead', { kind: 'text', icon: 'keyboard', color: L.muted })}
    </div>
  </div>
  <div style="border-top: 1px solid ${L.outline};">
    ${sectionHead(L, 'Recent', allHistory(L))}
    ${row(L, R.mow)}${row(L, R.furnaceReview)}${row(L, R.pool)}
  </div>`,
})));

// Listening
w('HomeListening.dc.html', doc(phone(L, {
  top: homeTop(L), nav: 'log',
  body: `
  <div style="display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px 24px 20px; flex-shrink: 0;">
    <div aria-live="polite" style="display: flex; align-items: center; gap: 8px; color: ${L.primary}; font-size: 14px; line-height: 20px; font-weight: 700; letter-spacing: 0.02em;">
      <div style="width: 8px; height: 8px; border-radius: 4px; background: ${L.primary};"></div><span>Listening…</span></div>
    <div style="min-height: 72px; display: flex; align-items: center;">${quote(L, 'I cut the grass this morning', 24, L.ink).replace('text-wrap: pretty;', 'text-wrap: pretty; text-align: center;')}</div>
    ${micButton(L, { listening: true })}
    <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted};">Tap to stop · stops by itself when you pause</div>
  </div>
  <div style="border-top: 1px solid ${L.outline}; opacity: 0.4;" aria-hidden="true">
    ${sectionHead(L, 'Recent')}
    ${row(L, R.mow)}${row(L, R.furnaceReview)}
  </div>`,
})));

// Success + undo
const successCard = (t, remaining = 0.62) => `
  <div role="status" aria-live="polite" style="background: ${t.primaryContainer}; border-radius: 16px; overflow: hidden;">
    <div style="display: flex; align-items: center; gap: 12px; padding: 14px 8px 12px 16px;">
      ${ic('saved', 28, t.primary, 2)}
      <div style="flex-grow: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0;">
        <div style="font-size: 18px; line-height: 24px; font-weight: 600; color: ${t.onPrimaryContainer};">Mow lawn — this morning</div>
        ${quote(t, 'I cut the grass this morning', 14, t.onPrimaryContainer)}
      </div>
      ${btn(t, 'Undo', { kind: 'text', icon: 'undo', color: t.onPrimaryContainer })}
    </div>
    <div aria-hidden="true" style="height: 4px; background: ${t.name === 'light' ? '#BFD9CB' : '#2B5A48'};"><div style="width: ${remaining * 100}%; height: 4px; background: ${t.primary};"></div></div>
  </div>`;

w('HomeSuccess.dc.html', doc(phone(L, {
  top: homeTop(L), nav: 'log',
  body: `
  <div style="display: flex; flex-direction: column; gap: 24px; padding: 20px 16px 24px; flex-shrink: 0;">
    ${successCard(L)}
    <div style="display: flex; flex-direction: column; align-items: center; gap: 10px;">
      ${micButton(L, { size: 96 })}
      <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted};">Tap to speak</div>
    </div>
  </div>
  <div style="border-top: 1px solid ${L.outline};">
    ${sectionHead(L, 'Recent', allHistory(L))}
    ${row(L, { ...R.mowMorning, hl: true })}${row(L, R.furnaceReview)}${row(L, R.pool)}
  </div>`,
})));

// Needs review
const option = (t, label, sub, icon = 'chev') => `<div role="button" style="display: flex; align-items: center; gap: 12px; min-height: 56px; padding: 8px 12px 8px 16px; box-sizing: border-box; border-radius: 12px; background: ${t.surface}; border: 1px solid ${t.outline};">
  <div style="flex-grow: 1; display: flex; flex-direction: column;"><div style="font-size: 16px; line-height: 22px; font-weight: 600; color: ${t.ink};">${label}</div>${sub ? `<div style="font-size: 13px; line-height: 18px; color: ${t.muted};">${sub}</div>` : ''}</div>
  ${ic(icon, 20, t.muted)}</div>`;

w('NeedsReview.dc.html', doc(phone(L, {
  top: homeTop(L), nav: 'log',
  body: `
  <div style="display: flex; flex-direction: column; gap: 16px; padding: 16px 16px 0;">
    <div role="status" style="background: ${L.reviewContainer}; border-radius: 16px; padding: 16px; display: flex; flex-direction: column; gap: 14px;">
      <div style="display: flex; align-items: center; gap: 8px; color: ${L.review}; font-size: 15px; line-height: 20px; font-weight: 700;">${ic('review', 22, L.review, 2)}<span>Needs review</span></div>
      ${quote(L, 'I did the thing with the furnace.', 22, L.onReviewContainer)}
      <div style="display: flex; align-items: center; gap: 6px; font-size: 13px; line-height: 18px; color: ${L.onReviewContainer};">${ic('lock', 14, L.onReviewContainer, 2)}<span>Your words are saved. Nothing was guessed.</span></div>
    </div>
    <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted}; padding: 4px 4px 0;">Which activity was this?</div>
    <div style="display: flex; flex-direction: column; gap: 8px;">
      ${option(L, 'Replace furnace filter', 'Last logged Sep 12')}
      ${option(L, 'Choose another activity', null, 'search')}
      ${option(L, 'Create new activity', null, 'plus')}
    </div>
    <div style="display: flex; gap: 8px; flex-wrap: wrap; padding-top: 4px;">
      ${btn(L, 'Say it again', { kind: 'outline', icon: 'retry' })}
      ${btn(L, 'Decide later', { kind: 'text', color: L.muted })}
    </div>
  </div>`,
})));

// AI unavailable
const pipeline = (t) => `
  <div role="group" aria-label="Captured: done. Categorized: not yet." style="display: flex; align-items: center; gap: 10px;">
    <div style="display: flex; align-items: center; gap: 6px; font-size: 14px; line-height: 20px; font-weight: 700; color: ${t.ink};">
      <div style="width: 22px; height: 22px; border-radius: 11px; background: ${t.ink}; color: ${t.bg}; display: flex; align-items: center; justify-content: center;">${ic('check', 14, t.bg, 2.6)}</div><span>Captured</span></div>
    <div style="flex-grow: 1; height: 0; border-top: 2px dashed ${t.outlineStrong};"></div>
    <div style="display: flex; align-items: center; gap: 6px; font-size: 14px; line-height: 20px; font-weight: 600; color: ${t.muted};">
      <div style="width: 20px; height: 20px; border-radius: 11px; border: 1.5px dashed ${t.outlineStrong};"></div><span>Categorized — not yet</span></div>
  </div>`;

w('AiUnavailable.dc.html', doc(phone(L, {
  top: homeTop(L),
  nav: 'log',
  body: `
  <div role="button" style="margin: 0 16px; display: flex; align-items: center; gap: 10px; min-height: 48px; padding: 0 12px; border-radius: 12px; background: ${L.container}; color: ${L.ink}; font-size: 14px; line-height: 20px;">
    ${ic('chip', 20, L.muted)}<span style="flex-grow: 1;">On-device AI isn’t ready. Captures are still saved.</span>${ic('chev', 18, L.muted)}</div>
  <div style="display: flex; flex-direction: column; gap: 16px; padding: 16px 16px 20px; flex-shrink: 0;">
    <div role="status" style="border: 1.5px dashed ${L.outlineStrong}; border-radius: 16px; padding: 16px; display: flex; flex-direction: column; gap: 14px; background: ${L.surface};">
      ${pipeline(L)}
      ${quote(L, 'I changed the furnace filter', 22, L.ink)}
      <p style="font-size: 16px; line-height: 22px; font-weight: 600; color: ${L.ink};">Saved your words, but couldn't categorize them yet.</p>
      <p style="font-size: 14px; line-height: 20px; color: ${L.muted};">They’ll be categorized on this phone when on-device AI is available.</p>
    </div>
    <div style="display: flex; flex-direction: column; align-items: center; gap: 8px;">
      ${micButton(L, { size: 88 })}
      <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted};">Tap to speak</div>
    </div>
  </div>
  <div style="border-top: 1px solid ${L.outline};">
    ${sectionHead(L, 'Recent', allHistory(L))}
    ${row(L, { kind: 'pending', when: 'Today, 3:40 PM', quote: 'I changed the furnace filter', dev: 'phone', hl: true })}
    ${row(L, R.mow)}
  </div>`,
})));

// Capture failure (recognition)
w('CaptureFailure.dc.html', doc(phone(L, {
  top: homeTop(L), nav: 'log',
  body: `
  <div style="display: flex; flex-direction: column; gap: 24px; padding: 20px 16px 24px; flex-shrink: 0;">
    <div role="alert" style="background: ${L.errorContainer}; border-radius: 16px; padding: 16px; display: flex; flex-direction: column; gap: 12px;">
      <div style="display: flex; align-items: flex-start; gap: 10px;">
        ${ic('alert', 24, L.error, 2)}
        <div style="display: flex; flex-direction: column; gap: 4px;">
          <p style="font-size: 17px; line-height: 24px; font-weight: 600; color: ${L.onErrorContainer};">Couldn't make out any words.</p>
          <p style="font-size: 14px; line-height: 20px; color: ${L.onErrorContainer};">Nothing was saved.</p>
        </div>
      </div>
      <div style="display: flex; gap: 8px; flex-wrap: wrap;">
        ${btn(L, 'Try again', { icon: 'mic' }).replace(`background: ${L.primary}`, `background: ${L.error}`)}
        ${btn(L, 'Type instead', { kind: 'text', icon: 'keyboard', color: L.onErrorContainer })}
      </div>
    </div>
    <div style="display: flex; flex-direction: column; align-items: center; gap: 10px;">
      ${micButton(L, { size: 96 })}
      <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted};">Tap to speak</div>
    </div>
  </div>
  <div style="border-top: 1px solid ${L.outline};">
    ${sectionHead(L, 'Recent', allHistory(L))}
    ${row(L, R.mow)}${row(L, R.furnaceReview)}
  </div>`,
})));

// History
const filterChip = (t, label, on, count) => `<div role="radio" aria-checked="${on}" style="height: 36px; padding: 0 14px; border-radius: 8px; display: inline-flex; align-items: center; gap: 6px; font-size: 14px; line-height: 20px; font-weight: 600; box-sizing: border-box; ${on ? `background: ${t.ink}; color: ${t.bg};` : `border: 1px solid ${t.outlineStrong}; color: ${t.ink};`}">${on ? ic('check', 16, t.bg, 2.4) : ''}<span>${label}</span>${count ? `<span style="color: ${on ? t.bg : t.muted}; font-weight: 500;">${count}</span>` : ''}</div>`;

w('History.dc.html', doc(phone(L, {
  top: topBar(L, { title: 'History' }), nav: 'history',
  body: `
  <div style="display: flex; gap: 8px; padding: 4px 16px 12px; flex-wrap: wrap;">
    ${filterChip(L, 'All', true)}${filterChip(L, 'Needs review', false, '1')}${filterChip(L, 'Not categorized', false, '0')}
  </div>
  <div style="border-top: 1px solid ${L.outline};">
    ${row(L, R.mow)}${row(L, R.furnaceReview)}${row(L, R.pool)}${row(L, R.filter)}${row(L, R.gutters)}${row(L, R.oil)}${row(L, R.mow2)}
  </div>`,
})));

// Occurrence sheet (tap on a history row)
const kv = (t, k, v) => `<div style="display: flex; justify-content: space-between; gap: 16px; padding: 10px 0; border-bottom: 1px solid ${t.outline}; font-size: 15px; line-height: 20px;"><span style="color: ${t.muted};">${k}</span><span style="color: ${t.ink}; font-weight: 600; text-align: right;">${v}</span></div>`;
const menuRow = (t, icon, label, color) => `<div role="button" style="display: flex; align-items: center; gap: 16px; min-height: 56px; padding: 0 8px; color: ${color || t.ink}; font-size: 16px; line-height: 22px; font-weight: 600;">${ic(icon, 22, color || t.ink)}<span style="flex-grow: 1;">${label}</span></div>`;

w('OccurrenceSheet.dc.html', doc(phone(L, {
  top: topBar(L, { title: 'History' }), nav: 'history',
  body: `
  <div aria-hidden="true" style="opacity: 0.35;">
    <div style="display: flex; gap: 8px; padding: 4px 16px 12px;">${filterChip(L, 'All', true)}${filterChip(L, 'Needs review', false, '1')}</div>
    <div style="border-top: 1px solid ${L.outline};">${row(L, R.mow)}${row(L, R.furnaceReview)}</div>
  </div>
  <div style="position: absolute; inset: 0; background: rgba(34,32,28,0.32);"></div>
  <div role="dialog" aria-label="Logged activity" style="position: absolute; left: 0; right: 0; bottom: 0; background: ${L.surface}; border-radius: 28px 28px 0 0; padding: 12px 24px 16px; display: flex; flex-direction: column; gap: 14px;">
    <div style="align-self: center; width: 32px; height: 4px; border-radius: 2px; background: ${L.outlineStrong};"></div>
    <div style="display: flex; flex-direction: column; gap: 6px;">
      <div style="font-size: 12px; line-height: 16px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: ${L.muted};">Your words</div>
      ${quote(L, 'I cut the grass this afternoon', 22, L.ink)}
    </div>
    <div>
      ${kv(L, 'Activity', 'Mow lawn')}${kv(L, 'When', 'Today, afternoon')}${kv(L, 'State', 'Completed')}${kv(L, 'Captured', 'Today, 3:12 PM · Phone')}
    </div>
    <div style="display: flex; flex-direction: column;">
      ${menuRow(L, 'edit', 'Edit interpretation')}
      ${menuRow(L, 'activity', 'Open “Mow lawn”')}
      ${menuRow(L, 'hide', 'Remove from history', L.error)}
    </div>
  </div>`,
})));

// Activity detail
const occ = (t, when, q, dev) => `<div role="button" style="display: flex; gap: 12px; align-items: flex-start; padding: 12px 8px 12px 20px; border-bottom: 1px solid ${t.outline};">
  <div style="flex-grow: 1; display: flex; flex-direction: column; gap: 2px;"><div style="font-size: 16px; line-height: 22px; font-weight: 600; color: ${t.ink};">${when}</div>${quote(t, q)}</div>${deviceIcon(t, dev)}</div>`;
const phraseChip = (t, p) => `<div style="padding: 6px 12px; border-radius: 8px; background: ${t.container};">${quote(t, p, 14, t.ink)}</div>`;

w('ActivityDetail.dc.html', doc(phone(L, {
  top: topBar(L, { title: '', lead: 'back', actions: iconBtn(L, 'edit', 'Rename activity') }), nav: 'history',
  body: `
  <div style="padding: 0 20px 20px; display: flex; flex-direction: column; gap: 18px;">
    <h1 style="font-size: 32px; line-height: 40px; font-weight: 600; letter-spacing: -0.02em; color: ${L.ink};">Mow lawn</h1>
    <div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px;">
      <div style="background: ${L.surface}; border: 1px solid ${L.outline}; border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 2px;">
        <div style="font-size: 13px; line-height: 18px; color: ${L.muted}; font-weight: 600;">Last logged</div>
        <div style="font-size: 18px; line-height: 24px; font-weight: 600; color: ${L.ink};">Today, afternoon</div></div>
      <div style="background: ${L.surface}; border: 1px solid ${L.outline}; border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 2px;">
        <div style="font-size: 13px; line-height: 18px; color: ${L.muted}; font-weight: 600;">Logged</div>
        <div style="font-size: 18px; line-height: 24px; font-weight: 600; color: ${L.ink};">14 times</div></div>
    </div>
    <div style="display: flex; flex-direction: column; gap: 8px;">
      <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.muted};">Words that mean this</div>
      <div style="display: flex; flex-wrap: wrap; gap: 8px;">${phraseChip(L, 'cut the grass')}${phraseChip(L, 'mowed the lawn')}${phraseChip(L, 'finished mowing')}${phraseChip(L, 'the grass is done')}</div>
    </div>
  </div>
  <div style="border-top: 1px solid ${L.outline};">
    ${sectionHead(L, 'Occurrences · newest first')}
    ${occ(L, 'Today, afternoon', 'I cut the grass this afternoon', 'phone')}
    ${occ(L, 'Sep 6, 6:15 PM', 'Mowed the lawn', 'watch')}
    ${occ(L, 'Aug 30, 10:02 AM', 'Finished mowing', 'watch')}
    ${occ(L, 'Aug 23, morning', 'The grass is done', 'phone')}
  </div>`,
})));

// Correction
const field = (t, label, value, { tag, icon = 'down', focused } = {}) => `<div style="display: flex; flex-direction: column; gap: 6px;">
  <div style="font-size: 13px; line-height: 18px; font-weight: 600; color: ${focused ? t.primary : t.muted};">${label}</div>
  <div role="button" style="min-height: 56px; box-sizing: border-box; border-radius: 12px; border: ${focused ? `2px solid ${t.primary}` : `1px solid ${t.outlineStrong}`}; padding: 0 12px 0 16px; display: flex; align-items: center; gap: 10px; background: ${t.surface};">
    <span style="flex-grow: 1; font-size: 17px; line-height: 24px; font-weight: 600; color: ${t.ink};">${value}</span>${tag || ''}${ic(icon, 22, t.muted)}</div></div>`;
const seg = (t, opts, on) => `<div role="radiogroup" style="display: grid; grid-template-columns: repeat(${opts.length}, minmax(0, 1fr)); border: 1px solid ${t.outlineStrong}; border-radius: 24px; overflow: hidden;">${opts.map((o, i) => `<div role="radio" aria-checked="${i === on}" style="height: 48px; display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 15px; font-weight: 600; ${i === on ? `background: ${t.primaryContainer}; color: ${t.onPrimaryContainer};` : `color: ${t.ink};`} ${i ? `border-left: 1px solid ${t.outlineStrong};` : ''}">${i === on ? ic('check', 18, t.onPrimaryContainer, 2.2) : ''}<span>${o}</span></div>`).join('')}</div>`;

w('Correction.dc.html', doc(phone(L, {
  top: topBar(L, { title: 'Edit interpretation', lead: 'close', actions: `<div style="padding-right: 8px;">${btn(L, 'Save')}</div>` }),
  body: `
  <div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; margin: 4px 16px 0; border: 1px solid ${L.outline}; border-radius: 16px; overflow: hidden; background: ${L.surface};">
    <div style="padding: 14px; display: flex; flex-direction: column; gap: 8px; border-right: 1px solid ${L.outline};">
      <div style="display: flex; align-items: center; gap: 6px; font-size: 12px; line-height: 16px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: ${L.muted};">${ic('lock', 14, L.muted, 2)}<span>Original capture</span></div>
      ${quote(L, 'I edged the lawn.', 20, L.ink)}
      <div style="font-size: 13px; line-height: 18px; color: ${L.muted};">Today, 4:05 PM · Watch</div>
    </div>
    <div style="padding: 14px; display: flex; flex-direction: column; gap: 8px; background: ${L.bg};">
      <div style="font-size: 12px; line-height: 16px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: ${L.muted};">Interpreted as</div>
      <div style="font-size: 20px; line-height: 26px; font-weight: 600; color: ${L.ink};">Mow lawn</div>
      <div style="font-size: 13px; line-height: 18px; color: ${L.muted};">Completed · Today, 4:05 PM</div>
    </div>
  </div>
  <div style="display: flex; align-items: center; gap: 8px; padding: 10px 20px 0; font-size: 13px; line-height: 18px; color: ${L.muted};">
    ${ic('lock', 14, L.muted, 2)}<span>The original stays exactly as captured. Your change is recorded alongside it.</span></div>
  <div style="display: flex; flex-direction: column; gap: 18px; padding: 22px 16px 16px;">
    <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.ink};">Correct to</div>
    ${field(L, 'Activity', 'Edge lawn', { focused: true, tag: `<span style="font-size: 12px; font-weight: 700; padding: 3px 8px; border-radius: 6px; background: ${L.primaryContainer}; color: ${L.onPrimaryContainer};">New activity</span>` })}
    <div style="display: flex; flex-direction: column; border: 1px solid ${L.outline}; border-radius: 12px; background: ${L.surface}; margin-top: -10px;">
      <div style="display: flex; align-items: center; gap: 12px; min-height: 52px; padding: 0 14px; border-bottom: 1px solid ${L.outline}; font-size: 15px; color: ${L.ink};">${ic('plus', 20, L.primary)}<span style="flex-grow: 1;">Create “Edge lawn”</span></div>
      <div style="display: flex; align-items: center; gap: 12px; min-height: 52px; padding: 0 14px; border-bottom: 1px solid ${L.outline}; font-size: 15px; color: ${L.ink};">${ic('activity', 20, L.muted)}<span style="flex-grow: 1;">Mow lawn</span><span style="font-size: 13px; color: ${L.muted};">current</span></div>
      <div style="display: flex; align-items: center; gap: 12px; min-height: 52px; padding: 0 14px; font-size: 15px; color: ${L.ink};">${ic('activity', 20, L.muted)}<span style="flex-grow: 1;">Trim hedges</span></div>
    </div>
    ${field(L, 'When', 'Today, 4:05 PM')}
    <div style="display: flex; flex-direction: column; gap: 6px;">
      <div style="font-size: 13px; line-height: 18px; font-weight: 600; color: ${L.muted};">State</div>
      ${seg(L, ['Completed', 'In progress'], 0)}
    </div>
  </div>`,
})));

// Ask history — thread
const userMsg = (t, text) => `<div style="align-self: flex-end; max-width: 80%; background: ${t.containerHigh}; color: ${t.ink}; border-radius: 20px 20px 6px 20px; padding: 10px 14px; font-size: 16px; line-height: 22px;">${text}</div>`;
const answer = (t, inner) => `<div role="article" style="align-self: stretch; background: ${t.surface}; border: 1px solid ${t.outline}; border-radius: 16px; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px;">${inner}</div>`;
const source = (t, text) => `<div role="button" style="display: flex; align-items: center; gap: 8px; min-height: 40px; margin: 2px -6px -6px; padding: 0 6px; border-top: 1px solid ${t.outline}; font-size: 13px; line-height: 18px; color: ${t.muted};">${ic('activity', 16, t.muted)}<span style="flex-grow: 1;">${text}</span>${ic('chev', 16, t.muted)}</div>`;

w('AskHistory.dc.html', doc(phone(L, {
  top: topBar(L, { title: 'Ask', actions: btn(L, 'Clear', { kind: 'text', color: L.muted }) }),
  nav: 'ask',
  bottom: `<div style="padding: 8px 16px 12px; display: flex; gap: 8px; align-items: center; background: ${L.bg}; border-top: 1px solid ${L.outline}; flex-shrink: 0;">
    <div style="flex-grow: 1; height: 52px; border-radius: 26px; background: ${L.container}; display: flex; align-items: center; padding: 0 8px 0 18px; gap: 8px; font-size: 16px; color: ${L.muted};"><span style="flex-grow: 1;">Ask about your history</span>${iconBtn(L, 'mic', 'Ask by voice', L.ink)}</div>
    <div role="button" aria-label="Send" style="width: 52px; height: 52px; border-radius: 26px; background: ${L.primary}; color: ${L.onPrimary}; display: flex; align-items: center; justify-content: center;">${ic('send', 22)}</div>
  </div>`,
  body: `
  <div style="display: flex; flex-direction: column; gap: 12px; padding: 4px 16px 16px; justify-content: flex-end; flex-grow: 1;">
    ${userMsg(L, 'When did I last change the furnace filter?')}
    ${answer(L, `
      <p style="font-size: 17px; line-height: 24px; color: ${L.ink};">Last logged furnace filter replacement: <strong style="font-weight: 700;">September 12, 2026 at 9:40 AM.</strong></p>
      <p style="font-size: 14px; line-height: 20px; color: ${L.muted};">Previous: June 3, 2026 · 101 days between</p>
      ${source(L, 'From <strong style="color: ' + L.ink + '; font-weight: 600;">Replace furnace filter</strong> · 4 logged')}`)}
    ${userMsg(L, 'How many times did I mow in August?')}
    ${answer(L, `
      <p style="font-size: 17px; line-height: 24px; color: ${L.ink};"><strong style="font-weight: 700;">5 times</strong> in August 2026.</p>
      <p style="font-size: 14px; line-height: 20px; color: ${L.muted};">Aug 2 · Aug 9 · Aug 16 · Aug 23 · Aug 30</p>
      ${source(L, 'From <strong style="color: ' + L.ink + '; font-weight: 600;">Mow lawn</strong> · Aug 1–31')}`)}
    ${userMsg(L, 'I flushed the water heater this morning')}
    <div role="article" style="background: ${L.primaryContainer}; border-radius: 16px; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px;">
      <div style="font-size: 13px; line-height: 18px; font-weight: 700; color: ${L.onPrimaryContainer};">That sounds like something you did, not a question.</div>
      <div style="display: flex; flex-direction: column; gap: 2px;">
        <div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${L.onPrimaryContainer};">New activity?</div>
        <div style="font-size: 20px; line-height: 26px; font-weight: 600; color: ${L.onPrimaryContainer};">Flush water heater <span style="font-weight: 500; font-size: 16px;">— this morning</span></div>
      </div>
      <div style="display: flex; gap: 8px; flex-wrap: wrap;">
        ${btn(L, 'Log it', { icon: 'check' })}${btn(L, 'Choose existing', { kind: 'text', color: L.onPrimaryContainer })}${btn(L, 'Rename', { kind: 'text', color: L.onPrimaryContainer })}
      </div>
    </div>
  </div>`,
})));

// Empty states: two phones in one artboard
const emptyHistory = phone(L, {
  top: topBar(L, { title: 'History' }), nav: 'history',
  body: `<div style="flex-grow: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20px; padding: 0 40px 80px; text-align: center;">
    <div style="width: 96px; height: 96px; border-radius: 48px; border: 1.5px dashed ${L.outlineStrong}; display: flex; align-items: center; justify-content: center; color: ${L.muted};">${ic('history', 40, L.muted, 1.5)}</div>
    <p style="font-size: 20px; line-height: 28px; font-weight: 500; color: ${L.ink}; text-wrap: balance;">No activities logged yet. Tap the microphone and say what you just did.</p>
    ${btn(L, 'Log something', { icon: 'mic' })}
  </div>`,
});
const emptyAsk = phone(L, {
  top: topBar(L, { title: 'Ask' }), nav: 'ask',
  bottom: `<div style="padding: 8px 16px 12px; display: flex; gap: 8px; align-items: center; border-top: 1px solid ${L.outline}; flex-shrink: 0;">
    <div style="flex-grow: 1; height: 52px; border-radius: 26px; background: ${L.container}; display: flex; align-items: center; padding: 0 8px 0 18px; font-size: 16px; color: ${L.muted};"><span style="flex-grow: 1;">Ask about your history</span>${iconBtn(L, 'mic', 'Ask by voice', L.ink)}</div>
    <div style="width: 52px; height: 52px; border-radius: 26px; background: ${L.primary}; color: ${L.onPrimary}; display: flex; align-items: center; justify-content: center;">${ic('send', 22)}</div></div>`,
  body: `<div style="display: flex; flex-direction: column; gap: 12px; padding: 4px 16px 16px; justify-content: flex-end; flex-grow: 1;">
    ${userMsg(L, 'When did I last change the oil?')}
    ${answer(L, `<div style="display: flex; gap: 10px; align-items: flex-start;">${ic('pending', 22, L.muted)}<p style="font-size: 17px; line-height: 24px; color: ${L.ink};">There isn't enough history yet to answer that.</p></div>`)}
  </div>`,
});
w('EmptyStates.dc.html', doc(`<div style="display: flex; gap: 56px; padding: 0; background: ${L.bg}; width: ${PW * 2 + 56}px;">${emptyHistory}${emptyAsk}</div>`));

// Settings / diagnostics
const group = (t, title, inner) => `<div style="display: flex; flex-direction: column; gap: 8px;"><div style="font-size: 14px; line-height: 20px; font-weight: 600; color: ${t.muted}; padding: 0 4px;">${title}</div><div style="background: ${t.surface}; border: 1px solid ${t.outline}; border-radius: 16px; overflow: hidden;">${inner}</div></div>`;
const srow = (t, { icon, label, value, status, statusColor, last, sub, chev }) => `<div style="display: flex; align-items: flex-start; gap: 14px; padding: 14px 14px 14px 16px; min-height: 56px; box-sizing: border-box; ${last ? '' : `border-bottom: 1px solid ${t.outline};`}">
  ${icon ? `<div style="padding-top: 1px;">${ic(icon, 22, t.muted)}</div>` : ''}
  <div style="flex-grow: 1; display: flex; flex-direction: column; gap: 3px; min-width: 0;">
    <div style="font-size: 16px; line-height: 22px; font-weight: 600; color: ${t.ink};">${label}</div>
    ${value ? `<div style="font-size: 14px; line-height: 20px; color: ${t.muted};">${value}</div>` : ''}
    ${sub || ''}
  </div>
  ${status ? `<div style="display: flex; align-items: center; gap: 6px; font-size: 13px; line-height: 20px; font-weight: 700; color: ${statusColor}; white-space: nowrap;">${status}</div>` : ''}
  ${chev ? ic('chev', 18, t.muted) : ''}
</div>`;
const okStatus = (t, label = 'Available') => `${ic('saved', 16, t.primary, 2)}<span>${label}</span>`;

w('Settings.dc.html', doc(phone(L, {
  top: topBar(L, { title: 'Settings', lead: 'back' }),
  body: `<div style="display: flex; flex-direction: column; gap: 20px; padding: 4px 16px 16px;">
    ${group(L, 'On-device interpretation', `
      ${srow(L, { icon: 'chip', label: 'Gemini Nano', value: 'Downloading model · 62% of 1.1 GB', status: `${ic('pending', 16, L.muted, 2)}<span>Not ready</span>`, statusColor: L.muted,
        sub: `<div role="progressbar" aria-valuenow="62" aria-valuemin="0" aria-valuemax="100" style="height: 6px; border-radius: 3px; background: ${L.container}; margin-top: 6px; overflow: hidden;"><div style="width: 62%; height: 6px; background: ${L.primary}; border-radius: 3px;"></div></div>
        <p style="font-size: 13px; line-height: 18px; color: ${L.muted}; margin-top: 6px;">Downloads on Wi‑Fi. Captures are saved meanwhile.</p>` })}
      ${srow(L, { label: 'Structured output', value: 'Checked when the model is ready', status: `<span>—</span>`, statusColor: L.muted })}
      ${srow(L, { label: '3 captures waiting to be categorized', value: 'They’ll be categorized automatically', chev: true, last: true })}`)}
    ${group(L, 'Speech', srow(L, { icon: 'mic', label: 'Speech recognition', value: 'On this phone · English (US)', status: okStatus(L), statusColor: L.primary, last: true }))}
    ${group(L, 'Watch', srow(L, { icon: 'watch', label: 'OnePlus Watch 3', value: 'Connected · last capture received 2:58 PM', status: okStatus(L, 'Connected'), statusColor: L.primary, last: true }))}
    ${group(L, 'Privacy', `
      ${srow(L, { icon: 'shield', label: 'Your words stay on this phone', value: 'Nothing is sent to the cloud.' })}
      ${srow(L, { icon: 'copy', label: 'Copy technical report', value: 'Device and model status only — no activity text', last: true })}`)}
    <p style="font-size: 12px; line-height: 16px; color: ${L.muted}; padding: 0 4px;">Version 0.1.0 · Interpreter prompt v1 · Database schema v1</p>
  </div>`,
})));

w('SettingsUnsupported.dc.html', doc(phone(L, {
  top: topBar(L, { title: 'Settings', lead: 'back' }),
  body: `<div style="display: flex; flex-direction: column; gap: 20px; padding: 4px 16px 16px;">
    ${group(L, 'On-device interpretation', `
      ${srow(L, { icon: 'chip', label: 'Gemini Nano', value: 'This phone can’t run on-device AI.', status: `${ic('alert', 16, L.error, 2)}<span>Not supported</span>`, statusColor: L.error })}
      <div style="padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; background: ${L.bg};">
        <p style="font-size: 15px; line-height: 22px; color: ${L.ink};">Your words are still saved every time. They won’t be categorized automatically — you can choose the activity for each one from History.</p>
        <p style="font-size: 13px; line-height: 18px; color: ${L.muted};">Activity Ledger never falls back to cloud AI.</p>
      </div>`)}
    ${group(L, 'Captures', srow(L, { icon: 'pending', label: '12 captures not categorized', value: 'Open in History', chev: true, last: true }))}
    ${group(L, 'Speech', srow(L, { icon: 'mic', label: 'Speech recognition', value: 'On this phone · English (US)', status: okStatus(L), statusColor: L.primary, last: true }))}
    ${group(L, 'Watch', srow(L, { icon: 'watch', label: 'No watch paired', value: 'Install Activity Ledger on a Wear OS watch to log from your wrist.', last: true }))}
  </div>`,
})));

// Accessibility: dark + 200% font scale + TalkBack annotations
const S = 2; // font scale for text only
const bigRow = (t, r) => `<div style="padding: 14px 16px 14px 20px; border-bottom: 1px solid ${t.outline}; display: flex; gap: 12px;">
  <div style="flex-grow: 1; display: flex; flex-direction: column; gap: 4px;">
    <div style="font-size: ${17 * 1.6}px; line-height: ${24 * 1.6}px; font-weight: 600; color: ${t.ink};">${r.name}</div>
    <div style="font-size: ${14 * 1.6}px; line-height: ${20 * 1.6}px; color: ${t.muted};">${r.when}</div>
    ${r.kind === 'progress' ? `<div style="display: flex; gap: 8px; align-items: center; color: ${t.primary}; font-size: ${13 * 1.6}px; line-height: 30px; font-weight: 600;">${ic('progress', 26, t.primary, 2)}<span>In progress</span></div>` : ''}
    <p class="ev" style="font-size: ${14 * 1.6}px; line-height: ${20 * 1.6}px; color: ${t.muted};">“${r.quote}”</p>
  </div>${deviceIcon(t, r.dev)}</div>`;
const a11yPhone = phone(D, {
  top: topBar(D, { title: 'Activity Ledger', actions: iconBtn(D, 'settings', 'Settings and diagnostics') }).replace('font-size: 22px; line-height: 28px', 'font-size: 30px; line-height: 38px'),
  nav: 'log',
  body: `
  <div style="display: flex; flex-direction: column; align-items: center; gap: 16px; padding: 12px 20px 20px; flex-shrink: 0;">
    <p style="font-size: 40px; line-height: 50px; font-weight: 500; color: ${D.ink}; text-align: center;">Say what you just did.</p>
    ${micButton(D, { size: 112 })}
    <div style="font-size: 26px; line-height: 34px; font-weight: 600; color: ${D.muted};">Tap to speak</div>
  </div>
  <div style="border-top: 1px solid ${D.outline};">
    ${bigRow(D, R.mow)}${bigRow(D, R.pool)}
  </div>`,
});
const note = (n, title, body) => `<div style="display: flex; gap: 14px; align-items: flex-start;">
  <div style="width: 28px; height: 28px; border-radius: 14px; background: ${L.ink}; color: ${L.bg}; font-size: 14px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0;">${n}</div>
  <div style="display: flex; flex-direction: column; gap: 4px;"><div style="font-size: 16px; line-height: 22px; font-weight: 700; color: ${L.ink};">${title}</div><div style="font-size: 15px; line-height: 22px; color: ${L.muted}; text-wrap: pretty;">${body}</div></div></div>`;
const pin = (n, top, left) => `<div style="position: absolute; top: ${top}px; left: ${left}px; width: 28px; height: 28px; border-radius: 14px; background: #F3E4C4; color: #3D2804; border: 2px solid #22201C; font-size: 14px; font-weight: 700; display: flex; align-items: center; justify-content: center;">${n}</div>`;

w('Accessibility.dc.html', doc(`<div style="width: 960px; height: ${PH}px; display: flex; gap: 56px; background: ${L.bg};">
  <div style="position: relative; flex-shrink: 0;">${a11yPhone}${pin(1, 300, 300)}${pin(2, 578, 12)}${pin(3, 690, 360)}${pin(4, 868, 36)}${pin(5, 180, 360)}</div>
  <div style="display: flex; flex-direction: column; gap: 22px; padding: 40px 24px 0 0; max-width: 460px;">
    <div style="display: flex; flex-direction: column; gap: 6px;">
      <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: ${L.muted};">Dark theme · 200% font scale · TalkBack</div>
      <h2 style="font-size: 26px; line-height: 32px; font-weight: 600; color: ${L.ink};">Home still works when text is huge</h2>
    </div>
    ${note(1, 'Mic button has a spoken name and state', '“Log by voice, button.” While listening: “Stop listening, button” and the transcript region is a polite live region, so what was heard is read out. 112dp target, never shrinks with text.')}
    ${note(2, 'Each row reads as one sentence', 'Merged semantics: “Mow lawn. Today, afternoon. Logged from phone. Your words: I cut the grass this afternoon.” Rows grow in height; nothing truncates the original words.')}
    ${note(3, 'State is never colour alone', '“In progress”, “Needs review”, “Not categorized yet” each pair an icon shape with a text label. Grayscale-legible in both themes.')}
    ${note(4, 'Navigation labels always visible', 'Log / History / Ask labels stay shown (no icon-only mode). Tabs expose selected state to TalkBack.')}
    ${note(5, 'Feedback is announced', 'Success, review and error cards are live regions: “Saved. Mow lawn, this morning. Undo available.” Undo timeout is suspended while TalkBack is on (see Decisions · D5).')}
    <div style="font-size: 14px; line-height: 21px; color: ${L.muted}; border-top: 1px solid ${L.outline}; padding-top: 16px;">Touch targets ≥ 48dp everywhere · text uses sp and reflows, containers use min-height not height · contrast ≥ 4.5:1 for text on every token pair in both themes.</div>
  </div>
</div>`));

// ============ WATCH ============
const WS = 300; // 1.3× of ~230dp round display
const watchFace = (inner, ambient = false, label = '') => `<div style="display: flex; flex-direction: column; align-items: center; gap: 12px;">
  <div role="img" aria-label="${label}" style="width: ${WS}px; height: ${WS}px; border-radius: ${WS / 2}px; background: #000; box-shadow: 0 0 0 10px ${ambient ? '#2A2724' : '#3A3631'}; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; text-align: center; padding: 40px; box-sizing: border-box; overflow: hidden; position: relative;">${inner}</div></div>`;
const G = '#8FC9AE', GC = '#1E4838', AMB = '#B8B2A8', AMBDIM = '#6E6960', REV = '#E4BA6C', ERR = '#F0A193';
const wTitle = (txt, color = '#F2EEE6', size = 26) => `<p style="font-size: ${size}px; line-height: ${Math.round(size * 1.2)}px; font-weight: 600; color: ${color}; text-wrap: balance;">${txt}</p>`;
const wSub = (txt, color = '#A69F93') => `<p style="font-size: 17px; line-height: 22px; color: ${color}; text-wrap: balance;">${txt}</p>`;

const W = {
  listening: [
    `<div style="width: 132px; height: 132px; border-radius: 66px; background: ${GC}; display: flex; align-items: center; justify-content: center;"><div style="width: 96px; height: 96px; border-radius: 48px; background: ${G}; display: flex; align-items: center; justify-content: center;">${ic('mic', 48, '#0B2A1E', 2)}</div></div>${wTitle('Listening…', '#F2EEE6', 22)}`,
    `<div style="width: 96px; height: 96px; border-radius: 48px; border: 2px solid ${AMB}; display: flex; align-items: center; justify-content: center;">${ic('mic', 44, AMB, 1.6)}</div>${wTitle('Listening…', AMB, 22)}`,
  ],
  queued: [
    `${ic('queued', 56, '#F2EEE6', 2)}${wTitle('Queued for phone')}${wSub('Will send when phone reconnects')}`,
    `${ic('queued', 48, AMB, 1.5)}${wTitle('Queued for phone', AMB)}`,
  ],
  success: [
    `<div style="width: 84px; height: 84px; border-radius: 42px; background: ${G}; display: flex; align-items: center; justify-content: center;">${ic('check', 52, '#0B2A1E', 2.6)}</div>${wTitle('Mow lawn', '#F2EEE6', 30)}`,
    `<div style="width: 72px; height: 72px; border-radius: 36px; border: 2px solid ${AMB}; display: flex; align-items: center; justify-content: center;">${ic('check', 42, AMB, 1.8)}</div>${wTitle('Mow lawn', AMB, 30)}`,
  ],
  review: [
    `${ic('review', 60, REV, 2)}${wTitle('Saved — review on phone', '#F2EEE6', 24)}`,
    `${ic('review', 50, AMB, 1.5)}${wTitle('Saved — review on phone', AMB, 24)}`,
  ],
  failure: [
    `${ic('alert', 56, ERR, 2)}${wTitle("Couldn't capture.", '#F2EEE6', 24)}<div style="display: flex; align-items: center; gap: 8px; height: 48px; padding: 0 20px; border-radius: 24px; background: #4B221B; color: #FAD9D2; font-size: 18px; font-weight: 600;">${ic('retry', 20, '#FAD9D2', 2)}<span>Tap to retry</span></div>`,
    `${ic('alert', 48, AMB, 1.5)}${wTitle("Couldn't capture.", AMB, 24)}${wSub('Tap to retry', AMBDIM)}`,
  ],
};
const wcol = (key, name, haptic) => `<div style="display: flex; flex-direction: column; gap: 22px; align-items: center; width: ${WS + 20}px;">
  <div style="display: flex; flex-direction: column; align-items: center; gap: 4px;">
    <div style="font-size: 20px; line-height: 26px; font-weight: 700; color: #EDE8DF;">${name}</div>
    <div style="font-size: 13px; line-height: 18px; color: #A69F93;">Haptic: ${haptic}</div></div>
  ${watchFace(W[key][0], false, name)}
  <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: #7A7368;">Ambient</div>
  ${watchFace(W[key][1], true, name + ' (ambient)')}
</div>`;

const complication = `<div style="display: flex; gap: 48px; align-items: center; padding-top: 12px; border-top: 1px solid #403B35;">
  ${watchFace(`<div style="font-size: 64px; line-height: 64px; font-weight: 500; color: #F2EEE6; letter-spacing: -0.02em;">4:05</div><div style="font-size: 16px; color: #A69F93;">Tue 15</div>
    <div role="button" aria-label="Log activity" style="position: absolute; bottom: 36px; left: ${WS / 2 - 36}px; width: 72px; height: 72px; border-radius: 36px; background: #282521; border: 2px solid ${G}; display: flex; align-items: center; justify-content: center;">${ic('mic', 32, G, 2)}</div>`, false, 'Watch face with capture complication')}
  ${watchFace(`<div style="font-size: 64px; line-height: 64px; font-weight: 500; color: #F2EEE6; letter-spacing: -0.02em;">4:05</div><div style="font-size: 16px; color: #A69F93;">Tue 15</div>
    <div role="button" aria-label="Log activity. 2 entries waiting for phone." style="position: absolute; bottom: 36px; left: ${WS / 2 - 36}px; width: 72px; height: 72px; border-radius: 36px; background: #282521; border: 2px solid #7A7368; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 0;">${ic('mic', 24, '#EDE8DF', 2)}<div style="display: flex; align-items: center; gap: 2px; font-size: 14px; font-weight: 700; color: #EDE8DF;">${ic('queued', 12, '#EDE8DF', 2.4)}2</div></div>`, false, 'Complication with 2 queued')}
  <div style="display: flex; flex-direction: column; gap: 10px; max-width: 520px;">
    <div style="font-size: 20px; line-height: 26px; font-weight: 700; color: #EDE8DF;">First entry surface: launcher + complication</div>
    <p style="font-size: 16px; line-height: 24px; color: #A69F93;">One tap on the watch face opens capture straight into listening. The complication is a monochromatic mic; while captures wait for the phone it adds a queued count (never a review count — review lives on the phone). Tile comes later. Rationale in Decisions · D2.</p>
    <p style="font-size: 14px; line-height: 21px; color: #7A7368;">Shown at 1.3× (≈230dp round display). Ambient: black ground, outline-only glyphs, no brand fills, text ≤ #B8B2A8, under 10% lit pixels.</p>
  </div>
</div>`;

w('WatchStates.dc.html', doc(`<div style="width: 1760px; background: #0E0D0C; padding: 48px 56px 56px; box-sizing: border-box; display: flex; flex-direction: column; gap: 40px; font-family: ${SANS};">
  <div style="display: flex; flex-direction: column; gap: 6px;">
    <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: #7A7368;">Wear OS · WATCH_SPEC §11</div>
    <h2 style="font-size: 32px; line-height: 40px; font-weight: 600; color: #EDE8DF;">Five states, readable in a glance</h2>
  </div>
  <div style="display: flex; gap: 12px; justify-content: space-between;">
    ${wcol('listening', 'Listening', 'none (mic opening is the cue)')}
    ${wcol('queued', 'Queued', 'one short tick')}
    ${wcol('success', 'Success', 'two short ticks')}
    ${wcol('review', 'Needs review', 'two short ticks')}
    ${wcol('failure', 'Failure', 'one long pulse')}
  </div>
  ${complication}
</div>`, '#0E0D0C'));

// ============ SYSTEM ============
const sw = (hex, role, onHex) => `<div style="display: flex; flex-direction: column; gap: 6px;">
  <div style="height: 56px; border-radius: 10px; background: ${hex}; border: 1px solid rgba(0,0,0,0.08); display: flex; align-items: flex-end; padding: 6px 8px; box-sizing: border-box; font-size: 12px; font-weight: 600; color: ${onHex || 'transparent'};">${onHex ? 'Aa' : ''}</div>
  <div style="font-size: 12px; line-height: 16px; font-weight: 600; color: ${L.ink};">${role}</div>
  <div style="font-size: 11px; line-height: 14px; color: ${L.muted}; font-family: ui-monospace, Consolas, monospace;">${hex}</div></div>`;
const palette = (t, label) => `<div style="display: flex; flex-direction: column; gap: 12px;">
  <div style="font-size: 14px; font-weight: 700; color: ${L.ink};">${label}</div>
  <div style="display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px;">
    ${sw(t.bg, 'background', t.ink)}${sw(t.surface, 'surface', t.ink)}${sw(t.container, 'surfaceContainer', t.ink)}${sw(t.outline, 'outlineVariant')}${sw(t.ink, 'onSurface', t.bg)}${sw(t.muted, 'onSurfaceVariant', t.bg)}
    ${sw(t.primary, 'primary', t.onPrimary)}${sw(t.primaryContainer, 'primaryContainer', t.onPrimaryContainer)}${sw(t.review, 'review (custom)', t.bg)}${sw(t.reviewContainer, 'reviewContainer', t.onReviewContainer)}${sw(t.error, 'error', t.bg)}${sw(t.errorContainer, 'errorContainer', t.onErrorContainer)}
  </div></div>`;
const typeRow = (role, spec, sample, style) => `<div style="display: grid; grid-template-columns: 180px minmax(0, 1fr); gap: 16px; align-items: baseline; padding: 10px 0; border-bottom: 1px solid ${L.outline};">
  <div style="display: flex; flex-direction: column;"><span style="font-size: 13px; font-weight: 700; color: ${L.ink};">${role}</span><span style="font-size: 12px; color: ${L.muted};">${spec}</span></div>
  <div style="${style} color: ${L.ink};">${sample}</div></div>`;
const vocab = (icon, color, label, meaning, container) => `<div style="display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 16px; align-items: center; padding: 10px 0; border-bottom: 1px solid ${L.outline};">
  <div style="display: inline-flex; align-items: center; gap: 8px; color: ${color}; font-size: 15px; font-weight: 700; ${container ? `background: ${container}; padding: 6px 10px; border-radius: 8px; justify-self: start;` : ''}">${ic(icon, 20, color, 2)}<span>${label}</span></div>
  <div style="font-size: 14px; line-height: 20px; color: ${L.muted};">${meaning}</div></div>`;

w('DesignSystem.dc.html', doc(`<div style="width: 1280px; background: ${L.bg}; padding: 56px; box-sizing: border-box; display: flex; flex-direction: column; gap: 48px;">
  <div style="display: flex; flex-direction: column; gap: 10px; max-width: 820px;">
    <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: ${L.muted};">Visual system · v0</div>
    <h1 style="font-size: 44px; line-height: 52px; font-weight: 600; letter-spacing: -0.025em; color: ${L.ink};">Two voices: your words, and what the app understood.</h1>
    <p style="font-size: 18px; line-height: 28px; color: ${L.muted}; text-wrap: pretty;">Material 3 structure with a quiet, paper-and-ink palette. The one signature move: <span class="ev" style="color: ${L.ink};">anything the user actually said is set in serif italic, in quotes</span> — interpretations, labels and controls are sans. Evidence is never styled like app output, so the original phrase is always recognisable at a glance.</p>
  </div>

  <div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 32px;">
    <div style="background: ${L.surface}; border: 1px solid ${L.outline}; border-radius: 16px; padding: 24px; display: flex; flex-direction: column; gap: 6px;">
      <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: ${L.muted};">Evidence · Source Serif 4 Italic</div>
      ${quote(L, 'I cut the grass this morning', 28, L.ink)}
      <div style="font-size: 13px; color: ${L.muted};">Always quoted. Never edited, truncated only with a way to expand.</div>
    </div>
    <div style="background: ${L.surface}; border: 1px solid ${L.outline}; border-radius: 16px; padding: 24px; display: flex; flex-direction: column; gap: 6px;">
      <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: ${L.muted};">Interpretation · Instrument Sans</div>
      <div style="font-size: 28px; line-height: 40px; font-weight: 600; color: ${L.ink};">Mow lawn — this morning</div>
      <div style="font-size: 13px; color: ${L.muted};">Canonical names, times, states, all UI chrome.</div>
    </div>
  </div>

  <div style="display: flex; flex-direction: column; gap: 24px;">
    <h2 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink};">Colour roles</h2>
    ${palette(L, 'Light')}
    ${palette(D, 'Dark')}
    <p style="font-size: 14px; line-height: 21px; color: ${L.muted}; max-width: 900px;">One accent (ledger green, primary). Review ochre is a <em>custom</em> role added via an extended colour scheme; error uses M3 error. Dynamic colour is off in MVP so state roles stay distinguishable and consistent. Dark theme follows the system setting. Text/background pairs meet 4.5:1.</p>
  </div>

  <div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 48px;">
    <div style="display: flex; flex-direction: column; gap: 8px;">
      <h2 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink}; margin-bottom: 8px;">Type scale (sp)</h2>
      ${typeRow('headlineMedium', '600 · 28/36', 'Say what you just did.', 'font-size: 28px; line-height: 36px; font-weight: 600;')}
      ${typeRow('titleLarge', '600 · 22/28', 'Activity Ledger', 'font-size: 22px; line-height: 28px; font-weight: 600;')}
      ${typeRow('titleMedium', '600 · 17/24', 'Replace furnace filter', 'font-size: 17px; line-height: 24px; font-weight: 600;')}
      ${typeRow('bodyLarge', '400 · 16/22', 'They’ll be categorized automatically.', 'font-size: 16px; line-height: 22px;')}
      ${typeRow('bodyMedium', '400 · 14/20', 'Sep 12, 9:40 AM', 'font-size: 14px; line-height: 20px;')}
      ${typeRow('labelLarge', '600 · 15/20', 'Undo', 'font-size: 15px; line-height: 20px; font-weight: 600;')}
      ${typeRow('evidence', 'Serif italic · 14–28', '“Changed the HVAC filter”', `font-family: ${SERIF}; font-style: italic; font-size: 18px; line-height: 26px;`)}
      <p style="font-size: 13px; line-height: 19px; color: ${L.muted}; margin-top: 8px;">Both families (SIL OFL) are bundled as font resources in the APK — no downloadable fonts, no network. Watch uses the Wear M3 default type for legibility.</p>
    </div>
    <div style="display: flex; flex-direction: column; gap: 8px;">
      <h2 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink}; margin-bottom: 8px;">State vocabulary</h2>
      ${vocab('saved', L.primary, 'Saved', 'Captured, interpreted, persisted. The quiet default — rows show no tag at all.')}
      ${vocab('progress', L.primary, 'In progress', 'ActivityState IN_PROGRESS (“I’m mowing the lawn”).')}
      ${vocab('review', L.review, 'Needs review', 'Interpretation unsafe. Words saved, no activity guessed.', L.reviewContainer)}
      ${vocab('pending', L.muted, 'Not categorized yet', 'Captured, not interpreted (AI unavailable). Dashed outline = incomplete.')}
      ${vocab('queued', L.muted, 'Queued', 'On the watch, waiting for the phone.')}
      ${vocab('alert', L.error, "Couldn't capture", 'Nothing saved. Always paired with a retry.', L.errorContainer)}
      <p style="font-size: 13px; line-height: 19px; color: ${L.muted}; margin-top: 8px;">Every state = distinct icon shape + text label + colour. Remove the colour and it still reads.</p>
      <h2 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink}; margin: 24px 0 8px;">Shape &amp; spacing</h2>
      <p style="font-size: 14px; line-height: 21px; color: ${L.muted};">Cards 16dp radius · fields/rows 12dp · chips 8dp · buttons full pill · capture button 112dp (96/88 when a feedback card is shown) · 16dp screen gutter · 48dp minimum target · list rows min 72dp.</p>
      <h2 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink}; margin: 24px 0 8px;">Not in this system</h2>
      <p style="font-size: 14px; line-height: 21px; color: ${L.muted};">No checkboxes, badges with unread counts, priority flags, due-date pickers, streak or score widgets, kanban columns, or calendar grids (UX_SPEC §15). No sparkle/“AI” iconography — interpretation is presented as a plain fact with its evidence beside it.</p>
    </div>
  </div>
</div>`));

// Decisions
const dec = (id, title, decision, why, detail) => `<div style="display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 20px; padding: 28px 0; border-top: 1px solid ${L.outline};">
  <div style="font-size: 28px; line-height: 34px; font-weight: 600; color: ${L.primary};">${id}</div>
  <div style="display: flex; flex-direction: column; gap: 10px;">
    <h3 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink};">${title}</h3>
    <p style="font-size: 17px; line-height: 26px; color: ${L.ink}; text-wrap: pretty;"><strong>Decision.</strong> ${decision}</p>
    <p style="font-size: 15px; line-height: 23px; color: ${L.muted}; text-wrap: pretty;"><strong style="color: ${L.ink};">Why.</strong> ${why}</p>
    ${detail || ''}
  </div></div>`;
const box = (label, sub, { tone = 'plain', w: width = 150 } = {}) => {
  const st = { plain: `background: ${L.surface}; border: 1px solid ${L.outlineStrong};`, top: `background: ${L.primaryContainer}; border: 1px solid ${L.primary};`, sheet: `background: ${L.bg}; border: 1.5px dashed ${L.outlineStrong};` }[tone];
  return `<div style="${st} border-radius: 12px; padding: 10px 12px; width: ${width}px; box-sizing: border-box; display: flex; flex-direction: column; gap: 2px;"><span style="font-size: 14px; font-weight: 700; color: ${L.ink};">${label}</span>${sub ? `<span style="font-size: 12px; line-height: 16px; color: ${L.muted};">${sub}</span>` : ''}</div>`;
};
const arrow = `<div style="color: ${L.outlineStrong}; display: flex; align-items: center;">${ic('chev', 18, L.outlineStrong, 2)}</div>`;
const navMap = `<div style="background: ${L.surface}; border: 1px solid ${L.outline}; border-radius: 16px; padding: 20px; display: flex; flex-direction: column; gap: 14px; margin-top: 6px; overflow-x: auto;">
  <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: ${L.muted};">Navigation graph — one Activity, one NavHost</div>
  <div style="display: flex; align-items: center; gap: 8px;">${box('Log (start)', 'capture + recent', { tone: 'top' })}${arrow}${box('Settings', 'from top bar')}</div>
  <div style="display: flex; align-items: center; gap: 8px;">${box('History', 'filters: review / not categorized', { tone: 'top' })}${arrow}${box('Occurrence', 'bottom sheet', { tone: 'sheet' })}${arrow}${box('Edit interpretation', 'full screen')}</div>
  <div style="display: flex; align-items: center; gap: 8px; padding-left: 316px;">${arrow}${box('Activity detail')}${arrow}${box('Occurrence', 'sheet', { tone: 'sheet', w: 110 })}</div>
  <div style="display: flex; align-items: center; gap: 8px;">${box('Ask', 'session thread', { tone: 'top' })}${arrow}${box('Activity detail', 'from answer source')}</div>
  <div style="font-size: 13px; line-height: 19px; color: ${L.muted};">Green = NavigationBar destinations (each keeps its own back stack; system Back from History/Ask returns to Log). Review card on Log → “Choose another activity” opens the same activity picker used by Edit interpretation. Deep link <span style="font-family: ui-monospace, Consolas, monospace;">activityledger://capture</span> opens Log already listening (for launcher shortcut / assistant).</div>
</div>`;

w('Decisions.dc.html', doc(`<div style="width: 1040px; background: ${L.bg}; padding: 56px 64px 64px; box-sizing: border-box; display: flex; flex-direction: column;">
  <div style="display: flex; flex-direction: column; gap: 10px; padding-bottom: 28px;">
    <div style="font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: ${L.muted};">Decisions the specs left open · proposed for docs/UX_VISUAL_SPEC.md</div>
    <h1 style="font-size: 40px; line-height: 48px; font-weight: 600; letter-spacing: -0.02em; color: ${L.ink};">What this mockup commits to</h1>
    <p style="font-size: 17px; line-height: 26px; color: ${L.muted}; max-width: 780px;">Each item unblocks IMPLEMENTATION_HANDOFF Step 7 (phone UX) or Step 8 (Wear capture). All are reversible UI choices; none changes the data model or an architectural invariant.</p>
  </div>
  ${dec('D1', 'Phone navigation', 'Single Activity with one Compose Navigation NavHost (type-safe routes). Three top-level destinations in an M3 NavigationBar — <strong>Log</strong> (start; capture + recent history on one screen), <strong>History</strong>, <strong>Ask</strong>. Settings/diagnostics opens from the Log top bar. Occurrence details are a modal bottom sheet; Activity detail and Edit interpretation are pushed screens. “Review” is not its own destination.',
    'Capture must be zero-navigation on launch (UX §1), so Log is the start destination and holds the mic. History and Ask are the two other things people come back for; a three-item bar keeps both one tap away without a hub-and-spoke detour. Single-activity is the Compose default and keeps process-death and deep-link handling in one place. Keeping review inside History (as a filter) instead of a tab avoids an inbox (UX §15).', navMap)}
  ${dec('D2', 'First Wear entry surface', 'Ship the <strong>app launcher</strong> entry (required anyway) plus a <strong>watch-face complication</strong> in the Step 8 milestone. The complication tap opens capture directly in listening. <strong>Tile is deferred</strong> to a later milestone.',
    'A complication is the fastest practical path — one tap from the face the user already sees, satisfying “one intentional action before speaking”. It is a small ComplicationDataSourceService with a tap PendingIntent, whereas a Tile needs a ProtoLayout surface that adds a swipe before the tap. The complication also doubles as the glanceable queued indicator (“mic · 2”). Open dependency: whether the in-app listening screen or the system dictation UI is used depends on on-device speech availability on the OnePlus Watch 3 (backlog item 1); the designed Listening state applies to the in-app path.')}
  ${dec('D3', 'Visual design system', 'Compose Material 3 (phone) and Wear Compose Material 3 (watch). Static brand colour scheme, <strong>dynamic colour off</strong> for MVP, light + dark following system. Instrument Sans for UI, Source Serif 4 Italic reserved for the user’s own words, both bundled. One custom colour role (review) via an extended scheme. See the Design system artboard.',
    'Dynamic colour would re-tint primary and make “review” vs “saved” unpredictable across wallpapers; a fixed palette keeps state semantics stable and testable. The serif/sans split visually encodes the product’s core rule — evidence vs interpretation — so it can never be mistaken for app output.')}
  ${dec('D4', 'Settings / diagnostics contents', 'Groups, in order: <strong>On-device interpretation</strong> (Gemini Nano status: Ready / Downloading n% / Not supported; Structured Output; count of captures waiting, linking to History › Not categorized) · <strong>Speech</strong> (on-device recognizer + language) · <strong>Watch</strong> (paired device, connection, last capture received, items queued) · <strong>Privacy</strong> (stays-on-phone statement; “Copy technical report” with device/model status only, no activity text) · version line (app, prompt, schema).',
    'ARCHITECTURE §21 requires capability detection to be visible without pretending interpretation succeeded; this is the one place all four capabilities (AICore, Structured Output, speech, Data Layer) are shown together. The report excludes user content per AGENTS.md §11. Anything else (export, backup choice) is left out until it has a real behaviour behind it.')}
  ${dec('D5', 'Undo mechanics', 'Undo lives <strong>inside the inline success card</strong> on Log, not a snackbar. Visible for <strong>8 seconds</strong> with a draining progress bar; the timer pauses while the card is touched and is scaled with <span style="font-family: ui-monospace, Consolas, monospace;">AccessibilityManager.getRecommendedTimeoutMillis</span> (effectively no timeout while TalkBack is on). Starting a new capture or leaving Log dismisses it. Undo sets the occurrence to hidden; the RawCapture and interpretation are kept. After the window, the same action is “Remove from history” in the occurrence sheet. No undo on the watch in MVP.',
    'The success card is where the user is already looking, so undo there is discoverable without a second surface; a snackbar would stack over the NavigationBar and compete with the card. 8 s is between M3’s short (4 s) and long (10 s) durations — enough to read a short confirmation. Hiding rather than deleting satisfies UX §11 (“preserve raw capture/audit evidence”).')}
  ${dec('D6', 'Ask History interaction shape', 'A <strong>scrollable session thread</strong>: question bubble, then an answer card that states the database fact and names its source activity (tappable → Activity detail). If the input is recognised as a statement of something done, the thread shows the §10 “New activity?” card (Log it / Choose existing / Rename) instead of an answer. The thread is in-memory for the session and cleared with “Clear” or when the process ends; questions are not stored.',
    'Follow-ups (“and before that?”) and the occasional capture both need a place to land that a single Q&A box can’t give. Showing the source activity keeps answers auditable (“database truth”, UX §9). Not persisting queries avoids creating a second store of user text for no historical purpose.')}
  ${dec('D7', 'Review without an inbox', 'Needs-review and not-categorized captures are reachable from History filter chips and from Settings’ waiting count. No NavigationBar badge, no notification, no “clear all” goal.',
    'UX §4.2 requires “keep for later review”, and §15 forbids inboxes. A filter over the one history list gives a way back without a to-do queue.')}
  <div style="border-top: 1px solid ${L.outline}; padding-top: 28px; display: flex; flex-direction: column; gap: 12px;">
    <h3 style="font-size: 22px; line-height: 28px; font-weight: 700; color: ${L.ink};">Spec gaps found while drawing</h3>
    <ul style="margin: 0; padding-left: 20px; display: flex; flex-direction: column; gap: 10px; font-size: 16px; line-height: 24px; color: ${L.muted};">
      <li><strong style="color: ${L.ink};">Time precision in rows.</strong> UX §6’s example shows “Today, 3:12 PM” for “I cut the grass this afternoon”; ADR-018 forbids fabricated precision. Mockups render APPROXIMATE as “Today, afternoon” and DATE_ONLY as “Sat, Sep 12”. UX_SPEC §6 example corrected to match.</li>
      <li><strong style="color: ${L.ink};">Phone recognition-failure copy.</strong> §4.3 gives no wording. Proposed: “Couldn't make out any words. Nothing was saved.” + Try again. Mirrors the watch’s “Couldn't capture.”</li>
      <li><strong style="color: ${L.ink};">Occurrence view.</strong> §3 lists no screen for a single logged occurrence, but §8.1’s “Edit interpretation” needs an entry point. Resolved as a bottom sheet (D1).</li>
      <li><strong style="color: ${L.ink};">Typed capture.</strong> “Type instead” is shown as a quiet secondary action for noisy places and speech-unavailable devices (§12). It feeds the same capture pipeline with source text flagged as typed.</li>
      <li><strong style="color: ${L.ink};">Unsupported-device path.</strong> On a phone that can never run Gemini Nano (Pixel 7 Pro), captures stay uncategorized until the user assigns an activity from History. Confirm this is acceptable MVP behaviour.</li>
    </ul>
  </div>
</div>`));

// ---------- canvas.json ----------
const gap = 96;
const col = (i) => i * (PW + gap);
const canvas = {
  pages: [
    { id: 'capture', name: 'Phone · Capture' },
    { id: 'history', name: 'Phone · History, Ask, Correction' },
    { id: 'settings', name: 'Phone · Settings & accessibility' },
    { id: 'watch', name: 'Watch' },
    { id: 'system', name: 'Design system & decisions' },
  ],
  artboards: [
    { file: 'Main.dc.html', title: 'Log — ready', x: col(0), y: 0, w: PW, h: PH, page: 'capture' },
    { file: 'HomeListening.dc.html', title: 'Log — listening', x: col(1), y: 0, w: PW, h: PH, page: 'capture' },
    { file: 'HomeSuccess.dc.html', title: 'Log — saved (undo 8s)', x: col(2), y: 0, w: PW, h: PH, page: 'capture' },
    { file: 'NeedsReview.dc.html', title: 'Log — needs review (§4.2)', x: col(3), y: 0, w: PW, h: PH, page: 'capture' },
    { file: 'AiUnavailable.dc.html', title: 'Log — AI unavailable (§4.4)', x: col(4), y: 0, w: PW, h: PH, page: 'capture' },
    { file: 'CaptureFailure.dc.html', title: 'Log — recognition failed (§4.3)', x: col(5), y: 0, w: PW, h: PH, page: 'capture' },

    { file: 'History.dc.html', title: 'History (§6)', x: col(0), y: 0, w: PW, h: PH, page: 'history' },
    { file: 'OccurrenceSheet.dc.html', title: 'Occurrence sheet', x: col(1), y: 0, w: PW, h: PH, page: 'history' },
    { file: 'ActivityDetail.dc.html', title: 'Activity detail (§7)', x: col(2), y: 0, w: PW, h: PH, page: 'history' },
    { file: 'Correction.dc.html', title: 'Edit interpretation (§8)', x: col(3), y: 0, w: PW, h: PH, page: 'history' },
    { file: 'AskHistory.dc.html', title: 'Ask — thread (§9, §10)', x: col(4), y: 0, w: PW, h: PH, page: 'history' },
    { file: 'EmptyStates.dc.html', title: 'Empty states (§13)', x: col(0), y: PH + 160, w: PW * 2 + 56, h: PH, page: 'history' },

    { file: 'Settings.dc.html', title: 'Settings — model downloading', x: col(0), y: 0, w: PW, h: PH, page: 'settings' },
    { file: 'SettingsUnsupported.dc.html', title: 'Settings — AI not supported', x: col(1), y: 0, w: PW, h: PH, page: 'settings' },
    { file: 'Accessibility.dc.html', title: 'Accessibility — dark, 200% text', x: col(2), y: 0, w: 960, h: PH, page: 'settings' },

    { file: 'WatchStates.dc.html', title: 'Watch states + complication', x: 0, y: 0, w: 1760, h: 1420, page: 'watch' },

    { file: 'DesignSystem.dc.html', title: 'Design system', x: 0, y: 0, w: 1280, h: 2150, page: 'system' },
    { file: 'Decisions.dc.html', title: 'Decisions D1–D7', x: 1280 + 120, y: 0, w: 1040, h: 3900, page: 'system' },
  ],
  annotations: [
    { id: 'capture-flow', page: 'capture', x: 0, y: -210, w: 760, text: 'Capture flow, left to right: ready → listening → one of four outcomes. High-confidence captures need zero taps after speaking; the result card replaces the prompt and the new row lands at the top of Recent. Review, AI-unavailable and failure use three visibly different treatments (ochre fill / dashed outline / error fill) plus icon and text — never colour alone.' },
    { id: 'history-flow', page: 'history', x: 0, y: -190, w: 760, text: 'History row tap → occurrence sheet (evidence first) → Edit interpretation or Activity detail. Rows that have no interpretation lead with the user’s own words. Ask is a session thread; the last exchange shows a capture landing in Ask and becoming a §10 “New activity?” card.' },
    { id: 'settings-note', page: 'settings', x: 0, y: -170, w: 760, text: 'Settings is where capability state lives (ARCHITECTURE §21). Left: Pixel 10 Pro while the Gemini Nano model downloads. Middle: Pixel 7 Pro, which cannot run it — the app says so plainly and never implies a cloud fallback.' },
  ],
  launch: { view: 'canvas', page: 'capture' },
};
w('canvas.json', JSON.stringify(canvas, null, 2));
console.log('generated');
