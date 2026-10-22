'use strict';

// Real business UI module, controlled DOM and HTTP; no npm or browser required.
// Run: node --test tools/test-frontend-business.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/business.js'), 'utf8');

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

function drug(overrides = {}) {
  return { id: 11, code: 'DRUG-A', name: '演示药品', specification: '每盒12片', unit: '盒', unitPrice: '12.30', status: 'ENABLED', version: 2, ...overrides };
}
function rx(status = 'DRAFT', version = 2) {
  return { prescription: { id: 21, encounterId: 61, appointmentId: 81, patientName: '李明', doctorName: '王医生', departmentName: '内科',
    status, totalAmount: '36.90', version, createdAt: '2026-10-20T09:00:00', issuedAt: status === 'ISSUED' ? '2026-10-20T09:01:00' : null },
  items: [{ id: 31, drugId: 11, drugCode: 'DRUG-A', drugName: '演示药品', specification: '每盒12片', unit: '盒', quantity: 3, instructions: '仅用于模拟测试', unitPrice: '12.30', lineAmount: '36.90' }],
  bill: status === 'ISSUED' ? { id: 41, prescriptionId: 21, totalAmount: '36.90', status: 'UNPAID', paidAt: null } : null };
}
function stats(overrides = {}) {
  return { from: '2026-10-14', to: '2026-10-20', appointmentTrend: [{ date: '2026-10-19', count: 0 }, { date: '2026-10-20', count: 0 }],
    departmentVisits: [], doctorWorkload: [], ...overrides };
}
function harness(overrides = {}) {
  const document = { createElement(tag) { return new Element(tag, this); } };
  document.body = document.createElement('body'); document.body.innerHTML = '<main id="view"></main><div id="modals"></div>';
  const calls = []; const toasts = []; const failures = [];
  const methods = {
    drugs: { page: async () => page([drug()]), create: async (payload) => drug(payload), update: async (id, payload) => drug(payload), available: async () => page([drug()]) },
    doctor: { encounter: async () => ({ encounter: { id: 61, patientName: '李明', doctorName: '王医生', departmentName: '内科', status: 'IN_PROGRESS' } }) },
    prescriptions: { doctorDetail: async () => rx(), save: async (id, payload) => ({ ...rx(), prescription: { ...rx().prescription, version: payload.version + 1, totalAmount: '24.60' },
      items: payload.items.map((item) => ({ ...rx().items[0], ...item, lineAmount: '24.60' })) }),
      issue: async (id, version) => rx('ISSUED', version + 1), discard: async (id, version) => ({ ...rx('DISCARDED', version + 1), items: [], prescription: { ...rx('DISCARDED', version + 1).prescription, totalAmount: '0.00' } }),
      patientPage: async () => page([{ ...rx('ISSUED').prescription, bill: rx('ISSUED').bill }]), patientDetail: async () => rx('ISSUED'),
      pay: async () => ({ ...rx('ISSUED'), bill: { ...rx('ISSUED').bill, status: 'PAID', paidAt: '2026-10-20T09:02:00' } }) },
    statistics: { get: async () => stats() }
  };
  Object.keys(overrides).forEach((group) => Object.assign(methods[group], overrides[group]));
  const api = {}; Object.keys(methods).forEach((group) => { api[group] = {}; Object.keys(methods[group]).forEach((method) => {
    api[group][method] = (...args) => { calls.push({ method: group + '.' + method, args }); return methods[group][method](...args); };
  }); });
  const ctx = { api, esc, profile: { role: 'ADMIN', sub: '管理员' }, icon: () => '', fmtDate: (value) => value, fmtDateTime: (value) => value || '—',
    todayIso: (offset = 0) => new Date(Date.UTC(2026, 9, 20 + offset)).toISOString().slice(0, 10),
    emptyState: (title, description) => '<div class="empty">' + esc(title) + esc(description) + '</div>',
    errorPanel: (failure) => '<div class="error-panel">' + esc(failure.message) + '</div>',
    handleApiError: (failure) => { failures.push(failure); return failure.status === 401; }, toast: (...args) => toasts.push(args),
    openModal(options) {
      const host = document.body.querySelector('#modals'); host.innerHTML = '';
      const node = document.createElement('div'); host.appendChild(node);
      node.innerHTML = '<div class="modal-body">' + options.body + '</div><footer>' + options.footer + '</footer>';
      return { root: node, close: () => node.remove() };
    } };
  const window = {}; vm.runInNewContext(source, { window, Date, Set }, { filename: 'business.js' });
  const module = window.MedFlowBusiness; const root = document.body.querySelector('#view');
  const $ = (selector) => document.body.querySelector(selector);
  return { module, root, ctx, calls, toasts, failures, $, methods };
}
async function openRx(h) { await h.module.openDoctorPrescription(61, h.ctx); await Promise.resolve(); }
async function openBill(h) { await h.module.views.patientPrescriptions(h.root, h.ctx); await h.$('[data-biz-prescription]').fire('click'); }

test('drug directory shows two decimals and escapes display strings', async () => {
  const h = harness({ drugs: { page: async () => page([drug({ name: '<img onerror=alert(1)>', unitPrice: '0.10' })]) } });
  await h.module.views.adminDrugs(h.root, h.ctx);
  assert.match(h.$('#biz-drug-list').innerHTML, /¥ 0\.10/); assert.match(h.$('#biz-drug-list').innerHTML, /&lt;img/);
  assert.doesNotMatch(h.$('#biz-drug-list').innerHTML, /<img/);
});

test('new drug rejects invalid prices and sends a valid decimal string once', async () => {
  const gate = deferred(); const h = harness({ drugs: { create: () => gate.promise } });
  await h.module.views.adminDrugs(h.root, h.ctx); await h.$('#biz-new-drug').fire('click');
  h.$('#biz-drug-code').value = 'DRUG-B'; h.$('#biz-drug-name').value = '药品B'; h.$('#biz-drug-specification').value = '规格'; h.$('#biz-drug-unit').value = '盒';
  for (const invalid of ['0', '-1', '1.001', '1000000', '1e2']) { h.$('#biz-drug-price').value = invalid; await h.$('#biz-drug-form').fire('submit'); }
  assert.equal(h.calls.filter((call) => call.method === 'drugs.create').length, 0);
  h.$('#biz-drug-price').value = '0.10'; const form = h.$('#biz-drug-form'); const first = form.fire('submit'); const second = form.fire('submit');
  assert.equal(h.calls.filter((call) => call.method === 'drugs.create').length, 1);
  assert.equal(h.calls.find((call) => call.method === 'drugs.create').args[0].unitPrice, '0.10');
  gate.resolve(drug()); await Promise.all([first, second]);
});

test('drug version conflict preserves edits and does not send a mutable code', async () => {
  const h = harness({ drugs: { update: async () => { throw fail(409, '版本已更新'); } } });
  await h.module.views.adminDrugs(h.root, h.ctx); await h.$('[data-biz-drug]').fire('click');
  h.$('#biz-drug-name').value = '本页名称'; h.$('#biz-drug-code').value = 'FORCED-CODE'; await h.$('#biz-drug-form').fire('submit');
  const payload = h.calls.find((call) => call.method === 'drugs.update').args[1];
  assert.equal(payload.version, 2); assert.equal(payload.code, undefined); assert.equal(h.$('#biz-drug-name').value, '本页名称');
  assert.match(h.$('#biz-drug-error').innerHTML, /版本已更新/); assert.ok(h.$('#biz-drug-reload'));
  assert.equal(h.$('#biz-drug-code').disabled, true);
});

test('doctor saves only command fields and uses the returned server amount', async () => {
  const h = harness(); await openRx(h);
  h.$('[data-biz-quantity]').value = '2'; h.$('[data-biz-instructions]').value = '新的模拟说明'; await h.$('#biz-rx-save').fire('click');
  const payload = h.calls.find((call) => call.method === 'prescriptions.save').args[1];
  assert.deepEqual(JSON.parse(JSON.stringify(payload)), { version: 2, items: [{ drugId: 11, quantity: 2, instructions: '新的模拟说明' }] });
  assert.match(h.$('.modal-body').innerHTML, /¥ 24\.60/); assert.equal(payload.totalAmount, undefined);
});

test('saving prescription preserves disabled catalogue pagination', async () => {
  const h = harness(); await openRx(h);
  assert.equal(h.$('[data-biz-page="0"]').disabled, true);
  assert.equal(h.$('[data-biz-page="2"]').disabled, true);
  await h.$('#biz-rx-save').fire('click');
  assert.equal(h.$('[data-biz-page="0"]').disabled, true);
  assert.equal(h.$('[data-biz-page="2"]').disabled, true);
});

test('failed prescription save restores editing without enabling unavailable catalogue pages', async () => {
  const h = harness({ prescriptions: { save: async () => { throw fail(409, '版本冲突'); } } }); await openRx(h);
  await h.$('#biz-rx-save').fire('click');
  assert.equal(h.$('[data-biz-page="0"]').disabled, true); assert.equal(h.$('[data-biz-page="2"]').disabled, true);
  assert.equal(h.$('[data-biz-quantity]').disabled, false); assert.equal(h.$('[data-biz-add]').disabled, false);
});

test('first prescription save uses version zero and draft text survives adding another drug', async () => {
  const h = harness({ prescriptions: { doctorDetail: async () => null }, drugs: { available: async () => page([drug(), drug({ id: 12, code: 'DRUG-B', name: '药品B', unitPrice: '0.10' })]) } });
  await openRx(h); await h.$('[data-biz-add="11"]').fire('click');
  h.$('[data-biz-quantity]').value = '7'; h.$('[data-biz-instructions]').value = '添加其他药品时保留';
  await h.$('[data-biz-add="12"]').fire('click');
  assert.equal(h.$('[data-biz-quantity]').value, '7'); assert.equal(h.$('[data-biz-instructions]').value, '添加其他药品时保留');
  await h.$('#biz-rx-save').fire('click');
  const command = h.calls.find((call) => call.method === 'prescriptions.save').args[1];
  assert.equal(command.version, 0); assert.equal(command.items.length, 2); assert.equal(command.items[0].quantity, 7);
});

test('doctor cannot issue unsaved changes or skip explicit review acknowledgement', async () => {
  const h = harness(); await openRx(h);
  await h.$('#biz-rx-issue').fire('click'); assert.equal(h.calls.filter((call) => call.method === 'prescriptions.issue').length, 0);
  h.$('#biz-rx-confirm').checked = true; h.$('[data-biz-quantity]').value = '4'; await h.$('#biz-rx-issue').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'prescriptions.issue').length, 0); assert.match(h.toasts.at(-1)[0], /未保存/);
});

test('duplicate issue clicks are serialized and issued snapshots become read only', async () => {
  const gate = deferred(); const h = harness({ prescriptions: { issue: () => gate.promise } }); await openRx(h);
  h.$('#biz-rx-confirm').checked = true; const button = h.$('#biz-rx-issue'); const first = button.fire('click'); const second = button.fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'prescriptions.issue').length, 1);
  assert.deepEqual(h.calls.find((call) => call.method === 'prescriptions.issue').args, [61, 2]);
  gate.resolve(rx('ISSUED', 3)); await Promise.all([first, second]);
  assert.equal(h.$('#biz-rx-save'), null); assert.equal(h.$('[data-biz-quantity]'), null); assert.match(h.$('.modal-body').innerHTML, /¥ 36\.90/);
  assert.match(h.$('.modal-body').innerHTML, /已提交并冻结/);
});

test('prescription version conflict preserves quantity and instructions until explicit reload', async () => {
  const h = harness({ prescriptions: { save: async () => { throw fail(409, '处方版本冲突'); } } }); await openRx(h);
  h.$('[data-biz-quantity]').value = '8'; h.$('[data-biz-instructions]').value = '保留的文字'; await h.$('#biz-rx-save').fire('click');
  assert.equal(h.$('[data-biz-quantity]').value, '8'); assert.equal(h.$('[data-biz-instructions]').value, '保留的文字');
  assert.ok(h.$('#biz-rx-reload')); assert.match(h.$('#biz-rx-error').innerHTML, /处方版本冲突/);
});

test('duplicate drugs and out of bounds quantities do not create an invalid prescription', async () => {
  const h = harness(); await openRx(h); await h.$('[data-biz-add]').fire('click');
  assert.equal(h.root.ownerDocument.body.querySelectorAll('[data-biz-item]').length, 1);
  h.$('[data-biz-quantity]').value = '1001'; await h.$('#biz-rx-save').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'prescriptions.save').length, 0);
  h.$('[data-biz-quantity]').value = '1.5'; await h.$('#biz-rx-save').fire('click');
  assert.equal(h.calls.filter((call) => call.method === 'prescriptions.save').length, 0);
});

test('discard needs acknowledgement and recovered draft uses current version instead of zero', async () => {
  const h = harness(); await openRx(h);
  await h.$('#biz-rx-discard').fire('click'); assert.equal(h.calls.filter((call) => call.method === 'prescriptions.discard').length, 0);
  h.$('#biz-rx-discard-confirm').checked = true; await h.$('#biz-rx-discard').fire('click'); await Promise.resolve();
  assert.equal(h.$('[data-biz-item]'), null); assert.equal(h.$('#biz-rx-issue'), null); assert.match(h.$('.modal-body').innerHTML, /上一份草稿已放弃/);
  await h.$('[data-biz-add]').fire('click'); await h.$('#biz-rx-save').fire('click');
  assert.equal(h.calls.find((call) => call.method === 'prescriptions.save').args[1].version, 3);
});

test('a completed encounter cannot create a new prescription and issued prices stay frozen', async () => {
  const h = harness({ doctor: { encounter: async () => ({ encounter: { id: 61, status: 'COMPLETED' } }) }, prescriptions: { doctorDetail: async () => rx('ISSUED') } });
  await openRx(h); assert.equal(h.$('#biz-rx-save'), null); assert.equal(h.calls.filter((call) => call.method === 'drugs.available').length, 0);
  assert.match(h.$('.modal-body').innerHTML, /¥ 12\.30/); assert.match(h.$('.modal-body').innerHTML, /¥ 36\.90/);
});

test('patient only sees submitted records with explicit simulated billing description', async () => {
  const h = harness(); await openBill(h);
  assert.match(h.$('.modal-body').innerHTML, /不会发生实际扣款/); assert.match(h.$('#biz-patient-list').innerHTML, /¥ 36\.90/);
  h.methods.prescriptions.patientDetail = async () => rx('DRAFT'); await h.$('#biz-bill-refresh').fire('click');
  assert.match(h.$('.modal-body').innerHTML, /尚未提交/); assert.doesNotMatch(h.$('.modal-body').innerHTML, /仅用于模拟测试/);
});

test('patient pay sends no amount, requires confirmation and serializes duplicate clicks', async () => {
  const gate = deferred(); const h = harness({ prescriptions: { pay: () => gate.promise } }); await openBill(h);
  await h.$('#biz-pay').fire('click'); assert.equal(h.calls.filter((call) => call.method === 'prescriptions.pay').length, 0);
  h.$('#biz-pay-confirm').checked = true; const button = h.$('#biz-pay'); const first = button.fire('click'); const second = button.fire('click');
  assert.deepEqual(h.calls.find((call) => call.method === 'prescriptions.pay').args, [41]);
  assert.equal(h.calls.filter((call) => call.method === 'prescriptions.pay').length, 1);
  gate.resolve({ ...rx('ISSUED'), bill: { ...rx('ISSUED').bill, status: 'PAID', paidAt: '2026-10-20T09:02:00' } }); await Promise.all([first, second]);
  assert.equal(h.$('#biz-pay'), null); assert.match(h.$('.modal-body').innerHTML, /已模拟缴费/);
});

test('patient bill content is escaped and failed payment can refresh authoritative status', async () => {
  const detail = rx('ISSUED'); detail.items[0].instructions = '<script>alert(1)</script>';
  const h = harness({ prescriptions: { patientDetail: async () => detail, pay: async () => { throw fail(500, '网络异常'); } } });
  await openBill(h); assert.match(h.$('.modal-body').innerHTML, /&lt;script&gt;/); assert.doesNotMatch(h.$('.modal-body').innerHTML, /<script>/);
  h.$('#biz-pay-confirm').checked = true; await h.$('#biz-pay').fire('click');
  assert.match(h.$('#biz-pay-error').innerHTML, /刷新账单状态/); assert.equal(h.$('#biz-bill-refresh').disabled, false);
});

test('statistics preserve real zeroes, display three tables and explain counting rules', async () => {
  const h = harness(); await h.module.views.adminStatistics(h.root, h.ctx);
  const html = h.$('#biz-stat-result').innerHTML;
  assert.equal((html.match(/stat-value">0</g) || []).length, 2);
  assert.equal((html.match(/数据表<\/summary>/g) || []).length, 3);
  assert.match(html, /每天创建量均为 0/); assert.match(html, /包含后续取消、过期/); assert.match(html, /completed_at/);
  assert.doesNotMatch(html, /NaN|Infinity/);
});

test('statistics validates the inclusive 93 day bound before making a request', async () => {
  const h = harness(); await h.module.views.adminStatistics(h.root, h.ctx); const before = h.calls.length;
  h.$('#biz-stat-from').value = '2026-01-01'; h.$('#biz-stat-to').value = '2026-04-04'; await h.$('#biz-stat-filter').fire('submit');
  assert.equal(h.calls.length, before);
  h.$('#biz-stat-to').value = '2026-04-03'; await h.$('#biz-stat-filter').fire('submit'); assert.equal(h.calls.length, before + 1);
});

test('latest statistics response wins and escaped department names remain text', async () => {
  const first = deferred(); const second = deferred(); let request = 0;
  const h = harness({ statistics: { get: () => (++request === 1 ? first.promise : second.promise) } });
  const initial = h.module.views.adminStatistics(h.root, h.ctx); h.$('#biz-stat-to').value = '2026-10-21'; const refresh = h.$('#biz-stat-filter').fire('submit');
  second.resolve(stats({ departmentVisits: [{ departmentId: 1, departmentName: '<img new>', count: 8 }], doctorWorkload: [{ doctorId: 2, doctorName: '新医生', departmentName: '内科', count: 8 }] })); await refresh;
  first.resolve(stats({ departmentVisits: [{ departmentName: '旧科室', count: 99 }] })); await initial;
  const html = h.$('#biz-stat-result').innerHTML; assert.match(html, /&lt;img new&gt;/); assert.doesNotMatch(html, /<img|旧科室/); assert.match(html, /新医生/);
});

test('failed statistics never look like successful zero counts', async () => {
  const h = harness({ statistics: { get: async () => { throw fail(500, '统计加载失败'); } } }); await h.module.views.adminStatistics(h.root, h.ctx);
  const html = h.$('#biz-stat-result').innerHTML; assert.match(html, /统计加载失败/); assert.doesNotMatch(html, /stat-value|创建量均为/);
});

test('removed business page and modal ignore late results', async () => {
  const gate = deferred(); const h = harness({ drugs: { page: () => gate.promise } });
  const rendering = h.module.views.adminDrugs(h.root, h.ctx); h.root.remove(); gate.resolve(page([drug({ name: '迟到药品' })])); await rendering;
  assert.doesNotMatch(h.$('#modals').innerHTML, /迟到药品/);
  const prescriptionGate = deferred(); h.methods.prescriptions.doctorDetail = () => prescriptionGate.promise;
  const opening = h.module.openDoctorPrescription(61, h.ctx); h.ctx.openModal({ body: '新窗口', footer: '' });
  prescriptionGate.resolve(rx()); await opening; assert.equal(h.$('.modal-body').innerHTML, '新窗口');
});
