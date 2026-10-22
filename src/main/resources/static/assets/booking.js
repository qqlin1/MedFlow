(function (global) {
  'use strict';

  function $(root, selector) { return root.querySelector(selector); }
  function $$(root, selector) { return Array.prototype.slice.call(root.querySelectorAll(selector)); }
  function token() { return global.MedFlow && global.MedFlow.session ? global.MedFlow.session.token() : null; }
  function validDate(value, today) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || value < today) return false;
    const date = new Date(value + 'T00:00:00Z');
    return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
  }
  function addDays(value, days) { const date = new Date(value + 'T00:00:00Z'); date.setUTCDate(date.getUTCDate() + days); return date.toISOString().slice(0, 10); }
  function text(ctx, value, fallback) { return ctx.esc(typeof value === 'string' && value.trim() ? value : fallback); }
  function loading(message) { return '<div class="booking-loading" role="status">' + message + '</div>'; }
  const DAY_LABEL = { AVAILABLE: '可预约', FULL: '已约满', NO_SCHEDULE: '无排班', CLOSED: '已停止预约' };

  async function view(root, ctx, options) {
    const openedToken = token();
    const generation = root.__medflowBookingVersion = (root.__medflowBookingVersion || 0) + 1;
    const shared = ctx.state || {};
    let followUpSource = options && options.followUpSource || ctx.followUpSource || shared.followUpSource || null;
    let autoPrefill = Boolean(followUpSource);
    let mode = followUpSource ? 'doctor' : 'department';
    let departments = [], department = null, doctor = null, directory = [];
    let keyword = '', searchDepartment = '';
    let date = validDate(shared.slotDate, ctx.todayIso()) ? shared.slotDate : ctx.todayIso(1);
    let dateSelected = false, rangeFrom = ctx.todayIso();
    let availability = [], availabilityLoading = false, availabilityError = null;
    let departmentsLoading = true, departmentsError = null;
    let directoryLoading = false, directoryError = null;
    let slotLoading = false, slotError = null, slotResult = null;
    let prefillLoading = false, prefillError = null;
    let directoryTicket = 0, slotTicket = 0, departmentsTicket = 0, availabilityTicket = 0, prefillTicket = 0;
    let page = 1, handingOff = false;

    function alive() { return root.isConnected && root.__medflowBookingVersion === generation && token() === openedToken; }
    function syncState() {
      shared.slotDate = date; shared.slotPage = page; shared.followUpSource = followUpSource;
      shared.slotDoctor = doctor ? { id: doctor.id, name: doctor.name } : null;
      shared.slotDepartment = department ? { id: department.id, name: department.name }
        : doctor ? { id: doctor.departmentId, name: doctor.departmentName } : null;
    }
    function clearSlots() { ++slotTicket; slotResult = null; slotError = null; slotLoading = false; page = 1; handingOff = false; }
    function clearAvailability() { ++availabilityTicket; availability = []; availabilityError = null; availabilityLoading = false; }
    function clearDoctor() { doctor = null; clearSlots(); syncState(); }
    function reset(nextMode) {
      if (!alive() || handingOff) return;
      autoPrefill = false; ++directoryTicket; ++prefillTicket;
      mode = nextMode; department = null; directory = []; directoryLoading = false; directoryError = null;
      prefillLoading = false; prefillError = null; dateSelected = false; rangeFrom = ctx.todayIso();
      keyword = ''; searchDepartment = ''; clearAvailability(); clearDoctor(); render();
      if (mode === 'doctor') return loadDirectory();
    }
    function scope() {
      return mode === 'department' && department ? { departmentId: department.id }
        : mode === 'doctor' && doctor ? { departmentId: doctor.departmentId, doctorId: doctor.id } : null;
    }
    function scopeKey() { const value = scope(); return value ? mode + ':' + value.departmentId + ':' + (value.doctorId || '') : ''; }
    function followUpBanner() {
      if (!followUpSource) return '';
      return '<aside class="booking-follow-up"><div><b>基于已完成就诊再次预约 · ' + ctx.esc(followUpSource.patientName) + '</b><p>前次就诊 '
        + ctx.esc(ctx.fmtDate(followUpSource.workDate)) + '。本次仍为同一位就诊人，可更换科室、医生和日期；提交预约时才占用号源。</p></div>'
        + '<button class="btn btn-sm" id="booking-clear-follow-up">取消复诊关联</button></aside>';
    }
    function modeButtons() {
      return '<div class="booking-entry" role="group" aria-label="选择预约方式">' + ['department', 'doctor'].map(function (value) {
        return '<button class="booking-entry-button' + (mode === value ? ' is-active' : '') + '" data-booking-mode="' + value + '" aria-pressed="'
          + String(mode === value) + '"><b>' + (value === 'department' ? '按科室预约' : '找医生预约') + '</b><span>'
          + (value === 'department' ? '先看日期余量，再选当天出诊医生' : '搜索全院医生，再看该医生出诊日期') + '</span></button>';
      }).join('') + '</div>';
    }
    function progress() {
      const labels = mode === 'department' ? ['选择科室', '选择日期', '医生与时段', '就诊人与确认'] : ['选择医生', '选择日期', '选择时段', '就诊人与确认'];
      const current = mode === 'department' ? !department ? 0 : !dateSelected ? 1 : 2 : !doctor ? 0 : !dateSelected ? 1 : 2;
      return '<ol class="booking-progress" aria-label="预约步骤">' + labels.map(function (label, index) {
        return '<li class="' + (index === current ? 'is-current' : index < current ? 'is-done' : '') + '"' + (index === current ? ' aria-current="step"' : '')
          + '><span>' + (index + 1) + '</span>' + label + '</li>';
      }).join('') + '</ol>';
    }
    function departmentCards() {
      if (departmentsLoading) return loading('正在读取科室目录…');
      if (departmentsError) return ctx.errorPanel(departmentsError) + '<button class="btn" id="booking-retry-departments">重新加载科室</button>';
      if (!departments.length) return ctx.emptyState('暂未开放预约科室', '门诊准备完成后将在这里展示，请稍后查看。');
      return '<div class="booking-section-head"><div><h2>先选择就诊科室</h2><p>先比较日期余量，再了解当天可预约的医生。</p></div></div>'
        + '<div class="booking-departments">' + departments.map(function (item) {
          return '<button class="booking-department-card" data-booking-department="' + ctx.esc(item.id) + '"><span class="booking-department-mark" aria-hidden="true">+</span><b>'
            + ctx.esc(item.name) + '</b><span>查看出诊日期 <span aria-hidden="true">→</span></span></button>';
        }).join('') + '</div>';
    }
    function doctorCard(item) {
      const daily = mode === 'department';
      return '<article class="booking-doctor-card"><div class="booking-doctor-heading"><span class="booking-doctor-avatar" aria-hidden="true">'
        + ctx.esc(String(item.name || '?').slice(0, 1)) + '</span><div><h3>' + ctx.esc(item.name) + '</h3><p>'
        + text(ctx, item.departmentName, '科室信息待维护') + ' · ' + text(ctx, item.title, '职称待维护') + '</p></div></div>'
        + '<dl><dt>擅长</dt><dd>' + text(ctx, item.specialty, '医生擅长信息暂未维护') + '</dd><dt>简介</dt><dd>'
        + text(ctx, item.introduction, '医生简介暂未维护') + '</dd></dl>'
        + (daily ? '<p class="booking-doctor-availability">当日 ' + ctx.esc(item.availableSlotCount) + ' 个可约时段 · 余 ' + ctx.esc(item.remainingCapacity) + ' 号</p>' : '')
        + '<button class="btn btn-primary" data-booking-doctor="' + ctx.esc(item.id) + '">' + (daily ? '选择具体时段' : '查看出诊日期') + ' <span aria-hidden="true">→</span></button></article>';
    }
    function doctorList() {
      if (directoryLoading) return loading(mode === 'department' ? '正在查询当日可预约医生…' : '正在读取医生资料…');
      if (directoryError) return ctx.errorPanel(directoryError) + '<button class="btn" id="booking-retry-directory">重新查询医生</button>';
      return directory.length ? '<div class="booking-doctors">' + directory.map(doctorCard).join('') + '</div>'
        : ctx.emptyState(mode === 'department' ? '该科室当日暂无可预约医生' : '没有找到符合条件的医生',
          mode === 'department' ? '可切换日期或刷新余量，也可以返回选择其他科室。' : '可尝试其他姓名、擅长关键词，或切换科室。');
    }
    function doctorSearch() {
      return '<div class="booking-section-head"><div><h2>查找全院医生</h2><p>先了解医生的职称、擅长和简介，再查看出诊日期。</p></div></div>'
        + '<form class="booking-search" id="booking-search-form"><label class="field"><span>医生姓名或擅长</span><input id="booking-search-query" type="search" maxlength="64" placeholder="输入医生姓名或擅长关键词" value="'
        + ctx.esc(keyword) + '"></label><label class="field"><span>所属科室</span><select id="booking-search-department"><option value="">全部科室</option>'
        + departments.map(function (item) { return '<option value="' + ctx.esc(item.id) + '">' + ctx.esc(item.name) + '</option>'; }).join('')
        + '</select></label><button class="btn btn-primary" type="submit">查找医生</button></form>' + doctorList();
    }
    function datePanel() {
      const days = availabilityLoading ? loading('正在读取按日号源余量…') : availabilityError
        ? ctx.errorPanel(availabilityError) + '<button class="btn" id="booking-retry-days">重新加载日期余量</button>'
        : '<div class="booking-summary-days" aria-label="出诊日期与号源余量">' + availability.map(function (item) {
          const selected = dateSelected && item.workDate === date;
          return '<button class="booking-day booking-day-summary' + (selected ? ' is-active' : '') + '" data-booking-date="' + ctx.esc(item.workDate)
            + '" data-availability-status="' + ctx.esc(item.status) + '" aria-pressed="' + String(selected) + '"><b>' + ctx.esc(ctx.fmtDate(item.workDate))
            + '</b><span>' + ctx.esc(DAY_LABEL[item.status] || '状态待核对') + '</span><strong>' + (item.status === 'AVAILABLE' ? '余 ' + ctx.esc(item.remainingCapacity) + ' 号' : '—')
            + '</strong><small>' + ctx.esc(item.doctorCount) + ' 位可约医生 · ' + ctx.esc(item.availableSlotCount) + ' 个时段</small></button>';
        }).join('') + '</div>';
      return '<section class="booking-date-panel"><div class="booking-section-head"><div><h2>选择就诊日期</h2><p>无排班、已约满与已停止预约分别展示。日期余量为查询时快照，以提交结果为准。</p></div>'
        + '<button class="btn btn-sm" id="booking-refresh-availability">刷新日期余量</button></div>' + days
        + '<div class="booking-range"><button class="btn btn-sm" id="booking-days-prev"' + (rangeFrom <= ctx.todayIso() ? ' disabled' : '') + '>前 7 天</button>'
        + '<span class="tiny muted">' + ctx.esc(ctx.fmtDate(rangeFrom)) + ' 起的 7 天</span><button class="btn btn-sm" id="booking-days-next">后 7 天</button></div>'
        + '<form id="booking-date-form" class="booking-date-form"><label class="field"><span>也可指定其他日期</span><input type="date" id="booking-date" min="'
        + ctx.esc(ctx.todayIso()) + '" value="' + ctx.esc(date) + '" required></label><button class="btn" type="submit">查看该日期</button></form></section>';
    }
    function selectedProfile() {
      return '<div class="booking-section-head"><div><h2>' + ctx.esc(doctor.name) + ' · ' + text(ctx, doctor.departmentName, '科室待维护') + '</h2><p>'
        + (dateSelected ? ctx.esc(ctx.fmtDate(date)) + ' · 请选择上午或下午的具体时段。' : '先选择日期，再查看具体可约时段。') + '</p></div>'
        + '<button class="btn btn-sm" id="booking-change-doctor">更换医生</button></div>'
        + '<details class="booking-selected-profile"><summary>查看医生资料 · ' + text(ctx, doctor.title, '职称待维护') + '</summary><dl><dt>擅长</dt><dd>'
        + text(ctx, doctor.specialty, '医生擅长信息暂未维护') + '</dd><dt>简介</dt><dd>' + text(ctx, doctor.introduction, '医生简介暂未维护') + '</dd></dl></details>';
    }
    function slotCards() {
      if (slotLoading) return loading('正在查询所选医生的号源…');
      if (slotError) return ctx.errorPanel(slotError) + '<button class="btn" id="booking-retry-slots">重新查询时段</button>';
      if (!slotResult) return '';
      if (!slotResult.items.length) return '<div class="booking-no-slots">' + ctx.emptyState('该医生当日暂无可预约时段', '可切换上方日期，或更换医生。') + '<button class="btn" id="booking-next-day">查看后一天</button></div>';
      const groups = [{ name: '上午', items: [] }, { name: '下午', items: [] }];
      slotResult.items.forEach(function (slot) { groups[Number(String(slot.startTime).slice(0, 2)) < 12 ? 0 : 1].items.push(slot); });
      return groups.map(function (group) {
        return '<section class="booking-session"><h3>' + group.name + '</h3>' + (group.items.length ? '<div class="booking-slots">' + group.items.map(function (slot) {
          const available = Number(slot.remainingCapacity) > 0;
          return '<article class="booking-slot-card"><b>' + ctx.esc(ctx.fmtTime(slot.startTime)) + '–' + ctx.esc(ctx.fmtTime(slot.endTime)) + '</b><span>'
            + text(ctx, slot.clinicRoomName, '诊室待维护') + '</span><p class="booking-slot-capacity">' + (available ? '余号 ' + ctx.esc(slot.remainingCapacity) : '已约满')
            + '</p><small>预约截止 ' + ctx.esc(ctx.fmtDateTime(slot.bookingDeadline)) + '</small><button class="btn btn-primary" data-booking-slot="' + ctx.esc(slot.slotId)
            + '"' + (!available || handingOff ? ' disabled' : '') + '>选择此时段</button></article>';
        }).join('') + '</div>' : '<p class="booking-session-empty">本页没有' + group.name + '可预约时段</p>') + '</section>';
      }).join('') + '<div class="booking-pager"><span>共 ' + ctx.esc(slotResult.total) + ' 个时段 · 第 ' + page + ' 页</span><div><button class="btn btn-sm" data-booking-page="'
        + (page - 1) + '"' + (page <= 1 ? ' disabled' : '') + '>上一页</button><button class="btn btn-sm" data-booking-page="' + (page + 1) + '"'
        + (page * slotResult.size >= slotResult.total ? ' disabled' : '') + '>下一页</button></div></div>';
    }
    function mainPanel() {
      if (prefillLoading) return loading('正在读取复诊医生的当前公开资料…');
      if (prefillError) return ctx.errorPanel(prefillError) + '<button class="btn" id="booking-retry-prefill">重试医生预选</button><p class="tiny muted">也可点击上方预约方式，选择其他启用医生。</p>';
      if (mode === 'department' && !department) return departmentCards();
      if (mode === 'doctor' && !doctor) return doctorSearch();
      const heading = mode === 'department' ? '<div class="booking-section-head"><div><h2>' + ctx.esc(department.name) + ' · 选择出诊日期</h2><p>日期下展示当天有余号的医生，医生简介仍可从「找医生预约」查看。</p></div><button class="btn btn-sm" id="booking-change-department">更换科室</button></div>' : selectedProfile();
      return heading + datePanel() + (mode === 'department' && !doctor ? dateSelected ? '<div class="booking-section-head"><div><h2>' + ctx.esc(ctx.fmtDate(date)) + ' · 可预约医生</h2></div></div>' + doctorList()
        : ctx.emptyState('先选择一个就诊日期', '日期卡展示真实余量，选择后查看当天可预约的医生。')
        : (mode === 'department' ? selectedProfile() : '') + (dateSelected ? '<p class="booking-slot-note">下一步核对就诊人与预约信息，提交后仍需患者自行确认。</p><section id="slot-result" aria-live="polite">' + slotCards() + '</section>'
          : ctx.emptyState('请选择出诊日期', '选择日期后显示该医生的具体时段，不会自动创建预约。')));
    }
    function render() {
      if (!alive()) return;
      root.innerHTML = '<header class="page-head"><div class="page-title"><h1>预约挂号</h1><p>比较出诊日期和医生资料，安排适合自己的门诊时间。</p></div></header>'
        + '<div class="booking-workspace">' + followUpBanner() + modeButtons() + progress() + '<section class="booking-main">' + mainPanel() + '</section>'
        + (handingOff ? '<p class="booking-slot-note" role="status">正在读取就诊人，请稍候…</p>' : '') + '</div>';
      bind();
      if (handingOff) $$(root, 'button, input, select').forEach(function (element) { element.disabled = true; });
    }
    function bind() {
      $$(root, '[data-booking-mode]').forEach(function (button) { button.addEventListener('click', function () { if (button.isConnected) return reset(button.getAttribute('data-booking-mode')); }); });
      $$(root, '[data-booking-department]').forEach(function (button) { button.addEventListener('click', function () {
        if (!alive() || handingOff || !button.isConnected) return;
        const selected = departments.find(function (item) { return String(item.id) === button.getAttribute('data-booking-department'); });
        if (!selected) return;
        autoPrefill = false; ++directoryTicket; ++prefillTicket; department = selected; dateSelected = false; directory = []; directoryError = null; directoryLoading = false;
        clearDoctor(); clearAvailability(); rangeFrom = ctx.todayIso(); render(); return loadAvailability();
      }); });
      const changeDepartment = $(root, '#booking-change-department');
      if (changeDepartment) changeDepartment.addEventListener('click', function () { return reset('department'); });
      const changeDoctor = $(root, '#booking-change-doctor');
      if (changeDoctor) changeDoctor.addEventListener('click', function () {
        if (!alive() || handingOff) return;
        ++directoryTicket; clearDoctor();
        if (mode === 'doctor') { dateSelected = false; clearAvailability(); render(); return loadDirectory(); }
        render(); return loadAvailableDoctors();
      });
      const query = $(root, '#booking-search-query'), filter = $(root, '#booking-search-department');
      if (query) query.addEventListener('input', function () { keyword = query.value; });
      if (filter) { filter.value = searchDepartment; filter.addEventListener('change', function () { searchDepartment = filter.value; return loadDirectory(); }); }
      const search = $(root, '#booking-search-form');
      if (search) search.addEventListener('submit', function (event) { event.preventDefault(); keyword = query.value; searchDepartment = filter.value; return loadDirectory(); });
      $$(root, '[data-booking-doctor]').forEach(function (button) { button.addEventListener('click', function () {
        if (!alive() || handingOff || !button.isConnected) return;
        const selected = directory.find(function (item) { return String(item.id) === button.getAttribute('data-booking-doctor'); });
        if (!selected) return;
        ++directoryTicket; clearSlots(); doctor = selected; directoryLoading = false; syncState();
        if (mode === 'doctor') { dateSelected = false; clearAvailability(); rangeFrom = ctx.todayIso(); render(); return loadAvailability(); }
        render(); return loadSlots(1);
      }); });
      const dateForm = $(root, '#booking-date-form');
      if (dateForm) { const input = $(root, '#booking-date'); dateForm.addEventListener('submit', function (event) { event.preventDefault(); return chooseDate(input.value); }); input.addEventListener('change', function () { return chooseDate(input.value); }); }
      $$(root, '[data-booking-date]').forEach(function (button) { button.addEventListener('click', function () { if (button.isConnected) return chooseDate(button.getAttribute('data-booking-date')); }); });
      const previousDays = $(root, '#booking-days-prev'), nextDays = $(root, '#booking-days-next');
      if (previousDays) previousDays.addEventListener('click', function () { if (!previousDays.disabled) return moveRange(-7); });
      if (nextDays) nextDays.addEventListener('click', function () { return moveRange(7); });
      $$(root, '[data-booking-page]').forEach(function (button) { button.addEventListener('click', function () { if (!button.disabled) return loadSlots(Number(button.getAttribute('data-booking-page'))); }); });
      $$(root, '[data-booking-slot]').forEach(function (button) { button.addEventListener('click', function () { return chooseSlot(button.getAttribute('data-booking-slot')); }); });
      const nextDay = $(root, '#booking-next-day');
      if (nextDay) nextDay.addEventListener('click', function () { return chooseDate(addDays(date, 1)); });
      const retrySlots = $(root, '#booking-retry-slots'); if (retrySlots) retrySlots.addEventListener('click', function () { return refreshSelected(page); });
      const retryDays = $(root, '#booking-retry-days'); if (retryDays) retryDays.addEventListener('click', loadAvailability);
      const refresh = $(root, '#booking-refresh-availability'); if (refresh) refresh.addEventListener('click', function () { return refreshSelected(page); });
      const retryDirectory = $(root, '#booking-retry-directory'); if (retryDirectory) retryDirectory.addEventListener('click', mode === 'department' ? loadAvailableDoctors : loadDirectory);
      const retryDepartments = $(root, '#booking-retry-departments'); if (retryDepartments) retryDepartments.addEventListener('click', loadDepartments);
      const retryPrefill = $(root, '#booking-retry-prefill'); if (retryPrefill) retryPrefill.addEventListener('click', prefillFollowUp);
      const clearFollowUp = $(root, '#booking-clear-follow-up');
      if (clearFollowUp) clearFollowUp.addEventListener('click', function () {
        if (!alive() || handingOff) return;
        autoPrefill = false; ++prefillTicket; prefillLoading = false; prefillError = null;
        followUpSource = null; syncState(); render();
        if (mode === 'doctor' && !doctor) return loadDirectory();
      });
    }
    async function loadDepartments() {
      if (!alive()) return;
      const ticket = ++departmentsTicket; departmentsLoading = true; departmentsError = null; render();
      try {
        const result = await ctx.api.directory.departments();
        if (!alive() || ticket !== departmentsTicket) return;
        if (!Array.isArray(result)) throw new Error('科室目录返回格式不正确，请重试。');
        departments = result;
      } catch (failure) { if (alive() && ticket === departmentsTicket && !ctx.handleApiError(failure)) departmentsError = failure; }
      finally { if (alive() && ticket === departmentsTicket) { departmentsLoading = false; render(); } }
    }
    async function loadDirectory() {
      if (!alive() || handingOff || mode !== 'doctor') return;
      autoPrefill = false;
      if (keyword.trim().length > 64) { ctx.toast('医生姓名或擅长关键词最多 64 个字符。', 'warn'); return; }
      const ticket = ++directoryTicket; clearDoctor(); clearAvailability(); dateSelected = false;
      directory = []; directoryError = null; directoryLoading = true; render();
      try {
        const result = await ctx.api.directory.catalogue({ departmentId: searchDepartment ? Number(searchDepartment) : undefined, query: keyword.trim() || undefined });
        if (!alive() || ticket !== directoryTicket || mode !== 'doctor' || doctor) return;
        if (!Array.isArray(result)) throw new Error('医生目录返回格式不正确，请重试。');
        directory = result;
      } catch (failure) { if (alive() && ticket === directoryTicket && !ctx.handleApiError(failure)) directoryError = failure; }
      finally { if (alive() && ticket === directoryTicket) { directoryLoading = false; render(); } }
    }
    async function loadAvailability() {
      const selectedScope = scope(); if (!alive() || !selectedScope) return;
      const ticket = ++availabilityTicket, selectedKey = scopeKey(), selectedFrom = rangeFrom;
      availabilityLoading = true; availabilityError = null; availability = []; render();
      try {
        const result = await ctx.api.slots.availability(Object.assign({}, selectedScope, { from: selectedFrom, days: 7 }));
        if (!alive() || ticket !== availabilityTicket || selectedKey !== scopeKey() || selectedFrom !== rangeFrom) return;
        if (!Array.isArray(result) || !result.length) throw new Error('日期余量返回格式不正确，请重试。');
        availability = result;
      } catch (failure) { if (alive() && ticket === availabilityTicket && !ctx.handleApiError(failure)) availabilityError = failure; }
      finally { if (alive() && ticket === availabilityTicket) { availabilityLoading = false; render(); } }
    }
    async function loadAvailableDoctors() {
      if (!alive() || mode !== 'department' || !department || !dateSelected) return;
      const ticket = ++directoryTicket, departmentId = department.id, selectedDate = date;
      directoryLoading = true; directoryError = null; directory = []; render();
      try {
        const result = await ctx.api.slots.availableDoctors({ departmentId: departmentId, workDate: selectedDate });
        if (!alive() || ticket !== directoryTicket || mode !== 'department' || !department || department.id !== departmentId || date !== selectedDate) return;
        if (!Array.isArray(result)) throw new Error('当日医生安排返回格式不正确，请重试。');
        directory = result.filter(function (item) { return Number(item.departmentId) === Number(departmentId); });
      } catch (failure) { if (alive() && ticket === directoryTicket && !ctx.handleApiError(failure)) directoryError = failure; }
      finally { if (alive() && ticket === directoryTicket) { directoryLoading = false; render(); } }
    }
    async function chooseDate(value) {
      if (!alive() || handingOff || !scope()) return;
      if (!validDate(value, ctx.todayIso())) { ctx.toast('请选择今天或之后的有效就诊日期。', 'warn'); return; }
      ++directoryTicket; date = value; dateSelected = true; clearSlots();
      if (mode === 'department') { doctor = null; directory = []; directoryLoading = false; directoryError = null; }
      if (value < rangeFrom || value > addDays(rangeFrom, 6)) rangeFrom = value > '9999-12-25' ? '9999-12-25' : value;
      syncState(); render();
      return Promise.all([loadAvailability(), mode === 'department' ? loadAvailableDoctors() : loadSlots(1)]);
    }
    async function moveRange(offset) {
      if (!alive() || handingOff) return;
      let next = addDays(rangeFrom, offset); if (next < ctx.todayIso()) next = ctx.todayIso();
      if (!validDate(next, ctx.todayIso()) || !validDate(addDays(next, 6), ctx.todayIso())) { ctx.toast('日期范围已超出支持范围。', 'warn'); return; }
      ++directoryTicket; date = next; dateSelected = false; clearSlots();
      if (mode === 'department') { doctor = null; directory = []; directoryLoading = false; directoryError = null; }
      rangeFrom = next; clearAvailability(); syncState(); render(); return loadAvailability();
    }
    async function loadSlots(requestedPage) {
      if (!alive() || !doctor || !dateSelected) return;
      const ticket = ++slotTicket, doctorId = doctor.id, selectedDate = date;
      page = requestedPage || 1; slotResult = null; slotError = null; slotLoading = true; syncState(); render();
      try {
        const result = await ctx.api.slots.list({ doctorId: doctorId, departmentId: doctor.departmentId, workDate: selectedDate, page: page, size: 12 });
        if (!alive() || ticket !== slotTicket || !doctor || doctor.id !== doctorId || date !== selectedDate) return;
        if (!result || !Array.isArray(result.items)) throw new Error('号源返回格式不正确，请重试。');
        slotResult = { items: result.items.filter(function (slot) { return Number(slot.doctorId) === Number(doctorId) && slot.workDate === selectedDate; }), total: Number(result.total) || 0, size: Number(result.size) || 12 };
      } catch (failure) { if (alive() && ticket === slotTicket && !ctx.handleApiError(failure)) slotError = failure; }
      finally { if (alive() && ticket === slotTicket) { slotLoading = false; render(); } }
    }
    async function refreshSelected(requestedPage) {
      if (!alive()) return;
      const requests = [loadAvailability()];
      if (mode === 'department' && dateSelected) requests.push(loadAvailableDoctors());
      if (doctor && dateSelected) requests.push(loadSlots(requestedPage || page));
      return Promise.all(requests);
    }
    async function chooseSlot(id) {
      if (!alive() || handingOff || !doctor || !dateSelected || !slotResult) return;
      const slot = slotResult.items.find(function (item) { return String(item.slotId) === id; });
      if (!slot || Number(slot.remainingCapacity) <= 0 || Number(slot.doctorId) !== Number(doctor.id) || slot.workDate !== date) return;
      handingOff = true; render();
      try { await ctx.openBooking(Object.assign({}, slot, { doctorTitle: doctor.title || '' }), followUpSource); }
      catch (failure) { if (alive()) ctx.handleApiError(failure); }
      finally { if (alive()) { handingOff = false; render(); } }
    }
    async function prefillFollowUp() {
      if (!alive() || !followUpSource) return;
      const source = followUpSource, ticket = ++prefillTicket;
      ++directoryTicket; clearDoctor(); clearAvailability(); mode = 'doctor'; dateSelected = false;
      prefillLoading = true; prefillError = null; render();
      try {
        const selected = await ctx.api.directory.doctorDetail(source.doctorId);
        if (!alive() || ticket !== prefillTicket || followUpSource !== source) return;
        if (!selected || Number(selected.id) !== Number(source.doctorId)) throw new Error('原医生资料暂不可用，请选择其他医生。');
        doctor = selected; department = null; searchDepartment = String(selected.departmentId); rangeFrom = ctx.todayIso(); syncState();
      } catch (failure) { if (alive() && ticket === prefillTicket && !ctx.handleApiError(failure)) prefillError = failure; }
      finally { if (alive() && ticket === prefillTicket) { prefillLoading = false; render(); } }
      if (alive() && ticket === prefillTicket && doctor) await loadAvailability();
    }

    shared.loadSlots = refreshSelected;
    syncState();
    await loadDepartments();
    if (alive() && autoPrefill && followUpSource) await prefillFollowUp();
  }

  global.MedFlowBooking = { view: view };
})(window);
