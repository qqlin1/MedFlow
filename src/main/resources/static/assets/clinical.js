(function (global) {
  'use strict';

  const ENCOUNTER_STATUS = {
    WAITING: ['待就诊', 'warn'], IN_PROGRESS: ['就诊中', 'ok'],
    COMPLETED: ['已完成', 'muted'], SKIPPED: ['已过号', 'muted']
  };
  const SCHEDULE_STATUS = {
    DRAFT: ['草稿', 'warn'], PUBLISHED: ['已发布', 'ok'],
    CLOSED: ['已关闭', 'muted'], CANCELLED: ['已取消出诊', 'muted']
  };
  const FIELDS = [
    ['chiefComplaint', '主诉', 2000], ['presentIllness', '现病史', 8000],
    ['diagnosis', '诊断', 4000], ['advice', '医嘱', 8000]
  ];

  function $(root, selector) { return root.querySelector(selector); }
  function $$(root, selector) { return Array.prototype.slice.call(root.querySelectorAll(selector)); }
  function time(value) { return value ? String(value).slice(0, 5) : '—'; }
  function range(item) { return time(item.startTime) + '–' + time(item.endTime); }
  function amount(value) { return value === null || value === undefined || !Number.isFinite(Number(value)) ? '金额未记录' : '¥ ' + Number(value).toFixed(2); }
  function badge(ctx, value, labels) {
    const meta = labels[value] || [value || '—', 'muted'];
    return '<span class="badge ' + meta[1] + '">' + ctx.esc(meta[0]) + '</span>';
  }
  function heading(title, description, actions) {
    return '<header class="page-head"><div class="page-title"><h1>' + title + '</h1><p>'
      + description + '</p></div><div class="page-tools">' + (actions || '') + '</div></header>';
  }
  function loading() {
    return '<div class="clinical-loading" role="status" aria-live="polite"><span class="spinner"></span>正在加载…</div>';
  }
  function options(ctx, labels, all) {
    return (all ? '<option value="">全部状态</option>' : '') + Object.keys(labels).map(function (key) {
      return '<option value="' + key + '">' + ctx.esc(labels[key][0]) + '</option>';
    }).join('');
  }
  function error(ctx, host, failure) {
    if (host.isConnected && !ctx.handleApiError(failure)) host.innerHTML = ctx.errorPanel(failure);
  }
  function pager(ctx, result, page) {
    const total = Math.max(0, Number(result.total) || 0);
    const size = Number(result.size) || 10;
    return '<div class="clinical-pager"><span class="muted tiny">共 ' + total + ' 条 · 第 ' + page
      + ' / ' + Math.max(1, Math.ceil(total / size)) + ' 页</span><div class="clinical-actions">'
      + '<button class="btn btn-sm" data-page="' + (page - 1) + '"' + (page <= 1 ? ' disabled' : '') + '>上一页</button>'
      + '<button class="btn btn-sm" data-page="' + (page + 1) + '"' + (page * size >= total ? ' disabled' : '') + '>下一页</button></div></div>';
  }
  function bindPages(host, load) {
    $$(host, '[data-page]').forEach(function (button) {
      button.addEventListener('click', function () { if (!button.disabled) return load(Number(button.getAttribute('data-page'))); });
    });
  }
  function dateInput(ctx, id, value, title) {
    return '<label class="field"><span>' + title + '</span><input type="date" id="' + id + '" value="' + ctx.esc(value) + '" required></label>';
  }
  function summary(ctx, item) {
    return '<div class="clinical-patient-heading"><span class="avatar">' + ctx.esc(String(item.patientName || '?').slice(0, 1))
      + '</span><div><h3>' + ctx.esc(item.patientName || '就诊人') + '</h3><p>' + ctx.esc(item.departmentName)
      + ' · ' + ctx.esc(item.doctorName) + ' · ' + ctx.esc(item.clinicRoomName) + '</p></div></div>'
      + '<div class="clinical-visit-summary"><span>' + ctx.esc(ctx.fmtDate(item.workDate)) + ' ' + ctx.esc(range(item))
      + '</span>' + badge(ctx, item.status, ENCOUNTER_STATUS) + '</div>' + snapshotNotice(ctx, item) + sourceNotice(ctx, item);
  }
  function recordHtml(ctx, record) {
    return '<div class="clinical-record-read">' + FIELDS.map(function (field) {
      return '<section><h4>' + field[1] + '</h4><p>' + ctx.esc((record || {})[field[0]] || '未填写') + '</p></section>';
    }).join('') + '</div>';
  }
  function snapshotNotice(ctx, visit) {
    return visit.hasBookingSnapshot === false ? '<p class="clinical-snapshot-note">这条旧记录未保存预约时的资料快照，医生、职称、科室和诊室使用当前资料展示，不能据此认定当时名称。</p>' : '';
  }
  function sourceNotice(ctx, visit) {
    return visit.sourceEncounterId ? '<p class="clinical-source-note">再次预约来源：' + ctx.esc(ctx.fmtDate(visit.sourceWorkDate))
      + ' 的已完成接诊。新旧病历和费用独立，不会复制旧病历。<small>来源预约 #' + ctx.esc(visit.sourceAppointmentId || '—') + '</small></p>' : '';
  }
  function appointmentSummary(ctx, visit, patient, options) {
    const person = patient || {};
    const beforeCreate = options && options.beforeCreate;
    const relationship = person.relationship === 'SELF' ? '本人' : (person.relationship || '关系未记录');
    return '<section class="clinical-appointment-summary"><h3>' + (beforeCreate ? '请核对本次就诊安排' : '门诊预约凭证') + '</h3><dl class="kv">'
      + '<dt>就诊人</dt><dd><b>' + ctx.esc(visit.patientName || person.name || '未记录') + '</b> · ' + ctx.esc(relationship) + '</dd>'
      + '<dt>联系电话</dt><dd>' + ctx.esc(person.maskedPhone || '未记录') + '</dd><dt>医生 / 职称</dt><dd>' + ctx.esc(visit.doctorName || '未记录')
      + ' · ' + ctx.esc(visit.doctorTitle || '职称未维护') + '</dd><dt>科室 / 诊室</dt><dd>' + ctx.esc(visit.departmentName || '未记录') + ' · '
      + ctx.esc(visit.clinicRoomName || '未记录') + '</dd><dt>就诊日期</dt><dd>' + ctx.esc(visit.workDate || '未记录') + ' · ' + ctx.esc(ctx.fmtDate(visit.workDate))
      + '</dd><dt>具体时段</dt><dd>' + ctx.esc(range(visit)) + '</dd><dt>确认截止</dt><dd>'
      + (beforeCreate ? '提交后由服务端计算，以成功凭证显示的时间为准' : ctx.esc(ctx.fmtDateTime(visit.confirmDeadline)))
      + '</dd><dt>取消截止</dt><dd>' + ctx.esc(ctx.fmtDateTime(visit.cancelDeadline)) + '（签到后不可取消）</dd></dl>'
      + (beforeCreate ? '<p class="muted tiny">提交后还需您自行确认预约。号源余量会变化，提交以服务端结果为准。</p>'
        : '<small class="muted clinical-voucher-number">预约编号 #' + ctx.esc(visit.appointmentId) + '</small>')
      + snapshotNotice(ctx, visit) + sourceNotice(ctx, visit) + '</section>';
  }
  function progressText(voucher) {
    const windowText = '请在就诊当天 ' + String(voucher.checkInOpensAt || '').replace('T', ' ') + ' 至 '
      + String(voucher.checkInClosesAt || '').replace('T', ' ') + ' 前到院签到。';
    const messages = {
      AWAITING_CONFIRMATION: ['请先自行确认预约', '请在确认截止前点击“确认预约”。无需管理员审批，确认后才能在当日窗口签到。'],
      CONFIRMATION_OVERDUE: ['确认时间已过', '当前不能再确认。预约状态可能仍在等待系统关闭，请刷新状态；不要重复创建相同预约。'],
      UPCOMING: ['等待就诊当天的签到窗口', windowText + ' 当前尚不能签到。'],
      READY_TO_CHECK_IN: ['现在可以到院签到', windowText + ' 确认已到院后再签到，签到后进入候诊队列。'],
      WAITING: ['已签到，等待医生叫号', '请按门诊安排候诊。病历在医生完成本次接诊后开放查看。'],
      IN_PROGRESS: ['医生正在接诊', '本次病历尚未完成，正文暂不向患者展示。'],
      COMPLETED: ['本次接诊已完成', voucher.bill && voucher.bill.status === 'UNPAID' ? '可以查看已完成病历；另有一笔处方药费待模拟缴费，缴费不会改变接诊完成状态。' : '可以查看已完成病历及已提交处方；如需再次就诊，可从档案中关联本次重新预约。'],
      SKIPPED: ['本次已过号', '过号为本版本的最终状态，不能补叫或重新加入本次队列。如需就诊，请重新预约。'],
      MISSED_CHECK_IN: ['已错过本次签到时间', '本次预约已确认，但结束前未签到。这与未确认超时不同；如需就诊，请重新预约。'],
      CANCELLED: ['预约已取消', '本次预约不能签到。如仍需就诊，请重新选择可预约的号源。'],
      EXPIRED: ['未确认预约已关闭', '确认期限内未完成确认，本次预约已失效。如需就诊，请重新预约。']
    };
    return messages[voucher.progress] || ['请刷新门诊进度', '当前进度暂不可识别，请刷新后再操作。'];
  }
  function voucherOverview(ctx, voucher) {
    const info = voucher.visit;
    const bill = voucher.bill;
    const next = progressText(voucher);
    return appointmentSummary(ctx, info, voucher)
      + '<div class="clinical-independent-status"><div><span>预约状态</span>' + ctx.statusBadge(info.status) + '</div><div><span>接诊状态</span>'
      + (info.encounterStatus ? badge(ctx, info.encounterStatus, ENCOUNTER_STATUS) : '<span class="badge muted">尚未签到</span>')
      + '</div><div><span>账单状态</span><span class="badge ' + (bill && bill.status === 'UNPAID' ? 'warn' : 'muted') + '">'
      + (bill ? bill.status === 'PAID' ? '已模拟缴费' : bill.status === 'UNPAID' ? '待模拟缴费' : ctx.esc(bill.status) : '无已提交处方账单') + '</span></div></div>'
      + '<section class="clinical-next-step" aria-live="polite"><h3>' + ctx.esc(next[0]) + '</h3><p>' + ctx.esc(next[1])
      + '</p><small>本次读取的服务端时间：' + ctx.esc(ctx.fmtDateTime(voucher.databaseNow)) + '。操作前服务端仍会重新校验。</small></section>';
  }
  function historyHtml(ctx, history) {
    if (!history || !history.length) return '';
    return '<details class="clinical-history"><summary>接诊状态记录（' + history.length + '）</summary><ol>'
      + history.map(function (item) {
        return '<li>' + ctx.esc((ENCOUNTER_STATUS[item.toStatus] || [item.toStatus])[0]) + ' <span class="muted">'
          + ctx.esc(ctx.fmtDateTime(item.createdAt)) + '</span></li>';
      }).join('') + '</ol></details>';
  }
  function appointmentHistoryHtml(ctx, history) {
    if (!history.length) return '';
    const labels = { CREATED: '创建预约', CONFIRMED: '确认预约', CANCELLED: '取消预约', EXPIRED: '超时关闭' };
    return '<details class="clinical-history"><summary>预约状态记录（' + history.length + '）</summary><ol>'
      + history.map(function (item) { return '<li>' + ctx.esc(labels[item.eventType] || '状态变化') + ' <span class="muted">'
        + ctx.esc(ctx.fmtDateTime(item.createdAt)) + ' · ' + (item.actorUserId == null ? '系统处理' : '患者操作') + '</span></li>'; }).join('')
      + '</ol></details>';
  }

  async function doctorOverview(root, ctx) {
    root.innerHTML = heading('医生工作台', '从今日候诊到病历完成，掌握每一次接诊。',
      '<a class="btn btn-primary" href="#/doctor-queue">进入候诊队列 →</a>') + '<div id="clinical-overview">' + loading() + '</div>';
    const body = $(root, '#clinical-overview');
    const today = ctx.todayIso();
    const results = await Promise.allSettled([
      ctx.api.doctor.schedules({ from: today, to: today, page: 1, size: 5 }),
      ctx.api.doctor.encounters({ workDate: today, status: 'WAITING', page: 1, size: 1 }),
      ctx.api.doctor.encounters({ workDate: today, status: 'IN_PROGRESS', page: 1, size: 1 }),
      ctx.api.doctor.encounters({ workDate: today, status: 'COMPLETED', page: 1, size: 1 })
    ]);
    if (!body.isConnected) return;
    const authFailure = results.find(function (result) { return result.status === 'rejected' && result.reason.status === 401; });
    if (authFailure) { ctx.handleApiError(authFailure.reason); return; }
    const labels = ['今日排班', '待就诊', '就诊中', '已完成'];
    const notes = ['今日全部状态排班数', '已签到，等候接诊', '当前进行中的接诊', '今日已完成的接诊'];
    body.innerHTML = '<div class="stats-grid">' + results.map(function (result, index) {
      return '<article class="stat-card"><div class="stat-label">' + labels[index] + '</div><div class="stat-value">'
        + (result.status === 'fulfilled' ? ctx.esc(result.value.total) : '—') + '</div><div class="stat-note">' + notes[index] + '</div></article>';
    }).join('') + '</div>' + results.filter(function (result) { return result.status === 'rejected'; }).map(function (result) {
      return ctx.errorPanel(result.reason);
    }).join('') + '<section class="card clinical-overview-card"><div class="card-head"><div><h3>今日门诊安排</h3><p>'
      + ctx.esc(ctx.fmtDate(today)) + ' · 展示最近 5 条</p></div><a class="text-link" href="#/doctor-schedules">全部排班 →</a></div><div class="card-body">'
      + (results[0].status === 'fulfilled' && results[0].value.items.length ? scheduleTable(ctx, results[0].value.items, false)
        : results[0].status === 'fulfilled' ? ctx.emptyState('今日暂无排班', '可以查看其他日期的本人排班。') : '<p class="muted">今日排班暂未加载。</p>')
      + '</div></section><div class="clinical-info-strip">' + ctx.icon('info')
      + '<p>患者签到后进入候诊队列。开始接诊后填写并保存病历，再完成本次就诊。</p></div>';
  }

  function scheduleTable(ctx, items, admin) {
    return '<div class="table-wrap"><table><thead><tr><th>日期 / 班次</th>' + (admin ? '<th>医生 / 科室</th>' : '<th>科室 / 诊室</th>')
      + '<th>门诊时间</th><th>状态</th><th>余量 / 总量</th>' + (admin ? '<th>有效预约</th><th>操作</th>' : '')
      + '</tr></thead><tbody>' + items.map(function (item) {
        return '<tr><td><b>' + ctx.esc(ctx.fmtDate(item.workDate)) + '</b><small class="cell-sub">' + ctx.esc(item.shiftName)
          + ' · #' + ctx.esc(item.id) + '</small></td><td>' + ctx.esc(admin ? item.doctorName : item.departmentName)
          + '<small class="cell-sub">' + ctx.esc(admin ? item.departmentName : item.clinicRoomName) + '</small></td><td class="mono">'
          + ctx.esc(range(item)) + '</td><td>' + badge(ctx, item.status, SCHEDULE_STATUS) + '</td><td>'
          + ctx.esc(item.remainingCapacity) + ' / ' + ctx.esc(item.totalCapacity) + '</td>'
          + (admin ? '<td>' + ctx.esc(item.activeAppointmentCount) + '</td><td><button class="btn btn-sm" data-schedule="'
            + ctx.esc(item.id) + '">查看详情</button></td>' : '') + '</tr>';
      }).join('') + '</tbody></table></div>';
  }

  async function doctorSchedules(root, ctx) {
    root.innerHTML = heading('我的排班', '查看本人各日期出诊安排及实时号源余量。')
      + '<form id="clinical-filters" class="clinical-filters">' + dateInput(ctx, 'clinical-from', ctx.todayIso(), '开始日期')
      + dateInput(ctx, 'clinical-to', ctx.todayIso(6), '结束日期')
      + '<label class="field"><span>状态</span><select id="clinical-status">' + options(ctx, SCHEDULE_STATUS, true)
      + '</select></label><button class="btn btn-primary" type="submit">查询排班</button></form><section id="clinical-list"></section>';
    const body = $(root, '#clinical-list');
    let version = 0;
    const load = async function (page) {
      const ticket = ++version;
      const from = $(root, '#clinical-from').value;
      const to = $(root, '#clinical-to').value;
      if (!validRange(ctx, from, to)) return;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.doctor.schedules({ from: from, to: to, status: $(root, '#clinical-status').value, page: page || 1, size: 10 });
        if (!body.isConnected || ticket !== version) return;
        body.innerHTML = result.items.length ? scheduleTable(ctx, result.items, false) + pager(ctx, result, page || 1)
          : ctx.emptyState('没有符合条件的排班', '可以调整日期范围或状态后查询。');
        bindPages(body, load);
      } catch (failure) { if (ticket === version) error(ctx, body, failure); }
    };
    $(root, '#clinical-filters').addEventListener('submit', function (event) { event.preventDefault(); return load(1); });
    await load(1);
  }

  function validRange(ctx, from, to) {
    if (!from || !to || from > to) { ctx.toast('请选择正确的起止日期。', 'warn'); return false; }
    if ((Date.parse(to) - Date.parse(from)) / 86400000 > 90) { ctx.toast('一次最多查询 90 天，请缩小日期范围。', 'warn'); return false; }
    return true;
  }

  async function doctorAppointments(root, ctx) {
    root.innerHTML = heading('我的预约', '仅展示安排给当前医生的预约，已确认患者签到后进入候诊队列。')
      + '<form id="clinical-filters" class="clinical-filters">' + dateInput(ctx, 'clinical-date', ctx.todayIso(), '就诊日期')
      + '<button class="btn btn-primary" type="submit">查询预约</button></form><section id="clinical-list"></section>';
    const body = $(root, '#clinical-list');
    let version = 0;
    const load = async function (page) {
      const ticket = ++version;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.doctor.appointments({ workDate: $(root, '#clinical-date').value, page: page || 1, size: 10 });
        if (!body.isConnected || ticket !== version) return;
        body.innerHTML = result.items.length ? '<div class="table-wrap"><table><thead><tr><th>就诊人 / 预约</th><th>时间 / 诊室</th><th>预约状态</th><th>接诊状态</th><th>操作</th></tr></thead><tbody>'
          + result.items.map(function (item) {
            return '<tr><td>' + (item.status === 'BOOKED' || item.encounterId ? '<button class="patient-history-name" data-doctor-history="' + ctx.esc(item.appointmentId) + '">' + ctx.esc(item.patientName) + '</button>' : '<b>' + ctx.esc(item.patientName) + '</b>') + '<small class="cell-sub">预约 #' + ctx.esc(item.appointmentId)
              + '</small></td><td>' + ctx.esc(range(item)) + '<small class="cell-sub">' + ctx.esc(item.clinicRoomName) + '</small></td><td>'
              + ctx.statusBadge(item.status) + '</td><td>' + (item.encounterStatus ? badge(ctx, item.encounterStatus, ENCOUNTER_STATUS) : '<span class="muted">未签到</span>')
              + '</td><td>' + (item.encounterId ? '<button class="btn btn-sm" data-encounter="' + ctx.esc(item.encounterId) + '">查看接诊</button>' : '<span class="muted tiny">等待患者签到</span>') + '</td></tr>';
          }).join('') + '</tbody></table></div>' + pager(ctx, result, page || 1)
          : ctx.emptyState('当日暂无预约', '已发布排班的预约会显示在这里。');
        bindPages(body, load);
        $$(body, '[data-doctor-history]').forEach(function (button) {
          button.addEventListener('click', function () { return global.MedFlowPatientHistory.openDoctor(Number(button.getAttribute('data-doctor-history')), ctx, { returnLabel: '返回预约名单' }); });
        });
        $$(body, '[data-encounter]').forEach(function (button) {
          button.addEventListener('click', function () { return openDoctorEncounter(Number(button.getAttribute('data-encounter')), ctx, function () { return load(page || 1); }); });
        });
      } catch (failure) { if (ticket === version) error(ctx, body, failure); }
    };
    $(root, '#clinical-filters').addEventListener('submit', function (event) { event.preventDefault(); return load(1); });
    await load(1);
  }

  async function doctorQueue(root, ctx) {
    root.innerHTML = heading('候诊与病历', '按门诊时段、签到时间排序；同一时间只接诊一人。', '<button class="btn" id="clinical-refresh">刷新队列</button>')
      + '<form id="clinical-filters" class="clinical-filters">' + dateInput(ctx, 'clinical-date', ctx.todayIso(), '就诊日期')
      + '<label class="field"><span>接诊状态</span><select id="clinical-status">' + options(ctx, ENCOUNTER_STATUS, true)
      + '</select></label><button class="btn btn-primary" type="submit">查询队列</button></form><section id="clinical-list"></section>';
    const body = $(root, '#clinical-list');
    let pageNow = 1;
    let version = 0;
    const load = async function (page) {
      const ticket = ++version;
      pageNow = page || 1;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.doctor.encounters({ workDate: $(root, '#clinical-date').value, status: $(root, '#clinical-status').value, page: pageNow, size: 10 });
        if (!body.isConnected || ticket !== version) return;
        body.innerHTML = result.items.length ? '<div class="clinical-queue">' + result.items.map(function (item) {
          return '<article class="clinical-queue-card"><div class="clinical-queue-main"><span class="avatar">' + ctx.esc(String(item.patientName || '?').slice(0, 1))
            + '</span><div><h3><button class="patient-history-name" data-doctor-history="' + ctx.esc(item.appointmentId) + '">' + ctx.esc(item.patientName) + '</button> ' + badge(ctx, item.status, ENCOUNTER_STATUS) + '</h3><p>'
            + ctx.esc(range(item)) + ' · ' + ctx.esc(item.clinicRoomName) + ' · 预约 #' + ctx.esc(item.appointmentId) + '</p><small class="muted">签到 '
            + ctx.esc(ctx.fmtDateTime(item.checkedInAt)) + '</small></div></div><button class="btn ' + (item.status === 'IN_PROGRESS' ? 'btn-primary' : '')
            + '" data-encounter="' + ctx.esc(item.id) + '">' + (item.status === 'IN_PROGRESS' ? '填写病历' : item.status === 'WAITING' ? '查看并接诊' : '查看记录') + '</button></article>';
        }).join('') + '</div>' + pager(ctx, result, pageNow) : ctx.emptyState('暂无符合条件的接诊', '已确认的患者在就诊时段签到后，会加入队列。可调整状态或点击刷新。');
        bindPages(body, load);
        $$(body, '[data-doctor-history]').forEach(function (button) {
          button.addEventListener('click', function () { return global.MedFlowPatientHistory.openDoctor(Number(button.getAttribute('data-doctor-history')), ctx, { returnLabel: '返回候诊队列' }); });
        });
        $$(body, '[data-encounter]').forEach(function (button) {
          button.addEventListener('click', function () { return openDoctorEncounter(Number(button.getAttribute('data-encounter')), ctx, function () { return load(pageNow); }); });
        });
      } catch (failure) { if (ticket === version) error(ctx, body, failure); }
    };
    $(root, '#clinical-filters').addEventListener('submit', function (event) { event.preventDefault(); return load(1); });
    $(root, '#clinical-refresh').addEventListener('click', function () { return load(pageNow); });
    await load(1);
  }

  async function openDoctorEncounter(id, ctx, onChange) {
    const modal = ctx.openModal({ title: '接诊与病历', subtitle: '接诊编号 #' + id, width: 780,
      body: loading(), footer: '<button class="btn" data-close>关闭</button>' });
    const body = $(modal.root, '.modal-body');
    let detail;
    let busy = false;
    let savedValues = {};
    function values() {
      const result = {};
      FIELDS.forEach(function (field) { result[field[0]] = $(body, '[name="' + field[0] + '"]').value; });
      return result;
    }
    function dirty() { return JSON.stringify(values()) !== JSON.stringify(savedValues); }
    function setBusy(value) {
      busy = value;
      $$(body, 'button, textarea').forEach(function (control) { control.disabled = value; });
    }
    async function action(kind) {
      if (busy) return;
      if (kind === 'complete' && dirty()) { ctx.toast('病历有未保存内容，请先保存草稿。', 'warn'); return; }
      if (kind === 'complete' && (!detail.medicalRecord || !savedValues.chiefComplaint.trim() || !savedValues.diagnosis.trim())) {
        ctx.toast('请先保存病历，并填写主诉和诊断。', 'warn'); return;
      }
      if (kind === 'skip' && !$(body, '#clinical-skip-confirm').checked) { ctx.toast('请先勾选确认过号。', 'warn'); return; }
      setBusy(true);
      $(body, '#clinical-record-error').innerHTML = '';
      try {
        if (kind === 'save') {
          const record = await ctx.api.doctor.saveRecord(id, Object.assign(values(), { version: detail.medicalRecord ? detail.medicalRecord.version : 0 }));
          if (!modal.root.isConnected) return;
          // The save endpoint returns the record; tolerate a detail envelope as well.
          detail.medicalRecord = record.medicalRecord || record;
          ctx.toast('病历草稿已保存。', 'ok');
          render();
        } else {
          const next = kind === 'complete' ? await ctx.api.doctor.complete(id, detail.medicalRecord.version)
            : kind === 'start' ? await ctx.api.doctor.start(id) : await ctx.api.doctor.skip(id);
          if (!modal.root.isConnected) return;
          detail = next;
          render();
          ctx.toast(kind === 'complete' ? '本次接诊已完成，患者可以查看病历。' : kind === 'start' ? '已开始接诊，请填写病历。' : '已标记过号。', 'ok');
          if (onChange) await onChange();
        }
      } catch (failure) {
        if (!modal.root.isConnected || ctx.handleApiError(failure)) return;
        $(body, '#clinical-record-error').innerHTML = ctx.errorPanel(failure)
          + (failure.status === 409 ? '<p class="muted tiny">当前内容已保留。状态或版本可能已更新，重新加载会舍弃未保存内容。</p><button class="btn btn-sm" id="clinical-reload">重新加载记录</button>' : '');
        const reload = $(body, '#clinical-reload');
        if (reload) reload.addEventListener('click', load);
      } finally { if (modal.root.isConnected) setBusy(false); }
    }
    function render() {
      const item = detail.encounter;
      const active = item.status === 'IN_PROGRESS';
      const record = detail.medicalRecord;
      body.innerHTML = summary(ctx, item) + (active ? '<form id="clinical-record" class="clinical-record-form">'
        + '<div class="clinical-info-strip"><p>先保存草稿，再完成接诊。完成后病历只读，患者可查看。</p></div>'
        + FIELDS.map(function (field) {
          return '<label class="field"><span>' + field[1] + (field[0] === 'chiefComplaint' || field[0] === 'diagnosis' ? ' <span class="clinical-required">*</span>' : '')
            + '</span><textarea name="' + field[0] + '" rows="' + (field[0] === 'presentIllness' ? 4 : 3) + '" maxlength="' + field[2] + '">'
            + ctx.esc((record || {})[field[0]] || '') + '</textarea></label>';
        }).join('') + '<div class="clinical-record-meta" id="clinical-save-state" role="status">'
        + (record ? '已保存 · 版本 ' + ctx.esc(record.version) + ' · ' + ctx.esc(ctx.fmtDateTime(record.updatedAt)) : '尚未保存病历')
        + '</div><div class="clinical-actions"><button class="btn" type="submit">保存病历草稿</button>'
        + '<button class="btn btn-primary" type="button" id="clinical-complete">完成接诊</button></div></form>'
        : item.status === 'WAITING' ? '<div class="clinical-info-strip"><p>开始接诊需到达该时段开始时间。同一医生只允许一位就诊中患者。</p></div>'
          + '<div class="clinical-actions"><button class="btn btn-primary" id="clinical-start">开始接诊</button></div>'
          + '<details class="clinical-skip"><summary>患者未到？</summary><p class="muted tiny">过号是本版本的最终状态，不能重新加入队列。</p>'
          + '<label class="clinical-check"><input type="checkbox" id="clinical-skip-confirm">确认该患者已过号</label><button class="btn btn-danger btn-sm" id="clinical-skip">标记过号</button></details>'
          : record ? recordHtml(ctx, record) : '<p class="muted">本次接诊没有病历。</p>')
        + '<div id="clinical-record-error" aria-live="polite"></div>'
        + '<div class="clinical-actions clinical-visit-refresh"><button class="btn" id="clinical-patient-history">查看患者院内历史</button>'
        + (item.sourceAppointmentId ? '<button class="btn" id="clinical-source-history">查看前次已完成病历</button>' : '') + '</div>'
        + ((active || item.status === 'COMPLETED') ? '<div class="clinical-info-strip"><p>' + (active ? '可选：为本次接诊填写处方。保存病历后再进入；完成接诊前请提交或放弃处方草稿。' : '接诊已完成，可查看本次处方和缴费状态，不能再新建或修改处方。') + '</p></div><button class="btn" id="clinical-prescription">处方与收费</button>' : '')
        + historyHtml(ctx, detail.history);
      $(body, '#clinical-patient-history').addEventListener('click', function () {
        if (busy) return;
        if (active && dirty()) { ctx.toast('病历有未保存内容，请先保存草稿再查看历史。', 'warn'); return; }
        return global.MedFlowPatientHistory.openDoctor(item.appointmentId, ctx, { returnLabel: '返回本次接诊', onReturn: function () { return openDoctorEncounter(id, ctx, onChange); } });
      });
      const sourceHistory = $(body, '#clinical-source-history');
      if (sourceHistory) sourceHistory.addEventListener('click', function () {
        if (busy) return;
        if (active && dirty()) { ctx.toast('病历有未保存内容，请先保存草稿再查看前次病历。', 'warn'); return; }
        return global.MedFlowPatientHistory.openDoctor(item.appointmentId, ctx, { initialAppointmentId: item.sourceAppointmentId,
          returnLabel: '返回本次接诊', onReturn: function () { return openDoctorEncounter(id, ctx, onChange); } });
      });
      const prescriptionButton = $(body, '#clinical-prescription');
      if (prescriptionButton) prescriptionButton.addEventListener('click', function () {
        if (active && dirty()) { ctx.toast('病历有未保存内容，请先保存草稿再进入处方。', 'warn'); return; }
        return global.MedFlowBusiness.openDoctorPrescription(id, ctx, onChange);
      });
      if (active) {
        savedValues = values();
        $(body, '#clinical-record').addEventListener('submit', function (event) { event.preventDefault(); return action('save'); });
        $(body, '#clinical-complete').addEventListener('click', function () { return action('complete'); });
        $$(body, 'textarea').forEach(function (control) { control.addEventListener('input', function () {
          $(body, '#clinical-save-state').textContent = dirty() ? '有未保存内容，请先保存草稿。' : record ? '内容与已保存版本一致。' : '尚未保存病历';
        }); });
      }
      if (item.status === 'WAITING') {
        $(body, '#clinical-start').addEventListener('click', function () { return action('start'); });
        $(body, '#clinical-skip').addEventListener('click', function () { return action('skip'); });
      }
    }
    async function load() {
      if (busy) return;
      busy = true;
      body.innerHTML = loading();
      try {
        detail = await ctx.api.doctor.encounter(id);
        if (modal.root.isConnected) render();
      } catch (failure) { error(ctx, body, failure); }
      finally { busy = false; }
    }
    await load();
  }

  async function openPatientVisit(appointmentId, ctx) {
    const openedToken = global.MedFlow && global.MedFlow.session ? global.MedFlow.session.token() : null;
    const modal = ctx.openModal({ title: '预约凭证与门诊进度', subtitle: '核对就诊安排，按当前进度完成下一步。', width: 760,
      body: loading(), footer: '<button class="btn" data-close>关闭</button>' });
    const body = $(modal.root, '.modal-body');
    let busy = false;
    let voucher = null;
    function alive() { return body.isConnected && (!global.MedFlow || !global.MedFlow.session || global.MedFlow.session.token() === openedToken); }
    function showFailure(failure, retry) {
      if (!alive() || ctx.handleApiError(failure)) return;
      body.innerHTML = ctx.errorPanel(failure) + '<p class="muted tiny">读取失败不代表预约不存在。请重试，不要因此重新提交预约。</p><button class="btn" id="clinical-visit-retry">重新读取预约凭证</button>';
      $(body, '#clinical-visit-retry').addEventListener('click', retry);
    }
    async function load() {
      if (busy || !alive()) return;
      busy = true;
      body.innerHTML = loading();
      try {
        voucher = await ctx.api.appointments.voucher(appointmentId);
        if (!alive()) return;
        const info = voucher.visit;
        let detail = null;
        let recordFailure = null;
        let appointmentDetail = null;
        let historyFailure = null;
        const results = await Promise.allSettled([
          info.encounterId ? ctx.api.appointments.encounter(appointmentId) : Promise.resolve(null),
          ctx.api.appointments.detail(appointmentId)
        ]);
        if (!alive()) return;
        if (results[0].status === 'fulfilled') detail = results[0].value;
        else recordFailure = results[0].reason;
        if (results[1].status === 'fulfilled') appointmentDetail = results[1].value;
        else historyFailure = results[1].reason;
        if ((recordFailure && ctx.handleApiError(recordFailure)) || (historyFailure && ctx.handleApiError(historyFailure))) return;
        const bill = voucher.bill;
        body.innerHTML = voucherOverview(ctx, voucher)
          + '<div class="clinical-actions">'
          + (voucher.canConfirm ? '<button class="btn btn-primary" id="clinical-confirm">确认预约</button>' : '')
          + (voucher.canCheckIn ? '<button class="btn btn-primary" id="clinical-checkin">确认已到院并签到</button>' : '')
          + (bill ? '<a class="btn" href="#/prescriptions" id="clinical-bill">' + (bill.status === 'UNPAID' ? '去模拟缴费' : '查看处方与缴费') + '</a>' : '') + '</div>'
          + (bill ? '<p class="clinical-bill-summary">已提交处方金额：<b>' + ctx.esc(amount(bill.totalAmount)) + '</b>'
            + (bill.paidAt ? ' · 模拟缴费时间 ' + ctx.esc(ctx.fmtDateTime(bill.paidAt)) : '') + '。仅模拟登记，不发生真实扣款。</p>' : '')
          + (info.encounterStatus === 'COMPLETED' && detail && detail.encounter.status === 'COMPLETED' && detail.medicalRecord ? '<h3 class="clinical-section-title">已完成病历</h3>' + recordHtml(ctx, detail.medicalRecord) : '')
          + (recordFailure ? '<section class="clinical-partial-error">' + ctx.errorPanel(recordFailure) + '<p class="muted tiny">预约凭证已读取，病历与接诊记录暂未读取成功；刷新即可重试。</p></section>' : '')
          + (detail ? historyHtml(ctx, detail.history) : '')
          + (appointmentDetail ? appointmentHistoryHtml(ctx, appointmentDetail.history || []) : '')
          + (historyFailure ? '<p class="muted tiny">预约状态时间线读取失败，请刷新重试。</p>' : '')
          + (voucher.canCancel ? '<details class="clinical-skip"><summary>需要取消本次预约？</summary><p class="muted tiny">取消后释放号源。签到后不能取消；是否仍在截止前由服务端复核。</p><label class="clinical-check"><input type="checkbox" id="clinical-cancel-confirm">我确认取消这次预约</label><button class="btn btn-danger" id="clinical-cancel">确认取消预约</button></details>' : '')
          + '<div id="clinical-visit-error" aria-live="polite"></div><div class="clinical-actions clinical-visit-refresh"><button class="btn btn-sm" id="clinical-visit-refresh">刷新凭证与进度</button>'
          + '<button class="btn btn-sm" id="clinical-patient-history">查看该就诊人全部档案</button></div>';
        const check = $(body, '#clinical-checkin');
        if (check) check.addEventListener('click', function () { return action('checkIn'); });
        const confirm = $(body, '#clinical-confirm');
        if (confirm) confirm.addEventListener('click', function () { return action('confirm'); });
        const cancel = $(body, '#clinical-cancel');
        if (cancel) cancel.addEventListener('click', function () {
          if (!$(body, '#clinical-cancel-confirm').checked) { ctx.toast('请先勾选确认取消本次预约。', 'warn'); return; }
          return action('cancel');
        });
        const billLink = $(body, '#clinical-bill');
        if (billLink) billLink.addEventListener('click', function () { modal.close(); });
        $(body, '#clinical-visit-refresh').addEventListener('click', load);
        $(body, '#clinical-patient-history').addEventListener('click', function () {
          if (busy) return;
          return global.MedFlowPatientHistory.openPatient(info.patientId, ctx, { returnLabel: '返回本次预约', onReturn: function () { return openPatientVisit(appointmentId, ctx); } });
        });
      } catch (failure) { showFailure(failure, load); }
      finally { busy = false; }
    }
    async function action(kind) {
      if (busy || !alive()) return;
      if (!voucher || !(kind === 'checkIn' ? voucher.canCheckIn : kind === 'confirm' ? voucher.canConfirm : voucher.canCancel)) return;
      busy = true;
      const controls = $$(body, 'button, input').map(function (control) { const item = { control: control, disabled: control.disabled }; control.disabled = true; return item; });
      $(body, '#clinical-visit-error').innerHTML = '<p role="status" class="muted tiny">正在提交，请稍候…</p>';
      try {
        if (kind === 'checkIn') await ctx.api.appointments.checkIn(appointmentId);
        else if (kind === 'confirm') await ctx.api.appointments.confirm(appointmentId);
        else await ctx.api.appointments.cancel(appointmentId);
        if (!alive()) return;
        ctx.toast(kind === 'checkIn' ? '签到成功，已加入医生候诊队列。' : kind === 'confirm' ? '预约已确认，请按时到院签到。' : '预约已取消。', 'ok');
        busy = false;
        await load();
      } catch (failure) {
        if (!alive() || ctx.handleApiError(failure)) return;
        $(body, '#clinical-visit-error').innerHTML = ctx.errorPanel(failure) + '<p class="muted tiny">若提交结果不确定，请先刷新凭证核对当前状态，再继续操作。</p>';
      } finally {
        busy = false;
        controls.forEach(function (item) { if (item.control.isConnected) item.control.disabled = item.disabled; });
      }
    }
    await load();
    return modal;
  }

  async function adminSchedules(root, ctx) {
    root.innerHTML = heading('排班管理', '查询全部服务端排班，安排医生出诊并发布号源。',
      '<button class="btn btn-primary" id="clinical-new-schedule">创建 / 批量排班</button>')
      + '<form id="clinical-filters" class="clinical-filters">' + dateInput(ctx, 'clinical-from', ctx.todayIso(), '开始日期')
      + dateInput(ctx, 'clinical-to', ctx.todayIso(30), '结束日期')
      + '<label class="field"><span>科室</span><select id="clinical-department"><option value="">全部科室</option></select></label>'
      + '<label class="field"><span>医生</span><select id="clinical-doctor"><option value="">全部医生</option></select></label>'
      + '<label class="field"><span>状态</span><select id="clinical-status">' + options(ctx, SCHEDULE_STATUS, true) + '</select></label>'
      + '<button class="btn btn-primary" type="submit">查询排班</button></form><div id="clinical-resource-error"></div><section id="clinical-list"></section>';
    const body = $(root, '#clinical-list');
    let pageNow = 1;
    let version = 0;
    let departmentVersion = 0;
    const load = async function (page) {
      const from = $(root, '#clinical-from').value;
      const to = $(root, '#clinical-to').value;
      if (!validRange(ctx, from, to)) return;
      const ticket = ++version;
      pageNow = page || 1;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.scheduleManagement.page({ from: from, to: to,
          departmentId: $(root, '#clinical-department').value || undefined, doctorId: $(root, '#clinical-doctor').value || undefined,
          status: $(root, '#clinical-status').value, page: pageNow, size: 10 });
        if (!body.isConnected || ticket !== version) return;
        body.innerHTML = result.items.length ? scheduleTable(ctx, result.items, true) + pager(ctx, result, pageNow)
          : ctx.emptyState('没有符合条件的排班', '可以调整筛选条件，或创建新的门诊排班。');
        bindPages(body, load);
        $$(body, '[data-schedule]').forEach(function (button) {
          button.addEventListener('click', function () { return openSchedule(Number(button.getAttribute('data-schedule')), ctx, function () { return load(pageNow); }); });
        });
      } catch (failure) { if (ticket === version) error(ctx, body, failure); }
    };
    $(root, '#clinical-filters').addEventListener('submit', function (event) { event.preventDefault(); return load(1); });
    $(root, '#clinical-new-schedule').addEventListener('click', function () { return openScheduleCreation(ctx, function () { return load(1); }); });
    $(root, '#clinical-department').addEventListener('change', async function () {
      const ticket = ++departmentVersion;
      const doctor = $(root, '#clinical-doctor');
      doctor.innerHTML = '<option value="">全部医生</option>';
      const departmentId = $(root, '#clinical-department').value;
      if (!departmentId) return;
      doctor.disabled = true;
      try {
        const doctors = await ctx.api.doctors.list(Number(departmentId));
        if (!root.isConnected || ticket !== departmentVersion) return;
        doctor.innerHTML = '<option value="">全部医生</option>' + entityOptions(ctx, doctors);
      } catch (failure) { if (ticket === departmentVersion) ctx.handleApiError(failure, '读取医生失败'); }
      finally { if (ticket === departmentVersion) doctor.disabled = false; }
    });
    await Promise.all([load(1), (async function () {
      try {
        const departments = await ctx.api.departments.list();
        if (root.isConnected) $(root, '#clinical-department').innerHTML = '<option value="">全部科室</option>' + entityOptions(ctx, departments);
      } catch (failure) { error(ctx, $(root, '#clinical-resource-error'), failure); }
    })()]);
  }

  function entityOptions(ctx, items) {
    return items.map(function (item) { return '<option value="' + ctx.esc(item.id) + '">' + ctx.esc(item.name + (item.username ? '（账号：' + item.username + '）' : '')) + '</option>'; }).join('');
  }

  async function openScheduleCreation(ctx, onChange) {
    const modal = ctx.openModal({ title: '创建门诊排班', subtitle: '同一医生、诊室与班次，最多安排 7 个未来日期', width: 650,
      body: loading(), footer: '<button class="btn" data-close>关闭</button>' });
    const body = $(modal.root, '.modal-body');
    let ready = false;
    let busy = false;
    let resourceVersion = 0;
    try {
      const resources = await Promise.all([ctx.api.departments.list(), ctx.api.shiftTemplates.list()]);
      if (!body.isConnected) return;
      if (!resources[0].length || !resources[1].length) {
        body.innerHTML = ctx.emptyState('排班资源尚未齐备', '请先创建科室，并确认有可用班次模板。'); return;
      }
      body.innerHTML = '<form id="clinical-create-form" class="form-grid"><div class="form-grid two">'
        + '<label class="field"><span>科室</span><select id="clinical-create-dept">' + entityOptions(ctx, resources[0]) + '</select></label>'
        + '<label class="field"><span>医生</span><select id="clinical-create-doctor"></select></label>'
        + '<label class="field"><span>诊室</span><select id="clinical-create-room"></select></label>'
        + '<label class="field"><span>班次</span><select id="clinical-create-template">' + entityOptions(ctx, resources[1]) + '</select></label></div>'
        + '<label class="field"><span>首个工作日期</span><input type="date" id="clinical-create-date" min="' + ctx.esc(ctx.todayIso(1)) + '" value="' + ctx.esc(ctx.todayIso(1)) + '" required></label>'
        + '<label class="field"><span>其他工作日期 <small class="hint">可选，最多再填写 6 个</small></span><textarea id="clinical-create-more" rows="2" placeholder="2026-10-22, 2026-10-23"></textarea>'
        + '<small class="muted">用逗号或换行分隔，格式 YYYY-MM-DD；所有日期必须晚于今天。</small></label>'
        + '<div class="clinical-info-strip"><p>整批创建为草稿，确认后逐条发布。任意一天冲突，整批不创建。</p></div>'
        + '<div id="clinical-create-error" aria-live="polite"></div><button class="btn btn-primary" id="clinical-create-submit" type="submit" disabled>创建排班草稿</button></form>';
      async function loadResources() {
        const ticket = ++resourceVersion;
        ready = false;
        $(body, '#clinical-create-submit').disabled = true;
        $(body, '#clinical-create-doctor').innerHTML = '<option value="">加载中…</option>';
        $(body, '#clinical-create-room').innerHTML = '<option value="">加载中…</option>';
        try {
          const departmentId = Number($(body, '#clinical-create-dept').value);
          const pair = await Promise.all([ctx.api.doctors.list(departmentId), ctx.api.clinicRooms.list(departmentId)]);
          if (!body.isConnected || ticket !== resourceVersion) return;
          $(body, '#clinical-create-doctor').innerHTML = pair[0].length ? entityOptions(ctx, pair[0]) : '<option value="">该科室暂无医生</option>';
          $(body, '#clinical-create-room').innerHTML = pair[1].length ? entityOptions(ctx, pair[1]) : '<option value="">该科室暂无诊室</option>';
          ready = Boolean(pair[0].length && pair[1].length);
          $(body, '#clinical-create-submit').disabled = !ready || busy;
        } catch (failure) { if (ticket === resourceVersion) error(ctx, $(body, '#clinical-create-error'), failure); }
      }
      $(body, '#clinical-create-dept').addEventListener('change', loadResources);
      $(body, '#clinical-create-form').addEventListener('submit', async function (event) {
        event.preventDefault();
        if (!ready || busy) return;
        const dates = [$(body, '#clinical-create-date').value].concat($(body, '#clinical-create-more').value.split(/[\s,，;；]+/).filter(Boolean));
        if (!dates.length || dates.length > 7 || new Set(dates).size !== dates.length || dates.some(function (date) {
          return !/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(Date.parse(date)) || new Date(Date.parse(date)).toISOString().slice(0, 10) !== date || date <= ctx.todayIso();
        })) { ctx.toast('请填写 1 至 7 个不重复的未来日期，格式为 YYYY-MM-DD。', 'warn'); return; }
        const payload = { doctorId: Number($(body, '#clinical-create-doctor').value), clinicRoomId: Number($(body, '#clinical-create-room').value),
          shiftTemplateId: Number($(body, '#clinical-create-template').value), workDates: dates };
        busy = true;
        $$(body, 'input, select, textarea, button').forEach(function (control) { control.disabled = true; });
        $(body, '#clinical-create-error').innerHTML = '';
        try {
          const result = await ctx.api.scheduleManagement.batch(payload);
          ctx.toast('已创建 ' + result.count + ' 条排班草稿，请在目录查看并发布。', 'ok');
          if (modal.root.isConnected) modal.close();
          if (onChange) await onChange();
        } catch (failure) {
          if (body.isConnected && !ctx.handleApiError(failure)) $(body, '#clinical-create-error').innerHTML = ctx.errorPanel(failure)
            + '<p class="muted tiny">若请求超时或网络中断，请先关闭窗口并查询这些日期的排班，确认结果后再决定是否重新创建。</p>';
        } finally {
          busy = false;
          $$(body, 'input, select, textarea, button').forEach(function (control) { control.disabled = false; });
          const submit = $(body, '#clinical-create-submit');
          if (submit) submit.disabled = !ready;
        }
      });
      await loadResources();
    } catch (failure) { error(ctx, body, failure); }
  }

  async function openSchedule(id, ctx, onChange) {
    const modal = ctx.openModal({ title: '排班详情', subtitle: '排班编号 #' + id, width: 760,
      body: loading(), footer: '<button class="btn" data-close>关闭</button>' });
    const body = $(modal.root, '.modal-body');
    let busy = false;
    async function load() {
      try {
        const detail = await ctx.api.scheduleManagement.detail(id);
        if (!body.isConnected) return;
        const item = detail.schedule;
        body.innerHTML = '<dl class="kv"><dt>医生 / 科室</dt><dd>' + ctx.esc(item.doctorName) + ' · ' + ctx.esc(item.departmentName)
          + '</dd><dt>诊室 / 班次</dt><dd>' + ctx.esc(item.clinicRoomName) + ' · ' + ctx.esc(item.shiftName)
          + '</dd><dt>工作日期</dt><dd>' + ctx.esc(ctx.fmtDate(item.workDate)) + ' ' + ctx.esc(range(item))
          + '</dd><dt>排班状态</dt><dd>' + badge(ctx, item.status, SCHEDULE_STATUS)
          + '</dd><dt>有效预约</dt><dd>' + ctx.esc(item.activeAppointmentCount) + ' 人</dd>'
          + (item.cancelReason ? '<dt>取消原因</dt><dd>' + ctx.esc(item.cancelReason) + '</dd>' : '') + '</dl>'
          + '<h3 class="clinical-section-title">号源时段</h3>'
          + ((detail.slots || []).length ? '<div class="table-wrap"><table><thead><tr><th>时间</th><th>余量 / 总量</th><th>状态</th><th>预约截止</th></tr></thead><tbody>'
            + detail.slots.map(function (slot) { return '<tr><td class="mono">' + ctx.esc(range(slot)) + '</td><td>'
              + ctx.esc(slot.remainingCapacity) + ' / ' + ctx.esc(slot.totalCapacity) + '</td><td>' + ctx.esc(slot.status)
              + '</td><td>' + ctx.esc(ctx.fmtDateTime(slot.bookingDeadline)) + '</td></tr>'; }).join('') + '</tbody></table></div>'
            : '<p class="muted">草稿尚未生成号源，发布后会按班次拆分时段。</p>')
          + (item.status === 'DRAFT' ? '<div class="clinical-actions"><button class="btn btn-primary" id="clinical-publish">发布并开放预约</button></div>' : '')
          + (item.status === 'DRAFT' || item.status === 'PUBLISHED' ? '<details class="clinical-skip"><summary>取消本次出诊</summary>'
            + '<p class="muted tiny">仅允许开诊前且没有待确认或已确认预约时取消；已有预约需保留，本版本不自动取消患者预约。</p>'
            + '<label class="field"><span>取消原因</span><textarea id="clinical-cancel-reason" rows="2" maxlength="200" placeholder="请填写取消出诊原因"></textarea></label>'
            + '<button class="btn btn-danger btn-sm" id="clinical-cancel-schedule">确认取消出诊</button></details>' : '')
          + '<div id="clinical-schedule-error" aria-live="polite"></div>';
        const publish = $(body, '#clinical-publish');
        if (publish) publish.addEventListener('click', function () { return action('publish'); });
        const cancel = $(body, '#clinical-cancel-schedule');
        if (cancel) cancel.addEventListener('click', function () { return action('cancel'); });
      } catch (failure) { error(ctx, body, failure); }
    }
    async function action(kind) {
      if (busy) return;
      const reason = kind === 'cancel' ? $(body, '#clinical-cancel-reason').value.trim() : '';
      if (kind === 'cancel' && !reason) { ctx.toast('请填写取消出诊原因。', 'warn'); return; }
      if (reason.length > 200) { ctx.toast('取消原因最多填写 200 字。', 'warn'); return; }
      busy = true;
      $$(body, 'button, textarea').forEach(function (control) { control.disabled = true; });
      try {
        if (kind === 'publish') await ctx.api.schedules.publish(id);
        else await ctx.api.scheduleManagement.cancel(id, reason);
        if (!body.isConnected) return;
        ctx.toast(kind === 'publish' ? '排班已发布，号源已开放。' : '本次出诊已取消。', 'ok');
        await load();
        if (onChange) await onChange();
      } catch (failure) {
        if (body.isConnected && !ctx.handleApiError(failure)) $(body, '#clinical-schedule-error').innerHTML = ctx.errorPanel(failure);
      } finally { busy = false; $$(body, 'button, textarea').forEach(function (control) { control.disabled = false; }); }
    }
    await load();
  }

  global.MedFlowClinical = { views: { doctorOverview: doctorOverview, doctorSchedules: doctorSchedules,
    doctorAppointments: doctorAppointments, doctorQueue: doctorQueue, adminSchedules: adminSchedules },
    openPatientVisit: openPatientVisit, appointmentSummary: appointmentSummary, voucherOverview: voucherOverview };
})(window);
