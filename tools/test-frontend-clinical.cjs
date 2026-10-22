'use strict';

// Execute the real clinical module against a small DOM, with only HTTP replaced.
// Run: node --test tools/test-frontend-clinical.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/clinical.js'), 'utf8');

function decode(text) { return text.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&'); }
function esc(value) { return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char])); }
function deferred() { let resolve; let reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function fail(status, message = '请求冲突') { return Object.assign(new Error(message), { status, code: 'TEST_ERROR' }); }
function page(items = [], total = items.length) { return { items, total, page: 1, size: 10 }; }

class Element {
  constructor(tag, document) {
    this.tagName = tag.toLowerCase(); this.ownerDocument = document; this.children = [];
    this.parentNode = null; this.attributes = {}; this.listeners = new Map();
    this.disabled = false; this.checked = false; this.selectedIndex = 0; this._html = ''; this._value = '';
  }
  set innerHTML(html) {
    this.children.forEach((child) => { child.parentNode = null; }); this.children = []; this._html = String(html);
    const stack = [this]; const tags = /<\/?([\w-]+)\b([^>]*)>/g; let match;
    while ((match = tags.exec(this._html))) {
      if (match[0].startsWith('</')) { if (stack.length > 1) stack.pop(); continue; }
      const node = new Element(match[1], this.ownerDocument);
      const attributes = /([^\s=/'"]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g; let attribute;
      while ((attribute = attributes.exec(match[2]))) node.setAttribute(attribute[1], decode(attribute[2] ?? attribute[3] ?? attribute[4] ?? ''));
      const end = this._html.indexOf('</' + node.tagName, tags.lastIndex);
      if (end >= 0) {
        node._html = this._html.slice(tags.lastIndex, end);
        if (node.tagName === 'textarea') node._value = decode(node._html);
      }
      stack.at(-1).appendChild(node);
      if (!['input', 'br', 'hr', 'img', 'meta', 'link', 'path', 'circle', 'rect'].includes(node.tagName)) stack.push(node);
    }
  }
  get innerHTML() { return this._html; }
  set textContent(value) { this.innerHTML = esc(value); }
  get textContent() { return decode(this._html.replace(/<[^>]*>/g, '')); }
  set value(value) {
    this._value = String(value);
    if (this.tagName === 'select') {
      const index = this.querySelectorAll('option').findIndex((option) => option.value === String(value));
      if (index >= 0) this.selectedIndex = index;
    }
  }
  get value() { return this.tagName === 'select' ? (this.querySelectorAll('option')[this.selectedIndex]?.value || '') : this._value; }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'value') this._value = String(value);
    if (name === 'disabled') this.disabled = true;
    if (name === 'checked') this.checked = true;
  }
  getAttribute(name) { return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null; }
  appendChild(node) { node.remove(); node.parentNode = this; this.children.push(node); return node; }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter((child) => child !== this); this.parentNode = null; }
  get isConnected() { return this === this.ownerDocument.body || Boolean(this.parentNode?.isConnected); }
  querySelectorAll(selector) {
    const found = []; function visit(node) {
      for (const child of node.children) {
        if (selector.split(',').some((part) => matches(child, part.trim()))) found.push(child); visit(child);
      }
    } visit(this); return found;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  addEventListener(type, callback) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(callback); }
  async fire(type) { await Promise.all((this.listeners.get(type) || []).map((callback) => callback({ target: this, currentTarget: this, preventDefault() {} }))); }
}
function matches(node, selector) {
  const attrs = [...selector.matchAll(/\[([^\]=]+)(?:=["']?([^\]"']+)["']?)?\]/g)];
  for (const [, name, value] of attrs) if (node.getAttribute(name) === null || (value !== undefined && node.getAttribute(name) !== value)) return false;
  selector = selector.replace(/\[[^\]]*\]/g, '');
  const id = /#([\w-]+)/.exec(selector); if (id && node.getAttribute('id') !== id[1]) return false;
  const classes = [...selector.matchAll(/\.([\w-]+)/g)].map((match) => match[1]);
  if (classes.some((name) => !(node.attributes.class || '').split(/\s+/).includes(name))) return false;
  const tag = /^[\w-]+/.exec(selector); return !tag || node.tagName === tag[0];
}

function fixture(status = 'IN_PROGRESS', record = true) {
  return {
    encounter: { id: 61, appointmentId: 101, patientId: 7, patientName: '李明', doctorId: 2, doctorName: '王医生',
      departmentName: '内科', clinicRoomName: '一诊室', workDate: '2026-10-19', startTime: '08:00:00', endTime: '08:30:00', status, checkedInAt: '2026-10-19T07:55:00' },
    medicalRecord: record ? { id: 81, encounterId: 61, chiefComplaint: '头痛', presentIllness: '今日起', diagnosis: '待查', advice: '复诊', version: 2, updatedAt: '2026-10-19T08:05:00' } : null,
    history: []
  };
}
function schedule(status = 'DRAFT') {
  return { id: 91, departmentId: 1, departmentName: '内科', doctorId: 2, doctorName: '王医生', clinicRoomId: 3, clinicRoomName: '一诊室', shiftName: '上午',
    workDate: '2026-10-20', startTime: '08:00:00', endTime: '12:00:00', status, remainingCapacity: 5, totalCapacity: 8, activeAppointmentCount: 0 };
}

function harness(overrides = {}) {
  const document = { createElement(tag) { return new Element(tag, this); } };
  document.body = document.createElement('body'); document.body.innerHTML = '<main id="view"></main><div id="modals"></div>';
  const calls = []; const toasts = []; const failures = [];
  const detail = fixture();
  const methods = {
    doctor: { schedules: async () => page([schedule('PUBLISHED')]), appointments: async () => page([]), encounters: async () => page([detail.encounter]), encounter: async () => detail,
      start: async () => detail, skip: async () => fixture('SKIPPED', false), saveRecord: async (id, payload) => ({ ...detail, medicalRecord: { ...detail.medicalRecord, ...payload, version: payload.version + 1 } }), complete: async () => fixture('COMPLETED') },
    appointments: { visitInfo: async () => ({ ...detail.encounter, status: 'BOOKED', canCheckIn: true }), encounter: async () => { throw fail(404); }, checkIn: async () => fixture('WAITING', false) },
    scheduleManagement: { page: async () => page([schedule()]), detail: async () => ({ schedule: schedule(), slots: [] }), batch: async (payload) => ({ count: payload.workDates.length }), cancel: async () => schedule('CANCELLED') },
    departments: { list: async () => [{ id: 1, name: '内科' }] }, doctors: { list: async () => [{ id: 2, name: '王医生' }] },
    clinicRooms: { list: async () => [{ id: 3, name: '一诊室' }] }, shiftTemplates: { list: async () => [{ id: 4, name: '上午' }] }, schedules: { publish: async () => ({ scheduleId: 91 }) }
  };
  Object.keys(overrides).forEach((group) => Object.assign(methods[group], overrides[group]));
  const api = {};
  Object.keys(methods).forEach((group) => { api[group] = {}; Object.keys(methods[group]).forEach((method) => {
    api[group][method] = (...args) => { calls.push({ method: group + '.' + method, args }); return methods[group][method](...args); };
  }); });
  const ctx = {
    api, profile: { role: 'DOCTOR', sub: '王医生' }, esc, icon: () => '', fmtDate: (value) => value, fmtDateTime: (value) => value || '—',
    todayIso: (offset = 0) => new Date(Date.UTC(2026, 9, 19 + offset)).toISOString().slice(0, 10),
    statusBadge: (status) => '<span>' + esc(status) + '</span>',
    emptyState: (title, description) => '<div class="empty">' + esc(title) + esc(description) + '</div>',
    errorPanel: (failure) => '<div class="error-panel">' + esc(failure.message) + '</div>',
    handleApiError: (failure) => { failures.push(failure); return failure.status === 401; },
    toast: (...args) => toasts.push(args),
    openModal(options) {
      const host = document.body.querySelector('#modals'); host.innerHTML = '';
      const modalRoot = document.createElement('div'); host.appendChild(modalRoot);
      modalRoot.innerHTML = '<div class="modal-body">' + options.body + '</div><footer>' + options.footer + '</footer>';
      return { root: modalRoot, close: () => modalRoot.remove() };
    }
  };
  const historyCalls = [];
  const window = { MedFlowPatientHistory: {
    openDoctor: async (id, context, options) => { historyCalls.push({ kind: 'doctor', id, options }); },
    openPatient: async (id, context, options) => { historyCalls.push({ kind: 'patient', id, options }); }
  } }; vm.runInNewContext(source, { window, Date, Set }, { filename: 'clinical.js' });
  const module = window.MedFlowClinical; const root = document.body.querySelector('#view');
  const $ = (selector) => document.body.querySelector(selector);
  return { module, root, ctx, calls, toasts, failures, $, methods, historyCalls };
}

async function openEncounter(h) { await h.module.views.doctorQueue(h.root, h.ctx); await h.$('[data-encounter]').fire('click'); }

test('doctor queue name opens history with appointment authorization anchor', async () => {
  const h = harness(); await h.module.views.doctorQueue(h.root, h.ctx); await h.$('[data-doctor-history]').fire('click');
  assert.equal(h.historyCalls[0].kind, 'doctor'); assert.equal(h.historyCalls[0].id, 101); assert.equal(h.historyCalls[0].options.returnLabel, '返回候诊队列');
});

test('unfinished note blocks history navigation and saved content can return to the current encounter', async () => {
  const h = harness(); await openEncounter(h);
  h.$('[name="chiefComplaint"]').value = '尚未保存的文字'; await h.$('#clinical-patient-history').fire('click');
  assert.equal(h.historyCalls.length, 0); assert.match(h.toasts.at(-1)[0], /先保存草稿/);
  h.$('[name="chiefComplaint"]').value = '头痛'; await h.$('#clinical-patient-history').fire('click');
  assert.equal(h.historyCalls[0].id, 101); assert.equal(h.historyCalls[0].options.returnLabel, '返回本次接诊');
  const before = h.calls.filter((call) => call.method === 'doctor.encounter').length;
  await h.historyCalls[0].options.onReturn(); assert.equal(h.calls.filter((call) => call.method === 'doctor.encounter').length, before + 1);
  assert.ok(h.$('#clinical-record'));
});

test('doctor appointment names offer history only after confirmation or an existing encounter', async () => {
  const h = harness({ doctor: { appointments: async () => page([
    { appointmentId: 101, patientName: '已确认', status: 'BOOKED', encounterId: null },
    { appointmentId: 102, patientName: '尚未确认', status: 'PENDING_CONFIRMATION', encounterId: null }
  ]) } }); await h.module.views.doctorAppointments(h.root, h.ctx);
  assert.ok(h.$('[data-doctor-history="101"]')); assert.equal(h.$('[data-doctor-history="102"]'), null);
});

test('patient current visit opens exact patient archive and returns to the same appointment', async () => {
  const h = harness(); await h.module.openPatientVisit(101, h.ctx); await h.$('#clinical-patient-history').fire('click');
  assert.equal(h.historyCalls[0].kind, 'patient'); assert.equal(h.historyCalls[0].id, 7);
  assert.equal(h.historyCalls[0].options.returnLabel, '返回本次预约');
  await h.historyCalls[0].options.onReturn(); assert.ok(h.$('#clinical-checkin'));
});

test('doctor dashboard reads actual totals independently and never invents successful zeroes', async () => {
  const h = harness({ doctor: { encounters: async (params) => { if (params.status === 'IN_PROGRESS') throw fail(500, '接诊暂不可用'); return page([], params.status === 'WAITING' ? 6 : 12); } } });
  await h.module.views.doctorOverview(h.root, h.ctx);
  const html = h.$('#clinical-overview').innerHTML;
  assert.match(html, /stat-value">6</); assert.match(html, /stat-value">12</); assert.match(html, /stat-value">—</); assert.match(html, /接诊暂不可用/);
  assert.equal(h.calls.filter((call) => call.method === 'doctor.encounters').length, 3);
  assert.ok(h.calls.every((call) => call.args[0].doctorId === undefined));
});

test('doctor schedules reject reversed and excessive date ranges without HTTP', async () => {
  const h = harness(); await h.module.views.doctorSchedules(h.root, h.ctx);
  const before = h.calls.length; h.$('#clinical-from').value = '2026-10-20'; h.$('#clinical-to').value = '2026-10-19';
  await h.$('#clinical-filters').fire('submit'); assert.equal(h.calls.length, before);
  h.$('#clinical-to').value = '2027-10-19'; await h.$('#clinical-filters').fire('submit'); assert.equal(h.calls.length, before);
  assert.equal(h.toasts.length, 2);
});

test('queue accepts only latest result when filters race', async () => {
  const first = deferred(); const second = deferred(); let request = 0;
  const h = harness({ doctor: { encounters: () => (++request === 1 ? first.promise : second.promise) } });
  const initial = h.module.views.doctorQueue(h.root, h.ctx);
  h.$('#clinical-status').value = 'COMPLETED'; const refreshed = h.$('#clinical-filters').fire('submit');
  second.resolve(page([{ ...fixture('COMPLETED').encounter, patientName: '新查询' }])); await refreshed;
  first.resolve(page([{ ...fixture().encounter, patientName: '旧查询' }])); await initial;
  assert.match(h.$('#clinical-list').innerHTML, /新查询/); assert.doesNotMatch(h.$('#clinical-list').innerHTML, /旧查询/);
});

test('dirty clinical note prevents completion until save returns new version', async () => {
  const h = harness(); await openEncounter(h);
  h.$('[name="chiefComplaint"]').value = '头痛两天'; await h.$('#clinical-complete').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'doctor.complete').length, 0);
  assert.match(h.toasts.at(-1)[0], /未保存/);
  await h.$('#clinical-record').fire('submit');
  const saved = h.calls.find((call) => call.method === 'doctor.saveRecord');
  assert.equal(saved.args[1].chiefComplaint, '头痛两天'); assert.equal(saved.args[1].version, 2);
  await h.$('#clinical-complete').fire('click');
  assert.equal(h.calls.find((call) => call.method === 'doctor.complete').args[1], 3);
  assert.equal(h.$('[name="chiefComplaint"]'), null); assert.match(h.$('.modal-body').innerHTML, /已完成/);
});

test('new record submits initial version zero and requires saved diagnosis for completion', async () => {
  const h = harness({ doctor: { encounter: async () => fixture('IN_PROGRESS', false) } }); await openEncounter(h);
  await h.$('#clinical-complete').fire('click'); assert.equal(h.calls.filter((call) => call.method === 'doctor.complete').length, 0);
  h.$('[name="chiefComplaint"]').value = '咳嗽'; h.$('[name="diagnosis"]').value = '诊断';
  await h.$('#clinical-record').fire('submit');
  assert.equal(h.calls.find((call) => call.method === 'doctor.saveRecord').args[1].version, 0);
});

test('record conflict preserves authored text and offers explicit reload', async () => {
  const h = harness({ doctor: { saveRecord: async () => { throw fail(409, '病历已更新'); } } }); await openEncounter(h);
  h.$('[name="diagnosis"]').value = '本页诊断'; await h.$('#clinical-record').fire('submit');
  assert.equal(h.$('[name="diagnosis"]').value, '本页诊断'); assert.match(h.$('#clinical-record-error').innerHTML, /病历已更新/);
  assert.ok(h.$('#clinical-reload')); assert.equal(h.$('[name="diagnosis"]').disabled, false);
});

test('double start is serialized and server rejection remains visible', async () => {
  const gate = deferred(); const h = harness({ doctor: { encounter: async () => fixture('WAITING', false), start: () => gate.promise } }); await openEncounter(h);
  const button = h.$('#clinical-start'); const first = button.fire('click'); const second = button.fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'doctor.start').length, 1);
  gate.reject(fail(409, '时段尚未开始')); await Promise.all([first, second]);
  assert.match(h.$('#clinical-record-error').innerHTML, /时段尚未开始/); assert.equal(button.disabled, false);
});

test('marking skipped requires explicit acknowledgement', async () => {
  const h = harness({ doctor: { encounter: async () => fixture('WAITING', false) } }); await openEncounter(h);
  await h.$('#clinical-skip').fire('click'); assert.equal(h.calls.filter((call) => call.method === 'doctor.skip').length, 0);
  h.$('#clinical-skip-confirm').checked = true; await h.$('#clinical-skip').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'doctor.skip').length, 1); assert.match(h.$('.modal-body').innerHTML, /已过号/);
});

test('patient missing encounter is normal and checkin rereads authoritative detail', async () => {
  let checked = false;
  const h = harness({ appointments: { encounter: async () => { if (!checked) throw fail(404); return fixture('WAITING', false); }, checkIn: async () => { checked = true; return fixture('WAITING', false); } } });
  await h.module.openPatientVisit(101, h.ctx);
  assert.match(h.$('.modal-body').innerHTML, /王医生/); assert.ok(h.$('#clinical-checkin')); assert.equal(h.failures.length, 0);
  await h.$('#clinical-checkin').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'appointments.visitInfo').length, 2);
  assert.match(h.$('.modal-body').innerHTML, /待就诊/); assert.equal(h.$('#clinical-checkin'), null);
});

test('patient checkin permission comes from server and unfinished record is never rendered', async () => {
  const h = harness({ appointments: { visitInfo: async () => ({ ...fixture().encounter, status: 'BOOKED', canCheckIn: false }) } });
  await h.module.openPatientVisit(101, h.ctx); assert.equal(h.$('#clinical-checkin'), null);
  h.methods.appointments.encounter = async () => fixture('IN_PROGRESS'); await h.$('#clinical-visit-refresh').fire('click');
  assert.doesNotMatch(h.$('.modal-body').innerHTML, /头痛/); assert.match(h.$('.modal-body').innerHTML, /病历在医生完成接诊后开放查看/);
});

test('patient completed note is escaped and visible, while non404 failure does not look like unsigned', async () => {
  const h = harness({ appointments: { encounter: async () => ({ ...fixture('COMPLETED'), medicalRecord: { ...fixture().medicalRecord, diagnosis: '<script>alert(1)</script>' } }) } });
  await h.module.openPatientVisit(101, h.ctx);
  assert.match(h.$('.modal-body').innerHTML, /&lt;script&gt;/); assert.doesNotMatch(h.$('.modal-body').innerHTML, /<script>/);
  h.methods.appointments.encounter = async () => { throw fail(500, '读取失败'); }; await h.$('#clinical-visit-refresh').fire('click');
  assert.match(h.$('.modal-body').innerHTML, /读取失败/); assert.equal(h.$('#clinical-checkin'), null);
});

test('patient duplicate checkin clicks issue only one request', async () => {
  const gate = deferred(); const h = harness({ appointments: { checkIn: () => gate.promise } });
  await h.module.openPatientVisit(101, h.ctx); const button = h.$('#clinical-checkin');
  const first = button.fire('click'); const second = button.fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'appointments.checkIn').length, 1);
  gate.reject(fail(409)); await Promise.all([first, second]); assert.equal(button.disabled, false);
});

test('administrator directory uses server rows and opens real schedule detail', async () => {
  const h = harness(); await h.module.views.adminSchedules(h.root, h.ctx);
  assert.equal(h.calls.filter((call) => call.method === 'scheduleManagement.page').length, 1);
  assert.match(h.$('#clinical-list').innerHTML, /王医生/); await h.$('[data-schedule]').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'scheduleManagement.detail').length, 1);
  assert.ok(h.$('#clinical-publish')); assert.match(h.$('.modal-body').innerHTML, /有效预约/);
});

test('batch validation rejects duplicates and malformed calendar days, then sends one atomic payload', async () => {
  const h = harness(); await h.module.views.adminSchedules(h.root, h.ctx); await h.$('#clinical-new-schedule').fire('click');
  h.$('#clinical-create-more').value = '2026-10-20'; await h.$('#clinical-create-form').fire('submit');
  assert.equal(h.calls.filter((call) => call.method === 'scheduleManagement.batch').length, 0);
  h.$('#clinical-create-more').value = '2026-11-31'; await h.$('#clinical-create-form').fire('submit');
  assert.equal(h.calls.filter((call) => call.method === 'scheduleManagement.batch').length, 0);
  h.$('#clinical-create-more').value = '2026-10-21, 2026-10-22'; await h.$('#clinical-create-form').fire('submit');
  const call = h.calls.find((item) => item.method === 'scheduleManagement.batch');
  assert.deepEqual(JSON.parse(JSON.stringify(call.args[0])), { doctorId: 2, clinicRoomId: 3, shiftTemplateId: 4, workDates: ['2026-10-20', '2026-10-21', '2026-10-22'] });
  assert.equal(h.$('#clinical-create-form'), null);
});

test('schedule cancellation requires a reason and server conflict preserves it', async () => {
  const h = harness({ scheduleManagement: { cancel: async () => { throw fail(409, '已有有效预约'); } } }); await h.module.views.adminSchedules(h.root, h.ctx); await h.$('[data-schedule]').fire('click');
  await h.$('#clinical-cancel-schedule').fire('click'); assert.equal(h.calls.filter((call) => call.method === 'scheduleManagement.cancel').length, 0);
  h.$('#clinical-cancel-reason').value = '医生临时休假'; await h.$('#clinical-cancel-schedule').fire('click');
  assert.deepEqual(h.calls.find((call) => call.method === 'scheduleManagement.cancel').args, [91, '医生临时休假']);
  assert.equal(h.$('#clinical-cancel-reason').value, '医生临时休假'); assert.match(h.$('#clinical-schedule-error').innerHTML, /已有有效预约/);
});

test('closing a pending doctor detail discards late response without touching a new modal', async () => {
  const gate = deferred(); const h = harness({ doctor: { encounter: () => gate.promise } });
  await h.module.views.doctorQueue(h.root, h.ctx); const pending = h.$('[data-encounter]').fire('click');
  await h.module.openPatientVisit(101, h.ctx); const patientBody = h.$('.modal-body');
  gate.resolve(fixture()); await pending; assert.equal(h.$('.modal-body'), patientBody); assert.match(patientBody.innerHTML, /确认到院签到/);
});
