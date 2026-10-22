(function (global) {
  'use strict';

  const APPOINTMENT = { PENDING_CONFIRMATION: ['待确认', 'warn'], BOOKED: ['已预约', 'ok'], CANCELLED: ['已取消', 'muted'], EXPIRED: ['已过期', 'muted'] };
  const ENCOUNTER = { WAITING: ['待就诊', 'warn'], IN_PROGRESS: ['就诊中', 'ok'], COMPLETED: ['已完成', 'ok'], SKIPPED: ['已过号', 'muted'] };
  const EVENTS = { CREATED: '创建预约', CONFIRMED: '确认预约', CANCELLED: '取消预约', EXPIRED: '超时关闭', CHECKED_IN: '到院签到', STARTED: '开始接诊', COMPLETED: '完成接诊', SKIPPED: '标记过号' };
  const FIELDS = [['chiefComplaint', '主诉'], ['presentIllness', '现病史'], ['diagnosis', '诊断'], ['advice', '医嘱']];
  function $(root, selector) { return root.querySelector(selector); }
  function $$(root, selector) { return Array.prototype.slice.call(root.querySelectorAll(selector)); }
  function time(value) { return value ? String(value).slice(0, 5) : '—'; }
  function money(value) { return value === null || value === undefined || !Number.isFinite(Number(value)) ? '—' : '¥ ' + Number(value).toFixed(2); }
  function badge(ctx, status, labels) {
    const item = labels[status] || [status || '未记录', 'muted'];
    return '<span class="badge ' + item[1] + '">' + ctx.esc(item[0]) + '</span>';
  }
  function loading() { return '<div class="patient-history-loading" role="status"><span class="spinner"></span>正在读取院内就诊档案…</div>'; }
  function historyContext(ctx, visit) {
    return (visit.sourceEncounterId ? '<p class="patient-history-source">本次预约关联 ' + ctx.esc(ctx.fmtDate(visit.sourceWorkDate))
      + ' 的前次已完成接诊；病历与费用独立。<small>来源预约 #' + ctx.esc(visit.sourceAppointmentId || '—') + '</small></p>' : '')
      + (visit.hasBookingSnapshot === false ? '<p class="patient-history-snapshot">旧记录未保存预约时的资料快照，医生、职称、科室和诊室回退展示当前资料，不代表当时名称。</p>' : '');
  }
  function followUpButton(ctx, kind, visit) {
    return kind === 'patient' && visit.encounterStatus === 'COMPLETED' && visit.encounterId
      ? '<button class="btn btn-sm btn-primary" data-history-followup="' + ctx.esc(visit.encounterId) + '">基于本次再次预约</button>' : '';
  }
  function patientHeader(ctx, patient) {
    const gender = { MALE: '男', FEMALE: '女', UNKNOWN: '未知' };
    return '<section class="patient-history-person"><span class="avatar">' + ctx.esc(String(patient.name || '?').slice(0, 1))
      + '</span><div><h3>' + ctx.esc(patient.name) + '</h3><p>' + ctx.esc(gender[patient.gender] || patient.gender) + ' · '
      + ctx.esc(patient.relationship === 'SELF' ? '本人' : patient.relationship) + ' · 出生日期 ' + ctx.esc(patient.birthDate)
      + '</p><small>联系电话 ' + ctx.esc(patient.maskedPhone || '未记录') + '</small></div></section>';
  }
  function statusLine(ctx, visit) {
    return '<div class="patient-history-status">' + badge(ctx, visit.appointmentStatus, APPOINTMENT)
      + (visit.encounterStatus ? badge(ctx, visit.encounterStatus, ENCOUNTER) : '<span class="muted tiny">无接诊记录</span>') + '</div>';
  }
  function actorLabel(item) {
    if (item.actorUserId === null || item.actorUserId === undefined) return '系统处理';
    if (['CREATED', 'CONFIRMED', 'CANCELLED', 'CHECKED_IN'].indexOf(item.eventType) !== -1) return '患者操作';
    if (['STARTED', 'COMPLETED', 'SKIPPED'].indexOf(item.eventType) !== -1) return '接诊医生操作';
    return '登记操作';
  }
  function timeline(ctx, items, labels, title) {
    return '<section class="patient-history-events"><h4>' + title + '</h4>' + (items.length ? '<ol>' + items.map(function (item) {
      const from = labels[item.fromStatus] || ['开始'];
      const to = labels[item.toStatus] || [item.toStatus || '未记录'];
      return '<li><div><b>' + ctx.esc(EVENTS[item.eventType] || item.eventType || '状态变化') + '</b><span>' + ctx.esc(from[0]) + ' → ' + ctx.esc(to[0])
        + '</span></div><small>' + ctx.esc(ctx.fmtDateTime(item.createdAt)) + ' · ' + actorLabel(item) + '</small></li>';
    }).join('') + '</ol>' : '<p class="muted tiny">没有记录到状态变化。</p>') + '</section>';
  }
  function prescription(ctx, detail) {
    const rx = detail.prescription;
    const bill = detail.bill;
    return '<section class="patient-history-section"><h4>已提交处方</h4><p class="patient-history-prescriber">开具医生：' + ctx.esc(rx.doctorName) + ' · '
      + ctx.esc(rx.departmentName) + ' · 提交于 ' + ctx.esc(ctx.fmtDateTime(rx.issuedAt)) + '</p><div class="table-wrap"><table><thead><tr>'
      + '<th>药品 / 规格</th><th>数量</th><th>单价</th><th>金额</th><th>用法说明</th></tr></thead><tbody>'
      + (detail.items || []).map(function (item) {
        return '<tr><td><b>' + ctx.esc(item.drugName) + '</b><small class="cell-sub">' + ctx.esc(item.specification) + ' · ' + ctx.esc(item.drugCode)
          + '</small></td><td>' + ctx.esc(item.quantity) + ' ' + ctx.esc(item.unit) + '</td><td class="patient-history-money">'
          + money(item.unitPrice) + '</td><td class="patient-history-money">' + money(item.lineAmount) + '</td><td class="patient-history-text">'
          + ctx.esc(item.instructions || '未填写') + '</td></tr>';
      }).join('') + '</tbody></table></div><div class="patient-history-total"><span>处方合计</span><b>' + money(rx.totalAmount) + '</b></div>'
      + (bill ? '<dl class="kv"><dt>账单编号</dt><dd>#' + ctx.esc(bill.id) + '</dd><dt>账单金额</dt><dd>' + money(bill.totalAmount)
        + '</dd><dt>缴费状态</dt><dd>' + ctx.esc(bill.status === 'PAID' ? '已模拟缴费' : bill.status === 'UNPAID' ? '待模拟缴费' : bill.status)
        + '</dd><dt>缴费登记时间</dt><dd>' + ctx.esc(ctx.fmtDateTime(bill.paidAt)) + '</dd></dl>' : '<p class="muted tiny">没有可展示的账单记录。</p>')
      + '<p class="muted tiny">此处只读展示已保存的药品与金额快照；模拟缴费不会发生真实扣款。</p></section>';
  }

  async function openHistory(kind, identity, ctx, options) {
    const config = options || {};
    const api = kind === 'doctor' ? ctx.api.doctorHistory : ctx.api.patientHistory;
    const modal = ctx.openModal({ title: '院内就诊档案', subtitle: kind === 'doctor' ? '基于本次预约授权，查看同一就诊人的院内历史；仅供只读查阅。' : '同一就诊人的历次预约、接诊、已完成病历和已提交处方。', width: 940,
      body: loading(), footer: '<button class="btn" id="patient-history-return">' + ctx.esc(config.returnLabel || (kind === 'doctor' ? '返回医生列表' : '返回就诊人列表')) + '</button>' });
    const body = $(modal.root, '.modal-body');
    let patient = null;
    let currentPage = 1;
    let ticket = 0;
    let followUpBusy = false;
    const openedToken = global.MedFlow && global.MedFlow.session ? global.MedFlow.session.token() : null;
    function alive() { return body.isConnected && (!global.MedFlow || !global.MedFlow.session || global.MedFlow.session.token() === openedToken); }
    function bindFollowUp() {
      $$(body, '[data-history-followup]').forEach(function (button) {
        button.addEventListener('click', async function () {
          if (followUpBusy || !alive()) return;
          if (!ctx.beginFollowUp) { ctx.toast('暂时无法进入再次预约，请关闭后重新打开档案。', 'warn'); return; }
          const version = ticket;
          followUpBusy = true;
          const controls = $$(body, 'button').map(function (control) { const old = { control: control, disabled: control.disabled }; control.disabled = true; return old; });
          try {
            const source = await ctx.api.appointments.followUpSource(Number(button.getAttribute('data-history-followup')));
            if (!alive() || ticket !== version) return;
            if (!patient || Number(source.patientId) !== Number(patient.id)) { ctx.toast('来源就诊人与当前档案不一致，请重新读取档案。', 'error'); return; }
            ++ticket;
            return ctx.beginFollowUp(source);
          } catch (failure) {
            if (alive() && ticket === version && !ctx.handleApiError(failure)) ctx.toast(failure.message || '读取再次预约来源失败，请重试。', 'error');
          } finally {
            followUpBusy = false;
            controls.forEach(function (item) { if (item.control.isConnected) item.control.disabled = item.disabled; });
          }
        });
      });
    }
    $(modal.root, '#patient-history-return').addEventListener('click', async function () {
      ++ticket;
      modal.close();
      if (config.onReturn) await config.onReturn();
    });
    function attachBack() {
      const back = $(body, '#patient-history-back');
      if (back) back.addEventListener('click', function () { return loadPage(currentPage); });
    }
    function showError(failure, retry, back) {
      if (!alive() || ctx.handleApiError(failure)) return;
      body.innerHTML = (back ? '<button class="btn btn-sm" id="patient-history-back">← 返回档案列表</button>' : '') + ctx.errorPanel(failure)
        + '<button class="btn btn-sm" id="patient-history-retry">重新读取</button>';
      attachBack();
      $(body, '#patient-history-retry').addEventListener('click', retry);
    }
    async function loadPage(page) {
      const version = ++ticket;
      currentPage = page || 1;
      body.innerHTML = loading();
      try {
        const result = await api.page(identity, { page: currentPage, size: 10 });
        if (!alive() || ticket !== version) return;
        patient = result.patient;
        body.innerHTML = patientHeader(ctx, patient) + '<div class="patient-history-intro"><p>按历次就诊查看院内记录，包含不同医生的就诊。检查、化验数据未接入此档案。</p>'
          + '<button class="btn btn-sm" id="patient-history-refresh">刷新档案</button></div>'
          + (result.items.length ? '<div class="patient-history-list">' + result.items.map(function (visit) {
            const completed = visit.encounterStatus === 'COMPLETED';
            return '<article class="patient-history-visit"><div class="patient-history-date"><b>' + ctx.esc(ctx.fmtDate(visit.workDate)) + '</b><span>'
              + ctx.esc(time(visit.startTime) + '–' + time(visit.endTime)) + '</span></div><div class="patient-history-visit-copy"><h4>' + ctx.esc(visit.departmentName)
              + ' · ' + ctx.esc(visit.doctorName) + (visit.doctorTitle ? ' · ' + ctx.esc(visit.doctorTitle) : '') + (kind === 'doctor' && String(visit.appointmentId) === String(identity) ? '<small class="badge muted">本次预约</small>' : '')
              + '</h4><p>' + ctx.esc(visit.clinicRoomName) + ' · 预约 #' + ctx.esc(visit.appointmentId) + '</p>' + statusLine(ctx, visit)
              + (completed ? '<p class="patient-history-brief"><span>主诉</span>' + ctx.esc(visit.chiefComplaint || '未填写') + '<br><span>诊断</span>' + ctx.esc(visit.diagnosis || '未填写') + '</p>'
                : '<p class="muted tiny">尚无可展示的已完成病历。</p>')
              + (visit.prescriptionId ? '<p class="muted tiny">已提交处方 · ' + money(visit.totalAmount) + ' · '
                + ctx.esc(visit.billStatus === 'PAID' ? '已模拟缴费' : visit.billStatus === 'UNPAID' ? '待模拟缴费' : '未记录缴费状态') + '</p>' : '')
              + historyContext(ctx, visit) + '</div><div class="patient-history-visit-actions"><button class="btn btn-sm" data-history-visit="' + ctx.esc(visit.appointmentId) + '">查看本次记录</button>'
              + followUpButton(ctx, kind, visit) + '</div></article>';
          }).join('') + '</div><div class="patient-history-pager"><span class="muted tiny">共 ' + ctx.esc(result.total) + ' 次预约 · 第 ' + currentPage + ' / '
            + Math.max(1, Math.ceil(Number(result.total || 0) / (Number(result.size) || 10))) + ' 页</span><div><button class="btn btn-sm" id="patient-history-prev"'
            + (currentPage <= 1 ? ' disabled' : '') + '>上一页</button><button class="btn btn-sm" id="patient-history-next"'
            + (currentPage * (Number(result.size) || 10) >= Number(result.total) ? ' disabled' : '') + '>下一页</button></div></div>'
          : ctx.emptyState('该就诊人暂无院内就诊记录', '建立档案后还需要预约；本页不会生成虚构检查、诊断或处方。'));
        $(body, '#patient-history-refresh').addEventListener('click', function () { return loadPage(currentPage); });
        $$(body, '[data-history-visit]').forEach(function (button) { button.addEventListener('click', function () { return loadDetail(Number(button.getAttribute('data-history-visit'))); }); });
        bindFollowUp();
        const previous = $(body, '#patient-history-prev');
        const next = $(body, '#patient-history-next');
        if (previous) previous.addEventListener('click', function () { if (!previous.disabled) return loadPage(currentPage - 1); });
        if (next) next.addEventListener('click', function () { if (!next.disabled) return loadPage(currentPage + 1); });
      } catch (failure) { if (ticket === version) showError(failure, function () { return loadPage(currentPage); }, false); }
    }
    async function loadDetail(appointmentId) {
      const version = ++ticket;
      body.innerHTML = '<button class="btn btn-sm" id="patient-history-back">← 返回档案列表</button>' + loading();
      attachBack();
      try {
        const detail = await api.detail(identity, appointmentId);
        if (!alive() || ticket !== version) return;
        const visit = detail.visit;
        const completed = visit.encounterStatus === 'COMPLETED';
        const record = completed ? detail.medicalRecord : null;
        const issued = detail.prescription && detail.prescription.prescription && detail.prescription.prescription.status === 'ISSUED';
        body.innerHTML = '<button class="btn btn-sm" id="patient-history-back">← 返回档案列表</button>' + patientHeader(ctx, patient)
          + '<section class="patient-history-detail-head"><h3>' + ctx.esc(ctx.fmtDate(visit.workDate)) + ' ' + ctx.esc(time(visit.startTime) + '–' + time(visit.endTime))
          + '</h3><p>' + ctx.esc(visit.departmentName) + ' · 就诊医生 ' + ctx.esc(visit.doctorName) + (visit.doctorTitle ? ' · ' + ctx.esc(visit.doctorTitle) : '') + ' · ' + ctx.esc(visit.clinicRoomName)
          + '</p><small class="muted">预约 #' + ctx.esc(visit.appointmentId) + (visit.encounterId ? ' · 接诊 #' + ctx.esc(visit.encounterId) : '') + '</small>' + statusLine(ctx, visit)
          + historyContext(ctx, visit) + followUpButton(ctx, kind, visit) + '</section>'
          + '<section class="patient-history-section"><h4>本次病历</h4>' + (record ? '<div class="patient-history-record">' + FIELDS.map(function (field) {
            return '<section><h5>' + field[1] + '</h5><p>' + ctx.esc(record[field[0]] || '未填写') + '</p></section>';
          }).join('') + '</div>' : '<p class="muted">' + (completed ? '本次已完成接诊，但没有可展示的病历记录。' : visit.encounterId
            ? '本次尚未完成接诊，病历正文暂不展示。' : '本次没有接诊或病历记录。') + '</p>') + '</section>'
          + (issued ? prescription(ctx, detail.prescription) : '<section class="patient-history-section"><h4>本次处方</h4><p class="muted">没有已提交的处方记录；未开药或未提交的草稿不会在此展示。</p></section>')
          + '<div class="patient-history-event-grid">' + timeline(ctx, detail.appointmentHistory || [], APPOINTMENT, '预约状态时间线')
          + timeline(ctx, detail.encounterHistory || [], ENCOUNTER, '接诊状态时间线') + '</div>'
          + '<p class="patient-history-scope">仅展示本系统实际保存的院内记录。检查、化验、影像等数据未接入；不根据预约状态推断检查结果。</p>';
        attachBack();
        bindFollowUp();
      } catch (failure) { if (ticket === version) showError(failure, function () { return loadDetail(appointmentId); }, true); }
    }
    await loadPage(1);
    if (patient && config.initialAppointmentId && alive()) await loadDetail(Number(config.initialAppointmentId));
    return modal;
  }

  global.MedFlowPatientHistory = {
    openPatient: function (patientId, ctx, options) { return openHistory('patient', patientId, ctx, options); },
    openDoctor: function (anchorAppointmentId, ctx, options) { return openHistory('doctor', anchorAppointmentId, ctx, options); }
  };
})(window);
