'use strict';

// Run with: node --test tools/test-frontend-api.cjs
// The browser API module is executed unchanged in an isolated VM.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/assets/api.js'), 'utf8');
const TOKEN_KEY = 'medflow.access-token';
const PROFILE_KEY = 'medflow.profile';

function token(overrides = {}) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return encode({ alg: 'HS256', typ: 'JWT' }) + '.' + encode({
    uid: 7, sub: 'patient.li', role: 'PATIENT', exp: Math.floor(Date.now() / 1000) + 1800,
    ...overrides
  }) + '.test-signature';
}

function result(data = [], status = 200, code = 'SUCCESS', message = '操作成功', traceId = 'trace-test') {
  return new Response(JSON.stringify({ code, message, data }), {
    status, headers: { 'Content-Type': 'application/json', 'X-Trace-Id': traceId }
  });
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function harness(options = {}) {
  const values = new Map(Object.entries(options.initialStorage || {}));
  const calls = [];
  const events = [];
  const timers = new Map();
  let timerId = 0;
  const localStorage = {
    getItem(key) {
      if (options.blockStorage) throw new Error('storage blocked');
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) {
      if (options.blockStorage || options.failWrites) throw new Error('storage full');
      values.set(key, String(value));
    },
    removeItem(key) {
      if (options.blockStorage) throw new Error('storage blocked');
      values.delete(key);
    }
  };
  const window = {
    localStorage,
    crypto: options.noCrypto ? undefined : { randomUUID: () => 'ce23afe8-2f53-4e3e-b4d0-f333dfbf7702' },
    dispatchEvent(event) { events.push(event); }
  };
  vm.runInNewContext(source, {
    window,
    atob: (value) => Buffer.from(value, 'base64').toString('binary'),
    TextDecoder, Uint8Array, URLSearchParams, AbortController,
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init.detail; } },
    setTimeout: options.fakeTimers ? (callback, delay) => {
      const id = ++timerId;
      timers.set(id, { callback, delay });
      return id;
    } : setTimeout,
    clearTimeout: options.fakeTimers ? (id) => timers.delete(id) : clearTimeout,
    fetch: async (url, config) => {
      calls.push({ url, config });
      return options.fetch ? options.fetch(url, config) : result({ accepted: true });
    }
  }, { filename: 'api.js' });
  return { ...window.MedFlow, values, calls, events, timers };
}

test('every API operation matches the backend route, verb, query and JSON fields', async (t) => {
  const authToken = token();
  const cases = [
    ['public demo flag', api => api.demoConfig(), '/api/v1/auth/demo-config', 'GET', undefined, false],
    ['admin replenishes demo data', api => api.demoData.refresh(), '/api/v1/admin/demo-data/refresh', 'POST'],
    ['provision doctor', api => api.doctors.createAccount({username:'doctor.li',password:'Example@123',name:'李医生',departmentId:2}), '/api/v1/admin/doctor-accounts','POST',{username:'doctor.li',password:'Example@123',name:'李医生',departmentId:2}],
    ['patient departments', api => api.directory.departments(),'/api/v1/patient/departments','GET'],
    ['patient doctors', api => api.directory.doctors(2),'/api/v1/patient/doctors?departmentId=2','GET'],
    ['cached doctor detail', api => api.directory.doctorDetail(7),'/api/v1/patient/doctors/7','GET'],
    ['update doctor profile', api => api.doctors.updateProfile(7,{name:'新姓名'}),'/api/v1/admin/doctors/7/profile','PUT',{name:'新姓名'}],
    ['server schedule catalog', api => api.scheduleManagement.page({from:'2026-11-01',to:'2026-11-07',page:2}),'/api/v1/admin/schedules?from=2026-11-01&to=2026-11-07&page=2','GET'],
    ['server schedule detail', api => api.scheduleManagement.detail(8),'/api/v1/admin/schedules/8','GET'],
    ['atomic batch', api => api.scheduleManagement.batch({doctorId:2,clinicRoomId:3,shiftTemplateId:1,workDates:['2026-11-01']}),'/api/v1/admin/schedules/batch','POST',{doctorId:2,clinicRoomId:3,shiftTemplateId:1,workDates:['2026-11-01']}],
    ['cancel schedule reason', api => api.scheduleManagement.cancel(8,'调整出诊'),'/api/v1/admin/schedules/8/cancel','POST',{reason:'调整出诊'}],
    ['doctor schedules', api => api.doctor.schedules({from:'2026-11-01'}),'/api/v1/doctor/schedules?from=2026-11-01','GET'],
    ['doctor appointments', api => api.doctor.appointments({workDate:'2026-11-01',page:1}),'/api/v1/doctor/appointments?workDate=2026-11-01&page=1','GET'],
    ['doctor encounter list', api => api.doctor.encounters({status:'WAITING',page:1}),'/api/v1/doctor/encounters?status=WAITING&page=1','GET'],
    ['doctor encounter detail', api => api.doctor.encounter(9),'/api/v1/doctor/encounters/9','GET'],
    ['doctor starts encounter', api => api.doctor.start(9),'/api/v1/doctor/encounters/9/start','POST'],
    ['doctor skips encounter', api => api.doctor.skip(9),'/api/v1/doctor/encounters/9/skip','POST'],
    ['record optimistic version', api => api.doctor.saveRecord(9,{chiefComplaint:'主诉',presentIllness:'病史',diagnosis:'诊断',advice:'医嘱',version:2}),'/api/v1/doctor/encounters/9/medical-record','PUT',{chiefComplaint:'主诉',presentIllness:'病史',diagnosis:'诊断',advice:'医嘱',version:2}],
    ['complete saved version', api => api.doctor.complete(9,3),'/api/v1/doctor/encounters/9/complete','POST',{version:3}],
    ['visit metadata', api => api.appointments.visitInfo(7),'/api/v1/appointments/7/visit-info','GET'],
    ['batch visit summaries', api => api.appointments.visitSummaries([7,9]),'/api/v1/appointments/visit-summaries?ids=7%2C9','GET'],
    ['patient check-in', api => api.appointments.checkIn(7),'/api/v1/appointments/7/check-in','POST'],
    ['patient encounter', api => api.appointments.encounter(7),'/api/v1/appointments/7/encounter','GET'],
    ['patient full history page', api => api.patientHistory.page(42,{page:2,size:10}),'/api/v1/patients/42/history?page=2&size=10','GET'],
    ['patient full history detail', api => api.patientHistory.detail(42,7),'/api/v1/patients/42/history/7','GET'],
    ['doctor anchored patient history page', api => api.doctorHistory.page(81,{page:1,size:10}),'/api/v1/doctor/appointments/81/patient-history?page=1&size=10','GET'],
    ['doctor anchored historical visit', api => api.doctorHistory.detail(81,7),'/api/v1/doctor/appointments/81/patient-history/7','GET'],
    ['drug search', api => api.drugs.page({keyword:'演示',page:2,size:10}),'/api/v1/admin/drugs?keyword=%E6%BC%94%E7%A4%BA&page=2&size=10','GET'],
    ['create drug', api => api.drugs.create({code:'DEMO',name:'演示',specification:'1片',unit:'盒',unitPrice:'2.50'}),'/api/v1/admin/drugs','POST',{code:'DEMO',name:'演示',specification:'1片',unit:'盒',unitPrice:'2.50'}],
    ['update drug', api => api.drugs.update(2,{name:'演示',specification:'1片',unit:'盒',unitPrice:'3.00',status:'DISABLED',version:1}),'/api/v1/admin/drugs/2','PUT',{name:'演示',specification:'1片',unit:'盒',unitPrice:'3.00',status:'DISABLED',version:1}],
    ['doctor drug catalog', api => api.drugs.available({keyword:'演示'}),'/api/v1/doctor/drugs?keyword=%E6%BC%94%E7%A4%BA','GET'],
    ['doctor prescription', api => api.prescriptions.doctorDetail(9),'/api/v1/doctor/encounters/9/prescription','GET'],
    ['save prescription', api => api.prescriptions.save(9,{version:0,items:[{drugId:2,quantity:3,instructions:'演示'}]}),'/api/v1/doctor/encounters/9/prescription','PUT',{version:0,items:[{drugId:2,quantity:3,instructions:'演示'}]}],
    ['issue prescription', api => api.prescriptions.issue(9,1),'/api/v1/doctor/encounters/9/prescription/issue','POST',{version:1}],
    ['discard prescription', api => api.prescriptions.discard(9,1),'/api/v1/doctor/encounters/9/prescription/discard','POST',{version:1}],
    ['patient prescription page', api => api.prescriptions.patientPage({page:2,size:10}),'/api/v1/patient/prescriptions?page=2&size=10','GET'],
    ['patient prescription detail', api => api.prescriptions.patientDetail(6),'/api/v1/patient/prescriptions/6','GET'],
    ['mock bill payment', api => api.prescriptions.pay(4),'/api/v1/patient/bills/4/pay','POST'],
    ['statistics date range', api => api.statistics.get({from:'2026-10-01',to:'2026-10-20'}),'/api/v1/admin/statistics?from=2026-10-01&to=2026-10-20','GET'],
    ['login', (api) => api.login('patient.li', 'secret'), '/api/v1/auth/login', 'POST', { username: 'patient.li', password: 'secret' }, false],
    ['list departments', (api) => api.departments.list(), '/api/v1/admin/departments', 'GET'],
    ['create department', (api) => api.departments.create('心内科'), '/api/v1/admin/departments', 'POST', { name: '心内科' }],
    ['list clinic rooms', (api) => api.clinicRooms.list(12), '/api/v1/admin/clinic-rooms?departmentId=12', 'GET'],
    ['create clinic room', (api) => api.clinicRooms.create(12, '一诊室'), '/api/v1/admin/clinic-rooms', 'POST', { departmentId: 12, name: '一诊室' }],
    ['list doctors', (api) => api.doctors.list(12), '/api/v1/admin/doctors?departmentId=12', 'GET'],
    ['bind doctor', (api) => api.doctors.create({ username: 'doctor.wang', departmentId: 12, name: '王医生' }), '/api/v1/admin/doctors', 'POST', { username: 'doctor.wang', departmentId: 12, name: '王医生' }],
    ['list templates', (api) => api.shiftTemplates.list(), '/api/v1/admin/shift-templates', 'GET'],
    ['create schedule', (api) => api.schedules.create({ doctorId: 4, clinicRoomId: 5, shiftTemplateId: 6, workDate: '2026-11-01' }), '/api/v1/admin/schedules', 'POST', { doctorId: 4, clinicRoomId: 5, shiftTemplateId: 6, workDate: '2026-11-01' }],
    ['publish schedule', (api) => api.schedules.publish(34), '/api/v1/admin/schedules/34/publish', 'POST'],
    ['filter available slots', (api) => api.slots.list({ workDate: '2026-11-01', departmentId: 12, doctorId: 4, page: 2, size: 12 }), '/api/v1/patient/slots?workDate=2026-11-01&departmentId=12&doctorId=4&page=2&size=12', 'GET'],
    ['default slot paging and omit optional filters', (api) => api.slots.list({ workDate: '2026-11-01', departmentId: null, doctorId: '' }), '/api/v1/patient/slots?workDate=2026-11-01&page=1&size=20', 'GET'],
    ['list patients', (api) => api.patients.list(), '/api/v1/patients', 'GET'],
    ['patient detail', (api) => api.patients.get(42), '/api/v1/patients/42', 'GET'],
    ['create patient', (api) => api.patients.create({ name: '李明', gender: 'MALE', birthDate: '2000-01-01', phone: '13800138000', relationship: '本人' }), '/api/v1/patients', 'POST', { name: '李明', gender: 'MALE', birthDate: '2000-01-01', phone: '13800138000', relationship: '本人' }],
    ['appointment page', (api) => api.appointments.page(2, 10), '/api/v1/appointments?page=2&size=10', 'GET'],
    ['first appointment cursor', (api) => api.appointments.cursor({ size: 10 }), '/api/v1/appointments/cursor?size=10', 'GET'],
    ['next appointment cursor', (api) => api.appointments.cursor({ size: 10, afterCreatedAt: '2026-10-20T08:30:00', afterId: 34 }), '/api/v1/appointments/cursor?size=10&afterCreatedAt=2026-10-20T08%3A30%3A00&afterId=34', 'GET'],
    ['appointment detail', (api) => api.appointments.detail(34), '/api/v1/appointments/34', 'GET'],
    ['create appointment', (api) => api.appointments.create({ patientId: 42, slotId: 81 }, 'intent-42-81'), '/api/v1/appointments', 'POST', { patientId: 42, slotId: 81 }, true, 'intent-42-81'],
    ['confirm appointment', (api) => api.appointments.confirm(34), '/api/v1/appointments/34/confirm', 'POST'],
    ['cancel appointment', (api) => api.appointments.cancel(34), '/api/v1/appointments/34/cancel', 'POST']
  ];
  for (const [name, call, url, method, body, authenticated = true, key] of cases) {
    await t.test(name, async () => {
      const h = harness({ initialStorage: { [TOKEN_KEY]: authToken } });
      assert.deepEqual(JSON.parse(JSON.stringify(await call(h.api))), { accepted: true });
      assert.equal(h.calls.length, 1);
      const actual = h.calls[0];
      assert.equal(actual.url, url);
      assert.equal(actual.config.method, method);
      assert.deepEqual(actual.config.body === undefined ? undefined : JSON.parse(actual.config.body), body);
      assert.equal(actual.config.headers.Authorization, authenticated ? 'Bearer ' + authToken : undefined);
      assert.equal(actual.config.headers.Accept, 'application/json');
      assert.equal(actual.config.headers['Content-Type'], body === undefined ? undefined : 'application/json');
      assert.equal(actual.config.headers['Idempotency-Key'], key);
      assert.equal(actual.config.cache, 'no-store');
      assert.ok(actual.config.signal instanceof AbortSignal);
    });
  }
});

test('successful JSON envelopes preserve null and primitive data', async () => {
  for (const data of [null, 0, false, '完成']) {
    const h = harness({ fetch: () => result(data) });
    assert.equal(await h.api.patients.list(), data);
  }
});

test('successful HTML, empty bodies and malformed envelopes never become empty successful data', async () => {
  const bodies = ['', '<html>Login page</html>', 'not json', 'null', '[]', '{}',
    '{"code":"SUCCESS","message":"ok"}', '{"code":200,"message":"ok","data":[]}',
    '{"code":"SUCCESS","data":[]}', '{"code":"","message":"ok","data":[]}'];
  for (const body of bodies) {
    const h = harness({ fetch: () => new Response(body, { headers: { 'X-Trace-Id': 'bad-response' } }) });
    await assert.rejects(h.api.patients.list(), (error) => {
      assert.equal(error.code, 'INVALID_RESPONSE');
      assert.equal(error.status, 200);
      assert.equal(error.traceId, 'bad-response');
      return true;
    });
  }
});

test('business errors retain backend status, message, code and trace identifier', async () => {
  const h = harness({ fetch: () => result(null, 409, 'SLOT_SOLD_OUT', '该号源已约满', 'trace-sold-out') });
  await assert.rejects(h.api.appointments.create({ patientId: 42, slotId: 81 }, 'same-intent'), (error) => {
    assert.ok(error instanceof h.ApiError);
    assert.equal(error.status, 409);
    assert.equal(error.code, 'SLOT_SOLD_OUT');
    assert.equal(error.message, '该号源已约满');
    assert.equal(error.traceId, 'trace-sold-out');
    return true;
  });
  assert.equal(h.calls.length, 1, 'mutations are never automatically retried');
});

test('a business failure inside HTTP 200 remains a failure', async () => {
  const h = harness({ fetch: () => result(null, 200, 'VALIDATION_FAILED', '参数错误') });
  await assert.rejects(h.api.patients.list(), { code: 'VALIDATION_FAILED', status: 200 });
});

test('HTML error pages retain HTTP status without leaking HTML as a message', async () => {
  const h = harness({ fetch: () => new Response('<html>proxy unavailable</html>', { status: 502 }) });
  await assert.rejects(h.api.patients.list(), (error) => {
    assert.equal(error.code, 'HTTP_502');
    assert.equal(error.status, 502);
    assert.doesNotMatch(error.message, /html|proxy/);
    return true;
  });
});

test('401 clears the matching session and dispatches a single unauthorized event', async () => {
  const h = harness({ initialStorage: { [TOKEN_KEY]: token(), [PROFILE_KEY]: '{}' }, fetch: () => result(null, 401, 'UNAUTHORIZED', '请先登录') });
  await assert.rejects(h.api.patients.list(), { status: 401, code: 'UNAUTHORIZED', sessionStale: false });
  assert.equal(h.session.token(), null);
  assert.equal(h.values.has(PROFILE_KEY), false);
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].type, 'medflow:unauthorized');
  assert.equal(h.events[0].detail.traceId, 'trace-test');
});

test('a malformed 401 still expires the matching session', async () => {
  const h = harness({ initialStorage: { [TOKEN_KEY]: token() }, fetch: () => new Response('Unauthorized', { status: 401 }) });
  await assert.rejects(h.api.patients.list(), { status: 401 });
  assert.equal(h.session.token(), null);
  assert.equal(h.events.length, 1);
});

test('login failures and 403 permission failures do not discard a valid session', async () => {
  const authToken = token();
  for (const status of [401, 403]) {
    const h = harness({ initialStorage: { [TOKEN_KEY]: authToken }, fetch: () => result(null, status, status === 401 ? 'INVALID_CREDENTIALS' : 'FORBIDDEN', '拒绝访问') });
    const pending = status === 401 ? h.api.login('wrong', 'password') : h.api.patients.list();
    await assert.rejects(pending, { status });
    assert.equal(h.session.token(), authToken);
    assert.equal(h.events.length, 0);
  }
});

test('late 401 from the old account cannot log out the newly signed-in account', async () => {
  const pending = deferred();
  const h = harness({ initialStorage: { [TOKEN_KEY]: token() }, fetch: () => pending.promise });
  const request = h.api.patients.list();
  const newToken = token({ uid: 8, sub: 'admin', role: 'ADMIN' });
  h.session.save(newToken);
  pending.resolve(result(null, 401, 'UNAUTHORIZED', '旧会话失效'));
  await assert.rejects(request, { status: 401, sessionStale: true });
  assert.equal(h.session.token(), newToken);
  assert.equal(h.events.length, 0);
});

test('concurrent unauthorized responses dispatch once and mark the later one stale', async () => {
  const first = deferred();
  const second = deferred();
  let count = 0;
  const h = harness({ initialStorage: { [TOKEN_KEY]: token() }, fetch: () => (++count === 1 ? first.promise : second.promise) });
  const a = h.api.patients.list();
  const b = h.api.appointments.page(1, 10);
  first.resolve(result(null, 401, 'UNAUTHORIZED', '会话失效'));
  await assert.rejects(a, { sessionStale: false });
  second.resolve(result(null, 401, 'UNAUTHORIZED', '会话失效'));
  await assert.rejects(b, { sessionStale: true });
  assert.equal(h.events.length, 1);
});

test('old successful data and old network failures are marked stale after an account change', async () => {
  for (const outcome of ['success', 'network']) {
    const pending = deferred();
    const h = harness({ initialStorage: { [TOKEN_KEY]: token() }, fetch: () => pending.promise });
    const request = h.api.patients.list();
    h.session.save(token({ uid: 8, sub: 'another.patient' }));
    if (outcome === 'success') pending.resolve(result([{ name: '旧账号就诊人' }]));
    else pending.reject(new TypeError('connection closed'));
    await assert.rejects(request, { sessionStale: true, code: outcome === 'success' ? 'SESSION_CHANGED' : 'NETWORK_ERROR' });
    assert.equal(h.events.length, 0);
  }
});

test('network failures have a port-independent recovery message and preserve the session', async () => {
  const authToken = token();
  const h = harness({ initialStorage: { [TOKEN_KEY]: authToken }, fetch: () => { throw new TypeError('offline'); } });
  await assert.rejects(h.api.patients.list(), (error) => {
    assert.equal(error.code, 'NETWORK_ERROR');
    assert.equal(error.status, 0);
    assert.doesNotMatch(error.message, /8080/);
    return true;
  });
  assert.equal(h.session.token(), authToken);
});

test('requests time out after 20 seconds and abort the underlying fetch', async () => {
  const h = harness({ fakeTimers: true, fetch: () => new Promise(() => {}) });
  const request = h.api.patients.list();
  assert.equal(h.timers.size, 1);
  const timer = [...h.timers.values()][0];
  assert.equal(timer.delay, 20000);
  timer.callback();
  await assert.rejects(request, { code: 'TIMEOUT', status: 0 });
  assert.equal(h.calls[0].config.signal.aborted, true);
  assert.equal(h.timers.size, 0);
});

test('timeout also covers a stalled response body and retains trace and status', async () => {
  const bodyRead = deferred();
  const h = harness({ fakeTimers: true, fetch: () => ({
    status: 200, ok: true, headers: new Headers({ 'X-Trace-Id': 'body-timeout' }),
    text: () => { bodyRead.resolve(); return new Promise(() => {}); }
  }) });
  const request = h.api.patients.list();
  await bodyRead.promise;
  [...h.timers.values()][0].callback();
  await assert.rejects(request, { code: 'TIMEOUT', status: 200, traceId: 'body-timeout' });
  assert.equal(h.calls[0].config.signal.aborted, true);
});

test('completed requests release timeout timers', async () => {
  const h = harness({ fakeTimers: true });
  await h.api.patients.list();
  assert.equal(h.timers.size, 0);
});

test('session profile always comes from the token rather than the independent cached profile', () => {
  const h = harness({ initialStorage: { [TOKEN_KEY]: token({ sub: '患者李明' }), [PROFILE_KEY]: '{"role":"ADMIN","uid":99}' } });
  assert.equal(h.session.profile().role, 'PATIENT');
  assert.equal(h.session.profile().uid, 7);
  assert.equal(h.session.profile().sub, '患者李明');
  h.values.set(TOKEN_KEY, token({ uid: 8, role: 'DOCTOR', sub: 'doctor.wang' }));
  assert.equal(h.session.profile().role, 'DOCTOR');
  assert.equal(h.session.profile().uid, 8);
});

test('invalid login payloads cannot replace an existing valid session', () => {
  const original = token();
  const h = harness({ initialStorage: { [TOKEN_KEY]: original } });
  const invalid = ['not-a-jwt', null, token({ uid: 0 }), token({ uid: '7' }), token({ uid: 1.5 }),
    token({ uid: Number.MAX_SAFE_INTEGER + 1 }), token({ role: 'OWNER' }), token({ exp: '9999999999' }),
    token({ exp: Math.floor(Date.now() / 1000) - 10 }), token({ sub: '' }), token({ role: null })];
  for (const value of invalid) {
    assert.throws(() => h.session.save(value), { code: 'INVALID_SESSION' });
    assert.equal(h.session.token(), original);
  }
});

test('a successful session save replaces the token and removes legacy cached profile', () => {
  const h = harness({ initialStorage: { [PROFILE_KEY]: '{"role":"ADMIN"}' } });
  const authToken = token();
  assert.equal(h.session.save(authToken).uid, 7);
  assert.equal(h.session.token(), authToken);
  assert.equal(h.values.has(PROFILE_KEY), false);
  assert.equal(h.session.expired(), false);
  h.values.set(TOKEN_KEY, token({ exp: 1 }));
  assert.equal(h.session.expired(), true);
  h.values.set(TOKEN_KEY, 'damaged');
  assert.equal(h.session.profile(), null);
  assert.equal(h.session.expired(), true);
});

test('browser storage failures are explicit during login and harmless for page bootstrap and drafts', () => {
  const blocked = harness({ blockStorage: true });
  assert.equal(blocked.session.token(), null);
  assert.equal(blocked.session.profile(), null);
  assert.equal(blocked.session.expired(), true);
  assert.doesNotThrow(() => blocked.session.clear());
  assert.equal(blocked.storage.get('drafts'), null);
  assert.equal(blocked.storage.set('drafts', '[]'), false);
  assert.equal(blocked.storage.remove('drafts'), false);
  assert.throws(() => blocked.session.save(token()), { code: 'STORAGE_UNAVAILABLE' });
  const original = token();
  const full = harness({ failWrites: true, initialStorage: { [TOKEN_KEY]: original } });
  assert.throws(() => full.session.save(token({ uid: 8 })), { code: 'STORAGE_UNAVAILABLE' });
  assert.equal(full.session.token(), original);
});

test('generated idempotency keys fit the backend header validation with and without crypto', () => {
  for (const options of [{}, { noCrypto: true }]) {
    const h = harness(options);
    const key = h.newIdempotencyKey();
    assert.match(key, /^[A-Za-z0-9._:-]+$/);
    assert.ok(key.length <= 64);
  }
});
