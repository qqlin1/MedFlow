'use strict';

// Run: node --test tools/test-frontend-workflows.cjs
// Execute the real application functions. Only browser/platform services and HTTP
// responses are replaced; the tiny DOM below is not a layout/accessibility test.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/app.js'), 'utf8');
const startup = source.indexOf("  window.addEventListener('hashchange'");
assert.ok(startup > 0, 'app bootstrap must be located before exposing private functions');
const instrumented = source.slice(0, startup) + `
  window.__workflows = { state, renderLogin, renderDemoDataControls, workflowGuide, openBooking, bookingKey,
    loadAppointments, viewAppointments, viewPatients };
})();`;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function decode(text) {
  return text.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

class Element {
  constructor(tag, document) {
    this.tagName = tag.toLowerCase();
    this.ownerDocument = document;
    this.children = [];
    this.parentNode = null;
    this.attributes = {};
    this.style = {};
    this.listeners = new Map();
    this.disabled = false;
    this.checked = false;
    this.inert = false;
    this.selectedIndex = 0;
    this._html = '';
    this._value = '';
    const element = this;
    this.classList = {
      add(...names) { element.className = [...new Set(element.className.split(/\s+/).concat(names))].filter(Boolean).join(' '); },
      remove(...names) { element.className = element.className.split(/\s+/).filter((name) => !names.includes(name)).join(' '); },
      toggle(name, force) {
        const present = element.className.split(/\s+/).includes(name);
        const next = force === undefined ? !present : force;
        if (next) this.add(name); else this.remove(name);
        return next;
      }
    };
  }
  set className(value) { this.attributes.class = value; }
  get className() { return this.attributes.class || ''; }
  get dataset() {
    return Object.fromEntries(Object.entries(this.attributes).filter(([name]) => name.startsWith('data-'))
      .map(([name, value]) => [name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()), value]));
  }
  set innerHTML(html) {
    this.children.forEach((child) => { child.parentNode = null; });
    this.children = [];
    this._html = String(html);
    const stack = [this];
    const tags = /<\/?([\w-]+)\b([^>]*)>/g;
    let match;
    while ((match = tags.exec(this._html))) {
      if (match[0].startsWith('</')) {
        if (stack.length > 1) stack.pop();
        continue;
      }
      const node = new Element(match[1], this.ownerDocument);
      const attributes = /([^\s=/'"]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
      let attribute;
      while ((attribute = attributes.exec(match[2]))) {
        node.setAttribute(attribute[1], decode(attribute[2] ?? attribute[3] ?? attribute[4] ?? ''));
      }
      if (node.tagName === 'option') {
        const closing = this._html.indexOf('</option', tags.lastIndex);
        node.text = decode(this._html.slice(tags.lastIndex, closing).replace(/<[^>]*>/g, ''));
      }
      stack[stack.length - 1].appendChild(node);
      if (!['input', 'br', 'hr', 'img', 'meta', 'link', 'path', 'circle', 'rect'].includes(node.tagName) && !match[0].endsWith('/>')) stack.push(node);
    }
  }
  get innerHTML() { return this._html; }
  set textContent(value) { this.innerHTML = String(value); }
  get textContent() { return decode(this._html.replace(/<[^>]*>/g, '')); }
  get options() { return this.querySelectorAll('option'); }
  set value(value) {
    this._value = String(value);
    if (this.tagName === 'select') {
      const index = this.options.findIndex((option) => option.value === String(value));
      if (index >= 0) this.selectedIndex = index;
    }
  }
  get value() {
    return this.tagName === 'select' ? (this.options[this.selectedIndex]?.value || '') : this._value;
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'value') this._value = String(value);
    if (name === 'disabled') this.disabled = true;
    if (name === 'checked') this.checked = true;
  }
  getAttribute(name) { return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null; }
  appendChild(node) {
    node.remove();
    node.parentNode = this;
    this.children.push(node);
    return node;
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter((node) => node !== this);
    this.parentNode = null;
  }
  insertBefore(node, reference) {
    node.remove();
    node.parentNode = this;
    this.children.splice(this.children.indexOf(reference), 0, node);
  }
  insertAdjacentElement(position, node) {
    assert.equal(position, 'afterend');
    node.remove();
    node.parentNode = this.parentNode;
    const siblings = this.parentNode.children;
    siblings.splice(siblings.indexOf(this) + 1, 0, node);
  }
  get isConnected() { return this === this.ownerDocument.body || Boolean(this.parentNode?.isConnected); }
  contains(node) { return node === this || this.children.some((child) => child.contains(node)); }
  focus() { this.ownerDocument.activeElement = this; }
  getClientRects() { return this.isConnected ? [{}] : []; }
  closest(selector) { return matches(this, selector) ? this : this.parentNode?.closest(selector); }
  querySelectorAll(selector) {
    const found = [];
    function visit(node) {
      for (const child of node.children) {
        if (selector.split(',').some((part) => matches(child, part.trim()))) found.push(child);
        visit(child);
      }
    }
    visit(this);
    return found;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  removeEventListener(type, listener) {
    this.listeners.set(type, (this.listeners.get(type) || []).filter((item) => item !== listener));
  }
  async fire(type) {
    const event = { currentTarget: this, target: this, preventDefault() {} };
    await Promise.all((this.listeners.get(type) || []).map((listener) => listener(event)));
  }
}

function matches(node, selector) {
  const parts = selector.split(/\s+/);
  if (parts.length > 1) {
    if (!matches(node, parts.pop())) return false;
    let ancestor = node.parentNode;
    const parentSelector = parts.join(' ');
    while (ancestor) {
      if (matches(ancestor, parentSelector)) return true;
      ancestor = ancestor.parentNode;
    }
    return false;
  }
  let allowed = true;
  selector = selector.replace(/:not\(([^)]*)\)/g, (_, excluded) => {
    if (matches(node, excluded)) allowed = false;
    return '';
  });
  if (!allowed) return false;
  if (selector.includes(':checked') && !node.checked) return false;
  if (selector.includes(':disabled') && !node.disabled) return false;
  selector = selector.replace(/:checked|:disabled/g, '');
  const attributes = [...selector.matchAll(/\[([^\]=]+)(?:=["']?([^\]"']+)["']?)?\]/g)];
  for (const [, name, value] of attributes) {
    if (node.getAttribute(name) === null || (value !== undefined && node.getAttribute(name) !== value)) return false;
  }
  selector = selector.replace(/\[[^\]]*\]/g, '');
  const id = /#([\w-]+)/.exec(selector);
  if (id && node.getAttribute('id') !== id[1]) return false;
  const classes = [...selector.matchAll(/\.([\w-]+)/g)].map((match) => match[1]);
  if (classes.some((name) => !node.className.split(/\s+/).includes(name))) return false;
  const tag = /^[\w-]+/.exec(selector);
  return !tag || node.tagName === tag[0].toLowerCase();
}

function harness(overrides = {}) {
  const document = {
    title: '', activeElement: null,
    createElement(tag) { return new Element(tag, this); },
    querySelector(selector) { return this.body.querySelector(selector); },
    querySelectorAll(selector) { return this.body.querySelectorAll(selector); },
    addEventListener() {}, removeEventListener() {}
  };
  document.body = document.createElement('body');
  document.activeElement = document.body;
  document.body.innerHTML = '<div id="root"><main id="view"></main></div><div id="modal-root"></div><div id="toast-stack"></div>';
  const profile = { uid: 7, sub: 'patient.li', role: 'PATIENT', exp: Math.floor(Date.now() / 1000) + 1800 };
  const values = new Map();
  const localStorage = {
    getItem: (key) => values.get(key) || null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key)
  };
  const patients = [
    { id: 42, name: '李明', gender: 'MALE', relationship: 'SELF', maskedPhone: '138****8000' },
    { id: 43, name: '王芳', gender: 'FEMALE', relationship: 'FAMILY', maskedPhone: '139****9000' }
  ];
  const api = {
    demoConfig: async () => ({ enabled: false }),
    demoData: { refresh: async () => ({}) },
    patients: { list: async () => patients },
    departments: { list: async () => [{ id: 11, name: '心内科' }, { id: 12, name: '眼科' }] },
    doctors: { list: async () => [{ id: 21, name: '王医生' }, { id: 22, name: '陈医生' }] },
    clinicRooms: { list: async () => [{ id: 31, name: '一诊室' }, { id: 32, name: '二诊室' }] },
    shiftTemplates: { list: async () => [
      { id: 51, name: '上午', startTime: '08:00:00', endTime: '12:00:00' },
      { id: 52, name: '下午', startTime: '14:00:00', endTime: '18:00:00' }
    ] },
    appointments: { visitSummaries: async (ids) => ids.map((appointmentId) => ({ appointmentId, patientId: 42, patientName: '李明',
      doctorName: '王医生', departmentName: '心内科', clinicRoomName: '一诊室', workDate: '2030-01-01', startTime: '08:00:00', endTime: '08:30:00' })) }, schedules: {}
  };
  Object.entries(overrides).forEach(([group, functions]) => Object.assign(api[group], functions));
  class ApiError extends Error {
    constructor(message, code, status = 0) { super(message); this.code = code; this.status = status; }
  }
  let keyCounter = 0;
  const window = { MedFlow: {
    api, ApiError,
    newIdempotencyKey: () => 'intent-' + (++keyCounter),
    session: { token: () => 'session-for-' + profile.uid, profile: () => profile, clear() {}, expired: () => false }
  } };
  window.MedFlowDashboard = { render: async (root) => { root.innerHTML = '<section class="dash-hero"></section>'; } };
  const historyCalls = [];
  window.MedFlowPatientHistory = { openPatient: async (id, context, options) => { historyCalls.push({ id, options }); } };
  const location = { hash: '#/overview' };
  vm.runInNewContext(instrumented, {
    window, document, localStorage, location,
    requestAnimationFrame: (callback) => callback(), setTimeout: () => 0, clearTimeout() {},
    setInterval: () => 0, clearInterval() {}, console
  }, { filename: 'app.js' });
  const app = window.__workflows;
  const view = document.querySelector('#view');
  return { app, api, document, view, profile, values, location, ApiError, historyCalls,
    $: (selector, scope = document) => scope.querySelector(selector),
    $$: (selector, scope = document) => scope.querySelectorAll(selector) };
}

function slot(slotId = 81) {
  return { slotId, departmentName: '心内科', doctorName: '王医生', workDate: '2030-01-01',
    startTime: '08:00:00', endTime: '08:30:00', remainingCapacity: 2, bookingDeadline: '2030-01-01T07:30:00' };
}

function appointment(appointmentId, status = 'PENDING_CONFIRMATION') {
  return { appointmentId, patientId: 42, slotId: 81, status, confirmDeadline: '2030-01-01T07:00:00', cancelDeadline: '2030-01-01T07:30:00' };
}

function cursorState(items = []) {
  return { mode: 'cursor', page: 1, size: 10, items, total: 0, hasNext: true,
    cursor: { createdAt: '2026-10-19T08:00:00', id: 100 }, version: 0, patientNames: { 42: '李明' } };
}

test('patient card name and archive action both use the exact patient identity', async () => {
  const h = harness(); await h.app.viewPatients(h.view);
  const buttons = h.$$('[data-patient-history="42"]'); assert.equal(buttons.length, 2);
  await buttons[0].fire('click'); await buttons[1].fire('click');
  assert.deepEqual(h.historyCalls.map((call) => call.id), [42, 42]);
  assert.equal(h.historyCalls[0].options.returnLabel, '返回就诊人列表');
});


test('normal login keeps the original patient and administrator shortcuts', async () => {
  const h = harness(); h.app.renderLogin(); await Promise.resolve();
  const chips = h.$$('[data-demo]');
  assert.deepEqual(chips.map((chip) => chip.getAttribute('data-demo')), ['patient.li', 'admin']);
  await chips[0].fire('click'); assert.equal(h.$('#login-username').value, 'patient.li'); assert.equal(h.$('#login-password').value, 'Patient@123');
  await chips[1].fire('click'); assert.equal(h.$('#login-username').value, 'admin'); assert.equal(h.$('#login-password').value, 'Admin@123');
});

test('enabled local demo offers all three roles without submitting login automatically', async () => {
  const h = harness(); let logins = 0;
  h.api.demoConfig = async () => ({ enabled: true }); h.api.login = async () => { logins++; };
  h.app.renderLogin(); await Promise.resolve();
  const chips = h.$$('[data-demo]'); assert.deepEqual(chips.map((chip) => chip.getAttribute('data-demo')), ['admin', 'doctor', 'patient']);
  for (const chip of chips) {
    await chip.fire('click');
    assert.equal(h.$('#login-username').value, chip.getAttribute('data-demo'));
    assert.equal(h.$('#login-password').value, '123456');
  }
  assert.equal(logins, 0);
});

test('unavailable demo configuration preserves normal login and entered values', async () => {
  const h = harness(); const gate = deferred(); h.api.demoConfig = () => gate.promise;
  h.app.renderLogin(); h.$('#login-username').value = 'my.account'; h.$('#login-password').value = 'my-password';
  gate.reject(new Error('configuration unavailable')); await Promise.resolve(); await Promise.resolve();
  assert.equal(h.$('#login-username').value, 'my.account'); assert.equal(h.$('#login-password').value, 'my-password');
  assert.deepEqual(h.$$('[data-demo]').map((chip) => chip.getAttribute('data-demo')), ['patient.li', 'admin']);
});

test('late enabled configuration changes only shortcuts and does not overwrite manual credentials', async () => {
  const h = harness(); const gate = deferred(); h.api.demoConfig = () => gate.promise;
  h.app.renderLogin(); h.$('#login-username').value = 'my.account'; h.$('#login-password').value = 'my-password';
  gate.resolve({ enabled: true }); await Promise.resolve();
  assert.equal(h.$('#login-username').value, 'my.account'); assert.equal(h.$('#login-password').value, 'my-password');
  assert.equal(h.$$('[data-demo]').length, 3);
});

test('configuration from a removed login form cannot replace a newer login form', async () => {
  const h = harness(); const gate = deferred(); h.api.demoConfig = () => gate.promise;
  h.app.renderLogin(); h.api.demoConfig = async () => ({ enabled: false }); h.app.renderLogin();
  gate.resolve({ enabled: true }); await Promise.resolve();
  assert.deepEqual(h.$$('[data-demo]').map((chip) => chip.getAttribute('data-demo')), ['patient.li', 'admin']);
});

test('demo data replenishment is offered only to an enabled administrator', async () => {
  const h = harness(); h.profile.role = 'ADMIN'; await h.app.renderDemoDataControls(h.view);
  assert.equal(h.$('#demo-data-refresh'), null);
  h.api.demoConfig = async () => ({ enabled: true }); await h.app.renderDemoDataControls(h.view);
  assert.equal(h.$('#demo-data-refresh'), null);
  h.api.demoConfig = async () => ({ enabled: true, workflowEnabled: true }); h.profile.role = 'DOCTOR'; await h.app.renderDemoDataControls(h.view);
  assert.equal(h.$('#demo-data-refresh'), null);
  h.profile.role = 'ADMIN'; await h.app.renderDemoDataControls(h.view); assert.ok(h.$('#demo-data-refresh'));
});

test('demo data refresh prevents duplicate clicks and reloads overview after success', async () => {
  const gate = deferred(); let refreshes = 0; const h = harness();
  h.profile.role = 'ADMIN'; h.api.demoConfig = async () => ({ enabled: true, workflowEnabled: true }); h.api.demoData.refresh = () => { refreshes++; return gate.promise; };
  await h.app.renderDemoDataControls(h.view); const button = h.$('#demo-data-refresh');
  const first = button.fire('click'); const second = button.fire('click'); assert.equal(refreshes, 1); assert.equal(button.disabled, true);
  gate.resolve({ ignoredSummary: true }); await Promise.all([first, second]);
  assert.ok(h.$('.dash-hero')); assert.ok(h.$('#demo-data-refresh')); assert.equal(h.$('#demo-data-refresh').disabled, false);
});

test('failed demo refresh preserves retry and an unavailable flag does not break the overview', async () => {
  const h = harness(); h.profile.role = 'ADMIN'; h.api.demoConfig = async () => ({ enabled: true, workflowEnabled: true });
  h.api.demoData.refresh = async () => { throw new h.ApiError('补齐失败', 'DEMO_FAILED', 409); };
  await h.app.renderDemoDataControls(h.view); await h.$('#demo-data-refresh').fire('click');
  assert.match(h.$('#demo-data-error').innerHTML, /补齐失败/); assert.equal(h.$('#demo-data-refresh').disabled, false);
  h.view.innerHTML = ''; h.api.demoConfig = async () => { throw new Error('unavailable'); };
  await h.app.renderDemoDataControls(h.view); assert.equal(h.$('#demo-data-refresh'), null);
});

test('demo replenishment reports skipped fixtures from the backend summary', async () => {
  const h = harness(); h.profile.role = 'ADMIN';
  h.api.demoConfig = async () => ({ enabled: true, workflowEnabled: true });
  h.api.demoData.refresh = async () => ({ message: '演示药品全部停用，未新增处方和账单。' });
  await h.app.renderDemoDataControls(h.view); await h.$('#demo-data-refresh').fire('click');
  assert.match(h.$('.toast').innerHTML, /演示药品全部停用，未新增处方和账单/);
});

test('role instructions distinguish administrator assignment and initial tomorrow date', () => {
  const h = harness(); assert.match(h.app.workflowGuide('DOCTOR'), /医生无需报名/);
  assert.match(h.app.workflowGuide('PATIENT'), /默认查询明天/);
});

test('a created appointment with unavailable details stays submitted and keeps its retry key across reopening', async () => {
  const requests = [];
  const h = harness({ appointments: {
    create: async (payload, key) => { requests.push({ payload, key }); return appointment(101); },
    detail: async () => { throw new Error('connection interrupted while reading details'); }
  } });
  const modal = await h.app.openBooking(slot());
  await h.$('#booking-submit', modal.root).fire('click');
  assert.match(h.$('.modal-body', modal.root).innerHTML, /预约已提交成功/);
  assert.match(h.$('.modal-body', modal.root).innerHTML, /已提交，待刷新确认/);
  assert.equal(h.$('#booking-confirm', modal.root), null);
  const key = h.values.get('medflow.booking.7.81.42');
  assert.equal(key, requests[0].key);
  modal.close();
  const reopened = await h.app.openBooking(slot());
  await h.$('#booking-submit', reopened.root).fire('click');
  assert.equal(requests.length, 2);
  assert.equal(requests[1].key, key);
  assert.equal(h.values.get('medflow.booking.7.81.42'), key);
});

test('a successful current-state lookup clears the completed booking key', async () => {
  const h = harness({ appointments: {
    create: async () => appointment(101), detail: async () => ({ appointment: appointment(101, 'BOOKED') })
  } });
  const modal = await h.app.openBooking(slot());
  await h.$('#booking-submit', modal.root).fire('click');
  assert.equal(h.values.has('medflow.booking.7.81.42'), false);
  assert.match(h.$('.modal-body', modal.root).innerHTML, /已预约/);
  assert.equal(h.$('#booking-confirm', modal.root), null);
});

test('booking retry keys are reused only for the same account, patient and slot', () => {
  const h = harness();
  const same = h.app.bookingKey(81, 42);
  assert.equal(h.app.bookingKey(81, 42).key, same.key);
  const otherPatient = h.app.bookingKey(81, 43);
  const otherSlot = h.app.bookingKey(82, 42);
  h.profile.uid = 8;
  const otherAccount = h.app.bookingKey(81, 42);
  assert.equal(new Set([same.key, otherPatient.key, otherSlot.key, otherAccount.key]).size, 4);
  same.clear();
  assert.equal(h.values.has('medflow.booking.7.81.42'), false);
  assert.equal(h.values.get('medflow.booking.8.81.42'), otherAccount.key);
  assert.equal(h.values.get('medflow.booking.7.81.43'), otherPatient.key);
});

test('a slower earlier booking dialog cannot replace the most recently selected slot', async () => {
  const first = deferred();
  const second = deferred();
  let count = 0;
  const h = harness({ patients: { list: () => (++count === 1 ? first.promise : second.promise) } });
  const earlier = h.app.openBooking(slot(81));
  const later = h.app.openBooking(slot(82));
  second.resolve([]);
  const shown = await later;
  assert.match(shown.root.innerHTML, />82</);
  first.resolve([]);
  assert.equal(await earlier, undefined);
  assert.equal(shown.root.isConnected, true);
  assert.equal(h.$$('.modal-backdrop').length, 1);
});

test('a confirmation finishing after its dialog closes does not navigate away from a newer task', async () => {
  const confirmation = deferred();
  const h = harness({ appointments: {
    create: async () => appointment(101), detail: async () => ({ appointment: appointment(101) }),
    confirm: () => confirmation.promise
  } });
  const modal = await h.app.openBooking(slot(81));
  await h.$('#booking-submit', modal.root).fire('click');
  const confirming = h.$('#booking-confirm', modal.root).fire('click');
  modal.close();
  const newer = await h.app.openBooking(slot(82));
  h.location.hash = '#/slots';
  confirmation.resolve(appointment(101, 'BOOKED'));
  await confirming;
  assert.equal(h.location.hash, '#/slots');
  assert.equal(newer.root.isConnected, true);
});

test('continuous appointment append deduplicates overlaps and refresh restarts at the first cursor', async () => {
  const requests = [];
  const responses = [
    { items: [appointment(100), appointment(99)], hasNext: true, nextCursor: { createdAt: '2026-10-19T08:00:00', id: 99 } },
    { items: [appointment(99), appointment(98)], hasNext: true, nextCursor: { createdAt: '2026-10-19T07:00:00', id: 98 } },
    { items: [appointment(101), appointment(100)], hasNext: false, nextCursor: null }
  ];
  const h = harness({ appointments: { cursor: async (params) => { requests.push(params); return responses.shift(); } } });
  h.view.innerHTML = '<div id="appt-body"></div>';
  h.app.state.appt = cursorState();
  await h.app.loadAppointments(h.view);
  await h.app.loadAppointments(h.view, true);
  assert.deepEqual(Array.from(h.app.state.appt.items, (item) => item.appointmentId), [100, 99, 98]);
  assert.equal(requests[1].afterId, 99);
  assert.equal(requests[1].afterCreatedAt, '2026-10-19T08:00:00');
  await h.app.loadAppointments(h.view);
  assert.deepEqual(Array.from(h.app.state.appt.items, (item) => item.appointmentId), [101, 100]);
  assert.equal(requests[2].afterId, undefined);
  assert.equal(requests[2].afterCreatedAt, undefined);
  assert.equal(h.app.state.appt.hasNext, false);
  assert.equal(h.$('#appt-more').disabled, true);
});

test('an older continuous response cannot overwrite a newer refresh or its cursor', async () => {
  const oldPage = deferred();
  const freshPage = deferred();
  let count = 0;
  const h = harness({ appointments: { cursor: () => (++count === 1 ? oldPage.promise : freshPage.promise) } });
  h.view.innerHTML = '<div id="appt-body"></div>';
  h.app.state.appt = cursorState([appointment(100)]);
  const append = h.app.loadAppointments(h.view, true);
  const refresh = h.app.loadAppointments(h.view);
  freshPage.resolve({ items: [appointment(101)], hasNext: false, nextCursor: null });
  await refresh;
  oldPage.resolve({ items: [appointment(99)], hasNext: true, nextCursor: { createdAt: '2026-10-18T08:00:00', id: 99 } });
  await append;
  assert.deepEqual(Array.from(h.app.state.appt.items, (item) => item.appointmentId), [101]);
  assert.equal(h.app.state.appt.cursor, null);
  assert.equal(h.app.state.appt.hasNext, false);
  assert.doesNotMatch(h.$('#appt-body').innerHTML, /#99</);
});

test('a failed continuous append preserves existing records and offers another load', async () => {
  const h = harness({ appointments: { cursor: async () => { throw new Error('offline'); } } });
  h.view.innerHTML = '<div id="appt-body"></div>';
  h.app.state.appt = cursorState([appointment(100)]);
  await h.app.loadAppointments(h.view, true);
  assert.deepEqual(Array.from(h.app.state.appt.items, (item) => item.appointmentId), [100]);
  assert.equal(h.app.state.appt.cursor.id, 100);
  assert.equal(h.$('#appt-more').disabled, false);
});

test('changing appointment browsing modes discards pending results from the previous mode', async () => {
  const pending = deferred();
  const h = harness({ appointments: { cursor: () => pending.promise } });
  h.view.innerHTML = '<div id="appt-body"></div>';
  const previous = cursorState([appointment(100)]);
  h.app.state.appt = previous;
  const loading = h.app.loadAppointments(h.view, true);
  const replacement = { ...cursorState([appointment(500)]), mode: 'page' };
  h.app.state.appt = replacement;
  pending.resolve({ items: [appointment(99)], hasNext: false, nextCursor: null });
  await loading;
  assert.equal(h.app.state.appt, replacement);
  assert.deepEqual(Array.from(replacement.items, (item) => item.appointmentId), [500]);
  assert.deepEqual(Array.from(previous.items, (item) => item.appointmentId), [100]);
});
