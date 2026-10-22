(function (global) {
  'use strict';

  const renders = new WeakMap();
  function sessionToken() { return global.MedFlow && global.MedFlow.session ? global.MedFlow.session.token() : null; }

  function fulfilled(result) {
    return result && result.status === 'fulfilled';
  }

  function data(result) {
    return fulfilled(result) ? result.value : null;
  }

  function needsLogin(results, ctx) {
    const failure = results.find(function (result) {
      return !fulfilled(result) && result.reason
        && (result.reason.status === 401 || result.reason.code === 'UNAUTHORIZED');
    });
    if (!failure) return false;
    ctx.handleApiError(failure.reason);
    return true;
  }

  function stat(ctx, name, value, note, symbol, tone) {
    return '<article class="stat-card ' + (tone || '') + '">'
      + '<div class="stat-label"><span>' + ctx.esc(name) + '</span>'
      + '<span class="stat-icon">' + ctx.icon(symbol) + '</span></div>'
      + '<div class="stat-value">' + ctx.esc(value === null ? '—' : value) + '</div>'
      + '<div class="stat-note">' + ctx.esc(note) + '</div></article>';
  }

  function quickLink(ctx, route, name, description, symbol) {
    return '<a class="quick-link" href="#/' + route + '">'
      + '<span class="quick-link-icon">' + ctx.icon(symbol) + '</span>'
      + '<span><b>' + ctx.esc(name) + '</b><small>' + ctx.esc(description) + '</small></span>'
      + '<span class="quick-link-arrow" aria-hidden="true">↗</span></a>';
  }

  function hero(ctx, admin) {
    const name = ctx.profile.sub || (admin ? '管理员' : '朋友');
    return '<section class="dash-hero"><div class="dash-hero-copy">'
      + '<div class="eyebrow">' + (admin ? 'CLINIC WORKSPACE' : 'YOUR HEALTH, CONNECTED') + '</div>'
      + '<h1>' + ctx.esc(name) + '，' + (admin ? '让门诊有序运转。' : '把健康安排进日常。') + '</h1>'
      + '<p>' + (admin ? '从资源准备到排班发布，在一个工作台里完成。' : '为自己和家人，轻松找到合适的就诊时间。') + '</p>'
      + '<a class="btn btn-primary" href="#/' + (admin ? 'schedules' : 'slots') + '">'
      + ctx.icon(admin ? 'clock' : 'calendar') + (admin ? '安排门诊排班' : '查找可约号源')
      + '<span aria-hidden="true">↗</span></a></div>'
      + '<div class="dash-hero-art" aria-hidden="true">'
      + '<div class="hero-art-ring"></div><div class="hero-art-ring second"></div>'
      + '<div class="hero-art-plus">+</div><span class="hero-art-dot"></span>'
      + '<div class="hero-art-card">' + ctx.icon(admin ? 'stethoscope' : 'calendar')
      + '<span>' + (admin ? '有序协同，安心接诊' : '让每一次就诊，更从容') + '</span></div></div></section>';
  }

  function section(title, subtitle, content, extra) {
    return '<section class="dash-section"><div class="section-title"><div><h2>' + title + '</h2>'
      + '<p>' + subtitle + '</p></div>' + (extra || '') + '</div>' + content + '</section>';
  }

  function loading(ctx, admin) {
    let cards = '';
    const labels = admin ? ['启用科室', '在岗医生', '可用诊室', '可用班次']
      : ['就诊人档案', '累计预约', '明日可约时段', '近期待确认'];
    labels.forEach(function (label) {
      cards += stat(ctx, label, '…', '正在更新', 'clock');
    });
    return hero(ctx, admin) + '<div class="stats-grid" aria-busy="true">' + cards + '</div>'
      + '<div class="dash-section" aria-live="polite"><div class="loading">'
      + '<span class="spinner"></span>正在整理您的工作台…</div></div>';
  }

  async function patientDashboard(root, ctx, current) {
    const tomorrow = ctx.todayIso(1);
    const results = await Promise.allSettled([
      ctx.api.patients.list(),
      ctx.api.appointments.page(1, 5),
      ctx.api.slots.list({ workDate: tomorrow, page: 1, size: 1 })
    ]);
    if (!current() || needsLogin(results, ctx)) return;

    const patients = data(results[0]);
    const page = data(results[1]);
    const slots = data(results[2]);
    const recent = page ? page.items : [];
    let summaries = {}, summaryError = null, summaryLoading = false;
    let summaryTicket = 0;
    function paint() {
      if (!current()) return;
      const pending = recent.filter(function (item) { return item.status === 'PENDING_CONFIRMATION'; });
      const patientMap = new Map((patients || []).map(function (patient) { return [patient.id, patient.name]; }));
      let cards = stat(ctx, '就诊人档案', patients ? patients.length : null,
        patients ? '自己和家人的健康档案' : '档案暂未加载', 'user');
      cards += stat(ctx, '累计预约', page ? page.total : null,
        page ? '包含全部预约状态' : '预约暂未加载', 'list');
      cards += stat(ctx, '明日可约时段', slots ? slots.total : null,
        ctx.fmtDate(tomorrow) + ' · 实时可约时段数', 'calendar');
      cards += stat(ctx, '近期待确认', page ? pending.length : null,
        '仅统计最近 5 条预约', 'clock', pending.length ? 'stat-attention' : '');

      let appointments;
      if (!fulfilled(results[1])) {
        appointments = ctx.errorPanel(results[1].reason);
      } else if (!recent.length) {
        appointments = ctx.emptyState('第一次预约，从这里开始', '先添加就诊人，再选择医生与时段。',
          '<a class="btn btn-primary btn-sm" href="#/slots">查找号源</a>');
      } else {
        appointments = pending.length
          ? '<div class="note warn dash-reminder">' + ctx.icon('clock') + '<span>最近 5 条预约中有 <b>'
            + pending.length + '</b> 条待确认，请及时查看确认截止时间。</span><a href="#/appointments">去查看 →</a></div>'
          : '';
        appointments += summaryLoading ? '<p class="tiny muted" role="status">正在加载就诊日期与医生安排…</p>' : summaryError
          ? '<p class="tiny muted">预约记录已加载，就诊安排待加载。</p>' + ctx.errorPanel(summaryError) + '<button class="btn btn-sm" id="dashboard-summary-retry">重试就诊安排</button>' : '';
        appointments += '<div class="table-wrap"><table><thead><tr><th>预约 / 就诊人</th><th>就诊日期与时段</th><th>医生 / 科室 / 诊室</th><th>状态</th></tr></thead><tbody>';
        appointments += recent.map(function (item) {
          const summary = summaries[item.appointmentId];
          return '<tr><td><b class="mono">#' + ctx.esc(item.appointmentId) + '</b><small class="cell-sub">'
            + ctx.esc(summary && summary.patientName || patientMap.get(item.patientId) || ('就诊人 #' + item.patientId)) + '</small></td>'
            + '<td>' + (summary ? ctx.esc(ctx.fmtDate(summary.workDate)) + '<small class="cell-sub mono">' + ctx.esc(String(summary.startTime || '').slice(0, 5)) + '–' + ctx.esc(String(summary.endTime || '').slice(0, 5)) + '</small>' : '<span class="muted">就诊安排待加载</span>') + '</td>'
            + '<td>' + (summary ? ctx.esc(summary.doctorName) + '<small class="cell-sub">' + ctx.esc(summary.departmentName) + ' · ' + ctx.esc(summary.clinicRoomName) + '</small>' : '<span class="muted">医生信息待加载</span>') + '</td>'
            + '<td>' + ctx.statusBadge(item.status) + (item.status === 'PENDING_CONFIRMATION' ? '<small class="cell-sub">请于 ' + ctx.esc(ctx.fmtDateTime(item.confirmDeadline)) + ' 前确认</small>' : '') + '</td></tr>';
        }).join('');
        appointments += '</tbody></table></div>';
      }

      let family;
      if (!fulfilled(results[0])) {
        family = ctx.errorPanel(results[0].reason);
      } else if (!patients.length) {
        family = '<div class="dash-soft-note">添加一份就诊人档案，预约时即可直接选择。</div>';
      } else {
        family = '<div class="dash-patient-list">' + patients.slice(0, 3).map(function (patient) {
          return '<div class="dash-patient"><span class="avatar">' + ctx.esc(patient.name.slice(0, 1)) + '</span>'
            + '<span><b>' + ctx.esc(patient.name) + '</b><small>' + ctx.esc(patient.relationship === 'SELF' ? '本人' : patient.relationship)
            + ' · ' + ctx.esc(patient.maskedPhone) + '</small></span></div>';
        }).join('') + '</div>';
        if (patients.length > 3) family += '<p class="muted tiny">共 ' + patients.length + ' 位就诊人，前往档案查看全部。</p>';
      }

      const slotNote = fulfilled(results[2])
        ? '<div class="dash-soft-note">' + ctx.icon('info') + '<span>'
          + (slots.total > 0 ? '明日还有 ' + slots.total + ' 个可约时段，选择适合自己的时间。' : '明日暂未查到可约时段，可以换个日期再看看。')
          + '</span></div>'
        : ctx.errorPanel(results[2].reason);
      root.innerHTML = hero(ctx, false) + '<div class="stats-grid">' + cards + '</div>'
        + '<div class="dash-columns"><div>'
        + section('最近预约', '最近 5 条记录，及时掌握预约进展', appointments,
          '<a class="text-link" href="#/appointments">全部预约 <span aria-hidden="true">↗</span></a>')
        + section('为就诊提前一步', '简单三步，让预约更从容',
          '<ol class="dash-steps"><li><span>01</span><div><b>建立就诊档案</b><p>为自己或家人添加就诊信息。</p></div></li>'
          + '<li><span>02</span><div><b>选择医生与时段</b><p>先选科室、了解医生，再选择出诊日期。</p></div></li>'
          + '<li><span>03</span><div><b>及时确认预约</b><p>预约提交后，请在截止时间前完成确认。</p></div></li></ol>')
        + '</div><aside>'
        + section('快捷办理', '从这里，开始下一步',
          quickLink(ctx, 'slots', '预约挂号', '查看日期、医生与余量', 'calendar')
          + quickLink(ctx, 'appointments', '查看预约', '确认、取消与进度记录', 'list') + slotNote)
        + section('我的就诊人', '和家人的健康，一起照顾', family,
          '<a class="text-link" href="#/patients">管理 <span aria-hidden="true">↗</span></a>')
        + '</aside></div>';
      const retry = root.querySelector && root.querySelector('#dashboard-summary-retry');
      if (retry) retry.addEventListener('click', loadSummaries);
    }
    async function loadSummaries() {
      if (!current()) return;
      const ticket = ++summaryTicket;
      const ids = Array.from(new Set(recent.map(function (item) { return item.appointmentId; })));
      summaryLoading = true; summaryError = null; paint();
      try {
        const result = await ctx.api.appointments.visitSummaries(ids);
        if (!current() || ticket !== summaryTicket) return;
        if (!Array.isArray(result)) throw new Error('就诊安排返回格式不正确，请重试。');
        const next = {};
        result.forEach(function (summary) {
          if (ids.some(function (id) { return Number(id) === Number(summary.appointmentId); })) next[summary.appointmentId] = summary;
        });
        summaries = next;
        if (ids.some(function (id) { return !summaries[id]; })) summaryError = new Error('部分预约的就诊安排暂不可用，请重试。');
      } catch (error) {
        if (!current() || ticket !== summaryTicket) return;
        if (needsLogin([{ status: 'rejected', reason: error }], ctx)) { ++summaryTicket; return; }
        summaryError = error;
      } finally {
        if (current() && ticket === summaryTicket) { summaryLoading = false; paint(); }
      }
    }
    if (recent.length) await loadSummaries(); else paint();
  }

  async function adminDashboard(root, ctx) {
    const results = await Promise.allSettled([
      ctx.api.departments.list(), ctx.api.shiftTemplates.list()
    ]);
    if (!root.isConnected || needsLogin(results, ctx)) return;
    const departments = data(results[0]);
    const templates = data(results[1]);
    const resources = departments ? await Promise.allSettled(departments.map(async function (department) {
      const pair = await Promise.allSettled([
        ctx.api.doctors.list(department.id), ctx.api.clinicRooms.list(department.id)
      ]);
      return { department: department, doctors: pair[0], rooms: pair[1] };
    })) : [];
    if (!root.isConnected) return;
    const groups = resources.filter(fulfilled).map(function (result) { return result.value; });
    const nested = groups.reduce(function (all, group) { return all.concat([group.doctors, group.rooms]); }, []);
    if (needsLogin(nested, ctx)) return;
    const doctorsReady = departments !== null && groups.length === departments.length
      && groups.every(function (group) { return fulfilled(group.doctors); });
    const roomsReady = departments !== null && groups.length === departments.length
      && groups.every(function (group) { return fulfilled(group.rooms); });
    const doctors = doctorsReady ? groups.reduce(function (sum, group) { return sum + group.doctors.value.length; }, 0) : null;
    const rooms = roomsReady ? groups.reduce(function (sum, group) { return sum + group.rooms.value.length; }, 0) : null;
    let cards = stat(ctx, '启用科室', departments ? departments.length : null, '当前可用的门诊科室', 'building');
    cards += stat(ctx, '在岗医生', doctors, doctorsReady ? '全部启用科室的医生' : '部分资源暂未加载', 'stethoscope');
    cards += stat(ctx, '可用诊室', rooms, roomsReady ? '全部启用科室的诊室' : '部分资源暂未加载', 'door');
    cards += stat(ctx, '可用班次', templates ? templates.length : null, '当前启用的班次模板', 'layers');

    let preparation;
    if (!fulfilled(results[0])) {
      preparation = ctx.errorPanel(results[0].reason);
    } else if (!departments.length) {
      preparation = ctx.emptyState('从第一个科室开始', '创建科室后，继续配置医生与诊室，即可准备排班。',
        '<a class="btn btn-primary btn-sm" href="#/departments">创建科室</a>');
    } else {
      preparation = '<div class="table-wrap"><table><thead><tr><th>科室</th><th>医生</th><th>诊室</th><th>资源状态</th></tr></thead><tbody>';
      preparation += groups.map(function (group) {
        const loaded = fulfilled(group.doctors) && fulfilled(group.rooms);
        const ready = loaded && group.doctors.value.length > 0 && group.rooms.value.length > 0;
        const failure = !fulfilled(group.doctors) ? group.doctors.reason : !fulfilled(group.rooms) ? group.rooms.reason : null;
        return '<tr><td><b>' + ctx.esc(group.department.name) + '</b></td><td>'
          + (fulfilled(group.doctors) ? group.doctors.value.length + ' 位' : '<span class="muted">加载失败</span>')
          + '</td><td>' + (fulfilled(group.rooms) ? group.rooms.value.length + ' 间' : '<span class="muted">加载失败</span>')
          + '</td><td><span class="badge ' + (ready ? 'ok' : 'warn') + '">'
          + (!loaded ? '待重新加载' : ready ? '医生与诊室齐备' : '待补齐资源') + '</span>'
          + (failure ? '<small class="cell-sub">' + ctx.esc(failure.message || '资源加载失败') + '</small>' : '') + '</td></tr>';
      }).join('');
      preparation += '</tbody></table></div>';
    }

    let templateContent;
    if (!fulfilled(results[1])) {
      templateContent = ctx.errorPanel(results[1].reason);
    } else if (!templates.length) {
      templateContent = '<div class="dash-soft-note">暂时没有可用班次，请先准备班次模板后再创建排班。</div>';
    } else {
      templateContent = '<div class="dash-soft-note">' + ctx.icon('layers') + '<span>已有 <b>' + templates.length
        + '</b> 个可用班次。创建排班时选择班次，即可安排就诊时段。</span></div>';
    }
    root.innerHTML = hero(ctx, true) + '<div class="stats-grid">' + cards + '</div>'
      + '<div class="dash-columns"><div>'
      + section('科室资源准备', '按科室检查医生与诊室，为排班做好准备', preparation,
        '<a class="text-link" href="#/departments">管理科室 <span aria-hidden="true">↗</span></a>')
      + section('让排班有序发布', '准备资源，安排时间，开放预约',
        '<ol class="dash-steps"><li><span>01</span><div><b>准备科室资源</b><p>确保同一科室已有医生和可用诊室。</p></div></li>'
        + '<li><span>02</span><div><b>创建门诊排班</b><p>选择日期、医生、诊室与班次。</p></div></li>'
        + '<li><span>03</span><div><b>检查并发布</b><p>发布排班后，患者即可查询可约号源。</p></div></li></ol>')
      + '</div><aside>'
      + section('快捷管理', '常用操作，触手可及',
        quickLink(ctx, 'schedules', '创建与发布排班', '安排日期、班次与门诊资源', 'calendar')
        + quickLink(ctx, 'doctors', '管理医生', '为科室绑定医生档案', 'stethoscope')
        + quickLink(ctx, 'rooms', '管理诊室', '准备各科室的接诊空间', 'door'))
      + section('班次安排', '让每天的接诊时间更清晰', templateContent,
        '<a class="text-link" href="#/templates">查看 <span aria-hidden="true">↗</span></a>')
      + '</aside></div>';
  }

  global.MedFlowDashboard = {
    render: async function (root, ctx) {
      const admin = ctx.profile.role === 'ADMIN';
      const ticket = (renders.get(root) || 0) + 1;
      renders.set(root, ticket);
      const openingToken = sessionToken();
      function current() { return root.isConnected && renders.get(root) === ticket && sessionToken() === openingToken; }
      root.innerHTML = loading(ctx, admin);
      if (admin) await adminDashboard(root, ctx);
      else await patientDashboard(root, ctx, current);
    }
  };
})(window);
