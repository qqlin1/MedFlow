/*
 * MedFlow 前端 API 封装
 * ---------------------------------------------------------------------------
 * 后端所有接口都返回统一信封：{ code, message, data }
 *   - HTTP 2xx 且 code === "SUCCESS"  → 取 data
 *   - 其余情况                        → 抛 ApiError(message, code, status)
 *
 * 认证方式为 JWT（HS256），登录后放在 Authorization: Bearer <token>。
 * Token 有效期 30 分钟，payload 自带 role / uid / exp，前端据此渲染菜单。
 */
(function (global) {
  'use strict';

  const TOKEN_KEY = 'medflow.access-token';
  const PROFILE_KEY = 'medflow.profile';
  const REQUEST_TIMEOUT_MS = 20000;

  class ApiError extends Error {
    constructor(message, code, status, details) {
      super(message);
      this.name = 'ApiError';
      this.code = code || 'UNKNOWN';
      this.status = status || 0;
      this.traceId = (details && details.traceId) || '';
      this.sessionStale = Boolean(details && details.sessionStale);
    }
  }

  /** 解析 JWT payload（不做签名校验，签名由后端负责）。 */
  function decodeJwt(token) {
    try {
      const parts = String(token).split('.');
      if (parts.length !== 3) return null;
      const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
      const binary = atob(padded);
      const bytes = Uint8Array.from(binary, function (c) { return c.charCodeAt(0); });
      return JSON.parse(new TextDecoder('utf-8').decode(bytes));
    } catch (error) {
      return null;
    }
  }

  // 禁用站点存储或配额不足时，页面仍能显示，并让调用方提示保存失败。
  const storage = {
    get: function (key) {
      try { return global.localStorage.getItem(key); } catch (error) { return null; }
    },
    set: function (key, value) {
      try { global.localStorage.setItem(key, value); return true; } catch (error) { return false; }
    },
    remove: function (key) {
      try { global.localStorage.removeItem(key); return true; } catch (error) { return false; }
    }
  };

  function validProfile(payload) {
    return payload && typeof payload === 'object' && !Array.isArray(payload)
      && ['PATIENT', 'ADMIN', 'DOCTOR'].indexOf(payload.role) !== -1
      && Number.isSafeInteger(payload.uid) && payload.uid > 0
      && typeof payload.sub === 'string' && Boolean(payload.sub.trim())
      && Number.isFinite(payload.exp) && payload.exp > 0;
  }

  const session = {
    save: function (token) {
      const payload = decodeJwt(token);
      if (typeof token !== 'string' || !validProfile(payload) || payload.exp * 1000 <= Date.now()) {
        throw new ApiError('登录响应中的会话无效，请重新登录。', 'INVALID_SESSION', 0);
      }
      // 校验成功后只写一个权威值，失败登录不会覆盖之前的有效会话。
      if (!storage.set(TOKEN_KEY, token)) {
        throw new ApiError('无法保存登录会话，请允许此站点使用浏览器存储。', 'STORAGE_UNAVAILABLE', 0);
      }
      storage.remove(PROFILE_KEY);
      return payload;
    },
    token: function () {
      return storage.get(TOKEN_KEY);
    },
    profile: function () {
      const payload = decodeJwt(this.token());
      return validProfile(payload) ? payload : null;
    },
    expired: function () {
      const payload = this.profile();
      if (!payload || !payload.exp) return true;
      return payload.exp * 1000 <= Date.now();
    },
    clear: function () {
      storage.remove(TOKEN_KEY);
      storage.remove(PROFILE_KEY);
    }
  };

  function buildQuery(params) {
    const search = new URLSearchParams();
    Object.keys(params || {}).forEach(function (key) {
      const value = params[key];
      if (value !== undefined && value !== null && value !== '') {
        search.append(key, value);
      }
    });
    const query = search.toString();
    return query ? '?' + query : '';
  }

  async function request(path, options) {
    const config = options || {};
    const method = config.method || 'GET';
    const headers = Object.assign({ Accept: 'application/json' }, config.headers || {});

    if (config.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    const authenticated = config.auth !== false;
    const sentToken = authenticated ? session.token() : null;
    if (sentToken) headers.Authorization = 'Bearer ' + sentToken;

    const controller = new AbortController();
    let response = null;
    let traceId = '';
    let timer;
    try {
      // 超时包括响应体读取。Promise.race 也能处理不能及时响应 abort 的连接。
      const work = (async function () {
        response = await fetch(path, {
          method: method,
          headers: headers,
          body: config.body === undefined ? undefined : JSON.stringify(config.body),
          cache: 'no-store',
          signal: controller.signal
        });
        traceId = response.headers.get('X-Trace-Id') || '';
        const text = await response.text();
        let payload = null;
        try { payload = JSON.parse(text); } catch (error) { /* 由信封校验统一处理。 */ }

        const envelope = payload && typeof payload === 'object' && !Array.isArray(payload)
          && typeof payload.code === 'string' && payload.code.length > 0
          && typeof payload.message === 'string'
          && Object.prototype.hasOwnProperty.call(payload, 'data');

        if (!response.ok) {
          throw new ApiError(
            envelope ? payload.message || '请求失败' : '请求失败（HTTP ' + response.status + '）',
            envelope && payload.code !== 'SUCCESS' ? payload.code : 'HTTP_' + response.status,
            response.status
          );
        }
        if (!envelope) {
          throw new ApiError('服务返回的数据格式不正确，请稍后重试。', 'INVALID_RESPONSE', response.status);
        }
        if (payload.code !== 'SUCCESS') {
          throw new ApiError(payload.message || '请求失败', payload.code, response.status);
        }
        return payload.data;
      })();
      const timeout = new Promise(function (resolve, reject) {
        timer = setTimeout(function () {
          reject(new ApiError('请求超时，请检查网络后重试。', 'TIMEOUT', response ? response.status : 0));
          controller.abort();
        }, REQUEST_TIMEOUT_MS);
      });
      const data = await Promise.race([work, timeout]);
      if (authenticated && sentToken !== session.token()) {
        throw new ApiError('登录会话已更换，请重试。', 'SESSION_CHANGED', response.status, { sessionStale: true });
      }
      return data;
    } catch (error) {
      const failure = error instanceof ApiError ? error
        : new ApiError('无法连接服务，请检查网络连接后重试。', 'NETWORK_ERROR', response ? response.status : 0);
      failure.traceId = traceId;
      failure.sessionStale = authenticated && sentToken !== session.token();
      if (authenticated && failure.status === 401 && !failure.sessionStale) {
        session.clear();
        global.dispatchEvent(new CustomEvent('medflow:unauthorized', { detail: failure }));
      }
      throw failure;
    } finally {
      clearTimeout(timer);
    }
  }

  function newIdempotencyKey() {
    if (global.crypto && typeof global.crypto.randomUUID === 'function') {
      return global.crypto.randomUUID();
    }
    return 'apt-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12);
  }

  const api = {
    demoConfig: function () {
      return request('/api/v1/auth/demo-config', { auth: false });
    },

    demoData: {
      refresh: function () {
        return request('/api/v1/admin/demo-data/refresh', { method: 'POST' });
      }
    },

    login: function (username, password) {
      return request('/api/v1/auth/login', {
        method: 'POST',
        auth: false,
        body: { username: username, password: password }
      });
    },

    departments: {
      list: function () {
        return request('/api/v1/admin/departments');
      },
      create: function (name) {
        return request('/api/v1/admin/departments', { method: 'POST', body: { name: name } });
      }
    },

    clinicRooms: {
      list: function (departmentId) {
        return request('/api/v1/admin/clinic-rooms' + buildQuery({ departmentId: departmentId }));
      },
      create: function (departmentId, name) {
        return request('/api/v1/admin/clinic-rooms', {
          method: 'POST',
          body: { departmentId: departmentId, name: name }
        });
      }
    },

    doctors: {
      profile: function (id) { return request('/api/v1/admin/doctors/' + id + '/profile'); },
      updateProfile: function (id, payload) { return request('/api/v1/admin/doctors/' + id + '/profile', { method: 'PUT', body: payload }); },
      createAccount: function (payload) {
        return request('/api/v1/admin/doctor-accounts', { method: 'POST', body: payload });
      },
      list: function (departmentId) {
        return request('/api/v1/admin/doctors' + buildQuery({ departmentId: departmentId }));
      },
      create: function (payload) {
        return request('/api/v1/admin/doctors', { method: 'POST', body: payload });
      }
    },

    shiftTemplates: {
      list: function () {
        return request('/api/v1/admin/shift-templates');
      }
    },

    schedules: {
      create: function (payload) {
        return request('/api/v1/admin/schedules', { method: 'POST', body: payload });
      },
      publish: function (scheduleId) {
        return request('/api/v1/admin/schedules/' + scheduleId + '/publish', { method: 'POST' });
      }
    },

    directory: {
      catalogue: function (params) { return request('/api/v1/patient/doctor-directory' + buildQuery({ departmentId: params && params.departmentId, query: params && params.query })); },
      doctorDetail: function (id) { return request('/api/v1/patient/doctors/' + id); },
      departments: function () { return request('/api/v1/patient/departments'); },
      doctors: function (departmentId) { return request('/api/v1/patient/doctors' + buildQuery({ departmentId: departmentId })); }
    },

    scheduleManagement: {
      page: function (params) { return request('/api/v1/admin/schedules' + buildQuery(params)); },
      detail: function (id) { return request('/api/v1/admin/schedules/' + id); },
      batch: function (payload) { return request('/api/v1/admin/schedules/batch', { method: 'POST', body: payload }); },
      cancel: function (id, reason) { return request('/api/v1/admin/schedules/' + id + '/cancel', { method: 'POST', body: { reason: reason } }); }
    },

    doctor: {
      schedules: function (params) { return request('/api/v1/doctor/schedules' + buildQuery(params)); },
      appointments: function (params) { return request('/api/v1/doctor/appointments' + buildQuery(params)); },
      encounters: function (params) { return request('/api/v1/doctor/encounters' + buildQuery(params)); },
      encounter: function (id) { return request('/api/v1/doctor/encounters/' + id); },
      start: function (id) { return request('/api/v1/doctor/encounters/' + id + '/start', { method: 'POST' }); },
      skip: function (id) { return request('/api/v1/doctor/encounters/' + id + '/skip', { method: 'POST' }); },
      saveRecord: function (id, payload) { return request('/api/v1/doctor/encounters/' + id + '/medical-record', { method: 'PUT', body: payload }); },
      complete: function (id, version) { return request('/api/v1/doctor/encounters/' + id + '/complete', { method: 'POST', body: { version: version } }); }
    },

    slots: {
      availableDoctors: function (params) {
        return request('/api/v1/patient/available-doctors' + buildQuery(params));
      },
      availability: function (params) {
        return request('/api/v1/patient/availability-days' + buildQuery(params));
      },
      list: function (params) {
        return request('/api/v1/patient/slots' + buildQuery({
          workDate: params.workDate,
          departmentId: params.departmentId,
          doctorId: params.doctorId,
          page: params.page || 1,
          size: params.size || 20
        }));
      }
    },

    drugs: {
      page: function (params) { return request('/api/v1/admin/drugs' + buildQuery(params)); },
      create: function (payload) { return request('/api/v1/admin/drugs', { method: 'POST', body: payload }); },
      update: function (id, payload) { return request('/api/v1/admin/drugs/' + id, { method: 'PUT', body: payload }); },
      available: function (params) { return request('/api/v1/doctor/drugs' + buildQuery(params)); }
    },
    prescriptions: {
      doctorDetail: function (id) { return request('/api/v1/doctor/encounters/' + id + '/prescription'); },
      save: function (id, payload) { return request('/api/v1/doctor/encounters/' + id + '/prescription', { method: 'PUT', body: payload }); },
      issue: function (id, version) { return request('/api/v1/doctor/encounters/' + id + '/prescription/issue', { method: 'POST', body: { version: version } }); },
      discard: function (id, version) { return request('/api/v1/doctor/encounters/' + id + '/prescription/discard', { method: 'POST', body: { version: version } }); },
      patientPage: function (params) { return request('/api/v1/patient/prescriptions' + buildQuery(params)); },
      patientDetail: function (id) { return request('/api/v1/patient/prescriptions/' + id); },
      pay: function (id) { return request('/api/v1/patient/bills/' + id + '/pay', { method: 'POST' }); }
    },
    statistics: {
      get: function (params) { return request('/api/v1/admin/statistics' + buildQuery(params)); }
    },
    patients: {
      list: function () {
        return request('/api/v1/patients');
      },
      get: function (patientId) {
        return request('/api/v1/patients/' + patientId);
      },
      create: function (payload) {
        return request('/api/v1/patients', { method: 'POST', body: payload });
      }
    },

    patientHistory: {
      page: function (patientId, params) { return request('/api/v1/patients/' + patientId + '/history' + buildQuery(params)); },
      detail: function (patientId, appointmentId) { return request('/api/v1/patients/' + patientId + '/history/' + appointmentId); }
    },
    doctorHistory: {
      page: function (anchorId, params) { return request('/api/v1/doctor/appointments/' + anchorId + '/patient-history' + buildQuery(params)); },
      detail: function (anchorId, appointmentId) { return request('/api/v1/doctor/appointments/' + anchorId + '/patient-history/' + appointmentId); }
    },

    appointments: {
      voucher: function (id) { return request('/api/v1/appointments/' + id + '/voucher'); },
      followUpSource: function (encounterId) { return request('/api/v1/patient/follow-up-sources/' + encounterId); },
      visitSummaries: function (ids) { return request('/api/v1/appointments/visit-summaries' + buildQuery({ ids: ids.join(',') })); },
      visitInfo: function (id) { return request('/api/v1/appointments/' + id + '/visit-info'); },
      checkIn: function (id) { return request('/api/v1/appointments/' + id + '/check-in', { method: 'POST' }); },
      encounter: function (id) { return request('/api/v1/appointments/' + id + '/encounter'); },
      page: function (page, size) {
        return request('/api/v1/appointments' + buildQuery({ page: page, size: size }));
      },
      cursor: function (params) {
        return request('/api/v1/appointments/cursor' + buildQuery({
          size: params.size,
          afterCreatedAt: params.afterCreatedAt,
          afterId: params.afterId
        }));
      },
      detail: function (appointmentId) {
        return request('/api/v1/appointments/' + appointmentId);
      },
      create: function (payload, idempotencyKey) {
        return request('/api/v1/appointments', {
          method: 'POST',
          headers: { 'Idempotency-Key': idempotencyKey },
          body: payload
        });
      },
      confirm: function (appointmentId) {
        return request('/api/v1/appointments/' + appointmentId + '/confirm', { method: 'POST' });
      },
      cancel: function (appointmentId) {
        return request('/api/v1/appointments/' + appointmentId + '/cancel', { method: 'POST' });
      }
    }
  };

  global.MedFlow = {
    ApiError: ApiError,
    storage: storage,
    session: session,
    api: api,
    newIdempotencyKey: newIdempotencyKey
  };
})(window);
