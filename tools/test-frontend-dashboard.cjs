'use strict';

// Run with: node --test tools/test-frontend-dashboard.cjs
// Execute the real dashboard module in a fresh VM; no browser, network or npm.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/dashboard.js'), 'utf8');

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character]);
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fail(message, status = 500, code = 'INTERNAL_SERVER_ERROR') {
  return Object.assign(new Error(message), { status, code });
}

function patientFixture() {
  return {
    patients: [
      { id: 11, name: '李明', relationship: '本人', maskedPhone: '138****0000' },
      { id: 12, name: '李小明', relationship: '子女', maskedPhone: '139****0000' }
    ],
    page: {
      total: 37,
      items: ['PENDING_CONFIRMATION', 'BOOKED', 'CANCELLED', 'EXPIRED', 'PENDING_CONFIRMATION'].map((status, index) => ({
        appointmentId: 61 - index,
        patientId: index % 2 ? 12 : 11,
        slotId: 201 + index,
        status,
        confirmDeadline: '2026-10-20T08:15:00',
        cancelDeadline: '2026-10-20T08:30:00'
      }))
    },
    slots: { total: 14, items: [], page: 1, size: 1 }
  };
}

function harness(role = 'PATIENT', overrides = {}, options = {}) {
  const fixture = patientFixture();
  const calls = [];
  const redirects = [];
  const writes = [];
  const departments = [{ id: 1, name: '内科' }, { id: 2, name: '外科' }, { id: 3, name: '儿科' }];
  const methods = {
    patients: async () => fixture.patients,
    appointments: async () => fixture.page,
    visitSummaries: async (ids) => ids.slice().reverse().map((appointmentId) => ({ appointmentId, patientName: appointmentId % 2 ? '李明' : '李小明',
      doctorName: '王医生', departmentName: '心内科', clinicRoomName: '一诊室', workDate: '2026-11-01', startTime: '08:00:00', endTime: '08:30:00' })),
    slots: async () => fixture.slots,
    departments: async () => departments,
    templates: async () => [{ id: 1 }, { id: 2 }],
    doctors: async (departmentId) => Array.from({ length: departmentId + 1 }, (_, index) => ({ id: departmentId * 10 + index })),
    rooms: async (departmentId) => Array.from({ length: departmentId - 1 }, (_, index) => ({ id: departmentId * 20 + index })),
    ...overrides
  };
  const call = (method) => (...args) => {
    calls.push({ method, args });
    return methods[method](...args);
  };
  const api = {
    patients: { list: call('patients') },
    appointments: { page: call('appointments'), visitSummaries: call('visitSummaries') },
    slots: { list: call('slots') },
    departments: { list: call('departments') },
    shiftTemplates: { list: call('templates') },
    doctors: { list: call('doctors') },
    clinicRooms: { list: call('rooms') }
  };
  const root = {
    isConnected: true,
    get innerHTML() { return writes.at(-1) || ''; },
    set innerHTML(value) {
      writes.push(value);
      this.retry = value.includes('id="dashboard-summary-retry"') ? {
        listeners: [], addEventListener(type, listener) { if (type === 'click') this.listeners.push(listener); },
        async fire() { await Promise.all(this.listeners.map((listener) => listener())); }
      } : null;
    },
    querySelector(selector) { return selector === '#dashboard-summary-retry' ? this.retry : null; }
  };
  const ctx = {
    api, profile: { role, sub: options.username || '演示用户' }, esc,
    icon: (name) => '<svg data-icon="' + esc(name) + '"></svg>',
    fmtDate: (value) => value,
    fmtDateTime: (value) => value || '—',
    statusBadge: (status) => '<span class="badge">' + esc(status) + '</span>',
    todayIso: (offset) => {
      assert.equal(offset, 1);
      return '2026-10-20';
    },
    emptyState: (title, description, action) => '<div class="empty">' + esc(title) + esc(description) + (action || '') + '</div>',
    errorPanel: (error) => '<div class="error-panel">' + esc(error.message) + '</div>',
    handleApiError: (error) => { redirects.push(error); return true; }
  };
  const window = { MedFlow: { session: { token: () => options.tokenRef ? options.tokenRef.value : 'dashboard-session' } } };
  vm.runInNewContext(source, { window }, { filename: 'dashboard.js' });
  return { render: () => window.MedFlowDashboard.render(root, ctx), root, ctx, calls, redirects, writes };
}

function stats(html) {
  const values = {};
  for (const match of html.matchAll(/<article class="stat-card[^\"]*">([\s\S]*?)<\/article>/g)) {
    const label = /class="stat-label"><span>(.*?)<\/span>/.exec(match[1]);
    const value = /class="stat-value">(.*?)<\/div>/.exec(match[1]);
    if (label && value) values[label[1]] = value[1];
  }
  return values;
}

test('patient dashboard uses actual totals and limits pending count to the latest five records', async () => {
  const h = harness();
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '就诊人档案': '2', '累计预约': '37', '明日可约时段': '14', '近期待确认': '2'
  });
  assert.match(h.root.innerHTML, /仅统计最近 5 条预约/);
  assert.match(h.root.innerHTML, /最近 5 条预约中有 <b>2<\/b> 条待确认/);
  assert.match(h.root.innerHTML, /李小明/);
  assert.match(h.root.innerHTML, /#61/);
  assert.match(h.root.innerHTML, /href="#\/appointments"/);
  assert.match(h.root.innerHTML, /href="#\/slots"/);
});

test('patient resources load in parallel with correct page size and tomorrow date', async () => {
  const gates = [deferred(), deferred(), deferred()];
  const fixture = patientFixture();
  const h = harness('PATIENT', {
    patients: () => gates[0].promise,
    appointments: () => gates[1].promise,
    slots: () => gates[2].promise
  });
  const rendering = h.render();
  assert.deepEqual(h.calls.map((call) => call.method), ['patients', 'appointments', 'slots']);
  assert.deepEqual(h.calls[1].args, [1, 5]);
  assert.deepEqual(JSON.parse(JSON.stringify(h.calls[2].args)), [{ workDate: '2026-10-20', page: 1, size: 1 }]);
  assert.match(h.root.innerHTML, /aria-busy="true"/);
  gates[0].resolve(fixture.patients);
  gates[1].resolve(fixture.page);
  gates[2].resolve(fixture.slots);
  await rendering;
  assert.equal(stats(h.root.innerHTML)['累计预约'], '37');
});

test('patient file failure stays unknown while successful appointment and slot totals remain', async () => {
  const h = harness('PATIENT', { patients: async () => { throw fail('档案读取失败'); } });
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '就诊人档案': '—', '累计预约': '37', '明日可约时段': '14', '近期待确认': '2'
  });
  assert.match(h.root.innerHTML, /档案读取失败/);
  assert.match(h.root.innerHTML, /李明/);
});

test('failed appointment list never invents zero totals or an empty successful list', async () => {
  const h = harness('PATIENT', { appointments: async () => { throw fail('预约读取失败'); } });
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '就诊人档案': '2', '累计预约': '—', '明日可约时段': '14', '近期待确认': '—'
  });
  assert.match(h.root.innerHTML, /预约读取失败/);
  assert.doesNotMatch(h.root.innerHTML, /第一次预约，从这里开始/);
});

test('failed tomorrow query preserves other panels and shows unknown slot count', async () => {
  const h = harness('PATIENT', { slots: async () => { throw fail('号源暂时不可用'); } });
  await h.render();
  assert.equal(stats(h.root.innerHTML)['明日可约时段'], '—');
  assert.equal(stats(h.root.innerHTML)['累计预约'], '37');
  assert.match(h.root.innerHTML, /号源暂时不可用/);
  assert.match(h.root.innerHTML, /李明/);
});

test('successful empty responses display real zero counts with useful next actions', async () => {
  const h = harness('PATIENT', {
    patients: async () => [],
    appointments: async () => ({ items: [], total: 0 }),
    slots: async () => ({ items: [], total: 0 })
  });
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '就诊人档案': '0', '累计预约': '0', '明日可约时段': '0', '近期待确认': '0'
  });
  assert.match(h.root.innerHTML, /第一次预约，从这里开始/);
  assert.match(h.root.innerHTML, /明日暂未查到可约时段/);
});

test('admin counts include every department and checklist reflects per-department resources', async () => {
  const h = harness('ADMIN');
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '启用科室': '3', '在岗医生': '9', '可用诊室': '3', '可用班次': '2'
  });
  for (const method of ['doctors', 'rooms']) {
    assert.deepEqual(h.calls.filter((call) => call.method === method).map((call) => call.args[0]), [1, 2, 3]);
  }
  assert.match(h.root.innerHTML, /儿科/);
  assert.match(h.root.innerHTML, /待补齐资源/);
  assert.match(h.root.innerHTML, /医生与诊室齐备/);
  assert.match(h.root.innerHTML, /href="#\/schedules"/);
});

test('a failed department doctor query keeps its total unknown instead of using the partial sum', async () => {
  const h = harness('ADMIN', {
    doctors: async (departmentId) => {
      if (departmentId === 2) throw fail('外科医生查询失败');
      return [{ id: departmentId }];
    }
  });
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '启用科室': '3', '在岗医生': '—', '可用诊室': '3', '可用班次': '2'
  });
  assert.match(h.root.innerHTML, /外科医生查询失败/);
  assert.match(h.root.innerHTML, /待重新加载/);
});

test('department catalog failure blocks dependent counts without hiding available templates', async () => {
  const h = harness('ADMIN', { departments: async () => { throw fail('科室目录加载失败'); } });
  await h.render();
  assert.deepEqual(stats(h.root.innerHTML), {
    '启用科室': '—', '在岗医生': '—', '可用诊室': '—', '可用班次': '2'
  });
  assert.match(h.root.innerHTML, /科室目录加载失败/);
  assert.equal(h.calls.filter((call) => ['doctors', 'rooms'].includes(call.method)).length, 0);
});

test('401 failures redirect once and do not render data after the login handler', async (t) => {
  for (const role of ['PATIENT', 'ADMIN']) {
    await t.test(role, async () => {
      const unauthorized = fail('请重新登录', 401, 'UNAUTHORIZED');
      const h = harness(role, role === 'PATIENT'
        ? { patients: async () => { throw unauthorized; }, slots: async () => { throw unauthorized; } }
        : { doctors: async () => { throw unauthorized; } });
      await h.render();
      assert.equal(h.redirects.length, 1);
      assert.equal(h.redirects[0], unauthorized);
      assert.equal(h.writes.length, 1, 'only the initial loading state may be written');
    });
  }
});

test('patient result never updates a root removed while requests were running', async () => {
  const gate = deferred();
  const h = harness('PATIENT', { patients: () => gate.promise });
  const rendering = h.render();
  h.root.isConnected = false;
  gate.resolve(patientFixture().patients);
  await rendering;
  assert.equal(h.writes.length, 1);
  assert.equal(h.redirects.length, 0);
});

test('admin result never updates a root removed during dependent resource queries', async () => {
  const gate = deferred();
  const resourcesStarted = deferred();
  const h = harness('ADMIN', { doctors: () => { resourcesStarted.resolve(); return gate.promise; } });
  const rendering = h.render();
  await resourcesStarted.promise;
  h.root.isConnected = false;
  gate.resolve([]);
  await rendering;
  assert.equal(h.writes.length, 1);
});

test('patient names, relationships and login names are HTML escaped', async () => {
  const attack = '<img src=x onerror="alert(1)">&';
  const h = harness('PATIENT', {
    patients: async () => [{ id: 11, name: attack, relationship: attack, maskedPhone: attack }]
  }, { username: '<script>alert(1)</script>' });
  await h.render();
  assert.doesNotMatch(h.root.innerHTML, /<img|<script/);
  assert.match(h.root.innerHTML, /&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt;&amp;/);
  assert.match(h.root.innerHTML, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
});

test('admin department names and resource error messages are HTML escaped', async () => {
  const h = harness('ADMIN', {
    departments: async () => [{ id: 1, name: '<b>测试科室</b>' }],
    doctors: async () => { throw fail('<img src=x onerror=alert(1)>'); }
  });
  await h.render();
  assert.match(h.root.innerHTML, /&lt;b&gt;测试科室&lt;\/b&gt;/);
  assert.match(h.root.innerHTML, /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.doesNotMatch(h.root.innerHTML, /<img/);
});

test('recent appointments use one bounded summary batch and show actual date, doctor, department and room', async () => {
  const h = harness(); await h.render();
  const reads = h.calls.filter((call) => call.method === 'visitSummaries'); assert.equal(reads.length, 1);
  assert.deepEqual(Array.from(reads[0].args[0]), [61, 60, 59, 58, 57]);
  assert.match(h.root.innerHTML, /2026-11-01/); assert.match(h.root.innerHTML, /08:00–08:30/);
  assert.match(h.root.innerHTML, /王医生/); assert.match(h.root.innerHTML, /心内科 · 一诊室/);
  assert.doesNotMatch(h.root.innerHTML, /<th>号源|#201/);
});

test('dashboard summary failure keeps appointment totals and rows, and retry only reads the summaries', async () => {
  let batches = 0; const h = harness('PATIENT', { visitSummaries: async (ids) => {
    if (++batches === 1) throw fail('就诊安排暂不可用');
    return ids.map((appointmentId) => ({ appointmentId, doctorName: '重试医生', departmentName: '内科', clinicRoomName: '二诊室', workDate: '2026-11-02', startTime: '09:00:00', endTime: '09:30:00' }));
  } });
  await h.render(); assert.equal(stats(h.root.innerHTML)['累计预约'], '37');
  assert.match(h.root.innerHTML, /#61/); assert.match(h.root.innerHTML, /就诊安排暂不可用/); assert.ok(h.root.retry);
  await h.root.retry.fire(); assert.match(h.root.innerHTML, /重试医生/); assert.equal(h.root.retry, null);
  assert.equal(h.calls.filter((call) => call.method === 'appointments').length, 1);
  assert.equal(h.calls.filter((call) => call.method === 'visitSummaries').length, 2);
});

test('late dashboard summary response cannot repaint a more recently rendered dashboard', async () => {
  const gate = deferred(); const started = deferred(); let batches = 0;
  const h = harness('PATIENT', { visitSummaries: async (ids) => {
    if (++batches === 1) { started.resolve(); return gate.promise; }
    return ids.map((appointmentId) => ({ appointmentId, doctorName: '新工作台医生', workDate: '2026-11-02' }));
  } });
  const old = h.render(); await started.promise; await h.render();
  gate.resolve([{ appointmentId: 61, doctorName: '旧工作台医生' }]); await old;
  assert.match(h.root.innerHTML, /新工作台医生/); assert.doesNotMatch(h.root.innerHTML, /旧工作台医生/);
});

test('changed session discards dashboard metadata without repainting the previous account', async () => {
  const tokenRef = { value: 'old-session' }; const gate = deferred(); const started = deferred();
  const h = harness('PATIENT', { visitSummaries: () => { started.resolve(); return gate.promise; } }, { tokenRef });
  const loading = h.render(); await started.promise; const writes = h.writes.length;
  tokenRef.value = 'new-session'; gate.resolve([{ appointmentId: 61, doctorName: '旧会话医生' }]); await loading;
  assert.equal(h.writes.length, writes); assert.doesNotMatch(h.root.innerHTML, /旧会话医生/);
});

test('unauthorized metadata redirects once and cannot repaint data after the authentication handler', async () => {
  const unauthorized = fail('会话已失效', 401, 'UNAUTHORIZED'); const h = harness('PATIENT', { visitSummaries: async () => { throw unauthorized; } });
  await h.render(); assert.equal(h.redirects.length, 1); assert.equal(h.redirects[0], unauthorized);
  assert.equal(h.writes.length, 2); assert.doesNotMatch(h.root.innerHTML, /王医生/);
});

test('dashboard ignores extra IDs, identifies missing summaries and escapes all public metadata', async () => {
  const attack = '<img src=x onerror="alert(1)">'; const h = harness('PATIENT', { visitSummaries: async () => [
    { appointmentId: 61, doctorName: attack, departmentName: attack, clinicRoomName: attack, patientName: attack, workDate: attack, startTime: '08:00:00', endTime: '08:30:00' },
    { appointmentId: 999, doctorName: '不属于列表的医生' }
  ] });
  await h.render(); assert.match(h.root.innerHTML, /&lt;img/); assert.doesNotMatch(h.root.innerHTML, /<img|不属于列表的医生/);
  assert.match(h.root.innerHTML, /部分预约的就诊安排暂不可用/); assert.ok(h.root.retry);
});
