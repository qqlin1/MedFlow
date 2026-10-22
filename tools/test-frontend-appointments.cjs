'use strict';

// Run: node --test tools/test-frontend-appointments.cjs
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
    loadAppointments, viewAppointments, renderAppointments, loadAppointmentSummaries, viewPatients };
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
      const closing = this._html.indexOf('</' + node.tagName, tags.lastIndex);
      if (closing >= 0) node._html = this._html.slice(tags.lastIndex, closing);
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
  const calls = [];
  for (const [group, methods] of Object.entries(api)) {
    if (!methods || typeof methods !== 'object') continue;
    for (const [name, method] of Object.entries(methods)) {
      methods[name] = (...args) => { calls.push({ method: group + '.' + name, args }); return method(...args); };
    }
  }
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
  return { app, api, document, view, profile, values, location, ApiError, historyCalls, calls,
    $: (selector, scope = document) => scope.querySelector(selector),
    $$: (selector, scope = document) => scope.querySelectorAll(selector) };
}

function appointment(id = 101, status = 'BOOKED') {
  return { appointmentId: id, patientId: 42, slotId: id + 1000, status, confirmDeadline: '2099-01-01T07:00:00', cancelDeadline: '2099-01-01T07:30:00' };
}
function summary(id = 101, overrides = {}) {
  return { appointmentId: id, patientId: 42, patientName: '李明', doctorName: '王医生', departmentName: '心内科', clinicRoomName: '一诊室',
    workDate: '2030-01-01', startTime: '08:00:00', endTime: '08:30:00', ...overrides };
}
function prepare(h, mode = 'page', items = []) {
  h.view.innerHTML = '<div id="appt-body"></div>';
  h.app.state.appt = { mode, page: 1, size: 10, items, total: items.length, hasNext: true,
    cursor: { createdAt: '2026-10-20T08:00:00', id: 100 }, version: 0, patientNames: { 42: '李明' }, summaries: {} };
}

test('one batch maps unordered summaries to exact appointment rows and replaces raw slot columns', async () => {
  const h = harness({ appointments: { page: async () => ({ items: [appointment(102, 'PENDING_CONFIRMATION'), appointment(101)], total: 2, size: 10, page: 1 }),
    visitSummaries: async () => [summary(101, { doctorName: '陈医生', departmentName: '皮肤科' }), summary(102)] } });
  prepare(h); await h.app.loadAppointments(h.view);
  const reads = h.calls.filter((call) => call.method === 'appointments.visitSummaries');
  assert.equal(reads.length, 1); assert.deepEqual(Array.from(reads[0].args[0]), [102, 101]);
  const rows = h.$$('tbody tr'); assert.match(rows[0].innerHTML, /王医生/); assert.match(rows[0].innerHTML, /08:00–08:30/);
  assert.match(rows[1].innerHTML, /陈医生/); assert.match(rows[1].innerHTML, /皮肤科/);
  assert.match(rows[0].innerHTML, /1月1日/); assert.match(rows[0].innerHTML, /一诊室/);
  assert.doesNotMatch(h.$('#appt-body').innerHTML, /号源 ID|取消截止|<th>确认截止/);
  assert.ok(h.$('[data-confirm="102"]')); assert.ok(h.$('[data-cancel="101"]'));
  assert.equal(h.calls.filter((call) => call.method === 'appointments.visitInfo').length, 0);
});

test('appointment records and actions render while a slow metadata request is still loading', async () => {
  const gate = deferred(); const started = deferred();
  const h = harness({ appointments: { page: async () => ({ items: [appointment(101, 'PENDING_CONFIRMATION')], total: 1 }), visitSummaries: () => { started.resolve(); return gate.promise; } } });
  prepare(h); const loading = h.app.loadAppointments(h.view); await started.promise;
  assert.match(h.$('#appt-body').innerHTML, /就诊安排待加载/); assert.match(h.$('#appt-body').innerHTML, /正在加载就诊日期/);
  assert.ok(h.$('[data-confirm="101"]')); gate.resolve([summary()]); await loading;
  assert.match(h.$('#appt-body').innerHTML, /王医生/); assert.equal(h.$('#appt-summary-retry'), null);
});

test('summary failure preserves list and metadata retry does not reload records or resend appointment mutations', async () => {
  let reads = 0; const h = harness({ appointments: { page: async () => ({ items: [appointment()], total: 1 }),
    visitSummaries: async () => { if (++reads === 1) throw new Error('就诊安排暂不可用'); return [summary()]; } } });
  prepare(h); await h.app.loadAppointments(h.view);
  assert.equal(h.app.state.appt.items[0].appointmentId, 101); assert.match(h.$('#appt-body').innerHTML, /预约记录已加载/);
  assert.match(h.$('#appt-body').innerHTML, /就诊安排暂不可用/); assert.ok(h.$('[data-detail="101"]'));
  await h.$('#appt-summary-retry').fire('click');
  assert.match(h.$('#appt-body').innerHTML, /王医生/); assert.equal(h.calls.filter((call) => call.method === 'appointments.page').length, 1);
  assert.equal(h.calls.filter((call) => call.method === 'appointments.visitSummaries').length, 2);
  assert.equal(h.calls.filter((call) => /create|confirm|cancel/.test(call.method)).length, 0);
});

test('a late previous-page summary cannot overwrite a newly selected page', async () => {
  const old = deferred(); const started = deferred(); let pages = 0;
  const h = harness({ appointments: { page: async () => ({ items: [appointment(++pages === 1 ? 101 : 102)], total: 2 }),
    visitSummaries: async (ids) => { if (ids[0] === 101) { started.resolve(); return old.promise; } return [summary(102, { doctorName: '新页医生' })]; } } });
  prepare(h); const first = h.app.loadAppointments(h.view); await started.promise;
  h.app.state.appt.page = 2; await h.app.loadAppointments(h.view);
  old.resolve([summary(101, { doctorName: '旧页医生' })]); await first;
  assert.match(h.$('#appt-body').innerHTML, /新页医生/); assert.doesNotMatch(h.$('#appt-body').innerHTML, /旧页医生/);
  assert.equal(h.app.state.appt.items[0].appointmentId, 102);
});

test('cursor append deduplicates overlap and batches only new missing summaries', async () => {
  let pages = 0; const h = harness({ appointments: { cursor: async () => ++pages === 1
    ? { items: [appointment(100), appointment(99)], hasNext: true, nextCursor: { createdAt: '2026-10-20T07:00:00', id: 99 } }
    : { items: [appointment(99), appointment(98)], hasNext: false, nextCursor: null } } });
  prepare(h, 'cursor'); await h.app.loadAppointments(h.view); await h.app.loadAppointments(h.view, true);
  const reads = h.calls.filter((call) => call.method === 'appointments.visitSummaries'); assert.equal(reads.length, 2);
  assert.deepEqual(Array.from(reads[0].args[0]), [100, 99]); assert.deepEqual(Array.from(reads[1].args[0]), [98]);
  assert.deepEqual(Array.from(h.app.state.appt.items, (item) => item.appointmentId), [100, 99, 98]);
  assert.equal(h.app.state.appt.hasNext, false); assert.equal(h.$('#appt-more').disabled, true);
});

test('failed append metadata keeps existing summaries and the advanced cursor, retrying only missing records', async () => {
  let pages = 0; let batches = 0;
  const h = harness({ appointments: { cursor: async () => ({ items: [appointment(++pages === 1 ? 100 : 99)], hasNext: true, nextCursor: { createdAt: '2026-10-20T07:00:00', id: pages === 1 ? 100 : 99 } }),
    visitSummaries: async (ids) => { if (++batches === 2) throw new Error('新记录安排未加载'); return ids.map((id) => summary(id)); } } });
  prepare(h, 'cursor'); await h.app.loadAppointments(h.view); await h.app.loadAppointments(h.view, true);
  assert.equal(h.app.state.appt.cursor.id, 99); assert.ok(h.app.state.appt.summaries[100]); assert.equal(h.app.state.appt.summaries[99], undefined);
  assert.deepEqual(Array.from(h.app.state.appt.items, (item) => item.appointmentId), [100, 99]);
  await h.$('#appt-summary-retry').fire('click'); assert.equal(h.app.state.appt.cursor.id, 99);
  assert.deepEqual(Array.from(h.calls.filter((call) => call.method === 'appointments.visitSummaries').at(-1).args[0]), [99]);
  assert.equal(h.calls.filter((call) => call.method === 'appointments.cursor').length, 2);
});

test('failed record append leaves existing rows and metadata visible with an explicit retry path', async () => {
  const h = harness({ appointments: { cursor: async () => { throw new Error('更多预约读取失败'); } } });
  prepare(h, 'cursor', [appointment(100)]); h.app.state.appt.summaries[100] = summary(100);
  await h.app.loadAppointments(h.view, true);
  assert.match(h.$('#appt-body').innerHTML, /更多预约读取失败/); assert.match(h.$('#appt-body').innerHTML, /王医生/);
  assert.equal(h.app.state.appt.cursor.id, 100); assert.equal(h.$('#appt-more').disabled, false);
});

test('missing and foreign response IDs stay explicitly unavailable and cannot supply another row biography', async () => {
  const h = harness({ appointments: { page: async () => ({ items: [appointment(101), appointment(102)], total: 2 }),
    visitSummaries: async () => [summary(101), summary(999, { doctorName: '不属于当前列表的医生' })] } });
  prepare(h); await h.app.loadAppointments(h.view);
  assert.ok(h.$('#appt-summary-retry')); assert.match(h.$('#appt-body').innerHTML, /就诊安排待加载/);
  assert.doesNotMatch(h.$('#appt-body').innerHTML, /不属于当前列表的医生/); assert.equal(h.app.state.appt.summaries[999], undefined);
});

test('retrying more than 50 accumulated missing records uses bounded batches rather than row-by-row requests', async () => {
  const h = harness(); prepare(h, 'cursor', Array.from({ length: 51 }, (_, index) => appointment(101 + index)));
  h.app.state.appt.summaryError = new Error('待重试'); h.app.renderAppointments(h.view);
  await h.$('#appt-summary-retry').fire('click');
  const reads = h.calls.filter((call) => call.method === 'appointments.visitSummaries');
  assert.deepEqual(reads.map((call) => call.args[0].length), [50, 1]); assert.equal(Object.keys(h.app.state.appt.summaries).length, 51);
  assert.equal(h.$('#appt-summary-retry'), null);
});

test('session changes or detached pages discard summary responses', async () => {
  for (const disconnect of [false, true]) {
    const gate = deferred(); const started = deferred(); const h = harness({ appointments: { page: async () => ({ items: [appointment()], total: 1 }),
      visitSummaries: () => { started.resolve(); return gate.promise; } } });
    prepare(h); const pending = h.app.loadAppointments(h.view); await started.promise; const before = h.$('#appt-body').innerHTML;
    if (disconnect) h.view.remove(); else h.profile.uid = 99;
    gate.resolve([summary(101, { doctorName: '延迟医生' })]); await pending;
    assert.equal(h.$('#appt-body', h.view).innerHTML, before); assert.equal(h.app.state.appt.summaries[101], undefined);
  }
});

test('switching browsing mode while metadata is pending cannot update the new appointment state', async () => {
  const gate = deferred(); const started = deferred(); const h = harness({ appointments: { page: async () => ({ items: [appointment()], total: 1 }),
    visitSummaries: () => { started.resolve(); return gate.promise; } } });
  prepare(h); const oldState = h.app.state.appt; const pending = h.app.loadAppointments(h.view); await started.promise;
  prepare(h, 'cursor', [appointment(500)]); gate.resolve([summary()]); await pending;
  assert.notEqual(h.app.state.appt, oldState); assert.equal(h.app.state.appt.items[0].appointmentId, 500);
  assert.equal(oldState.summaries[101], undefined);
});

test('empty appointment pages never send an invalid empty batch request', async () => {
  const h = harness({ appointments: { page: async () => ({ items: [], total: 0 }) } }); prepare(h); await h.app.loadAppointments(h.view);
  assert.match(h.$('#appt-body').innerHTML, /还没有预约记录/); assert.equal(h.calls.filter((call) => call.method === 'appointments.visitSummaries').length, 0);
});

test('summary fields are escaped in dates, doctors, department, room and patient name', async () => {
  const attack = '<img src=x onerror="alert(1)">'; const h = harness({ appointments: { page: async () => ({ items: [appointment()], total: 1 }),
    visitSummaries: async () => [summary(101, { doctorName: attack, departmentName: attack, clinicRoomName: attack, patientName: attack, workDate: attack })] } });
  prepare(h); await h.app.loadAppointments(h.view);
  assert.equal(h.$('img'), null); assert.match(h.$('#appt-body').innerHTML, /&lt;img/); assert.doesNotMatch(h.$('#appt-body').innerHTML, /<img/);
});
