'use strict';

// Run: node --test tools/test-frontend-booking.cjs
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
  window.__workflows = { state, bookingContext, viewSlots, loadSlotsPage, viewDoctors, openDoctorProfile };
})();`;
const bookingSource = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/booking.js'), 'utf8');
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
      if (closing >= 0) {
        node._html = this._html.slice(tags.lastIndex, closing);
        if (node.tagName === 'textarea') node._value = decode(node._html);
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
    directory: { departments: async () => [{ id: 11, name: '心内科' }, { id: 12, name: '皮肤科' }],
      catalogue: async (params) => [doctor(21, params.departmentId || 11), doctor(22, params.departmentId || 11)] },
    slots: { list: async (params) => ({ items: [availableSlot(81, params.doctorId, params.workDate), availableSlot(82, params.doctorId, params.workDate, '14:00:00')], total: 2, page: params.page, size: params.size }) },
    demoConfig: async () => ({ enabled: false }),
    demoData: { refresh: async () => ({}) },
    patients: { list: async () => patients },
    departments: { list: async () => [{ id: 11, name: '心内科' }, { id: 12, name: '眼科' }] },
    doctors: { list: async () => [{ id: 21, name: '王医生', username: 'doctor.wang', userId: 3, departmentId: 11, status: 'ENABLED' }],
      profile: async (id) => doctor(id), updateProfile: async (id, payload) => ({ id, ...payload }) },
    clinicRooms: { list: async () => [{ id: 31, name: '一诊室' }, { id: 32, name: '二诊室' }] },
    shiftTemplates: { list: async () => [
      { id: 51, name: '上午', startTime: '08:00:00', endTime: '12:00:00' },
      { id: 52, name: '下午', startTime: '14:00:00', endTime: '18:00:00' }
    ] },
    appointments: {}, schedules: {}
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
  vm.runInNewContext(bookingSource, { window, Date }, { filename: 'booking.js' });
  vm.runInNewContext(instrumented, {
    window, document, localStorage, location,
    requestAnimationFrame: (callback) => callback(), setTimeout: () => 0, clearTimeout() {},
    setInterval: () => 0, clearInterval() {}, console
  }, { filename: 'app.js' });
  const app = window.__workflows;
  const ctx = app.bookingContext();
  ctx.todayIso = (offset = 0) => new Date(Date.UTC(2026, 9, 20 + offset)).toISOString().slice(0, 10);
  app.state.slotDate = ctx.todayIso(1);
  const view = document.querySelector('#view');
  return { app, api, document, view, profile, values, location, ApiError, historyCalls, window, ctx, calls, module: window.MedFlowBooking,
    $: (selector, scope = document) => scope.querySelector(selector),
    $$: (selector, scope = document) => scope.querySelectorAll(selector) };
}

function doctor(id = 21, departmentId = 11) {
  return { id, departmentId, name: id === 21 ? '王医生' : '陈医生', departmentName: departmentId === 11 ? '心内科' : '皮肤科',
    title: '主任医师', specialty: '常见病诊疗', introduction: '从事门诊工作十年。' };
}
function availableSlot(slotId = 81, doctorId = 21, workDate = '2026-10-21', startTime = '08:00:00') {
  return { slotId, doctorId, departmentId: 11, doctorName: doctor(doctorId).name, departmentName: '心内科',
    clinicRoomName: '一诊室', workDate, startTime, endTime: startTime < '12:00:00' ? '08:30:00' : '14:30:00',
    remainingCapacity: 2, bookingDeadline: workDate + 'T07:30:00' };
}
async function chooseDepartment(h, id = 11) { await h.$('[data-booking-department="' + id + '"]').fire('click'); }
async function chooseDoctor(h, id = 21) { await h.$('[data-booking-doctor="' + id + '"]').fire('click'); }
async function openSlots(h) { await h.module.view(h.view, h.ctx); await chooseDepartment(h); await chooseDoctor(h); }

test('initial appointment view shows departments without querying doctors or unscoped slots', async () => {
  const h = harness(); await h.module.view(h.view, h.ctx);
  assert.ok(h.$('[data-booking-department="11"]')); assert.ok(h.$('[data-booking-department="12"]'));
  assert.equal(h.calls.filter((call) => call.method === 'directory.catalogue').length, 0);
  assert.equal(h.calls.filter((call) => call.method === 'slots.list').length, 0);
  assert.equal(h.app.state.slotDoctor, null); assert.equal(h.app.state.slotDepartment, null);
  assert.equal(h.$('#slot-date'), null);
});

test('department to doctor to tomorrow slots preserves identity, public biography and session state', async () => {
  const h = harness(); await h.module.view(h.view, h.ctx); await chooseDepartment(h);
  const catalogue = h.calls.find((call) => call.method === 'directory.catalogue');
  assert.equal(catalogue.args[0].departmentId, 11); assert.equal(catalogue.args[0].query, undefined);
  assert.match(h.view.innerHTML, /主任医师/); assert.match(h.view.innerHTML, /常见病诊疗/); assert.match(h.view.innerHTML, /从事门诊工作十年/);
  assert.equal(h.calls.filter((call) => call.method === 'slots.list').length, 0);
  await chooseDoctor(h);
  const query = h.calls.find((call) => call.method === 'slots.list').args[0];
  assert.equal(query.doctorId, 21); assert.equal(query.departmentId, 11); assert.equal(query.workDate, '2026-10-21');
  assert.equal(query.size, 12); assert.equal(query.page, 1);
  assert.equal(h.app.state.slotDoctor.id, 21); assert.equal(h.app.state.slotDepartment.id, 11);
  assert.equal(h.$('#booking-date').getAttribute('min'), '2026-10-20');
  assert.equal(h.$('.booking-selected-profile').tagName, 'details');
});

test('morning and afternoon group actual returned times without invented fees or fabricated availability', async () => {
  const h = harness(); await openSlots(h);
  const sections = h.$$('.booking-session'); assert.equal(sections.length, 2);
  assert.match(sections[0].innerHTML, /上午/); assert.match(sections[0].innerHTML, /08:00/); assert.doesNotMatch(sections[0].innerHTML, /14:00/);
  assert.match(sections[1].innerHTML, /下午/); assert.match(sections[1].innerHTML, /14:00/);
  assert.doesNotMatch(h.$('#slot-result').innerHTML, /￥|挂号费|免费/);
});

test('changing doctors immediately clears old slots and ignores a late slot response', async () => {
  const gate = deferred();
  const h = harness({ slots: { list: async (params) => params.doctorId === 21 ? gate.promise : { items: [availableSlot(92, 22, params.workDate)], total: 1, size: 12 } } });
  await h.module.view(h.view, h.ctx); await chooseDepartment(h);
  const old = chooseDoctor(h);
  await h.$('#booking-change-doctor').fire('click');
  assert.equal(h.app.state.slotDoctor, null); assert.equal(h.$('#slot-result'), null);
  await chooseDoctor(h, 22); assert.ok(h.$('[data-booking-slot="92"]'));
  gate.resolve({ items: [availableSlot(81)], total: 1, size: 12 }); await old;
  assert.ok(h.$('[data-booking-slot="92"]')); assert.equal(h.$('[data-booking-slot="81"]'), null);
  assert.equal(h.app.state.slotDoctor.id, 22);
});

test('returning to departments invalidates an old directory and clears dependent identities', async () => {
  const gate = deferred();
  const h = harness({ directory: { catalogue: async (params) => params.departmentId === 11 ? gate.promise : [doctor(22, 12)] } });
  await h.module.view(h.view, h.ctx); const old = chooseDepartment(h);
  await h.$('#booking-change-department').fire('click');
  assert.equal(h.app.state.slotDepartment, null); assert.equal(h.app.state.slotDoctor, null);
  await chooseDepartment(h, 12); gate.resolve([doctor(21, 11)]); await old;
  assert.ok(h.$('[data-booking-doctor="22"]')); assert.equal(h.$('[data-booking-doctor="21"]'), null);
  assert.equal(h.app.state.slotDepartment.id, 12);
});

test('global physician search works before choosing any department and sends trimmed name or specialty', async () => {
  const h = harness(); await h.module.view(h.view, h.ctx); await h.$('[data-booking-mode="doctor"]').fire('click');
  assert.equal(h.calls.at(-1).method, 'directory.catalogue'); assert.equal(h.calls.at(-1).args[0].departmentId, undefined);
  h.$('#booking-search-query').value = '  常见病  '; h.$('#booking-search-department').value = '12';
  await h.$('#booking-search-form').fire('submit');
  const args = h.calls.at(-1).args[0]; assert.equal(args.query, '常见病'); assert.equal(args.departmentId, 12);
  assert.equal(h.$('#booking-search-department').value, '12');
  await chooseDoctor(h, 22); assert.equal(h.calls.at(-1).args[0].departmentId, 12);
  await h.$('#booking-change-doctor').fire('click'); assert.equal(h.$('#booking-search-query').value, '  常见病  ');
  assert.equal(h.$('#booking-search-department').value, '12');
});

test('64 character search limit blocks oversized manual input without issuing HTTP', async () => {
  const h = harness(); await h.module.view(h.view, h.ctx); await h.$('[data-booking-mode="doctor"]').fire('click');
  const before = h.calls.length; assert.equal(h.$('#booking-search-query').getAttribute('maxlength'), '64');
  h.$('#booking-search-query').value = 'a'.repeat(65); await h.$('#booking-search-form').fire('submit'); assert.equal(h.calls.length, before);
  assert.match(h.$('#toast-stack').children.at(-1).innerHTML, /最多 64/);
  h.$('#booking-search-query').value = 'a'.repeat(64); await h.$('#booking-search-form').fire('submit'); assert.equal(h.calls.length, before + 1);
});

test('late global search response cannot replace the most recent query', async () => {
  const old = deferred(); const latest = deferred(); let count = 0;
  const h = harness({ directory: { catalogue: () => ++count === 1 ? old.promise : latest.promise } });
  await h.module.view(h.view, h.ctx); const first = h.$('[data-booking-mode="doctor"]').fire('click');
  h.$('#booking-search-query').value = '陈'; const second = h.$('#booking-search-form').fire('submit');
  latest.resolve([doctor(22)]); await second; old.resolve([doctor(21)]); await first;
  assert.ok(h.$('[data-booking-doctor="22"]')); assert.equal(h.$('[data-booking-doctor="21"]'), null);
});

test('no-slot state offers another day and keeps doctor and department context', async () => {
  const h = harness({ slots: { list: async () => ({ items: [], total: 0, size: 12 }) } }); await openSlots(h);
  assert.match(h.$('#slot-result').innerHTML, /当日暂无可预约时段/); assert.ok(h.$('#booking-change-doctor'));
  await h.$('#booking-next-day').fire('click');
  const query = h.calls.at(-1).args[0]; assert.equal(query.workDate, '2026-10-22'); assert.equal(query.doctorId, 21);
  assert.equal(h.app.state.slotDepartment.id, 11); assert.equal(h.app.state.slotDate, '2026-10-22');
});

test('invalid and past dates do not query while today remains a valid choice', async () => {
  const h = harness(); await openSlots(h); const before = h.calls.length;
  for (const value of ['2026-10-19', '2026-02-30', '', '2026-99-10']) {
    h.$('#booking-date').value = value; await h.$('#booking-date-form').fire('submit');
  }
  assert.equal(h.calls.length, before); assert.equal(h.app.state.slotDate, '2026-10-21');
  await h.$('[data-booking-date="2026-10-20"]').fire('click'); assert.equal(h.calls.at(-1).args[0].workDate, '2026-10-20');
});

test('rapid date changes discard old availability and never mix days', async () => {
  const old = deferred(); const h = harness({ slots: { list: async (params) => params.workDate === '2026-10-21' ? old.promise : { items: [availableSlot(92, 21, params.workDate)], total: 1, size: 12 } } });
  await h.module.view(h.view, h.ctx); await chooseDepartment(h); const initial = chooseDoctor(h);
  await h.$('[data-booking-date="2026-10-22"]').fire('click');
  old.resolve({ items: [availableSlot(81)], total: 1, size: 12 }); await initial;
  assert.ok(h.$('[data-booking-slot="92"]')); assert.equal(h.$('[data-booking-slot="81"]'), null);
  assert.equal(h.$('#booking-date').value, '2026-10-22');
});

test('selecting a slot delegates its exact server object to existing patient and confirmation workflow once', async () => {
  const h = harness(); const gate = deferred(); const selected = [];
  h.ctx.openBooking = (slot) => { selected.push(slot); return gate.promise; };
  await openSlots(h); const button = h.$('[data-booking-slot="81"]');
  const first = button.fire('click'); const second = button.fire('click');
  assert.equal(selected.length, 1); assert.equal(selected[0].slotId, 81); assert.equal(selected[0].doctorId, 21);
  assert.equal(selected[0].workDate, '2026-10-21'); assert.equal(selected[0].remainingCapacity, 2);
  gate.resolve(); await Promise.all([first, second]); assert.equal(h.$('[data-booking-slot="81"]').disabled, false);
});

test('fully booked slots cannot be selected and unexpected doctor or date rows are rejected', async () => {
  const h = harness({ slots: { list: async () => ({ items: [{ ...availableSlot(81), remainingCapacity: 0 }, availableSlot(82, 22), availableSlot(83, 21, '2026-10-22')], total: 3, size: 12 }) } });
  let handoffs = 0; h.ctx.openBooking = async () => handoffs++;
  await openSlots(h); assert.equal(h.$('[data-booking-slot="81"]').disabled, true);
  assert.equal(h.$('[data-booking-slot="82"]'), null); assert.equal(h.$('[data-booking-slot="83"]'), null);
  await h.$('[data-booking-slot="81"]').fire('click'); assert.equal(handoffs, 0);
});

test('server paging and the legacy refresh helper query the currently selected doctor and date', async () => {
  const h = harness({ slots: { list: async (params) => ({ items: [availableSlot(params.page === 1 ? 81 : 91, params.doctorId, params.workDate)], total: 13, size: 12 }) } });
  await openSlots(h); await h.$('[data-booking-page="2"]').fire('click');
  assert.equal(h.calls.at(-1).args[0].page, 2); assert.ok(h.$('[data-booking-slot="91"]'));
  await h.app.loadSlotsPage(1); assert.equal(h.calls.at(-1).args[0].page, 1);
  assert.equal(h.calls.at(-1).args[0].doctorId, 21); assert.equal(h.calls.at(-1).args[0].size, 12);
});

test('doctor biography, department and room text are escaped before rendering', async () => {
  const attack = '<img src=x onerror="alert(1)">';
  const h = harness({ directory: { departments: async () => [{ id: 11, name: attack }], catalogue: async () => [{ ...doctor(), name: attack, departmentName: attack, title: attack, specialty: attack, introduction: '</dd><script>alert(1)</script>' }] },
    slots: { list: async () => ({ items: [{ ...availableSlot(), clinicRoomName: attack }], total: 1, size: 12 }) } });
  await h.module.view(h.view, h.ctx); await chooseDepartment(h);
  assert.match(h.view.innerHTML, /&lt;img/); assert.equal(h.$('img'), null); assert.equal(h.$('script'), null);
  await chooseDoctor(h); assert.equal(h.$('img'), null); assert.equal(h.$('script'), null); assert.match(h.view.innerHTML, /&lt;script/);
});

test('missing doctor fields have explicit empty states and directory errors retain a retry action', async () => {
  let attempts = 0; const h = harness({ directory: { catalogue: async () => { if (++attempts === 1) throw new Error('目录暂不可用'); return [{ ...doctor(), title: '', specialty: null, introduction: '  ' }]; } } });
  await h.module.view(h.view, h.ctx); await chooseDepartment(h);
  assert.match(h.view.innerHTML, /目录暂不可用/); await h.$('#booking-retry-directory').fire('click');
  assert.match(h.view.innerHTML, /职称待维护/); assert.match(h.view.innerHTML, /擅长信息暂未维护/); assert.match(h.view.innerHTML, /简介暂未维护/);
});

test('slot errors keep selected context and retry the exact same date and doctor', async () => {
  let attempts = 0; const h = harness({ slots: { list: async (params) => { if (++attempts === 1) throw new Error('号源暂不可用'); return { items: [availableSlot(81, params.doctorId, params.workDate)], total: 1, size: 12 }; } } });
  await openSlots(h); assert.match(h.$('#slot-result').innerHTML, /号源暂不可用/);
  await h.$('#booking-retry-slots').fire('click'); assert.ok(h.$('[data-booking-slot="81"]'));
  assert.equal(h.app.state.slotDoctor.id, 21); assert.equal(h.app.state.slotDate, '2026-10-21');
});

test('session changes and detached views discard late directory and slot responses', async () => {
  const directory = deferred(); const h = harness({ directory: { catalogue: () => directory.promise } });
  await h.module.view(h.view, h.ctx); const pending = chooseDepartment(h); const html = h.view.innerHTML;
  h.profile.uid = 99; directory.resolve([doctor()]); await pending; assert.equal(h.view.innerHTML, html);
  const slots = deferred(); const h2 = harness({ slots: { list: () => slots.promise } });
  await h2.module.view(h2.view, h2.ctx); await chooseDepartment(h2); const loading = chooseDoctor(h2); const before = h2.view.innerHTML;
  h2.view.remove(); slots.resolve({ items: [availableSlot()], total: 1, size: 12 }); await loading; assert.equal(h2.view.innerHTML, before);
});

test('application route delegates to the new booking module with the original booking function', async () => {
  const h = harness(); let received;
  h.window.MedFlowBooking.view = async (root, ctx) => { received = { root, ctx }; };
  await h.app.viewSlots(h.view); assert.equal(received.root, h.view); assert.equal(received.ctx.api, h.api);
  assert.equal(received.ctx.state, h.app.state); assert.equal(typeof received.ctx.openBooking, 'function');
  assert.equal(received.ctx.fmtTime('08:30:00'), '08:30');
  assert.doesNotMatch(source, /function renderSlotResult\(/);
});

test('new appointment entry reaches the existing patient choice, idempotent create, current-state read and self-confirmation', async () => {
  const pending = { appointmentId: 101, patientId: 42, slotId: 81, status: 'PENDING_CONFIRMATION',
    confirmDeadline: '2099-01-01T07:00:00', cancelDeadline: '2099-01-01T07:30:00' };
  const h = harness({ appointments: { create: async () => pending, detail: async () => ({ appointment: pending }),
    confirm: async () => ({ ...pending, status: 'BOOKED' }) } });
  await h.app.viewSlots(h.view); await chooseDepartment(h); await chooseDoctor(h);
  await h.$('[data-booking-slot="81"]').fire('click');
  assert.equal(h.$$('input[name="pick-patient"]').length, 2);
  await h.$('#booking-submit').fire('click');
  const create = h.calls.find((call) => call.method === 'appointments.create');
  assert.equal(create.args[0].patientId, 42); assert.equal(create.args[0].slotId, 81); assert.equal(create.args[1], 'intent-1');
  assert.equal(h.calls.filter((call) => call.method === 'appointments.detail').length, 1);
  assert.equal(h.calls.filter((call) => call.method === 'slots.list').length, 2);
  const refreshed = h.calls.filter((call) => call.method === 'slots.list').at(-1).args[0];
  assert.equal(refreshed.doctorId, 21); assert.equal(refreshed.workDate, h.app.state.slotDate);
  assert.ok(h.$('#booking-confirm')); await h.$('#booking-confirm').fire('click');
  assert.equal(h.calls.find((call) => call.method === 'appointments.confirm').args[0], 101);
  assert.equal(h.location.hash, '#/appointments'); assert.equal(h.$('#booking-confirm'), null);
});

test('failed availability refresh after creation keeps successful confirmation dialog and never retries POST', async () => {
  const pending = { appointmentId: 101, patientId: 42, slotId: 81, status: 'PENDING_CONFIRMATION', confirmDeadline: '2099-01-01T07:00:00' };
  let reads = 0; const h = harness({ slots: { list: async (params) => {
    if (++reads > 1) throw new Error('余号刷新失败');
    return { items: [availableSlot(81, params.doctorId, params.workDate)], total: 1, size: 12 };
  } }, appointments: { create: async () => pending, detail: async () => ({ appointment: pending }) } });
  await h.app.viewSlots(h.view); await chooseDepartment(h); await chooseDoctor(h); await h.$('[data-booking-slot="81"]').fire('click');
  await h.$('#booking-submit').fire('click');
  assert.ok(h.$('#booking-confirm')); assert.match(h.$('.modal-body').innerHTML, /预约已创建/);
  assert.match(h.$('#slot-result').innerHTML, /余号刷新失败/); assert.equal(h.$('#booking-submit'), null);
  assert.equal(h.calls.filter((call) => call.method === 'appointments.create').length, 1);
});

test('unavailable appointment reread refreshes capacity without offering stale confirmation or reposting', async () => {
  const pending = { appointmentId: 101, patientId: 42, slotId: 81, status: 'PENDING_CONFIRMATION', confirmDeadline: '2099-01-01T07:00:00' };
  const h = harness({ appointments: { create: async () => pending, detail: async () => { throw new Error('预约状态暂不可用'); } } });
  await h.app.viewSlots(h.view); await chooseDepartment(h); await chooseDoctor(h); await h.$('[data-booking-slot="81"]').fire('click');
  await h.$('#booking-submit').fire('click');
  assert.match(h.$('.modal-body').innerHTML, /已提交，待刷新确认/); assert.equal(h.$('#booking-confirm'), null);
  assert.ok(h.$('#booking-later')); assert.equal(h.calls.filter((call) => call.method === 'slots.list').length, 2);
  assert.equal(h.calls.filter((call) => call.method === 'appointments.create').length, 1);
});

test('department loading failures offer retry and empty directories remain explicit', async () => {
  let attempts = 0; const h = harness({ directory: { departments: async () => { if (++attempts === 1) throw new Error('科室读取失败'); return []; } } });
  await h.module.view(h.view, h.ctx); assert.match(h.view.innerHTML, /科室读取失败/);
  await h.$('#booking-retry-departments').fire('click'); assert.match(h.view.innerHTML, /暂未开放预约科室/);
  assert.equal(h.calls.filter((call) => call.method === 'slots.list').length, 0);
});

test('admin doctor table opens authoritative profile GET and saves all public fields without changing account identity', async () => {
  const h = harness(); h.profile.role = 'ADMIN'; await h.app.viewDoctors(h.view);
  await h.$('[data-doctor-profile="21"]').fire('click');
  assert.equal(h.calls.at(-1).method, 'doctors.profile'); assert.equal(h.calls.at(-1).args[0], 21);
  assert.equal(h.$('[name="specialty"]').value, '常见病诊疗');
  h.$('[name="name"]').value = ' 新姓名 '; h.$('[name="title"]').value = ' 主治医师 ';
  h.$('[name="specialty"]').value = ' 新擅长 '; h.$('[name="introduction"]').value = ' 新简介 ';
  await h.$('#doctor-profile-form').fire('submit');
  const update = h.calls.find((call) => call.method === 'doctors.updateProfile');
  assert.equal(update.args[0], 21); assert.deepEqual(JSON.parse(JSON.stringify(update.args[1])), { name: '新姓名', title: '主治医师', specialty: '新擅长', introduction: '新简介' });
  assert.equal(h.$('#doctor-profile-form'), null); assert.equal(h.calls.at(-1).method, 'doctors.list');
});

test('admin profile validation blocks blank names and overlong fields', async () => {
  const h = harness(); h.profile.role = 'ADMIN'; await h.app.openDoctorProfile(21, h.view, async () => {});
  for (const [name, value] of [['name', ' '], ['title', 'a'.repeat(65)], ['specialty', 'a'.repeat(1001)], ['introduction', 'a'.repeat(4001)]]) {
    h.$('[name="name"]').value = '王医生'; h.$('[name="title"]').value = ''; h.$('[name="specialty"]').value = ''; h.$('[name="introduction"]').value = '';
    h.$('[name="' + name + '"]').value = value; await h.$('#doctor-profile-form').fire('submit');
  }
  assert.equal(h.calls.filter((call) => call.method === 'doctors.updateProfile').length, 0); assert.match(h.$('#doctor-profile-error').innerHTML, /长度限制/);
});

test('admin profile save serializes double submission and preserves authored text after server failure', async () => {
  const gate = deferred(); const h = harness({ doctors: { updateProfile: () => gate.promise } });
  await h.app.openDoctorProfile(21, h.view, async () => {}); h.$('[name="introduction"]').value = '尚未保存的简介';
  const first = h.$('#doctor-profile-form').fire('submit'); const second = h.$('#doctor-profile-form').fire('submit');
  assert.equal(h.calls.filter((call) => call.method === 'doctors.updateProfile').length, 1); assert.equal(h.$('#doctor-profile-save').disabled, true);
  gate.reject(new h.ApiError('保存失败', 'PROFILE_FAILED', 409)); await Promise.all([first, second]);
  assert.equal(h.$('[name="introduction"]').value, '尚未保存的简介'); assert.equal(h.$('[name="introduction"]').disabled, false);
  assert.match(h.$('#doctor-profile-error').innerHTML, /保存失败/); assert.equal(h.$('#doctor-profile-save').disabled, false);
});

test('closed or previous-session admin profile responses cannot paint a newer dialog', async () => {
  const gate = deferred(); const h = harness({ doctors: { profile: () => gate.promise } });
  const pending = h.app.openDoctorProfile(21, h.view, async () => {}); await h.$('[data-close]').fire('click');
  gate.resolve(doctor()); await pending; assert.equal(h.$('#doctor-profile-form'), null);
  const gate2 = deferred(); const h2 = harness({ doctors: { profile: () => gate2.promise } });
  const loading = h2.app.openDoctorProfile(21, h2.view, async () => {}); h2.profile.uid = 99;
  gate2.resolve(doctor()); await loading; assert.equal(h2.$('#doctor-profile-form'), null);
});

test('admin profile values are escaped in both attributes and textarea content', async () => {
  const attack = '\"><img src=x onerror=alert(1)>';
  const h = harness({ doctors: { profile: async () => ({ ...doctor(), name: attack, title: attack, specialty: '</textarea><script>alert(1)</script>', introduction: attack }) } });
  await h.app.openDoctorProfile(21, h.view, async () => {});
  assert.equal(h.$('img'), null); assert.equal(h.$('script'), null); assert.equal(h.$('[name="name"]').value, attack);
  assert.equal(h.$('[name="specialty"]').value, '</textarea><script>alert(1)</script>');
});

test('directory catalogue and administrator profile client methods use exact encoded endpoints and GET', async () => {
  const apiSource = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/api.js'), 'utf8');
  const calls = []; const values = new Map(); const window = { localStorage: { getItem: (key) => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) }, dispatchEvent() {} };
  vm.runInNewContext(apiSource, { window, URLSearchParams, AbortController, TextDecoder, Uint8Array, setTimeout, clearTimeout,
    atob: (value) => Buffer.from(value, 'base64').toString('binary'), CustomEvent: class {},
    fetch: async (url, config) => { calls.push({ url, config }); return new Response(JSON.stringify({ code: 'SUCCESS', message: '操作成功', data: [] }), { status: 200 }); } });
  await window.MedFlow.api.directory.catalogue({ departmentId: 11, query: '王 医生' });
  await window.MedFlow.api.directory.catalogue(); await window.MedFlow.api.doctors.profile(21);
  assert.equal(calls[0].url, '/api/v1/patient/doctor-directory?departmentId=11&query=%E7%8E%8B+%E5%8C%BB%E7%94%9F');
  assert.equal(calls[1].url, '/api/v1/patient/doctor-directory'); assert.equal(calls[2].url, '/api/v1/admin/doctors/21/profile');
  assert.ok(calls.every((call) => call.config.method === 'GET' && call.config.body === undefined));
});
