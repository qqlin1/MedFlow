/*
 * MedFlow 前端应用（原生 JS，无构建步骤）
 * ---------------------------------------------------------------------------
 * 路由：hash 路由，例如 #/slots、#/appointments、#/departments
 * 角色：PATIENT、ADMIN、DOCTOR 分别展示患者、管理和接诊菜单，与后端 SecurityConfig 对应。
 */
(function () {
  'use strict';

  const MedFlow = window.MedFlow;
  const session = MedFlow.session;
  const api = MedFlow.api;
  const ApiError = MedFlow.ApiError;

  /* =======================================================================
   * 一、常量与工具
   * ===================================================================== */

  const ROLE_LABEL = { PATIENT: '患者', DOCTOR: '医生', ADMIN: '管理员' };

  const APPT_STATUS = {
    PENDING_CONFIRMATION: { text: '待确认', tone: 'warn' },
    BOOKED: { text: '已预约', tone: 'ok' },
    CANCELLED: { text: '已取消', tone: 'muted' },
    EXPIRED: { text: '已过期', tone: 'danger' }
  };

  const EVENT_LABEL = {
    CREATED: '创建预约',
    CONFIRMED: '确认预约',
    CANCELLED: '取消预约',
    EXPIRED: '超时关闭'
  };

  const GENDER_LABEL = { MALE: '男', FEMALE: '女', UNKNOWN: '未知' };
  const RELATION_LABEL = { SELF: '本人' };

  const ICONS = {
    calendar: '<rect x="3" y="4.5" width="18" height="17" rx="2.5"/><path d="M16 2.5v4M8 2.5v4M3 10h18"/>',
    list: '<path d="M8.5 6h12M8.5 12h12M8.5 18h12M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
    user: '<path d="M20 21v-1.8a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4V21"/><circle cx="12" cy="7" r="4"/>',
    building: '<path d="M3 21h18M5 21V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2"/>',
    door: '<path d="M4 21h16M7 21V4a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v17"/><circle cx="14" cy="12" r="1"/>',
    stethoscope: '<path d="M6 3v5a6 6 0 0 0 12 0V3"/><path d="M12 14v3a3 3 0 0 0 3 3"/><circle cx="18" cy="17.5" r="2"/>',
    layers: '<rect x="3" y="3.5" width="18" height="5.5" rx="2"/><rect x="3" y="13" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="13" width="7.5" height="7.5" rx="2"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 1.8"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.8h.01"/>'
  };

  function icon(name) {
    const path = ICONS[name] || '';
    return '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" '
      + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + path + '</svg>';
  }

  function $(selector, scope) {
    return (scope || document).querySelector(selector);
  }

  function $$(selector, scope) {
    return Array.prototype.slice.call((scope || document).querySelectorAll(selector));
  }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function pad2(n) {
    return String(n).padStart(2, '0');
  }

  function todayIso(offsetDays) {
    const date = new Date();
    date.setDate(date.getDate() + (offsetDays || 0));
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
  }

  /** "08:00:00" → "08:00" */
  function fmtTime(value) {
    if (!value) return '—';
    return String(value).slice(0, 5);
  }

  /** "2026-10-19" → "10月19日 周一" */
  function fmtDate(value) {
    if (!value) return '—';
    const text = String(value);
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(text);
    if (!match) return text;
    const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
    return Number(match[2]) + '月' + Number(match[3]) + '日 ' + week[date.getDay()];
  }

  /**
   * "2026-10-19T08:00:00" / "2026-10-19T08:00:00.123" → "10-19 08:00"
   * 直接切字符串，不经过 Date 转换，避免把后端本地时间误当 UTC 而偏移 8 小时。
   */
  function fmtDateTime(value) {
    if (!value) return '—';
    const text = String(value);
    const splitIndex = text.indexOf('T');
    if (splitIndex < 0) return text;
    const datePart = text.slice(0, splitIndex).split('-');
    const timePart = text.slice(splitIndex + 1, splitIndex + 6);
    if (datePart.length < 3) return text;
    return datePart[1] + '-' + datePart[2] + ' ' + timePart;
  }

  function deadlinePassed(value) {
    if (!value) return false;
    const text = String(value);
    const instant = Date.parse(/[zZ]$|[+-]\d{2}:\d{2}$/.test(text) ? text : text + '+08:00');
    return Number.isFinite(instant) && instant <= Date.now();
  }

  function bookingKey(slotId, patientId, sourceEncounterId) {
    // A follow-up is a different intent even when patient and slot match a normal booking.
    const storageKey = 'medflow.booking.' + currentProfile().uid + '.' + slotId + '.' + patientId
      + (sourceEncounterId ? '.source.' + sourceEncounterId : '');
    let key;
    try { key = localStorage.getItem(storageKey); } catch (_) { /* Memory still permits retry in this dialog. */ }
    if (!key) {
      key = MedFlow.newIdempotencyKey();
      try { localStorage.setItem(storageKey, key); } catch (_) { /* Storage can be blocked. */ }
    }
    return { key: key, clear: function () { try { localStorage.removeItem(storageKey); } catch (_) {} } };
  }

  function statusBadge(status) {
    const meta = APPT_STATUS[status] || { text: status, tone: 'muted' };
    return '<span class="badge ' + meta.tone + '">' + esc(meta.text) + '</span>';
  }

  function initials(name) {
    const text = String(name || '?').trim();
    return text ? text.slice(0, 1).toUpperCase() : '?';
  }

  /* =======================================================================
   * 二、Toast 与弹窗
   * ===================================================================== */

  function toast(message, tone, title) {
    const stack = $('#toast-stack');
    const node = document.createElement('div');
    node.className = 'toast ' + (tone || '');
    node.innerHTML = '<span class="toast-mark"></span><div><b>' + esc(title || (tone === 'error' ? '操作失败' : '提示'))
      + '</b>' + esc(message) + '</div>';
    stack.appendChild(node);

    const remove = function () {
      node.classList.add('is-out');
      setTimeout(function () { node.remove(); }, 200);
    };
    setTimeout(remove, tone === 'error' ? 5200 : 3200);
    node.addEventListener('click', remove);
  }

  function openModal(options) {
    closeAllModals();
    const host = $('#modal-root');
    const previousFocus = document.activeElement;
    const backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';
    backdrop.innerHTML =
      '<div class="modal" style="max-width:' + (options.width || 520) + 'px" role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1">'
      + '<header class="modal-head"><div><h2 id="modal-title">' + esc(options.title) + '</h2>'
      + (options.subtitle ? '<p>' + esc(options.subtitle) + '</p>' : '')
      + '</div><button class="icon-btn" data-close aria-label="关闭">✕</button></header>'
      + '<div class="modal-body">' + (options.body || '') + '</div>'
      + '<footer class="modal-foot">' + (options.footer || '') + '</footer>'
      + '</div>';
    host.appendChild(backdrop);
    $('#root').inert = true;
    document.body.classList.add('modal-open');

    let closed = false;
    function close() {
      if (closed) return;
      closed = true;
      document.removeEventListener('keydown', onKey);
      $('#root').inert = false;
      document.body.classList.remove('modal-open');
      if (previousFocus && previousFocus.isConnected) previousFocus.focus();
      backdrop.classList.add('is-closing');
      // Remove synchronously: a new dialog must not leave duplicate IDs in the DOM.
      backdrop.remove();
    }
    backdrop.closeModal = close;
    function onKey(event) {
      if (event.key === 'Escape') close();
      if (event.key !== 'Tab') return;
      const targets = $$('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]', backdrop)
        .filter(function (item) { return item.getClientRects().length; });
      const first = targets[0];
      const last = targets[targets.length - 1];
      if (!first) { event.preventDefault(); $('.modal', backdrop).focus(); return; }
      if (event.shiftKey && (document.activeElement === first || !backdrop.contains(document.activeElement))) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !backdrop.contains(document.activeElement))) {
        event.preventDefault(); first.focus();
      }
    }

    document.addEventListener('keydown', onKey);
    backdrop.addEventListener('click', function (event) {
      if (event.target === backdrop) close();
    });
    $$('[data-close]', backdrop).forEach(function (button) {
      button.addEventListener('click', close);
    });

    requestAnimationFrame(function () {
      if (!backdrop.isConnected) return;
      backdrop.classList.add('is-open');
      const initial = $('.modal-body input:not([type="radio"]), .modal-body select, .modal-body input[type="radio"]:checked', backdrop);
      (initial || $('button', backdrop) || $('.modal', backdrop)).focus();
    });
    if (options.onMount) options.onMount(backdrop, close);

    return { root: backdrop, close: close };
  }

  function closeAllModals() {
    $$('.modal-backdrop').forEach(function (node) { if (node.closeModal) node.closeModal(); });
  }

  function errorPanel(error) {
    const message = (error && error.message) || '加载失败';
    const code = error && error.code ? '<span class="mono tiny">' + esc(error.code) + '</span>' : '';
    const hint = error && (error.code === 'NETWORK_ERROR' || error.code === 'TIMEOUT')
      ? '<p>请检查网络连接，稍后重试。若刚刚提交了预约，请先查看「我的预约」确认结果。</p>' : '';
    return '<div class="error-panel" role="alert"><strong>' + esc(message) + '</strong>' + code + hint
      + (error && error.traceId ? '<p>问题编号：' + esc(error.traceId) + '</p>' : '') + '</div>';
  }

  function emptyState(title, description, actionHtml) {
    return '<div class="empty">'
      + '<div class="empty-ico">' + icon('info') + '</div>'
      + '<strong>' + esc(title) + '</strong>'
      + '<p>' + esc(description) + '</p>'
      + (actionHtml || '')
      + '</div>';
  }

  function skeletonGrid(count) {
    let html = '<div class="skeleton-grid">';
    for (let i = 0; i < (count || 6); i += 1) html += '<div class="skeleton"></div>';
    return html + '</div>';
  }

  /* =======================================================================
   * 三、全局状态
   * ===================================================================== */

  const state = {
    route: null,
    slotDate: todayIso(1),
    slotPage: 1,
    slotDepartment: null,
    slotDoctor: null,
    followUpSource: null,
    appt: null,
    deptCache: null
  };

  function resetPatientState() {
    state.appt = null;
    state.slotDepartment = null;
    state.slotDoctor = null;
    state.followUpSource = null;
  }

  /* =======================================================================
   * 四、登录
   * ===================================================================== */

  function renderLogin() {
    closeAllModals();
    if (tokenTimer) clearInterval(tokenTimer);
    resetPatientState();
    state.deptCache = null;
    session.clear();
    document.title = 'MedFlow · 登录';

    $('#root').innerHTML =
      '<div class="login">'
      + '<section class="login-brand">'
      + '<div class="brand-mark"><span class="glyph">+</span>MedFlow</div>'
      + '<div class="eyebrow">CARE, CONNECTED.</div>'
      + '<h1>让每一次就诊<br/><em>从容开始。</em></h1>'
      + '<p class="login-lede">提前安排就诊时间，把等待留给更重要的事。<br/>MedFlow，让预约与门诊安排井然有序。</p>'
      + '<div class="care-illustration" aria-hidden="true"><div class="care-cross">+</div><span class="care-orbit orbit-one"></span><span class="care-orbit orbit-two"></span><div class="care-float">' + icon('calendar') + '<span>选好时间，安心就诊</span></div></div>'
      + '<ul class="login-points">'
      + '<li><span class="dot"></span><span>浏览门诊号源，选择合适的就诊时间</span></li>'
      + '<li><span class="dot"></span><span>管理本人和家人的就诊档案</span></li>'
      + '<li><span class="dot"></span><span>预约进度与变更记录，随时掌握</span></li>'
      + '</ul>'
      + '</section>'
      + '<section class="login-panel">'
      + '<div class="login-card">'
      + '<div class="eyebrow">WELCOME TO MEDFLOW</div><h2>欢迎回来</h2>'
      + '<p class="login-hint">登录后，开始安排您的就诊或门诊工作。</p>'
      + '<form id="login-form" class="form-grid">'
      + '<label class="field"><span>用户名</span>'
      + '<input type="text" id="login-username" name="username" autocomplete="username" placeholder="请输入用户名" maxlength="64" required /></label>'
      + '<label class="field"><span>密码</span>'
      + '<input type="password" id="login-password" name="password" autocomplete="current-password" placeholder="请输入密码" maxlength="128" required /></label>'
      + '<div id="login-error" class="error-panel" role="alert" style="display:none"></div>'
      + '<button class="btn btn-primary" type="submit" id="login-submit" style="width:100%;padding:11px">登录</button>'
      + '</form>'
      + '<p class="demo-label">演示体验 · 点击填入账号</p><div class="demo-row" id="demo-accounts">'
      + '<button class="demo-chip" type="button" data-demo="patient.li" data-pass="Patient@123">'
      + '<strong>患者</strong><span>patient.li</span></button>'
      + '<button class="demo-chip" type="button" data-demo="admin" data-pass="Admin@123">'
      + '<strong>管理员</strong><span>admin</span></button>'
      + '</div><p class="login-footer">MedFlow 门诊预约服务 · 演示环境请使用虚构信息</p>'
      + '</div></section></div>';

    const form = $('#login-form');
    const usernameInput = $('#login-username');
    const passwordInput = $('#login-password');
    const errorBox = $('#login-error');
    const submit = $('#login-submit');

    const demoAccounts = $('#demo-accounts');
    function bindDemoButtons() {
      $$('[data-demo]', demoAccounts).forEach(function (chip) {
        chip.addEventListener('click', function () {
          usernameInput.value = chip.getAttribute('data-demo');
          passwordInput.value = chip.getAttribute('data-pass');
          passwordInput.focus();
        });
      });
    }
    bindDemoButtons();
    // Default installations retain their original shortcuts. The optional
    // local demo bootstrap exposes only a boolean, never account credentials.
    api.demoConfig().then(function (config) {
      if (!form.isConnected || !config || config.enabled !== true) return;
      demoAccounts.innerHTML = [
        ['admin', '管理员'], ['doctor', '医生'], ['patient', '患者']
      ].map(function (account) {
        return '<button class="demo-chip" type="button" data-demo="' + account[0] + '" data-pass="123456">'
          + '<strong>' + account[1] + '</strong><span>' + account[0] + '</span></button>';
      }).join('');
      bindDemoButtons();
    }).catch(function () { /* Login still works when the optional flag cannot be read. */ });

    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      errorBox.style.display = 'none';
      submit.disabled = true;
      submit.textContent = '登录中…';

      try {
        const data = await api.login(usernameInput.value.trim(), passwordInput.value);
        session.save(data.accessToken);
        resetPatientState();
        const nextHash = '#/' + defaultRoute();
        if (location.hash !== nextHash) location.hash = nextHash;
        else renderShell();
        toast('欢迎使用 MedFlow', 'ok', '登录成功');
      } catch (error) {
        errorBox.style.display = 'grid';
        errorBox.innerHTML = '<strong>' + esc(error.message) + '</strong>'
          + (error.code ? '<span class="mono tiny">' + esc(error.code) + '</span>' : '');
        submit.disabled = false;
        submit.textContent = '登录';
      }
    });
  }

  /* =======================================================================
   * 五、外壳与路由
   * ===================================================================== */

  const NAV = {
    PATIENT: [
      { id: 'overview', label: '就诊工作台', icon: 'layers' },
      { id: 'slots', label: '预约号源', icon: 'calendar' },
      { id: 'appointments', label: '我的预约', icon: 'list' },
      { id: 'patients', label: '就诊人', icon: 'user' },
      { id: 'prescriptions', label: '处方与缴费', icon: 'list' }
    ],
    ADMIN: [
      { id: 'overview', label: '门诊工作台', icon: 'layers' },
      { id: 'departments', label: '科室', icon: 'building' },
      { id: 'rooms', label: '诊室', icon: 'door' },
      { id: 'doctors', label: '医生', icon: 'stethoscope' },
      { id: 'templates', label: '班次模板', icon: 'layers' },
      { id: 'schedules', label: '排班与发布', icon: 'clock' },
      { id: 'drugs', label: '药品目录', icon: 'layers' },
      { id: 'statistics', label: '业务统计', icon: 'list' }
    ],
    DOCTOR: [
      { id: 'doctor-overview', label: '医生工作台', icon: 'layers' },
      { id: 'doctor-schedules', label: '我的排班', icon: 'calendar' },
      { id: 'doctor-appointments', label: '预约患者', icon: 'list' },
      { id: 'doctor-queue', label: '候诊与接诊', icon: 'stethoscope' }
    ]
  };

  function clinicalContext() {
    return { api: api, profile: currentProfile(), icon: icon, esc: esc, fmtDate: fmtDate,
      fmtDateTime: fmtDateTime, statusBadge: statusBadge, todayIso: todayIso,
      emptyState: emptyState, errorPanel: errorPanel, handleApiError: handleApiError,
      openModal: openModal, toast: toast, closeAllModals: closeAllModals, beginFollowUp: beginFollowUp };
  }

  function clinicalView(name) {
    return function (root) { return window.MedFlowClinical.views[name](root, clinicalContext()); };
  }

  function businessView(name) {
    return function (root) { return window.MedFlowBusiness.views[name](root, clinicalContext()); };
  }

  function workflowGuide(role) {
    const items = role === 'ADMIN'
      ? [['departments', '1. 准备科室'], ['doctors', '2. 确认医生账号'], ['rooms', '3. 配置同科室诊室'], ['schedules', '4. 创建并发布排班'], ['drugs', '5. 维护药品'], ['statistics', '6. 查看统计']]
      : role === 'DOCTOR'
        ? [['doctor-schedules', '1. 查看本人排班'], ['doctor-appointments', '2. 按日期查预约'], ['doctor-queue', '3. 签到后接诊、病历及处方']]
        : [['patients', '1. 添加就诊人'], ['slots', '2. 选择科室、医生与日期'], ['appointments', '3. 确认预约、当日签到'], ['prescriptions', '4. 查看处方与模拟缴费']];
    const hint = role === 'ADMIN'
      ? '排班由管理员分配给医生。医生和诊室须同科室；草稿发布后患者才能挂号。已绑定的医生账号无需重复开通。'
      : role === 'DOCTOR'
        ? '当前登录：' + (currentProfile().sub || '') + '。排班由管理员分配，医生无需报名。这里只显示本人数据；预约与队列默认查今天，查看其他日期时请调整查询日期。'
        : '先选科室和医生、了解医生资料，再选择日期时段；默认查询明天，可改为其他日期。登录账号可为自己和家人建档；预约后由您自己确认，到院签到仅在就诊当天开放，缴费为模拟登记。';
    return '<details class="workflow-guide"><summary>第一次使用？按这里的顺序操作</summary><p>' + esc(hint) + '</p><nav aria-label="业务操作步骤">'
      + items.map(function (item) { return '<a href="#/' + item[0] + '">' + esc(item[1]) + '</a>'; }).join('') + '</nav></details>';
  }

  const VIEWS = {
    overview: async function (root) {
      await window.MedFlowDashboard.render(root, {
        api: api, profile: currentProfile(), icon: icon, esc: esc, fmtDate: fmtDate,
        fmtDateTime: fmtDateTime, statusBadge: statusBadge, todayIso: todayIso,
        emptyState: emptyState, errorPanel: errorPanel, handleApiError: handleApiError
      });
      if (currentProfile().role === 'ADMIN') await renderDemoDataControls(root);
    },
    slots: viewSlots,
    appointments: viewAppointments,
    patients: viewPatients,
    departments: viewDepartments,
    rooms: viewRooms,
    doctors: viewDoctors,
    drugs: businessView('adminDrugs'),
    statistics: businessView('adminStatistics'),
    prescriptions: businessView('patientPrescriptions'),
    templates: viewTemplates,
    schedules: clinicalView('adminSchedules'),
    'doctor-overview': clinicalView('doctorOverview'),
    'doctor-schedules': clinicalView('doctorSchedules'),
    'doctor-appointments': clinicalView('doctorAppointments'),
    'doctor-queue': clinicalView('doctorQueue'),
    unavailable: viewUnavailable
  };

  async function renderDemoDataControls(root) {
    if (!root.isConnected || currentProfile().role !== 'ADMIN') return;
    let config;
    try { config = await api.demoConfig(); }
    catch (_) { return; }
    if (!root.isConnected || currentProfile().role !== 'ADMIN' || !config || config.enabled !== true || config.workflowEnabled !== true) return;
    if ($('#demo-data-refresh', root)) return;
    const panel = document.createElement('section');
    panel.className = 'card';
    panel.style.marginBottom = '22px';
    panel.innerHTML = '<div class="card-body"><div class="section-title"><div><h2>本机演示数据</h2>'
      + '<p>为三角色补齐缺少的虚构资料、排班和业务示例；保留旧库，不重置已使用的业务状态。</p></div>'
      + '<button class="btn btn-primary" id="demo-data-refresh">补齐演示数据</button></div>'
      + '<p class="muted tiny">医生由管理员安排出诊，无需报名。患者初次查号默认明天；今日队列中的虚构患者已由演示种子预先签到。</p>'
      + '<div id="demo-data-error" aria-live="polite"></div></div>';
    const hero = $('.dash-hero', root);
    if (hero) hero.insertAdjacentElement('afterend', panel); else root.appendChild(panel);
    const button = $('#demo-data-refresh', panel);
    let busy = false;
    button.addEventListener('click', async function () {
      if (busy) return;
      busy = true;
      button.disabled = true;
      button.textContent = '补齐中…';
      $('#demo-data-error', panel).innerHTML = '';
      try {
        const summary = await api.demoData.refresh();
        if (!panel.isConnected) return;
        toast(summary && summary.message ? summary.message : '演示数据已补齐，已有记录和业务状态已保留。', 'ok');
        await VIEWS.overview(root);
      } catch (error) {
        if (panel.isConnected && !handleApiError(error)) $('#demo-data-error', panel).innerHTML = errorPanel(error);
      } finally {
        busy = false;
        button.disabled = false;
        button.textContent = '补齐演示数据';
      }
    });
  }

  function currentProfile() {
    return session.profile() || {};
  }

  function defaultRoute() {
    const nav = NAV[currentProfile().role] || [];
    return nav.length ? nav[0].id : 'unavailable';
  }

  function currentRouteId() {
    const raw = location.hash.replace(/^#\/?/, '').trim();
    const nav = NAV[currentProfile().role] || [];
    const found = nav.filter(function (item) { return item.id === raw; })[0];
    return found ? found.id : defaultRoute();
  }

  function renderShell() {
    closeAllModals();
    if (!session.token() || session.expired()) {
      renderLogin();
      return;
    }

    const profile = currentProfile();
    const role = profile.role;
    const nav = NAV[role] || [];
    const routeId = currentRouteId();
    const active = nav.filter(function (item) { return item.id === routeId; })[0] || nav[0] || { label: '不可用' };

    document.title = 'MedFlow · ' + active.label;

    const navHtml = nav.length
      ? nav.map(function (item) {
        return '<a href="#/' + item.id + '"' + (item.id === routeId ? ' aria-current="page"' : '') + ' class="' + (item.id === routeId ? 'is-active' : '') + '">'
          + icon(item.icon) + '<span>' + esc(item.label) + '</span></a>';
      }).join('')
      : '<div class="note warn" style="margin:10px 8px;padding:10px 12px">医生工作台正在建设中。</div>';

    $('#root').innerHTML =
      '<div class="app">'
      + '<aside class="sidebar">'
      + '<div class="side-brand"><span class="glyph">+</span>'
      + '<span class="wordmark">MedFlow<small>门诊预约</small></span></div>'
      + '<div class="nav-group-label">' + (role === 'ADMIN' ? '门诊管理' : role === 'DOCTOR' ? '医生服务' : '我的健康服务') + '</div>'
      + '<nav class="nav" aria-label="主要导航">' + navHtml + '</nav>'
      + '<div class="sidebar-note">' + icon('info') + '<span>每一次预约<br/>都是一份安心的安排</span></div>'
      + '<div class="side-foot">'
      + '<div class="user-card">'
      + '<span class="avatar">' + esc(initials(profile.sub)) + '</span>'
      + '<span class="who"><b>' + esc(profile.sub || '未知账号') + '</b>'
      + '<span>' + esc(ROLE_LABEL[role] || role) + '</span></span>'
      + '<button class="icon-btn" id="logout" title="退出登录" aria-label="退出登录">⏻</button>'
      + '</div></div></aside>'
      + '<div class="main">'
      + '<header class="topbar">'
      + '<div class="crumb">MedFlow<span>/</span><b>' + esc(active.label) + '</b></div>'
      + '<div class="topbar-right">'
      + '<span class="top-date">' + esc(fmtDate(todayIso())) + '</span>'
      + '<span class="token-chip" id="token-chip">会话 --:--</span>'
      + '<button class="btn btn-sm" id="logout-top">退出</button>'
      + '</div></header>'
      + '<main class="view" id="view" tabindex="-1"></main>'
      + '</div></div>';

    $('#logout').addEventListener('click', doLogout);
    $('#logout-top').addEventListener('click', doLogout);
    startTokenClock();

    const view = $('#view');
    const render = VIEWS[routeId] || viewUnavailable;
    Promise.resolve()
      .then(function () { return render(view); })
      .then(function () { if (view.isConnected) view.insertAdjacentHTML('afterbegin', workflowGuide(role)); })
      .catch(function (error) {
        if (view.isConnected && !handleApiError(error)) view.innerHTML = errorPanel(error);
      });
  }

  function doLogout() {
    session.clear();
    resetPatientState();
    state.deptCache = null;
    location.hash = '';
    renderLogin();
    toast('已退出登录', 'info', '会话结束');
  }

  let tokenTimer = null;
  function startTokenClock() {
    if (tokenTimer) clearInterval(tokenTimer);
    const chip = $('#token-chip');
    if (!chip) return;

    const tick = function () {
      const profile = currentProfile();
      if (!profile.exp) return;
      const left = Math.floor((profile.exp * 1000 - Date.now()) / 1000);
      if (left <= 0) {
        chip.textContent = '会话已到期';
        chip.classList.add('warn');
        clearInterval(tokenTimer);
        session.clear();
        renderLogin();
        toast('登录会话已到期，请重新登录。', 'warn', '需要重新登录');
        return;
      }
      chip.textContent = '会话 ' + pad2(Math.floor(left / 60)) + ':' + pad2(left % 60);
      chip.classList.toggle('warn', left <= 300);
    };

    tick();
    tokenTimer = setInterval(tick, 1000);
  }

  /* 统一错误处理：401 直接回登录页 */
  function handleApiError(error, fallback) {
    if (error && error.sessionStale) return true;
    if (error instanceof ApiError && error.status === 401) {
      if (session.token()) { session.clear(); renderLogin(); }
      return true;
    }
    toast(error && error.message ? error.message : fallback || '操作失败', 'error');
    return false;
  }

  /* =======================================================================
   * 六、患者：预约号源
   * ===================================================================== */

  function bookingContext() {
    return { api: api, esc: esc, fmtDate: fmtDate, fmtDateTime: fmtDateTime, fmtTime: fmtTime,
      todayIso: todayIso, emptyState: emptyState, errorPanel: errorPanel, handleApiError: handleApiError,
      openModal: openModal, toast: toast, openBooking: openBooking, state: state, followUpSource: state.followUpSource };
  }

  function beginFollowUp(source) {
    if (currentProfile().role !== 'PATIENT' || !source || !Number(source.encounterId) || !Number(source.patientId)) return;
    ++bookingOpenVersion;
    state.followUpSource = Object.assign({}, source);
    state.slotDoctor = null;
    state.slotDepartment = null;
    state.slotPage = 1;
    state.slotDate = todayIso(1);
    closeAllModals();
    if (location.hash === '#/slots') return viewSlots($('#view'));
    location.hash = '#/slots';
  }

  async function viewSlots(root) {
    return window.MedFlowBooking.view(root, bookingContext());
  }
  async function loadSlotsPage(page) {
    if ($('#slot-result') && state.loadSlots) await state.loadSlots(page);
  }

  /* ---------- 预约弹窗 ---------- */

  let bookingOpenVersion = 0;
  async function openBooking(slot, followUpSource) {
    const version = ++bookingOpenVersion;
    const openingToken = session.token();
    const openingView = $('#view');
    const openingBookingViewVersion = openingView.__medflowBookingVersion;
    const intents = {};
    const source = followUpSource ? Object.assign({}, followUpSource) : null;
    let submitting = false;

    let patients = [];
    let loadError = null;
    try {
      patients = await api.patients.list();
    } catch (error) {
      if (error.sessionStale || !openingView.isConnected) return;
      if (error.status === 401) { handleApiError(error); return; }
      loadError = error;
    }
    if (!openingView.isConnected || openingToken !== session.token() || version !== bookingOpenVersion
        || openingView.__medflowBookingVersion !== openingBookingViewVersion) return;

    const subtitle = slot.departmentName + ' · ' + slot.doctorName + ' 医生 · '
      + fmtDate(slot.workDate) + ' ' + fmtTime(slot.startTime) + '–' + fmtTime(slot.endTime);

    const eligiblePatients = source ? patients.filter(function (patient) { return Number(patient.id) === Number(source.patientId); }) : patients;
    const patientHtml = eligiblePatients.length
      ? '<div class="pick-list">' + eligiblePatients.map(function (patient, index) {
        return '<label class="pick-item' + (index === 0 ? ' is-selected' : '') + '">'
          + '<input type="radio" name="pick-patient" value="' + esc(patient.id) + '"'
          + (index === 0 ? ' checked' : '') + (source ? ' disabled' : '') + ' />'
          + '<span class="pick-main"><b>' + esc(patient.name) + '</b>'
          + '<span>' + esc(GENDER_LABEL[patient.gender] || patient.gender) + ' · '
          + esc(RELATION_LABEL[patient.relationship] || patient.relationship) + ' · ' + esc(patient.maskedPhone) + '</span></span>'
          + '</label>';
      }).join('') + '</div>'
      : '<div class="note warn"><div><strong>' + (loadError ? '就诊人尚未读取成功。' : '没有可选择的就诊人。') + '</strong><br/>'
      + (loadError ? '请重新读取；读取失败不代表尚未建档。' : source ? '来源就诊人已不可用，请关闭后取消复诊关联，再重新选择。' : '请先添加本人或家人的信息，再为其预约门诊。') + '</div></div>';

    const body =
      '<div class="form-grid">'
      + (source ? '<div class="note"><div><strong>基于 ' + esc(fmtDate(source.workDate)) + ' 的已完成接诊再次预约</strong><p>固定为同一就诊人；本次会建立独立预约、病历及费用，不复制旧病历。</p></div></div>' : '')
      + (loadError ? errorPanel(loadError) : '')
      + (eligiblePatients.length ? '<div><div class="field"><span>' + (source ? '本次复诊就诊人（不可更换）' : '选择就诊人') + '</span></div>' + patientHtml + '</div>' : patientHtml)
      + '<section id="booking-review" aria-live="polite"></section>'
      + '<div id="booking-error" class="error-panel" style="display:none"></div>'
      + '</div>';

    const footer =
      '<button class="btn" data-close>取消</button>'
      + (eligiblePatients.length
        ? '<button class="btn btn-primary" id="booking-submit">提交预约</button>'
        : loadError ? '<button class="btn btn-primary" id="booking-retry-patients">重新读取就诊人</button>'
          : source ? '<button class="btn" data-close>返回取消复诊关联</button>' : '<a class="btn btn-primary" href="#/patients" data-close>去创建就诊人</a>');

    const modal = openModal({
      title: '核对门诊预约',
      subtitle: subtitle,
      body: body,
      footer: footer,
      width: 640,
      onMount: function (root, close) {
        function renderReview() {
          const selected = $('input[name="pick-patient"]:checked', root);
          const patient = selected && eligiblePatients.find(function (item) { return String(item.id) === selected.value; });
          $('#booking-review', root).innerHTML = window.MedFlowClinical.appointmentSummary(clinicalContext(), Object.assign({}, slot, {
            patientName: patient ? patient.name : '请先选择就诊人', cancelDeadline: slot.bookingDeadline
          }), patient || {}, { beforeCreate: true });
        }
        renderReview();
        const retryPatients = $('#booking-retry-patients', root);
        if (retryPatients) retryPatients.addEventListener('click', function () { close(); return openBooking(slot, source); });
        $$('input[name="pick-patient"]', root).forEach(function (radio) {
          radio.addEventListener('change', function () {
            $$('.pick-item', root).forEach(function (other) { other.classList.remove('is-selected'); });
            radio.closest('.pick-item').classList.add('is-selected');
            renderReview();
          });
        });

        const submit = $('#booking-submit', root);
        if (!submit) return;

        submit.addEventListener('click', async function () {
          if (submitting || !root.isConnected || openingToken !== session.token()) return;
          const selected = $('input[name="pick-patient"]:checked', root);
          const errorBox = $('#booking-error', root);

          if (!selected) {
            errorBox.style.display = 'grid';
            errorBox.innerHTML = '<strong>请先选择一个就诊人。</strong>';
            return;
          }

          errorBox.style.display = 'none';
          submitting = true;
          submit.disabled = true;
          submit.textContent = '提交中…';
          const patientId = Number(selected.value);
          if (source && patientId !== Number(source.patientId)) { submit.disabled = false; submitting = false; return; }
          if (!intents[patientId]) intents[patientId] = bookingKey(slot.slotId, patientId, source && source.encounterId);
          const intent = intents[patientId];
          $$('input[name="pick-patient"]', root).forEach(function (radio) { radio.disabled = true; });

          try {
            let result = await api.appointments.create(
              Object.assign({ patientId: patientId, slotId: Number(slot.slotId) }, source ? { sourceEncounterId: Number(source.encounterId) } : {}),
              intent.key
            );
            // A replay returns the frozen creation response; read the current state before offering actions.
            try {
              const detail = await api.appointments.detail(result.appointmentId);
              result = detail.appointment;
            } catch (detailError) {
              if (detailError.sessionStale || detailError.status === 401) { handleApiError(detailError); return; }
              result.stateUnavailable = true;
            }
            if (!root.isConnected) return;
            renderBookingSuccess(root, close, result, slot, eligiblePatients.find(function (patient) { return Number(patient.id) === patientId; }), source, intent);
          } catch (error) {
            if (!root.isConnected || error.sessionStale) return;
            submitting = false;
            submit.disabled = false;
            submit.textContent = '重试预约';
            $$('input[name="pick-patient"]', root).forEach(function (radio) { radio.disabled = !!source; });
            if (error instanceof ApiError && error.status === 401) {
              close();
              handleApiError(error);
              return;
            }
            errorBox.style.display = 'grid';
            errorBox.innerHTML = '<strong>' + esc(error.message) + '</strong>'
              + (error.code ? '<span class="mono tiny">' + esc(error.code) + '</span>' : '')
              + '<p>若提交结果不确定，可为同一就诊人重试，或到「我的预约」查看结果。</p>';
          }
        });
      }
    });

    return modal;
  }

  function renderBookingSuccess(root, close, result, slot, patient, source, intent) {
    const body = $('.modal-body', root);
    const footer = $('.modal-foot', root);
    const openingToken = session.token();

    body.innerHTML =
      '<div class="result-panel">'
      + '<h4>预约已创建</h4>'
      + '<p>请核对就诊安排，按最新凭证提示完成下一步。</p></div>'
      + '<div id="booking-success-summary">' + window.MedFlowClinical.appointmentSummary(clinicalContext(), Object.assign({}, slot, result, { patientName: patient && patient.name }), patient || {}) + '</div>'
      + (source ? '<p class="muted tiny">关联来源：' + esc(fmtDate(source.workDate)) + ' ' + esc(source.doctorName) + ' 的已完成接诊；新旧诊疗与费用独立。</p>' : '')
      + '<div class="result-panel" id="booking-success-current">'
      + '<dl class="kv">'
      + '<dt>当前状态</dt><dd>' + (result.stateUnavailable ? '已提交，待刷新确认' : esc((APPT_STATUS[result.status] || {}).text || result.status)) + '</dd>'
      + '</dl>'
      + '<p class="tiny" style="opacity:.85">' + (result.stateUnavailable
        ? '预约已提交成功，暂时无法刷新最新状态。请打开预约凭证核对当前进度。'
        : '待确认预约需由您在截止前自行确认，无需管理员审批。已确认或已结束的预约请按当前进度处理。') + '</p>'
      + '</div><div id="booking-success-voucher-error" aria-live="polite"></div>';

    footer.innerHTML =
      '<button class="btn" id="booking-later">查看我的预约</button>'
      + '<button class="btn" id="booking-voucher">预约凭证与下一步</button>'
      + (!result.stateUnavailable && result.status === 'PENDING_CONFIRMATION' && !deadlinePassed(result.confirmDeadline)
        ? '<button class="btn btn-primary" id="booking-confirm">立即确认</button>' : '');

    // Capacity refresh is independent of the successful POST and its confirmation dialog.
    loadSlotsPage(state.slotPage).catch(function () { /* The appointment remains created even if availability cannot be refreshed. */ });

    $('#booking-later', root).addEventListener('click', function () {
      close();
      location.hash = '#/appointments';
    });
    $('#booking-voucher', root).addEventListener('click', function () {
      close();
      return window.MedFlowClinical.openPatientVisit(result.appointmentId, clinicalContext());
    });

    // A read failure must preserve the created appointment and the original retry intent.
    api.appointments.voucher(result.appointmentId).then(function (voucher) {
      if (!root.isConnected || openingToken !== session.token()) return;
      if (intent) intent.clear();
      $('#booking-success-summary', root).innerHTML = window.MedFlowClinical.voucherOverview(clinicalContext(), voucher);
      const previousState = $('#booking-success-current', root);
      if (previousState) previousState.remove();
      const button = $('#booking-confirm', root);
      if (button && !voucher.canConfirm) button.remove();
    }).catch(function (error) {
      if (!root.isConnected || openingToken !== session.token() || handleApiError(error)) return;
      $('#booking-success-voucher-error', root).innerHTML = errorPanel(error) + '<p class="muted tiny">预约已创建成功，最新凭证暂未读取成功。请点击“预约凭证与下一步”重新读取，无需重复提交。</p>';
    });

    const confirmButton = $('#booking-confirm', root);
    if (!confirmButton) return;
    confirmButton.addEventListener('click', async function (event) {
      const button = event.currentTarget;
      if (button.disabled) return;
      button.disabled = true;
      button.textContent = '确认中…';
      try {
        const confirmed = await api.appointments.confirm(result.appointmentId);
        toast('预约 #' + confirmed.appointmentId + ' 已确认，请按时就诊。', 'ok', '确认成功');
        if (!root.isConnected) return;
        close();
        return window.MedFlowClinical.openPatientVisit(result.appointmentId, clinicalContext());
      } catch (error) {
        button.disabled = false;
        button.textContent = '立即确认';
        handleApiError(error);
      }
    });
  }

  /* =======================================================================
   * 七、患者：我的预约
   * ===================================================================== */

  async function viewAppointments(root) {
    if (!state.appt) {
      state.appt = { mode: 'page', page: 1, size: 10, items: [], total: 0, hasNext: false, cursor: null, version: 0 };
    }
    const appt = state.appt;

    root.innerHTML =
      '<header class="page-head">'
      + '<div class="page-title"><h1>我的预约</h1>'
      + '<p>查看预约进度、确认就诊安排，或在截止时间前取消预约。</p></div>'
      + '<div class="page-tools">'
      + '<div class="seg">'
      + '<button data-mode="page" class="' + (appt.mode === 'page' ? 'is-active' : '') + '">按页浏览</button>'
      + '<button data-mode="cursor" class="' + (appt.mode === 'cursor' ? 'is-active' : '') + '">连续浏览</button>'
      + '</div>'
      + '<button class="btn" id="appt-refresh">刷新</button>'
      + '</div></header>'
      + '<div id="appt-body"></div>';

    $$('[data-mode]', root).forEach(function (button) {
      button.addEventListener('click', function () {
        state.appt = { mode: button.getAttribute('data-mode'), page: 1, size: 10, items: [], total: 0, hasNext: false, cursor: null, version: 0 };
        viewAppointments(root);
      });
    });
    $('#appt-refresh', root).addEventListener('click', function () { viewAppointments(root); });

    await loadAppointments(root);
  }

  async function loadAppointments(root, append) {
    const appt = state.appt;
    const version = appt.version = (appt.version || 0) + 1;
    const openingToken = session.token();
    const body = $('#appt-body', root);
    if (!body || !root.isConnected) return;
    function current() { return state.appt === appt && appt.version === version && root.isConnected && session.token() === openingToken; }
    appt.summaryVersion = (appt.summaryVersion || 0) + 1;
    appt.summaryLoading = false;
    appt.listError = null;
    if (append && appt.items.some(function (item) { return !(appt.summaries || {})[item.appointmentId]; })) appt.summaryError = new Error('部分预约的就诊安排待加载，请重试。');
    if (!append) body.innerHTML = skeletonGrid(3);

    try {
      if (!appt.patientNames) {
        const patients = await api.patients.list();
        if (!current()) return;
        appt.patientNames = {};
        patients.forEach(function (patient) { appt.patientNames[patient.id] = patient.name; });
      }
      let incoming;
      if (appt.mode === 'page') {
        const data = await api.appointments.page(appt.page, appt.size);
        if (!current()) return;
        incoming = (data && data.items) || [];
        appt.items = incoming;
        appt.total = Number((data && data.total) || 0);
        appt.size = Number((data && data.size) || appt.size);
        appt.page = Number((data && data.page) || appt.page);
      } else {
        const cursorArg = append && appt.cursor
          ? { size: appt.size, afterCreatedAt: appt.cursor.createdAt, afterId: appt.cursor.id }
          : { size: appt.size };
        const data = await api.appointments.cursor(cursorArg);
        if (!current()) return;
        incoming = (data && data.items) || [];
        const combined = (append ? appt.items : []).concat(incoming);
        const seen = new Set();
        appt.items = combined.filter(function (item) {
          if (seen.has(item.appointmentId)) return false;
          seen.add(item.appointmentId); return true;
        });
        appt.hasNext = Boolean(data && data.hasNext);
        appt.cursor = data ? data.nextCursor : null;
      }
      if (!append) appt.summaries = {};
      appt.summaries = appt.summaries || {};
      appt.summaryError = appt.items.some(function (item) { return !appt.summaries[item.appointmentId]; }) ? new Error('部分预约的就诊安排待加载，请重试。') : null;
      renderAppointments(root);
      await loadAppointmentSummaries(root, appt, version, incoming.filter(function (item) { return !appt.summaries[item.appointmentId]; }).map(function (item) { return item.appointmentId; }));
    } catch (error) {
      if (!current()) return;
      if (handleApiError(error)) return;
      if (append) { appt.listError = error; renderAppointments(root); }
      else body.innerHTML = errorPanel(error) + '<button class="btn" id="appt-retry" style="margin-top:12px">重新加载</button>';
      const retry = $('#appt-retry', body);
      if (retry) retry.addEventListener('click', function () { loadAppointments(root); });
    }
  }

  async function loadAppointmentSummaries(root, appt, version, ids) {
    const openingToken = session.token();
    const ticket = appt.summaryVersion = (appt.summaryVersion || 0) + 1;
    function current() { return state.appt === appt && appt.version === version && appt.summaryVersion === ticket && root.isConnected && session.token() === openingToken; }
    if (!current() || !ids.length) return;
    const uniqueIds = Array.from(new Set(ids));
    appt.summaryLoading = true; appt.summaryError = null; renderAppointments(root);
    try {
      const summaries = Object.assign({}, appt.summaries);
      // A normal page is one batch; retrying missing rows from many cursor pages uses bounded batches.
      for (let offset = 0; offset < uniqueIds.length; offset += 50) {
        const batch = uniqueIds.slice(offset, offset + 50);
        const result = await api.appointments.visitSummaries(batch);
        if (!current()) return;
        if (!Array.isArray(result)) throw new Error('就诊安排返回格式不正确，请重试。');
        result.forEach(function (summary) {
          if (batch.some(function (id) { return Number(id) === Number(summary.appointmentId); })) summaries[summary.appointmentId] = summary;
        });
      }
      appt.summaries = summaries;
      if (appt.items.some(function (item) { return !summaries[item.appointmentId]; })) appt.summaryError = new Error('部分预约的就诊安排暂不可用，请重试。');
    } catch (error) {
      if (!current() || handleApiError(error)) return;
      appt.summaryError = error;
    } finally {
      if (current()) { appt.summaryLoading = false; renderAppointments(root); }
    }
  }

  function renderAppointments(root) {
    const appt = state.appt;
    const body = $('#appt-body', root);

    if (!appt.items.length) {
      body.innerHTML = emptyState(
        '还没有预约记录',
        '选择合适的门诊时间后，您的预约和进度会显示在这里。',
        '<a class="btn btn-primary" href="#/slots">去预约</a>'
      );
      return;
    }

    const rows = appt.items.map(function (item) {
      const summary = (appt.summaries || {})[item.appointmentId];
      const canConfirm = item.status === 'PENDING_CONFIRMATION' && !deadlinePassed(item.confirmDeadline);
      const canCancel = (item.status === 'PENDING_CONFIRMATION' || item.status === 'BOOKED') && !deadlinePassed(item.cancelDeadline)
        && !(summary && summary.encounterId);

      return '<tr>'
        + '<td>' + (summary ? '<b>' + esc(fmtDate(summary.workDate)) + '</b><span class="cell-sub mono">' + esc(fmtTime(summary.startTime)) + '–' + esc(fmtTime(summary.endTime)) + '</span>'
          : '<span class="muted">就诊安排待加载</span>') + '<small class="cell-sub mono">预约 #' + esc(item.appointmentId) + '</small></td>'
        + '<td>' + (summary ? esc(summary.doctorName) + '<span class="cell-sub">' + esc(summary.departmentName) + ' · ' + esc(summary.clinicRoomName) + '</span>'
          : '<span class="muted">医生信息待加载</span>') + '</td>'
        + '<td>' + esc(summary && summary.patientName || (appt.patientNames || {})[item.patientId] || ('就诊人 #' + item.patientId)) + '</td>'
        + '<td>' + statusBadge(item.status)
          + (summary && summary.encounterStatus ? '<small class="cell-sub">接诊：' + esc(({ WAITING: '待就诊', IN_PROGRESS: '就诊中', COMPLETED: '已完成', SKIPPED: '已过号' })[summary.encounterStatus] || summary.encounterStatus) + '</small>' : '<small class="cell-sub">尚未签到接诊</small>')
          + (summary && summary.sourceEncounterId ? '<small class="cell-sub">关联前次已完成接诊的再次预约</small>' : '')
          + (item.status === 'PENDING_CONFIRMATION' && !canConfirm
          ? '<div class="tiny muted">确认时间已过，请刷新状态</div>' : '')
          + (canConfirm ? '<small class="cell-sub">请于 ' + esc(fmtDateTime(item.confirmDeadline)) + ' 前确认</small>' : '') + '</td>'
        + '<td><div class="cell-actions">'
        + '<button class="btn btn-sm" data-detail="' + esc(item.appointmentId) + '">凭证与进度</button>'
        + '<button class="btn btn-sm" data-visit="' + esc(item.appointmentId) + '">签到与病历</button>'
        + (canConfirm ? '<button class="btn btn-sm btn-primary" data-confirm="' + esc(item.appointmentId) + '">确认</button>' : '')
        + (canCancel ? '<button class="btn btn-sm btn-danger" data-cancel="' + esc(item.appointmentId) + '">取消</button>' : '')
        + '</div></td></tr>';
    }).join('');

    const pagerHtml = appt.mode === 'page'
      ? '<div class="pager">'
      + '<span class="pager-info">共 ' + appt.total + ' 条 · 第 ' + appt.page + '/'
      + Math.max(1, Math.ceil(appt.total / appt.size)) + ' 页</span>'
      + '<div class="pager-actions">'
      + '<button class="btn btn-sm" data-page="' + (appt.page - 1) + '"' + (appt.page <= 1 ? ' disabled' : '') + '>上一页</button>'
      + '<button class="btn btn-sm" data-page="' + (appt.page + 1) + '"'
      + (appt.page * appt.size >= appt.total ? ' disabled' : '') + '>下一页</button>'
      + '</div></div>'
      : '<div class="pager">'
      + '<span class="pager-info">已加载 ' + appt.items.length + ' 条' + (appt.hasNext ? ' · 还有更多' : ' · 已到底') + '</span>'
      + '<div class="pager-actions">'
      + '<button class="btn btn-sm" id="appt-more"' + (appt.hasNext ? '' : ' disabled') + '>加载更多</button>'
      + '</div></div>';

    body.innerHTML =
      (appt.listError ? errorPanel(appt.listError) + '<p class="tiny muted">更多预约加载失败，已加载记录仍保留，可点击下方「加载更多」重试。</p>' : '')
      + (appt.summaryLoading ? '<p class="tiny muted" role="status">正在加载就诊日期与医生安排…</p>' : appt.summaryError
        ? '<p class="tiny muted">预约记录已加载，部分就诊安排待加载；您仍可查看详情、确认或取消。</p>' + errorPanel(appt.summaryError)
          + '<button class="btn btn-sm" id="appt-summary-retry" style="margin-bottom:12px">重试就诊安排</button>' : '')
      +
      '<div class="table-wrap"><table>'
      + '<thead><tr><th>就诊日期与时段</th><th>医生 / 科室 / 诊室</th><th>就诊人</th><th>预约状态</th><th>操作</th></tr></thead>'
      + '<tbody>' + rows + '</tbody></table></div>'
      + pagerHtml;

    const summaryRetry = $('#appt-summary-retry', body);
    if (summaryRetry) summaryRetry.addEventListener('click', function () {
      return loadAppointmentSummaries(root, appt, appt.version, appt.items.filter(function (item) { return !(appt.summaries || {})[item.appointmentId]; }).map(function (item) { return item.appointmentId; }));
    });

    $$('[data-page]', body).forEach(function (button) {
      button.addEventListener('click', function () {
        state.appt.page = Number(button.getAttribute('data-page'));
        loadAppointments(root);
      });
    });

    const more = $('#appt-more', body);
    if (more) {
      more.addEventListener('click', async function () {
        more.disabled = true;
        more.textContent = '加载中…';
        await loadAppointments(root, true);
      });
    }

    $$('[data-detail]', body).forEach(function (button) {
      button.addEventListener('click', function () { openAppointmentDetail(button.getAttribute('data-detail')); });
    });
    $$('[data-visit]', body).forEach(function (button) {
      button.addEventListener('click', function () {
        window.MedFlowClinical.openPatientVisit(Number(button.dataset.visit), clinicalContext());
      });
    });

    $$('[data-confirm]', body).forEach(function (button) {
      button.addEventListener('click', function () {
        runAppointmentAction(button, 'confirm', button.getAttribute('data-confirm'), root);
      });
    });

    $$('[data-cancel]', body).forEach(function (button) {
      button.addEventListener('click', function () {
        runAppointmentAction(button, 'cancel', button.getAttribute('data-cancel'), root);
      });
    });
  }

  async function runAppointmentAction(button, action, appointmentId, root) {
    const label = action === 'confirm' ? '确认' : '取消';
    if (action === 'cancel') {
      const modal = openModal({
        title: '取消这次预约？', subtitle: '预约编号 #' + appointmentId,
        body: '<p>取消后，已占用的号源将释放。如需再次就诊，您可以重新选择可预约的时段。</p>',
        footer: '<button class="btn" data-close>保留预约</button><button class="btn btn-danger" id="cancel-confirm">确认取消预约</button>'
      });
      $('#cancel-confirm', modal.root).addEventListener('click', function () {
        modal.close();
        executeAppointmentAction(button, action, appointmentId, root);
      });
      return;
    }
    await executeAppointmentAction(button, action, appointmentId, root);
  }

  async function executeAppointmentAction(button, action, appointmentId, root) {
    const label = action === 'confirm' ? '确认' : '取消';
    button.disabled = true;
    button.textContent = label + '中…';
    try {
      const result = action === 'confirm'
        ? await api.appointments.confirm(appointmentId)
        : await api.appointments.cancel(appointmentId);
      toast('预约 ' + result.appointmentId + ' → ' + ((APPT_STATUS[result.status] || {}).text || result.status), 'ok', label + '成功');
      if (root.isConnected) await loadAppointments(root);
    } catch (error) {
      button.disabled = false;
      button.textContent = label;
      if (!root.isConnected) return;
      handleApiError(error, label + '失败');
      if (error.status === 409) await loadAppointments(root);
    }
  }

  async function openAppointmentDetail(appointmentId) {
    return window.MedFlowClinical.openPatientVisit(Number(appointmentId), clinicalContext());
  }

  /* =======================================================================
   * 八、患者：就诊人
   * ===================================================================== */

  async function viewPatients(root) {
    root.innerHTML =
      '<header class="page-head">'
      + '<div class="page-title"><h1>就诊人</h1>'
      + '<p>为本人和家人建立就诊档案，预约时可快速选择。联系电话会以脱敏形式展示。</p></div>'
      + '<div class="page-tools"><button class="btn btn-primary" id="patient-new">新增就诊人</button></div>'
      + '</header>'
      + '<div id="patient-body"></div>';

    $('#patient-new', root).addEventListener('click', openCreatePatient);

    const body = $('#patient-body', root);
    body.innerHTML = skeletonGrid(3);

    try {
      const patients = await api.patients.list();
      if (!root.isConnected) return;
      if (!patients.length) {
        body.innerHTML = emptyState(
          '还没有就诊人',
          '先创建一条就诊人记录，才能为它预约号源。演示环境可以使用虚构信息。',
          '<button class="btn btn-primary" id="patient-new-2">新增就诊人</button>'
        );
        const again = $('#patient-new-2', body);
        if (again) again.addEventListener('click', openCreatePatient);
        return;
      }

      body.innerHTML = '<div class="slot-grid">' + patients.map(function (patient) {
        return '<article class="slot-card">'
          + '<div style="display:flex;align-items:center;gap:10px">'
          + '<span class="avatar">' + esc(initials(patient.name)) + '</span>'
          + '<div><div class="slot-doctor"><button class="patient-history-name" data-patient-history="' + esc(patient.id) + '">' + esc(patient.name) + '</button></div>'
          + '<div class="slot-sub">' + esc(GENDER_LABEL[patient.gender] || patient.gender) + ' · '
          + esc(RELATION_LABEL[patient.relationship] || patient.relationship) + '</div></div></div>'
          + '<dl class="kv tiny" style="grid-template-columns:auto 1fr">'
          + '<dt>出生日期</dt><dd>' + esc(patient.birthDate) + '</dd>'
          + '<dt>联系电话</dt><dd>' + esc(patient.maskedPhone) + '</dd>'
          + '<dt>就诊人 ID</dt><dd>' + esc(patient.id) + '</dd>'
          + '</dl><button class="btn btn-sm patient-history-card-action" data-patient-history="' + esc(patient.id) + '">查看就诊档案</button></article>';
      }).join('') + '</div>';
      $$('[data-patient-history]', body).forEach(function (button) {
        button.addEventListener('click', function () {
          return window.MedFlowPatientHistory.openPatient(Number(button.getAttribute('data-patient-history')), clinicalContext(), { returnLabel: '返回就诊人列表' });
        });
      });
    } catch (error) {
      if (!handleApiError(error)) body.innerHTML = errorPanel(error);
    }
  }

  function openCreatePatient() {
    const body =
      '<form class="form-grid" id="patient-create-form">'
      + '<label class="field"><span>姓名</span>'
      + '<input type="text" id="p-name" maxlength="64" placeholder="例如 李小明" required /></label>'
      + '<div class="form-grid two">'
      + '<label class="field"><span>性别</span>'
      + '<select id="p-gender"><option value="MALE">男</option><option value="FEMALE">女</option>'
      + '<option value="UNKNOWN">未知</option></select></label>'
      + '<label class="field"><span>出生日期</span>'
      + '<input type="date" id="p-birth" max="' + todayIso(-1) + '" required /></label>'
      + '</div>'
      + '<label class="field"><span>手机号<span class="hint"> 11 位，1 开头</span></span>'
      + '<input type="text" id="p-phone" inputmode="numeric" pattern="1[0-9]{10}" maxlength="11" placeholder="13800000000" required /></label>'
      + '<label class="field"><span>与您的关系</span>'
      + '<select id="p-relationship"><option value="SELF">本人</option><option value="配偶">配偶</option><option value="子女">子女</option><option value="父母">父母</option><option value="其他">其他</option></select></label>'
      + '<div id="p-error" class="error-panel" style="display:none"></div>'
      + '</form>';

    const modal = openModal({
      title: '新增就诊人',
      subtitle: '记录归属当前登录账号，其他账号无法读取',
      body: body,
      footer: '<button class="btn" data-close>取消</button><button class="btn btn-primary" type="submit" form="patient-create-form" id="p-submit">保存</button>',
      width: 520,
      onMount: function (root, close) {
        $('#patient-create-form', root).addEventListener('submit', async function (event) {
          event.preventDefault();
          const button = $('#p-submit', root);
          const errorBox = $('#p-error', root);
          const payload = {
            name: $('#p-name', root).value.trim(),
            gender: $('#p-gender', root).value,
            birthDate: $('#p-birth', root).value,
            phone: $('#p-phone', root).value.trim(),
            relationship: $('#p-relationship', root).value.trim()
          };

          if (!payload.name || !payload.birthDate || !payload.phone || !payload.relationship) {
            errorBox.style.display = 'grid';
            errorBox.innerHTML = '<strong>请把姓名、出生日期、手机号和关系填写完整。</strong>';
            return;
          }

          button.disabled = true;
          button.textContent = '保存中…';
          errorBox.style.display = 'none';

          try {
            const created = await api.patients.create(payload);
            if (!root.isConnected) return;
            close();
            toast('就诊人 ' + created.name + ' 已创建（ID ' + created.id + '）', 'ok', '保存成功');
            viewPatients($('#view'));
          } catch (error) {
            if (!root.isConnected || handleApiError(error)) return;
            button.disabled = false;
            button.textContent = '保存';
            errorBox.style.display = 'grid';
            errorBox.innerHTML = '<strong>' + esc(error.message) + '</strong>'
              + (error.code ? '<span class="mono tiny">' + esc(error.code) + '</span>' : '');
          }
        });
      }
    });

    return modal;
  }

  /* =======================================================================
   * 九、管理员：科室
   * ===================================================================== */

  async function loadDepartments(force) {
    if (state.deptCache && !force) return state.deptCache;
    const departments = await api.departments.list();
    state.deptCache = departments || [];
    return state.deptCache;
  }

  async function viewDepartments(root) {
    root.innerHTML =
      '<header class="page-head">'
      + '<div class="page-title"><h1>科室</h1>'
      + '<p>管理当前启用的门诊科室，统一安排科室下的医生与诊室。</p></div>'
      + '<div class="page-tools"><button class="btn" id="dept-reload">刷新</button></div>'
      + '</header>'
      + '<div class="split">'
      + '<section class="card"><div class="card-head"><div><h3>新建科室</h3>'
      + '<p>名称最多 64 个字符，不可重复</p></div></div>'
      + '<div class="card-body"><form class="form-grid" id="dept-form">'
      + '<label class="field"><span>科室名称</span>'
      + '<input type="text" id="dept-name" maxlength="64" placeholder="例如 心血管内科" /></label>'
      + '<button class="btn btn-primary" type="submit" id="dept-submit">创建科室</button>'
      + '</form></div></section>'
      + '<section id="dept-list"></section>'
      + '</div>';

    const listHost = $('#dept-list', root);

    const load = async function () {
      listHost.innerHTML = '<div class="table-wrap"><table><tbody><tr><td class="muted">加载中…</td></tr></tbody></table></div>';
      try {
        const departments = await loadDepartments(true);
        if (!departments.length) {
          listHost.innerHTML = emptyState('还没有科室', '先创建第一个科室，然后才能创建诊室、绑定医生。');
          return;
        }
        listHost.innerHTML = '<div class="table-wrap"><table>'
          + '<thead><tr><th>ID</th><th>科室名称</th><th>状态</th></tr></thead><tbody>'
          + departments.map(function (department) {
            return '<tr><td class="mono">' + esc(department.id) + '</td>'
              + '<td>' + esc(department.name) + '</td>'
              + '<td><span class="badge ok">' + esc(department.status === 'ENABLED' ? '启用' : department.status) + '</span></td></tr>';
          }).join('')
          + '</tbody></table></div>';
      } catch (error) {
        if (!handleApiError(error)) listHost.innerHTML = errorPanel(error);
      }
    };

    $('#dept-reload', root).addEventListener('click', load);

    $('#dept-form', root).addEventListener('submit', async function (event) {
      event.preventDefault();
      const input = $('#dept-name', root);
      const button = $('#dept-submit', root);
      const name = input.value.trim();
      if (!name) {
        toast('请填写科室名称', 'warn');
        return;
      }
      button.disabled = true;
      button.textContent = '创建中…';
      try {
        const created = await api.departments.create(name);
        input.value = '';
        toast('科室「' + created.name + '」已创建，ID ' + created.id, 'ok', '创建成功');
        await load();
      } catch (error) {
        handleApiError(error, '创建科室失败');
      } finally {
        button.disabled = false;
        button.textContent = '创建科室';
      }
    });

    await load();
  }

  /* =======================================================================
   * 十、管理员：诊室
   * ===================================================================== */

  async function viewRooms(root) {
    root.innerHTML =
      '<header class="page-head">'
      + '<div class="page-title"><h1>诊室</h1>'
      + '<p>诊室隶属于科室，同一科室下的诊室名称不可重复。排班时选择的诊室必须与医生同科室。</p></div>'
      + '<div class="page-tools">'
      + '<label class="field inline"><span>选择科室</span><select id="room-dept"></select></label>'
      + '</div></header>'
      + '<div class="split">'
      + '<section class="card"><div class="card-head"><div><h3>新建诊室</h3>'
      + '<p>归属上方选中的科室</p></div></div>'
      + '<div class="card-body"><form class="form-grid" id="room-form">'
      + '<label class="field"><span>诊室名称</span>'
      + '<input type="text" id="room-name" maxlength="64" placeholder="例如 3 号诊室" /></label>'
      + '<button class="btn btn-primary" type="submit" id="room-submit">创建诊室</button>'
      + '</form></div></section>'
      + '<section id="room-list"></section>'
      + '</div>';

    const select = $('#room-dept', root);
    const listHost = $('#room-list', root);

    let departments = [];
    try {
      departments = await loadDepartments(true);
      if (!root.isConnected) return;
    } catch (error) {
      if (!handleApiError(error)) listHost.innerHTML = errorPanel(error);
      return;
    }

    if (!departments.length) {
      select.innerHTML = '<option value="">暂无科室</option>';
      listHost.innerHTML = emptyState('还没有科室', '诊室必须归属某个科室，请先到「科室」页面创建一个。',
        '<a class="btn btn-primary" href="#/departments">去创建科室</a>');
      return;
    }

    select.innerHTML = departments.map(function (department) {
      return '<option value="' + esc(department.id) + '">' + esc(department.name) + '</option>';
    }).join('');

    let requestVersion = 0;
    const load = async function () {
      const version = ++requestVersion;
      const departmentId = Number(select.value);
      if (!departmentId) return;
      listHost.innerHTML = '<div class="table-wrap"><table><tbody><tr><td class="muted">加载中…</td></tr></tbody></table></div>';
      try {
        const rooms = await api.clinicRooms.list(departmentId);
        if (!root.isConnected || version !== requestVersion) return;
        if (!rooms.length) {
          listHost.innerHTML = emptyState('该科室还没有诊室', '在左侧填写名称即可创建。');
          return;
        }
        listHost.innerHTML = '<div class="table-wrap"><table>'
          + '<thead><tr><th>ID</th><th>诊室名称</th><th>所属科室</th><th>状态</th></tr></thead><tbody>'
          + rooms.map(function (room) {
            return '<tr><td class="mono">' + esc(room.id) + '</td>'
              + '<td>' + esc(room.name) + '</td>'
              + '<td class="mono">' + esc(room.departmentId) + '</td>'
              + '<td><span class="badge ok">' + esc(room.status === 'ENABLED' ? '启用' : room.status) + '</span></td></tr>';
          }).join('')
          + '</tbody></table></div>';
      } catch (error) {
        if (!root.isConnected || version !== requestVersion) return;
        if (!handleApiError(error)) listHost.innerHTML = errorPanel(error);
      }
    };

    select.addEventListener('change', load);

    $('#room-form', root).addEventListener('submit', async function (event) {
      event.preventDefault();
      const input = $('#room-name', root);
      const button = $('#room-submit', root);
      const name = input.value.trim();
      const departmentId = Number(select.value);

      if (!name || !departmentId) {
        toast('请选择科室并填写诊室名称', 'warn');
        return;
      }
      button.disabled = true;
      button.textContent = '创建中…';
      try {
        const created = await api.clinicRooms.create(departmentId, name);
        input.value = '';
        toast('诊室「' + created.name + '」已创建，ID ' + created.id, 'ok', '创建成功');
        await load();
      } catch (error) {
        handleApiError(error, '创建诊室失败');
      } finally {
        button.disabled = false;
        button.textContent = '创建诊室';
      }
    });

    await load();
  }

  /* =======================================================================
   * 十一、管理员：医生
   * ===================================================================== */

  async function viewDoctors(root) {
    root.innerHTML =
      '<header class="page-head">'
      + '<div class="page-title"><h1>医生</h1>'
      + '<p>开通新的医生账号，或将已有医生账号绑定到科室。每个账号只对应一份医生档案。</p></div>'
      + '<div class="page-tools">'
      + '<label class="field inline"><span>选择科室</span><select id="doc-dept"></select></label>'
      + '</div></header>'
      + '<div class="split">'
      + '<section class="card"><div class="card-head"><div><h3>开通医生与档案</h3>'
      + '<p>账号和档案同时创建</p></div></div>'
      + '<div class="card-body"><form class="form-grid" id="doc-form">'
      + '<label class="field"><span>操作方式</span><select id="doc-mode"><option value="create">开通新账号</option><option value="bind">绑定已有账号</option></select></label>'
      + '<label class="field"><span>医生账号<span class="hint"> 已开通的医生用户名</span></span>'
      + '<input type="text" id="doc-username" maxlength="64" placeholder="doctor.wang" /></label>'
      + '<label class="field" id="doc-password-field"><span>初始密码</span><input type="password" id="doc-password" autocomplete="new-password" minlength="8" maxlength="72" placeholder="至少 8 位，最长 72 字节" /></label>'
      + '<label class="field"><span>医生姓名</span>'
      + '<input type="text" id="doc-name" maxlength="64" placeholder="例如 王建国" /></label>'
      + '<button class="btn btn-primary" type="submit" id="doc-submit">开通医生</button>'
      + '</form></div></section>'
      + '<section id="doc-list"></section>'
      + '</div>';

    const select = $('#doc-dept', root);
    const listHost = $('#doc-list', root);
    $('#doc-mode', root).addEventListener('change', function () {
      const create = $('#doc-mode', root).value === 'create';
      $('#doc-password-field', root).hidden = !create;
      $('#doc-password', root).disabled = !create;
      $('#doc-submit', root).textContent = create ? '开通医生' : '绑定医生';
    });

    let departments = [];
    try {
      departments = await loadDepartments(true);
      if (!root.isConnected) return;
    } catch (error) {
      if (!handleApiError(error)) listHost.innerHTML = errorPanel(error);
      return;
    }

    if (!departments.length) {
      select.innerHTML = '<option value="">暂无科室</option>';
      listHost.innerHTML = emptyState('还没有科室', '医生必须归属某个科室，请先创建科室。',
        '<a class="btn btn-primary" href="#/departments">去创建科室</a>');
      return;
    }

    select.innerHTML = departments.map(function (department) {
      return '<option value="' + esc(department.id) + '">' + esc(department.name) + '</option>';
    }).join('');

    let requestVersion = 0;
    const load = async function () {
      const version = ++requestVersion;
      const departmentId = Number(select.value);
      if (!departmentId) return;
      listHost.innerHTML = '<div class="table-wrap"><table><tbody><tr><td class="muted">加载中…</td></tr></tbody></table></div>';
      try {
        const doctors = await api.doctors.list(departmentId);
        if (!root.isConnected || version !== requestVersion) return;
        if (!doctors.length) {
          listHost.innerHTML = emptyState('该科室还没有医生', '在左侧绑定一个医生账号。');
          return;
        }
        listHost.innerHTML = '<div class="table-wrap"><table>'
          + '<thead><tr><th>ID</th><th>姓名</th><th>登录账号</th><th>账号 ID</th><th>科室 ID</th><th>状态</th><th>公开资料</th></tr></thead><tbody>'
          + doctors.map(function (doctor) {
            return '<tr><td class="mono">' + esc(doctor.id) + '</td>'
              + '<td>' + esc(doctor.name) + '</td>'
              + '<td class="mono">' + esc(doctor.username || '—') + '</td>'
              + '<td class="mono">' + esc(doctor.userId) + '</td>'
              + '<td class="mono">' + esc(doctor.departmentId) + '</td>'
              + '<td><span class="badge ok">' + esc(doctor.status === 'ENABLED' ? '启用' : doctor.status) + '</span></td>'
              + '<td><button class="btn btn-sm" data-doctor-profile="' + esc(doctor.id) + '">维护资料</button></td></tr>';
          }).join('')
          + '</tbody></table></div>';
        $$('[data-doctor-profile]', listHost).forEach(function (button) {
          button.addEventListener('click', function () {
            return openDoctorProfile(Number(button.getAttribute('data-doctor-profile')), root, load);
          });
        });
      } catch (error) {
        if (!root.isConnected || version !== requestVersion) return;
        if (!handleApiError(error)) listHost.innerHTML = errorPanel(error);
      }
    };

    select.addEventListener('change', load);

    $('#doc-form', root).addEventListener('submit', async function (event) {
      event.preventDefault();
      const username = $('#doc-username', root).value.trim();
      const name = $('#doc-name', root).value.trim();
      const departmentId = Number(select.value);
      const button = $('#doc-submit', root);
      const create = $('#doc-mode', root).value === 'create';
      const password = $('#doc-password', root).value;

      if (!username || !name || !departmentId) {
        toast('请选择科室并填写医生账号和姓名', 'warn');
        return;
      }
      if (create && (password.length < 8 || new TextEncoder().encode(password).length > 72)) {
        toast('初始密码至少 8 位，UTF-8 编码不能超过 72 字节。', 'warn'); return;
      }

      button.disabled = true;
      button.textContent = '绑定中…';
      try {
        const payload = { username: username, departmentId: departmentId, name: name };
        const created = create ? await api.doctors.createAccount(Object.assign(payload, { password: password }))
          : await api.doctors.create(payload);
        if (!root.isConnected) return;
        $('#doc-username', root).value = '';
        $('#doc-name', root).value = '';
        $('#doc-password', root).value = '';
        toast('医生「' + created.name + '」已' + (create ? '开通，可使用新账号登录。' : '绑定。'), 'ok', '操作成功');
        await load();
      } catch (error) {
        handleApiError(error, '绑定医生失败');
      } finally {
        button.disabled = false;
        button.textContent = $('#doc-mode', root).value === 'create' ? '开通医生' : '绑定医生';
      }
    });

    await load();
  }

  async function openDoctorProfile(doctorId, view, reload) {
    const openingToken = session.token();
    const modal = openModal({ title: '维护医生公开资料', width: 640,
      body: '<p class="muted" role="status">正在读取医生资料…</p>',
      footer: '<button class="btn" data-close>关闭</button>' });
    function alive() { return view.isConnected && modal.root.isConnected && session.token() === openingToken; }
    const body = $('.modal-body', modal.root);
    const footer = $('.modal-foot', modal.root);
    let busy = false;
    async function load() {
      if (!alive()) return;
      body.innerHTML = '<p class="muted" role="status">正在读取医生资料…</p>';
      try {
        const detail = await api.doctors.profile(doctorId);
        if (!alive()) return;
        body.innerHTML = '<p class="muted tiny">这些资料会展示给预约患者，请填写真实的职称、擅长与个人简介。</p>'
          + '<form id="doctor-profile-form" class="form-grid">'
          + '<label class="field"><span>医生姓名</span><input name="name" maxlength="64" required value="' + esc(detail.name) + '"></label>'
          + '<label class="field"><span>职称</span><input name="title" maxlength="64" placeholder="例如 主任医师" value="' + esc(detail.title) + '"></label>'
          + '<label class="field"><span>擅长</span><textarea name="specialty" maxlength="1000" rows="3" placeholder="填写诊疗方向和擅长领域">' + esc(detail.specialty) + '</textarea></label>'
          + '<label class="field"><span>个人简介</span><textarea name="introduction" maxlength="4000" rows="6" placeholder="填写患者需要了解的执业经历、诊疗方向等">' + esc(detail.introduction) + '</textarea></label>'
          + '<div id="doctor-profile-error" aria-live="polite"></div></form>';
        footer.innerHTML = '<button class="btn" id="doctor-profile-cancel">取消</button><button class="btn btn-primary" type="submit" form="doctor-profile-form" id="doctor-profile-save">保存资料</button>';
        $('#doctor-profile-cancel', modal.root).addEventListener('click', modal.close);
        const form = $('#doctor-profile-form', modal.root);
        const save = $('#doctor-profile-save', modal.root);
        form.addEventListener('submit', async function (event) {
          event.preventDefault();
          if (busy || !alive()) return;
          const payload = {};
          ['name', 'title', 'specialty', 'introduction'].forEach(function (field) { payload[field] = $('[name="' + field + '"]', form).value.trim(); });
          if (!payload.name || payload.name.length > 64 || payload.title.length > 64 || payload.specialty.length > 1000 || payload.introduction.length > 4000) {
            $('#doctor-profile-error', form).innerHTML = errorPanel({ message: '请填写医生姓名，并遵守各项资料的长度限制。' }); return;
          }
          busy = true; save.disabled = true; save.textContent = '保存中…';
          $$('input, textarea', form).forEach(function (input) { input.disabled = true; });
          $('#doctor-profile-error', form).innerHTML = '';
          try {
            await api.doctors.updateProfile(doctorId, payload);
            if (!alive()) return;
            modal.close(); toast('医生公开资料已保存。', 'ok');
            if (view.isConnected && session.token() === openingToken) await reload();
          } catch (error) {
            if (alive() && !handleApiError(error)) $('#doctor-profile-error', form).innerHTML = errorPanel(error);
          } finally {
            busy = false;
            if (alive()) {
              save.disabled = false; save.textContent = '保存资料';
              $$('input, textarea', form).forEach(function (input) { input.disabled = false; });
            }
          }
        });
        $('[name="name"]', form).focus();
      } catch (error) {
        if (!alive() || handleApiError(error)) return;
        body.innerHTML = errorPanel(error) + '<button class="btn" id="doctor-profile-retry">重新读取</button>';
        $('#doctor-profile-retry', modal.root).addEventListener('click', load);
      }
    }
    await load();
  }

  /* =======================================================================
   * 十二、管理员：班次模板
   * ===================================================================== */

  async function viewTemplates(root) {
    root.innerHTML =
      '<header class="page-head">'
      + '<div class="page-title"><h1>班次模板</h1>'
      + '<p>查看门诊时间、每个时段的长度与可预约人数。已创建的排班会保留当时的班次安排。</p></div>'
      + '</header>'
      + '<div id="tpl-body"></div>';

    const body = $('#tpl-body', root);
    body.innerHTML = skeletonGrid(2);

    try {
      const templates = await api.shiftTemplates.list();
      if (!templates.length) {
        body.innerHTML = emptyState('没有可用的班次模板', '请联系系统管理员配置班次后再安排门诊。');
        return;
      }

      body.innerHTML = '<div class="table-wrap"><table>'
        + '<thead><tr><th>ID</th><th>编码</th><th>名称</th><th>时间范围</th>'
        + '<th class="num">时段长度</th><th class="num">每段名额</th><th>可生成时段</th></tr></thead><tbody>'
        + templates.map(function (template) {
          const start = toMinutes(template.startTime);
          const end = toMinutes(template.endTime);
          const count = template.slotDurationMinutes > 0
            ? Math.floor((end - start) / template.slotDurationMinutes) : 0;
          return '<tr><td class="mono">' + esc(template.id) + '</td>'
            + '<td class="mono">' + esc(template.code) + '</td>'
            + '<td>' + esc(template.name) + '</td>'
            + '<td class="mono">' + esc(fmtTime(template.startTime)) + ' – ' + esc(fmtTime(template.endTime)) + '</td>'
            + '<td class="num">' + esc(template.slotDurationMinutes) + ' 分钟</td>'
            + '<td class="num">' + esc(template.capacityPerSlot) + '</td>'
            + '<td class="mono">' + count + ' 个</td></tr>';
        }).join('')
        + '</tbody></table></div>'
        + '<div class="note" style="margin-top:14px"><div>'
        + '例如上午门诊 08:00–12:00，每 30 分钟一个时段，会开放 8 个可约时段；每个时段的可约人数由名额配置决定。</div></div>';
    } catch (error) {
      if (!handleApiError(error)) body.innerHTML = errorPanel(error);
    }
  }

  function toMinutes(time) {
    if (!time) return 0;
    const parts = String(time).split(':');
    return Number(parts[0]) * 60 + Number(parts[1]);
  }


  /* =======================================================================
   * 十四、医生角色占位
   * ===================================================================== */

  async function viewUnavailable(root) {
    root.innerHTML =
      '<header class="page-head"><div class="page-title"><h1>暂无可用的医生功能</h1>'
      + '<p>您已使用医生账号登录。</p></div></header>'
      + emptyState('医生工作台正在建设中', '当前版本提供患者预约与管理员排班功能。医生接诊与预约查询将在后续版本开放。');
  }

  /* =======================================================================
   * 十五、启动
   * ===================================================================== */

  window.addEventListener('hashchange', function () {
    if (location.hash === '#view') { const view = $('#view'); if (view) view.focus(); return; }
    if (!session.token()) {
      renderLogin();
      return;
    }
    renderShell();
  });

  window.addEventListener('medflow:unauthorized', function () {
    renderLogin();
    toast('登录状态已失效，请重新登录。', 'warn', '需要重新登录');
  });

  window.addEventListener('storage', function (event) {
    if (event.key === 'medflow.access-token') {
      resetPatientState(); state.deptCache = null; renderShell();
    }
  });

  if (session.token() && !session.expired()) {
    if (!location.hash) location.hash = '#/' + defaultRoute();
    else renderShell();
  } else {
    renderLogin();
  }
})();
