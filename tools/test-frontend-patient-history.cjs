'use strict';

// Real read-only history module with controlled DOM and HTTP.
// Run: node --test tools/test-frontend-patient-history.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/patient-history.js'), 'utf8');

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

function visit(overrides = {}) {
  return { appointmentId: 81, workDate: '2026-10-20', startTime: '08:00:00', endTime: '08:30:00', doctorId: 2, doctorName: '王医生',
    departmentName: '内科', clinicRoomName: '一诊室', appointmentStatus: 'BOOKED', encounterId: 61, encounterStatus: 'COMPLETED', chiefComplaint: '头痛', diagnosis: '模拟诊断',
    prescriptionId: 21, billStatus: 'PAID', totalAmount: '24.60', ...overrides };
}
function historyPage(items = [visit()], overrides = {}) {
  return { patient: { id: 7, name: '李明', gender: 'MALE', relationship: 'SELF', birthDate: '1990-01-01', maskedPhone: '138****0000' }, items, total: items.length, page: 1, size: 10, ...overrides };
}
function detail(overrides = {}) {
  return { visit: visit(), medicalRecord: { chiefComplaint: '头痛', presentIllness: '现病史正文', diagnosis: '模拟诊断', advice: '医嘱正文' },
    prescription: { prescription: { id: 21, doctorName: '王医生', departmentName: '内科', status: 'ISSUED', totalAmount: '24.60', issuedAt: '2026-10-20T08:15:00' },
      items: [{ drugId: 11, drugName: '模拟药品', drugCode: 'DEMO-1', specification: '12片', unit: '盒', quantity: 2, unitPrice: '12.30', lineAmount: '24.60', instructions: '模拟说明' }],
      bill: { id: 41, status: 'PAID', totalAmount: '24.60', paidAt: '2026-10-20T08:20:00' } },
    appointmentHistory: [{ eventType: 'CONFIRMED', fromStatus: 'PENDING_CONFIRMATION', toStatus: 'BOOKED', actorUserId: 3, createdAt: '2026-10-19T09:00:00' }],
    encounterHistory: [{ eventType: 'COMPLETED', fromStatus: 'IN_PROGRESS', toStatus: 'COMPLETED', actorUserId: 2, createdAt: '2026-10-20T08:18:00' }], ...overrides };
}
function harness(overrides = {}) {
  const document = { createElement(tag) { return new Element(tag, this); } };
  document.body = document.createElement('body'); document.body.innerHTML = '<main id="view"></main><div id="modals"></div>';
  const calls = [], failures = [], returns = [];
  const methods = { patientHistory: { page: async () => historyPage(), detail: async () => detail() }, doctorHistory: { page: async () => historyPage(), detail: async () => detail() } };
  Object.keys(overrides).forEach((group) => Object.assign(methods[group], overrides[group]));
  const api = {}; Object.keys(methods).forEach((group) => { api[group] = {}; Object.keys(methods[group]).forEach((method) => {
    api[group][method] = (...args) => { calls.push({ method: group + '.' + method, args }); return methods[group][method](...args); };
  }); });
  const ctx = { api, esc, fmtDate: (value) => value, fmtDateTime: (value) => value || '—',
    emptyState: (title, description) => '<div class="empty">' + esc(title) + esc(description) + '</div>',
    errorPanel: (error) => '<div class="error-panel">' + esc(error.message) + '</div>',
    handleApiError: (failure) => { failures.push(failure); return failure.status === 401; },
    openModal(options) {
      const host = document.body.querySelector('#modals'); host.innerHTML = '';
      const node = document.createElement('div'); host.appendChild(node);
      node.innerHTML = '<div class="modal-body">' + options.body + '</div><footer>' + options.footer + '</footer>';
      return { root: node, close: () => node.remove() };
    } };
  const window = {}; vm.runInNewContext(source, { window }, { filename: 'patient-history.js' });
  return { module: window.MedFlowPatientHistory, ctx, calls, failures, returns, methods, $: (selector) => document.body.querySelector(selector) };
}

test('patient profile shows masked identity and real cross-doctor visits', async () => {
  const h = harness({ patientHistory: { page: async () => historyPage([visit(), visit({ appointmentId: 82, doctorName: '陈医生', departmentName: '外科', workDate: '2026-10-19' })]) } });
  await h.module.openPatient(7, h.ctx);
  const html = h.$('.modal-body').innerHTML; assert.match(html, /李明/); assert.match(html, /138\*\*\*\*0000/);
  assert.match(html, /王医生/); assert.match(html, /陈医生/); assert.match(html, /检查、化验数据未接入/);
  assert.equal(h.calls[0].args[0], 7); assert.equal(h.calls[0].args[1].page, 1);
});

test('one visit detail contains all four note fields, prescriber, medicine prices and timelines', async () => {
  const h = harness(); await h.module.openPatient(7, h.ctx); await h.$('[data-history-visit]').fire('click');
  const html = h.$('.modal-body').innerHTML;
  for (const text of ['头痛', '现病史正文', '模拟诊断', '医嘱正文', '开具医生', '模拟药品', '¥ 12.30', '¥ 24.60', '已模拟缴费', '预约状态时间线', '接诊状态时间线', '确认预约', '完成接诊']) assert.ok(html.includes(text), text);
  assert.equal(h.$('textarea'), null); assert.equal(h.$('#biz-pay'), null);
  assert.deepEqual(h.calls.find((call) => call.method === 'patientHistory.detail').args, [7, 81]);
});

test('timeline explains the acting role and preserves dates without exposing internal account ids', async () => {
  const data = detail();
  data.appointmentHistory = [
    { eventType: 'CONFIRMED', fromStatus: 'PENDING_CONFIRMATION', toStatus: 'BOOKED', actorUserId: 1234567, createdAt: '2026-10-19T09:00:00' },
    { eventType: 'EXPIRED', fromStatus: 'PENDING_CONFIRMATION', toStatus: 'EXPIRED', actorUserId: null, createdAt: '2026-10-19T09:15:00' },
    { eventType: 'IMPORTED', fromStatus: null, toStatus: 'BOOKED', actorUserId: 7654321, createdAt: '2026-10-19T10:00:00' }
  ];
  const h = harness({ patientHistory: { detail: async () => data } }); await h.module.openPatient(7, h.ctx); await h.$('[data-history-visit]').fire('click');
  const html = h.$('.modal-body').innerHTML;
  for (const label of ['患者操作', '接诊医生操作', '系统处理', '登记操作', '2026-10-19T09:15:00']) assert.ok(html.includes(label), label);
  assert.doesNotMatch(html, /1234567|7654321|操作人 #/);
});

test('unfinished note text and unissued prescription are never rendered even if response contains them', async () => {
  const draft = detail(); draft.visit = visit({ encounterStatus: 'IN_PROGRESS', chiefComplaint: '秘密主诉', diagnosis: '秘密诊断', prescriptionId: null });
  draft.medicalRecord.chiefComplaint = '秘密正文'; draft.prescription.prescription.status = 'DRAFT'; draft.prescription.items[0].drugName = '秘密草稿药品';
  const h = harness({ patientHistory: { page: async () => historyPage([draft.visit]), detail: async () => draft } });
  await h.module.openPatient(7, h.ctx); assert.doesNotMatch(h.$('.modal-body').innerHTML, /秘密主诉|秘密诊断/);
  await h.$('[data-history-visit]').fire('click'); assert.doesNotMatch(h.$('.modal-body').innerHTML, /秘密正文|秘密草稿药品/);
  assert.match(h.$('.modal-body').innerHTML, /尚未完成接诊/); assert.match(h.$('.modal-body').innerHTML, /没有已提交的处方记录/);
});

test('history missing encounter and clinical records is explicit rather than fabricated', async () => {
  const h = harness({ patientHistory: { detail: async () => detail({ visit: visit({ encounterId: null, encounterStatus: null, prescriptionId: null }), medicalRecord: null, prescription: null, appointmentHistory: [], encounterHistory: [] }) } });
  await h.module.openPatient(7, h.ctx); await h.$('[data-history-visit]').fire('click');
  const html = h.$('.modal-body').innerHTML; assert.match(html, /本次没有接诊或病历记录/); assert.match(html, /没有记录到状态变化/); assert.match(html, /检查、化验、影像等数据未接入/);
});

test('doctor history always uses authorized anchor appointment instead of a caller supplied patient id', async () => {
  const h = harness(); await h.module.openDoctor(501, h.ctx); await h.$('[data-history-visit]').fire('click');
  assert.deepEqual(h.calls.find((call) => call.method === 'doctorHistory.detail').args, [501, 81]);
  assert.equal(h.calls.some((call) => call.method.startsWith('patientHistory.')), false);
});

test('pagination and return from detail preserve the current list page', async () => {
  const h = harness({ patientHistory: { page: async (id, params) => historyPage([visit()], { total: 21, page: params.page }) } });
  await h.module.openPatient(7, h.ctx); assert.equal(h.$('#patient-history-prev').disabled, true);
  await h.$('#patient-history-next').fire('click'); assert.match(h.$('.modal-body').innerHTML, /第 2 \/ 3 页/);
  await h.$('[data-history-visit]').fire('click'); await h.$('#patient-history-back').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'patientHistory.page').at(-1).args[1].page, 2);
});

test('back during a slow detail query ignores its late response', async () => {
  const gate = deferred(); const h = harness({ patientHistory: { detail: () => gate.promise } });
  await h.module.openPatient(7, h.ctx); const pending = h.$('[data-history-visit]').fire('click');
  await h.$('#patient-history-back').fire('click'); gate.resolve(detail()); await pending;
  assert.ok(h.$('[data-history-visit]')); assert.equal(h.$('#patient-history-back'), null);
});

test('explicit return closes archive and restores the current encounter through callback', async () => {
  const h = harness(); let restored = 0;
  await h.module.openDoctor(501, h.ctx, { returnLabel: '返回本次接诊', onReturn: async () => { restored++; } });
  assert.match(h.$('#patient-history-return').textContent, /返回本次接诊/);
  await h.$('#patient-history-return').fire('click'); assert.equal(restored, 1); assert.equal(h.$('.modal-body'), null);
});

test('history escapes profile, medical, prescription and event text', async () => {
  const data = detail(); data.medicalRecord.advice = '<script>bad()</script>'; data.prescription.items[0].instructions = '<img onerror=bad()>';
  data.appointmentHistory[0].eventType = '<svg bad>'; const listing = historyPage(); listing.patient.name = '<iframe bad>';
  const h = harness({ patientHistory: { page: async () => listing, detail: async () => data } });
  await h.module.openPatient(7, h.ctx); assert.match(h.$('.modal-body').innerHTML, /&lt;iframe/);
  await h.$('[data-history-visit]').fire('click'); const html = h.$('.modal-body').innerHTML;
  assert.match(html, /&lt;script&gt;/); assert.match(html, /&lt;img/); assert.match(html, /&lt;svg/); assert.doesNotMatch(html, /<script|<iframe|<img|<svg/);
});

test('empty history and transient detail failure provide clear recoverable states', async () => {
  const h = harness({ patientHistory: { page: async () => historyPage([]) } }); await h.module.openPatient(7, h.ctx);
  assert.match(h.$('.modal-body').innerHTML, /暂无院内就诊记录/);
  h.methods.patientHistory.page = async () => historyPage(); await h.$('#patient-history-refresh').fire('click');
  h.methods.patientHistory.detail = async () => { throw fail(500, '档案暂不可用'); }; await h.$('[data-history-visit]').fire('click');
  assert.match(h.$('.modal-body').innerHTML, /档案暂不可用/); assert.ok(h.$('#patient-history-back')); assert.ok(h.$('#patient-history-retry'));
  h.methods.patientHistory.detail = async () => detail(); await h.$('#patient-history-retry').fire('click'); assert.match(h.$('.modal-body').innerHTML, /医嘱正文/);
});

test('closed archive ignores a pending list and never overwrites a newer modal', async () => {
  const gate = deferred(); const h = harness({ patientHistory: { page: () => gate.promise } });
  const opening = h.module.openPatient(7, h.ctx); h.ctx.openModal({ body: '新的工作', footer: '' });
  gate.resolve(historyPage()); await opening; assert.equal(h.$('.modal-body').innerHTML, '新的工作');
});

test('a late response for one family member cannot replace another member archive', async () => {
  const gate = deferred(); const h = harness({ patientHistory: { page: (id) => id === 7 ? gate.promise : Promise.resolve(historyPage([], { patient: { id: 8, name: '家属档案', gender: 'FEMALE', relationship: '父母', birthDate: '1960-01-01', maskedPhone: '139****0000' } })) } });
  const first = h.module.openPatient(7, h.ctx); await h.module.openPatient(8, h.ctx);
  gate.resolve(historyPage()); await first;
  const html = h.$('.modal-body').innerHTML; assert.match(html, /家属档案/); assert.doesNotMatch(html, /李明|头痛/);
});
