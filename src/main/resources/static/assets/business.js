(function (global) {
  'use strict';

  const DRUG_STATUS = { ENABLED: ['启用', 'ok'], DISABLED: ['停用', 'muted'] };
  const RX_STATUS = { DRAFT: ['草稿', 'warn'], ISSUED: ['已提交', 'ok'], DISCARDED: ['已放弃', 'muted'] };
  const BILL_STATUS = { UNPAID: ['待缴费', 'warn'], PAID: ['已模拟缴费', 'ok'] };
  function $(root, selector) { return root.querySelector(selector); }
  function $$(root, selector) { return Array.prototype.slice.call(root.querySelectorAll(selector)); }
  function money(value) {
    const number = Number(value);
    return value === null || value === undefined || !Number.isFinite(number) ? '—' : '¥ ' + number.toFixed(2);
  }
  function badge(ctx, status, labels) {
    const meta = labels[status] || [status || '—', 'muted'];
    return '<span class="badge ' + meta[1] + '">' + ctx.esc(meta[0]) + '</span>';
  }
  function heading(title, description, actions) {
    return '<header class="page-head"><div class="page-title"><h1>' + title + '</h1><p>' + description
      + '</p></div><div class="page-tools">' + (actions || '') + '</div></header>';
  }
  function loading() { return '<div class="business-loading" role="status"><span class="spinner"></span>正在加载…</div>'; }
  function alert(ctx, host, failure) { if (host.isConnected && !ctx.handleApiError(failure)) host.innerHTML = ctx.errorPanel(failure); }
  function notice(text) { return '<div class="business-notice"><p>' + text + '</p></div>'; }
  function pager(result, page) {
    const size = Number(result.size) || 10;
    return '<div class="business-pager"><span class="muted tiny">共 ' + Number(result.total || 0) + ' 条 · 第 ' + page + ' / '
      + Math.max(1, Math.ceil(Number(result.total || 0) / size)) + ' 页</span><div class="business-actions">'
      + '<button class="btn btn-sm" data-biz-page="' + (page - 1) + '"' + (page <= 1 ? ' disabled' : '') + '>上一页</button>'
      + '<button class="btn btn-sm" data-biz-page="' + (page + 1) + '"' + (page * size >= Number(result.total || 0) ? ' disabled' : '') + '>下一页</button></div></div>';
  }
  function bindPages(host, load) {
    $$(host, '[data-biz-page]').forEach(function (button) {
      button.addEventListener('click', function () { if (!button.disabled) return load(Number(button.getAttribute('data-biz-page'))); });
    });
  }
  function busyControls(root) {
    const previous = new Map();
    return function (busy) {
      if (busy) {
        $$(root, 'input, select, textarea, button').forEach(function (control) {
          if (!previous.has(control)) previous.set(control, control.disabled);
          control.disabled = true;
        });
      } else {
        previous.forEach(function (disabled, control) { control.disabled = disabled; });
        previous.clear();
      }
    };
  }

  async function adminDrugs(root, ctx) {
    root.innerHTML = heading('药品管理', '维护药品目录、价格和启用状态。已保存处方保留当时的价格快照。',
      '<button class="btn btn-primary" id="biz-new-drug">新增药品</button>')
      + '<form class="business-filters" id="biz-drug-filter"><label class="field"><span>搜索药品</span><input id="biz-drug-keyword" type="search" maxlength="100" placeholder="编码、名称或规格"></label>'
      + '<label class="field"><span>状态</span><select id="biz-drug-status"><option value="">全部状态</option><option value="ENABLED">启用</option><option value="DISABLED">停用</option></select></label>'
      + '<button class="btn btn-primary" type="submit">查询药品</button></form><section id="biz-drug-list"></section>';
    const body = $(root, '#biz-drug-list');
    let ticket = 0;
    let currentPage = 1;
    async function load(page) {
      const version = ++ticket;
      currentPage = page || 1;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.drugs.page({ keyword: $(root, '#biz-drug-keyword').value.trim(), status: $(root, '#biz-drug-status').value, page: currentPage, size: 10 });
        if (!body.isConnected || ticket !== version) return;
        body.innerHTML = result.items.length ? '<div class="table-wrap"><table><thead><tr><th>药品 / 编码</th><th>规格</th><th>单位</th><th>单价</th><th>状态</th><th>操作</th></tr></thead><tbody>'
          + result.items.map(function (drug) {
            return '<tr><td><b>' + ctx.esc(drug.name) + '</b><small class="cell-sub mono">' + ctx.esc(drug.code) + '</small></td><td>'
              + ctx.esc(drug.specification) + '</td><td>' + ctx.esc(drug.unit) + '</td><td class="business-money">' + money(drug.unitPrice)
              + '</td><td>' + badge(ctx, drug.status, DRUG_STATUS) + '</td><td><button class="btn btn-sm" data-biz-drug="' + ctx.esc(drug.id) + '">编辑</button></td></tr>';
          }).join('') + '</tbody></table></div>' + pager(result, currentPage)
          : ctx.emptyState('暂无符合条件的药品', '调整搜索条件，或新增药品后供医生选择。');
        bindPages(body, load);
        $$(body, '[data-biz-drug]').forEach(function (button) {
          button.addEventListener('click', function () {
            const drug = result.items.find(function (item) { return String(item.id) === button.getAttribute('data-biz-drug'); });
            return openDrug(drug, ctx, function () { return load(currentPage); });
          });
        });
      } catch (failure) { if (ticket === version) alert(ctx, body, failure); }
    }
    $(root, '#biz-drug-filter').addEventListener('submit', function (event) { event.preventDefault(); return load(1); });
    $(root, '#biz-new-drug').addEventListener('click', function () { return openDrug(null, ctx, function () { return load(1); }); });
    await load(1);
  }

  function openDrug(drug, ctx, onChange) {
    const editing = Boolean(drug);
    const item = drug || { code: '', name: '', specification: '', unit: '', unitPrice: '', status: 'ENABLED' };
    const modal = ctx.openModal({ title: editing ? '编辑药品' : '新增药品', subtitle: editing ? '药品编码 ' + item.code + ' · 保存时校验版本' : '维护模拟处方使用的基础目录', width: 570,
      body: '<form class="form-grid" id="biz-drug-form">'
        + '<label class="field"><span>药品编码</span><input id="biz-drug-code" maxlength="32" value="' + ctx.esc(item.code) + '" required' + (editing ? ' disabled' : '') + '></label>'
        + '<label class="field"><span>药品名称</span><input id="biz-drug-name" maxlength="100" value="' + ctx.esc(item.name) + '" required></label>'
        + '<label class="field"><span>规格</span><input id="biz-drug-specification" maxlength="100" value="' + ctx.esc(item.specification) + '" placeholder="例如 每盒 12 片" required></label>'
        + '<div class="form-grid two"><label class="field"><span>计价单位</span><input id="biz-drug-unit" maxlength="20" value="' + ctx.esc(item.unit) + '" placeholder="盒 / 瓶 / 支" required></label>'
        + '<label class="field"><span>单价（元）</span><input id="biz-drug-price" inputmode="decimal" value="' + ctx.esc(item.unitPrice === '' ? '' : Number(item.unitPrice).toFixed(2)) + '" placeholder="0.00" required></label></div>'
        + (editing ? '<label class="field"><span>状态</span><select id="biz-drug-enabled"><option value="ENABLED">启用</option><option value="DISABLED">停用</option></select></label>' : '')
        + '<div id="biz-drug-error" aria-live="polite"></div></form>',
      footer: '<button class="btn" data-close>取消</button><button class="btn btn-primary" type="submit" form="biz-drug-form" id="biz-drug-save">保存药品</button>' });
    const body = modal.root;
    const setControlsBusy = busyControls(body);
    if (editing) $(body, '#biz-drug-enabled').value = item.status;
    let busy = false;
    $(body, '#biz-drug-form').addEventListener('submit', async function (event) {
      event.preventDefault();
      if (busy) return;
      const price = $(body, '#biz-drug-price').value.trim();
      const payload = { name: $(body, '#biz-drug-name').value.trim(), specification: $(body, '#biz-drug-specification').value.trim(),
        unit: $(body, '#biz-drug-unit').value.trim(), unitPrice: price };
      if (!payload.name || !payload.specification || !payload.unit || !/^\d+(\.\d{1,2})?$/.test(price) || Number(price) < 0.01 || Number(price) > 999999.99) {
        ctx.toast('请补齐药品信息，单价为 0.01 至 999999.99 元且最多两位小数。', 'warn'); return;
      }
      if (editing) { payload.status = $(body, '#biz-drug-enabled').value; payload.version = item.version; }
      else {
        payload.code = $(body, '#biz-drug-code').value.trim();
        if (!payload.code) { ctx.toast('请填写药品编码。', 'warn'); return; }
      }
      busy = true;
      setControlsBusy(true);
      $(body, '#biz-drug-error').innerHTML = '';
      try {
        if (editing) await ctx.api.drugs.update(item.id, payload); else await ctx.api.drugs.create(payload);
        if (!body.isConnected) return;
        ctx.toast(editing ? '药品已更新。' : '药品已创建。', 'ok');
        modal.close();
        if (onChange) await onChange();
      } catch (failure) {
        if (body.isConnected && !ctx.handleApiError(failure)) {
          $(body, '#biz-drug-error').innerHTML = ctx.errorPanel(failure) + (failure.status === 409
            ? '<p class="muted tiny">当前输入已保留。如其他页面已编辑，请先核对内容，再关闭并刷新目录。</p><button class="btn btn-sm" type="button" id="biz-drug-reload">关闭并刷新目录</button>' : '');
          const reload = $(body, '#biz-drug-reload');
          if (reload) reload.addEventListener('click', async function () { modal.close(); if (onChange) await onChange(); });
        }
      } finally {
        busy = false;
        setControlsBusy(false);
      }
    });
    return modal;
  }

  function prescriptionSummary(ctx, prescription) {
    return '<div class="business-rx-summary"><div><h3>' + ctx.esc(prescription.patientName || '本次接诊') + '</h3><p>'
      + ctx.esc(prescription.departmentName) + ' · ' + ctx.esc(prescription.doctorName) + ' · 接诊 #' + ctx.esc(prescription.encounterId)
      + '</p></div>' + badge(ctx, prescription.status, RX_STATUS) + '</div>';
  }
  function itemTable(ctx, items) {
    return '<div class="table-wrap"><table><thead><tr><th>药品 / 规格</th><th>数量</th><th>单价</th><th>金额</th><th>用法说明</th></tr></thead><tbody>'
      + items.map(function (item) {
        return '<tr><td><b>' + ctx.esc(item.drugName) + '</b><small class="cell-sub">' + ctx.esc(item.specification) + ' · ' + ctx.esc(item.drugCode)
          + '</small></td><td>' + ctx.esc(item.quantity) + ' ' + ctx.esc(item.unit) + '</td><td class="business-money">' + money(item.unitPrice)
          + '</td><td class="business-money">' + money(item.lineAmount) + '</td><td class="business-instructions">' + ctx.esc(item.instructions || '—') + '</td></tr>';
      }).join('') + '</tbody></table></div>';
  }
  function billSummary(ctx, detail) {
    const bill = detail.bill;
    return '<div class="business-total"><span>处方合计</span><b>' + money(detail.prescription.totalAmount) + '</b></div>'
      + (bill ? '<dl class="kv business-bill"><dt>账单编号</dt><dd>#' + ctx.esc(bill.id) + '</dd><dt>缴费状态</dt><dd>' + badge(ctx, bill.status, BILL_STATUS)
        + '</dd><dt>账单金额</dt><dd>' + money(bill.totalAmount) + '</dd>'
        + (bill.paidAt ? '<dt>登记时间</dt><dd>' + ctx.esc(ctx.fmtDateTime(bill.paidAt)) + '</dd>' : '') + '</dl>' : '');
  }

  async function openDoctorPrescription(encounterId, ctx, onChange) {
    const modal = ctx.openModal({ title: '处方与模拟收费', subtitle: '接诊编号 #' + encounterId, width: 870,
      body: loading(), footer: '<button class="btn" data-close>关闭</button>' });
    const body = $(modal.root, '.modal-body');
    const setControlsBusy = busyControls(body);
    let detail = null;
    let encounter;
    let draft = [];
    let savedSignature = '[]';
    let busy = false;
    let drugSearchTicket = 0;
    let available = [];
    function version() { return detail ? detail.prescription.version : 0; }
    function capture() {
      draft.forEach(function (item) {
        const row = $(body, '[data-biz-item="' + item.drugId + '"]');
        if (!row) return;
        item.quantity = Number($(row, '[data-biz-quantity]').value);
        item.instructions = $(row, '[data-biz-instructions]').value;
      });
    }
    function commandItems() { return draft.map(function (item) { return { drugId: item.drugId, quantity: Number(item.quantity), instructions: item.instructions || '' }; }); }
    function dirty() { capture(); return JSON.stringify(commandItems()) !== savedSignature; }
    function editable() { return encounter && encounter.status === 'IN_PROGRESS' && (!detail || detail.prescription.status !== 'ISSUED'); }
    function bindDraftRows() {
      $$(body, '[data-biz-remove]').forEach(function (button) {
        button.addEventListener('click', function () {
          if (busy) return;
          capture();
          draft = draft.filter(function (item) { return String(item.drugId) !== button.getAttribute('data-biz-remove'); });
          renderDraft();
        });
      });
      $$(body, '[data-biz-quantity], [data-biz-instructions]').forEach(function (control) {
        control.addEventListener('input', function () { $(body, '#biz-rx-save-state').textContent = dirty() ? '有未保存修改，请先保存并核对金额后提交。' : '内容与已保存版本一致。'; });
      });
    }
    function renderDraft() {
      const host = $(body, '#biz-rx-items');
      host.innerHTML = draft.length ? draft.map(function (item) {
        return '<article class="business-draft-item" data-biz-item="' + ctx.esc(item.drugId) + '"><div class="business-draft-title"><div><b>' + ctx.esc(item.drugName)
          + '</b><small>' + ctx.esc(item.specification) + ' · ' + ctx.esc(item.unit) + ' · 参考单价 ' + money(item.unitPrice) + '</small></div>'
          + '<button class="btn btn-sm" type="button" data-biz-remove="' + ctx.esc(item.drugId) + '" aria-label="移除' + ctx.esc(item.drugName) + '">移除</button></div>'
          + '<div class="business-draft-controls"><label class="field"><span>数量（' + ctx.esc(item.unit) + '）</span><input type="number" min="1" max="1000" step="1" value="'
          + ctx.esc(item.quantity) + '" data-biz-quantity required></label><label class="field"><span>用法说明</span><textarea rows="2" maxlength="1000" data-biz-instructions>'
          + ctx.esc(item.instructions || '') + '</textarea></label></div></article>';
      }).join('') : '<div class="business-empty-draft">尚未添加药品，请从目录中选择。无开药需要时，可直接关闭此窗口。</div>';
      bindDraftRows();
      $(body, '#biz-rx-save-state').textContent = dirty() ? '有未保存修改，请先保存并核对金额后提交。' : detail && detail.prescription.status === 'DRAFT'
        ? '草稿已保存 · 版本 ' + detail.prescription.version : '尚未保存新的处方草稿。';
    }
    async function searchDrugs(page) {
      const ticket = ++drugSearchTicket;
      const host = $(body, '#biz-rx-drugs');
      host.innerHTML = loading();
      try {
        const result = await ctx.api.drugs.available({ keyword: $(body, '#biz-rx-keyword').value.trim(), page: page || 1, size: 8 });
        if (!host.isConnected || ticket !== drugSearchTicket) return;
        available = result.items;
        host.innerHTML = available.length ? '<div class="business-drug-options">' + available.map(function (drug) {
          return '<button class="business-drug-option" type="button" data-biz-add="' + ctx.esc(drug.id) + '"><span><b>' + ctx.esc(drug.name)
            + '</b><small>' + ctx.esc(drug.specification) + ' · ' + ctx.esc(drug.unit) + '</small></span><span class="business-money">' + money(drug.unitPrice) + ' <b aria-hidden="true">＋</b></span></button>';
        }).join('') + '</div>' + pager(result, page || 1) : '<p class="muted tiny">没有启用的匹配药品，请调整搜索条件或联系管理员维护目录。</p>';
        bindPages(host, searchDrugs);
        $$(host, '[data-biz-add]').forEach(function (button) {
          button.addEventListener('click', function () {
            if (busy) return;
            capture();
            const drug = available.find(function (item) { return String(item.id) === button.getAttribute('data-biz-add'); });
            if (draft.some(function (item) { return item.drugId === drug.id; })) { ctx.toast('该药品已在处方中，请直接修改数量。', 'warn'); return; }
            if (draft.length >= 20) { ctx.toast('每张处方最多 20 项药品。', 'warn'); return; }
            draft.push({ drugId: drug.id, drugCode: drug.code, drugName: drug.name, specification: drug.specification, unit: drug.unit, unitPrice: drug.unitPrice, quantity: 1, instructions: '' });
            renderDraft();
          });
        });
        if (busy) setControlsBusy(true);
      } catch (failure) { if (ticket === drugSearchTicket) alert(ctx, host, failure); }
    }
    function render() {
      ++drugSearchTicket;
      draft = detail ? detail.items.map(function (item) { return Object.assign({}, item); }) : [];
      savedSignature = JSON.stringify(commandItems());
      const prescription = detail ? detail.prescription : Object.assign({}, encounter, { encounterId: encounter.id, status: 'DRAFT' });
      body.innerHTML = prescriptionSummary(ctx, prescription)
        + (!editable() ? (detail && detail.prescription.status === 'ISSUED' ? notice('处方已提交并冻结；如有疑问请联系门诊工作人员。本版本不提供撤方、退款。')
          + itemTable(ctx, detail.items) + billSummary(ctx, detail) : notice(encounter.status !== 'IN_PROGRESS'
            ? '只有就诊中的接诊允许开方；当前没有可编辑处方。' : '当前处方不可编辑。'))
        : (detail && detail.prescription.status === 'DISCARDED' ? notice('上一份草稿已放弃，不会产生账单。可以重新选择药品并保存，沿用当前版本。') : '')
          + notice('仅用于模拟业务。保存时由后端读取药品价格并计算金额；提交沿用已保存快照、生成账单，提交后不能修改。')
          + '<section class="business-rx-catalog"><h3>选择启用药品</h3><form id="biz-rx-search" class="business-search"><label class="field"><span class="sr-only">搜索药品</span><input type="search" id="biz-rx-keyword" placeholder="药品编码或名称" maxlength="100"></label>'
          + '<button class="btn" type="submit">搜索药品</button></form><div id="biz-rx-drugs"></div></section>'
          + '<section><h3 class="business-section-title">处方明细 <span class="muted tiny">最多 20 项</span></h3><div id="biz-rx-items"></div></section>'
          + '<div class="business-total"><span>上次保存金额</span><b>' + (detail && detail.prescription.status === 'DRAFT' ? money(detail.prescription.totalAmount) : '尚未保存') + '</b></div>'
          + '<p class="muted tiny" id="biz-rx-save-state" role="status"></p><div class="business-actions"><button class="btn btn-primary" id="biz-rx-save" type="button">保存处方草稿</button></div>'
          + (detail && detail.prescription.status === 'DRAFT' ? '<div class="business-submit-box"><label class="business-check"><input type="checkbox" id="biz-rx-confirm">确认已核对药品、数量和已保存金额；提交后不可修改</label>'
            + '<button class="btn btn-primary" id="biz-rx-issue">提交处方并生成账单</button></div><details class="business-discard"><summary>不需要这份处方？</summary><p class="muted tiny">放弃草稿会清空药品和当前修改，不生成账单；后续仍可重新保存草稿。</p>'
            + '<label class="business-check"><input type="checkbox" id="biz-rx-discard-confirm">确认放弃这份未提交草稿</label><button class="btn btn-danger btn-sm" id="biz-rx-discard">放弃草稿</button></details>' : ''))
        + '<div id="biz-rx-error" aria-live="polite"></div>';
      if (editable()) {
        renderDraft();
        $(body, '#biz-rx-search').addEventListener('submit', function (event) { event.preventDefault(); return searchDrugs(1); });
        $(body, '#biz-rx-save').addEventListener('click', function () { return action('save'); });
        const issue = $(body, '#biz-rx-issue');
        if (issue) issue.addEventListener('click', function () { return action('issue'); });
        const discard = $(body, '#biz-rx-discard');
        if (discard) discard.addEventListener('click', function () { return action('discard'); });
        searchDrugs(1);
      }
      if (busy) setControlsBusy(true);
    }
    async function action(kind) {
      if (busy) return;
      capture();
      const items = commandItems();
      if (kind === 'save' && (!items.length || items.length > 20 || items.some(function (item) { return !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 1000; }))) {
        ctx.toast('请选择 1 至 20 项药品，每项数量为 1 至 1000 的整数。', 'warn'); return;
      }
      if (kind === 'issue' && dirty()) { ctx.toast('处方有未保存修改，请先保存并核对金额。', 'warn'); return; }
      if (kind === 'issue' && !$(body, '#biz-rx-confirm').checked) { ctx.toast('请先核对并确认已保存的处方内容。', 'warn'); return; }
      if (kind === 'discard' && !$(body, '#biz-rx-discard-confirm').checked) { ctx.toast('请先勾选确认放弃草稿。', 'warn'); return; }
      busy = true;
      setControlsBusy(true);
      $(body, '#biz-rx-error').innerHTML = '';
      try {
        const next = kind === 'save' ? await ctx.api.prescriptions.save(encounterId, { version: version(), items: items })
          : kind === 'issue' ? await ctx.api.prescriptions.issue(encounterId, version()) : await ctx.api.prescriptions.discard(encounterId, version());
        if (!body.isConnected) return;
        detail = next;
        render();
        ctx.toast(kind === 'save' ? '处方草稿已保存，请核对后端计算的金额。' : kind === 'issue' ? '处方已提交，已生成待缴费账单。' : '草稿已放弃，可以完成无处方接诊。', 'ok');
        if (onChange) await onChange();
      } catch (failure) {
        if (body.isConnected && !ctx.handleApiError(failure)) {
          $(body, '#biz-rx-error').innerHTML = ctx.errorPanel(failure) + (failure.status === 409
            ? '<p class="muted tiny">当前填写内容已保留。状态或版本可能已更新，重新加载会舍弃本页未保存修改。</p><button class="btn btn-sm" id="biz-rx-reload">重新加载处方</button>'
            : '<p class="muted tiny">如提交结果不确定，请先关闭并重新查看处方状态，再决定是否重试。</p>');
          const reload = $(body, '#biz-rx-reload');
          if (reload) reload.addEventListener('click', load);
        }
      } finally { busy = false; setControlsBusy(false); }
    }
    async function load() {
      if (busy) return;
      busy = true;
      body.innerHTML = loading();
      try {
        const pair = await Promise.all([ctx.api.doctor.encounter(encounterId), ctx.api.prescriptions.doctorDetail(encounterId)]);
        if (!body.isConnected) return;
        encounter = pair[0].encounter;
        detail = pair[1];
        render();
      } catch (failure) { alert(ctx, body, failure); }
      finally { busy = false; setControlsBusy(false); }
    }
    await load();
  }

  async function patientPrescriptions(root, ctx) {
    root.innerHTML = heading('处方与缴费', '查看本人就诊人的已提交处方。模拟缴费仅登记状态，不会实际扣款。',
      '<button class="btn" id="biz-patient-refresh">刷新处方</button>') + '<section id="biz-patient-list"></section>';
    const body = $(root, '#biz-patient-list');
    let currentPage = 1;
    let ticket = 0;
    async function load(page) {
      const version = ++ticket;
      currentPage = page || 1;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.prescriptions.patientPage({ page: currentPage, size: 10 });
        if (!body.isConnected || ticket !== version) return;
        body.innerHTML = result.items.length ? '<div class="business-patient-cards">' + result.items.map(function (item) {
          return '<article class="card business-prescription-card"><div><div class="business-card-top"><b>' + ctx.esc(item.patientName) + '</b>'
            + (item.bill ? badge(ctx, item.bill.status, BILL_STATUS) : badge(ctx, item.status, RX_STATUS)) + '</div><p>'
            + ctx.esc(item.departmentName) + ' · ' + ctx.esc(item.doctorName) + '</p><small class="muted">处方 #' + ctx.esc(item.id) + ' · '
            + ctx.esc(ctx.fmtDateTime(item.issuedAt)) + '</small></div><div class="business-card-bottom"><b class="business-money">' + money(item.totalAmount)
            + '</b><button class="btn ' + (item.bill && item.bill.status === 'UNPAID' ? 'btn-primary' : '') + '" data-biz-prescription="' + ctx.esc(item.id) + '">'
            + (item.bill && item.bill.status === 'UNPAID' ? '查看并模拟缴费' : '查看明细') + '</button></div></article>';
        }).join('') + '</div>' + pager(result, currentPage) : ctx.emptyState('暂无已提交处方', '医生提交处方后会显示在这里；未开药的就诊不会产生药品账单。');
        bindPages(body, load);
        $$(body, '[data-biz-prescription]').forEach(function (button) {
          button.addEventListener('click', function () { return openPatientPrescription(Number(button.getAttribute('data-biz-prescription')), ctx, function () { return load(currentPage); }); });
        });
      } catch (failure) { if (ticket === version) alert(ctx, body, failure); }
    }
    $(root, '#biz-patient-refresh').addEventListener('click', function () { return load(currentPage); });
    await load(1);
  }

  async function openPatientPrescription(id, ctx, onChange) {
    const modal = ctx.openModal({ title: '处方明细与模拟缴费', subtitle: '处方编号 #' + id, width: 800,
      body: loading(), footer: '<button class="btn" data-close>关闭</button>' });
    const body = $(modal.root, '.modal-body');
    const setControlsBusy = busyControls(body);
    let detail;
    let busy = false;
    function render() {
      body.innerHTML = prescriptionSummary(ctx, detail.prescription) + itemTable(ctx, detail.items) + billSummary(ctx, detail)
        + notice('模拟缴费只在本系统登记缴费状态，不会发生实际扣款，不代表真实支付或医保结算。')
        + (detail.bill && detail.bill.status === 'UNPAID' ? '<label class="business-check"><input type="checkbox" id="biz-pay-confirm">确认登记本次模拟缴费，无实际扣款</label>'
          + '<button class="btn btn-primary" id="biz-pay">确认模拟缴费</button>' : '')
        + '<div id="biz-pay-error" aria-live="polite"></div><div class="business-actions business-refresh"><button class="btn btn-sm" id="biz-bill-refresh">刷新账单状态</button></div>';
      const pay = $(body, '#biz-pay');
      if (pay) pay.addEventListener('click', async function () {
        if (busy) return;
        if (!$(body, '#biz-pay-confirm').checked) { ctx.toast('请先勾选确认登记模拟缴费。', 'warn'); return; }
        busy = true;
        setControlsBusy(true);
        try {
          const result = await ctx.api.prescriptions.pay(detail.bill.id);
          if (!body.isConnected) return;
          detail = result;
          render();
          ctx.toast('模拟缴费已登记，无实际扣款。', 'ok');
          if (onChange) await onChange();
        } catch (failure) {
          if (body.isConnected && !ctx.handleApiError(failure)) $(body, '#biz-pay-error').innerHTML = ctx.errorPanel(failure)
            + '<p class="muted tiny">如结果不确定，请先刷新账单状态。重复登记不会增加支付记录。</p>';
        } finally { busy = false; setControlsBusy(false); }
      });
      $(body, '#biz-bill-refresh').addEventListener('click', load);
      if (busy) setControlsBusy(true);
    }
    async function load() {
      if (busy) return;
      busy = true;
      body.innerHTML = loading();
      try {
        detail = await ctx.api.prescriptions.patientDetail(id);
        if (!detail || !detail.prescription || detail.prescription.status !== 'ISSUED') throw new Error('当前处方尚未提交，无法查看。');
        if (body.isConnected) render();
      } catch (failure) { alert(ctx, body, failure); }
      finally { busy = false; setControlsBusy(false); }
    }
    await load();
  }

  function count(value) { return Math.max(0, Number(value) || 0); }
  function trendChart(ctx, rows) {
    if (!rows.length) return ctx.emptyState('当前范围没有趋势数据', '调整日期范围后查询。');
    const max = Math.max(1, Math.max.apply(null, rows.map(function (row) { return count(row.count); })));
    const width = 640;
    const step = rows.length > 1 ? 580 / (rows.length - 1) : 0;
    const points = rows.map(function (row, index) { return { x: rows.length > 1 ? 42 + step * index : 330, y: 160 - count(row.count) / max * 125, row: row }; });
    return '<svg class="business-trend-svg" viewBox="0 0 ' + width + ' 200" role="img" aria-label="按创建日期统计的预约趋势"><title>预约趋势：包含已取消和已过期的创建记录</title>'
      + '<line x1="42" y1="160" x2="622" y2="160" class="business-axis"/><line x1="42" y1="35" x2="622" y2="35" class="business-gridline"/>'
      + '<text x="30" y="164" text-anchor="end">0</text><text x="30" y="39" text-anchor="end">' + max + '</text>'
      + '<polyline class="business-trend-line" points="' + points.map(function (point) { return point.x.toFixed(1) + ',' + point.y.toFixed(1); }).join(' ') + '"/>'
      + points.map(function (point, index) {
        const showLabel = rows.length <= 8 || index === 0 || index === rows.length - 1 || index % Math.ceil(rows.length / 6) === 0;
        return '<circle cx="' + point.x.toFixed(1) + '" cy="' + point.y.toFixed(1) + '" r="3" class="business-trend-point"><title>'
          + ctx.esc(point.row.date) + '：' + count(point.row.count) + ' 次</title></circle>'
          + (showLabel ? '<text x="' + point.x.toFixed(1) + '" y="185" text-anchor="middle">' + ctx.esc(String(point.row.date).slice(5)) + '</text>' : '');
      }).join('') + '</svg>';
  }
  function bars(ctx, rows, key, description) {
    if (!rows.length) return ctx.emptyState('当前范围没有已完成接诊', '候诊、就诊中与已预约不计入完成量。');
    const top = rows.slice().sort(function (a, b) { return count(b.count) - count(a.count); }).slice(0, 10);
    const max = Math.max(1, Math.max.apply(null, top.map(function (row) { return count(row.count); })));
    return '<div class="business-bar-chart" role="img" aria-label="' + description + '">'
      + top.map(function (row) { return '<div class="business-bar-row"><span title="' + ctx.esc(row[key]) + '">' + ctx.esc(row[key])
        + '</span><div class="business-bar-track"><i style="width:' + (count(row.count) / max * 100).toFixed(2) + '%"></i></div><b>' + count(row.count) + '</b></div>'; }).join('')
      + '</div>' + (rows.length > 10 ? '<p class="muted tiny">图示前 10 项，完整数据见下表。</p>' : '');
  }
  function statsTable(ctx, rows, columns, label) {
    return '<details class="business-data-table" open><summary>' + label + '数据表</summary><div class="table-wrap"><table><thead><tr>'
      + columns.map(function (column) { return '<th>' + column[1] + '</th>'; }).join('') + '<th>数量</th></tr></thead><tbody>'
      + (rows.length ? rows.map(function (row) { return '<tr>' + columns.map(function (column) { return '<td>' + ctx.esc(row[column[0]]) + '</td>'; }).join('')
        + '<td>' + count(row.count) + '</td></tr>'; }).join('') : '<tr><td colspan="' + (columns.length + 1) + '" class="muted">当前范围没有记录</td></tr>')
      + '</tbody></table></div></details>';
  }
  async function adminStatistics(root, ctx) {
    root.innerHTML = heading('业务统计', '按实际创建与完成时间观察门诊业务。默认近 7 天，单次最多 93 天。')
      + '<form class="business-filters" id="biz-stat-filter"><label class="field"><span>开始日期</span><input id="biz-stat-from" type="date" value="'
      + ctx.esc(ctx.todayIso(-6)) + '" required></label><label class="field"><span>结束日期</span><input id="biz-stat-to" type="date" value="'
      + ctx.esc(ctx.todayIso()) + '" required></label><button class="btn btn-primary" type="submit">查询统计</button></form><section id="biz-stat-result"></section>';
    const body = $(root, '#biz-stat-result');
    let ticket = 0;
    async function load() {
      const from = $(root, '#biz-stat-from').value;
      const to = $(root, '#biz-stat-to').value;
      const duration = (Date.parse(to) - Date.parse(from)) / 86400000;
      if (!from || !to || !Number.isFinite(duration) || duration < 0 || duration > 92) { ctx.toast('请选择正确的日期范围，最多包含 93 天。', 'warn'); return; }
      const version = ++ticket;
      body.innerHTML = loading();
      try {
        const result = await ctx.api.statistics.get({ from: from, to: to });
        if (!body.isConnected || ticket !== version) return;
        const trend = result.appointmentTrend || [];
        const departments = result.departmentVisits || [];
        const doctors = result.doctorWorkload || [];
        const totalAppointments = trend.reduce(function (sum, row) { return sum + count(row.count); }, 0);
        const completed = departments.reduce(function (sum, row) { return sum + count(row.count); }, 0);
        body.innerHTML = '<div class="business-stat-range">' + ctx.esc(result.from) + ' 至 ' + ctx.esc(result.to) + '</div>'
          + '<div class="business-stat-cards"><article class="stat-card"><div class="stat-label">创建预约次数</div><div class="stat-value">' + totalAppointments
          + '</div><div class="stat-note">包含后续取消、过期的记录</div></article><article class="stat-card"><div class="stat-label">完成接诊量</div><div class="stat-value">'
          + completed + '</div><div class="stat-note">仅 COMPLETED，按完成日期统计</div></article></div>'
          + '<div class="business-chart-grid"><section class="card business-chart-wide"><div class="card-head"><div><h3>预约趋势</h3><p>按预约创建日期统计全部记录，无记录日期补 0</p></div></div><div class="card-body">'
          + trendChart(ctx, trend) + (trend.length && totalAppointments === 0 ? '<p class="muted tiny">该范围每天创建量均为 0。</p>' : '')
          + statsTable(ctx, trend, [['date', '创建日期']], '预约趋势') + '</div></section>'
          + '<section class="card"><div class="card-head"><div><h3>科室就诊量</h3><p>仅已完成接诊，按 completed_at 归属日期</p></div></div><div class="card-body">'
          + bars(ctx, departments, 'departmentName', '科室已完成接诊量') + statsTable(ctx, departments, [['departmentName', '科室']], '科室就诊量') + '</div></section>'
          + '<section class="card"><div class="card-head"><div><h3>医生工作量</h3><p>仅已完成接诊，按 completed_at 归属日期</p></div></div><div class="card-body">'
          + bars(ctx, doctors, 'doctorName', '医生已完成接诊量') + statsTable(ctx, doctors, [['doctorName', '医生'], ['departmentName', '科室']], '医生工作量') + '</div></section></div>';
      } catch (failure) { if (ticket === version) alert(ctx, body, failure); }
    }
    $(root, '#biz-stat-filter').addEventListener('submit', function (event) { event.preventDefault(); return load(); });
    await load();
  }

  global.MedFlowBusiness = { views: { adminDrugs: adminDrugs, adminStatistics: adminStatistics,
    patientPrescriptions: patientPrescriptions }, openDoctorPrescription: openDoctorPrescription };
})(window);
