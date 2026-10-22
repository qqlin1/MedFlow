# 尚医通本地真实患者端与 MedFlow：逐场景源码对比

本报告以用户本地真实源码为主，研读日期 2026-10-21。没有启动程序、执行项目脚本或修改 MedFlow 业务代码；结论是静态代码判断，不冒充实际浏览器验收。

## 来源确认

本地真实代码含 `03-尚医通前端代码/yygh-site.zip`。患者端已完整正常解压，真实源码位于：[本地患者前端真实源码](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site)。不必再用公开网络前端补充，也不混淆公开版和本地版。

- 原始包：[yygh-site.zip](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/yygh-site.zip)
- SHA256：`134771C827454D3CAB58A58FB7C191D6FCB6487AA8BAA717EFE46415DB237606`
- 下文所有尚医通 Vue 行号均对应这个本地包内原始条目，此前纯文本分析副本的 22 个对应文件与此持久真实目录逐个 SHA256 相同，引用均改用真实目录。
- 本地后端根目录：[yygh_parent](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent)。

## 核心方法双列摘录

以下是本地真实代码与当前 MedFlow 的关键表达式，完整上下文见后面的双方文件行号。左列不代表应照抄技术实现。

| 场景 | 尚医通实际代码 | MedFlow 实际代码 | 采用选择 |
|---|---|---|---|
| 科室层级 | `_hoscode.vue:82`：`v-for="it in item.children"`，`schedule(it.depcode)` | `booking.js:74`：`departments.map(...)`；`booking.js:235`：`catalogue({ departmentId, query })` | 保留找医生入口，真实科室多时补临床分组 |
| 日期可约摘要 | `ScheduleServiceImpl.java:204–207`：`Aggregation.group("workDate")`、`.sum("availableNumber")`；`schedule.vue:44`：`无号 / 约满 / 有号` | `booking.js:131`：`Array.from({ length: 7 })`；`booking.js:257`：`slots.list({ doctorId, workDate, page, size: 12 })` | 用 MySQL 增加日期聚合读模型，不能拿单页求全天总量 |
| 完整确认信息 | `booking.vue:94–113`：`schedule.workDate`、`schedule.docname`、`schedule.title`、`schedule.skill`、`schedule.amount` | `app.js:662–684`：摘要写在 `subtitle`，主体仍为 `号源 ID / 余号 / 预约截止` | 补完整确认摘要，金额按当前真实业务范围展示 |
| 防重及未知结果 | `booking.vue:211`：`if(this.submitBnt == '正在提交...') return` | `app.js:728–745`：`bookingKey(slot.slotId, patientId)`、`appointments.create(..., intent.key)` 后读当前预约状态 | 保留 MedFlow 的请求幂等及状态恢复 |
| 预约凭证 | `order/show.vue:51–85`：患者、日期、科室、挂号单号、`fetchTime`、`quitTime` | `app.js:1112–1113`：`patientId / slotId`；`clinical.js:346–357` 才展示患者、医生、诊室及签到条件 | 统一为患者可读凭证，复用已有 visitInfo |
| 付款和就诊分开 | `order/show.vue:94`：`v-if="orderInfo.orderStatus == 0"` 显示支付 | `clinical.js:351`：`encounter.status === 'COMPLETED'` 才展示病历；`business.js:362`：`bill.status === 'UNPAID'` 才显示模拟缴费 | 保留独立预约、接诊、处方账单状态，不引入真实支付 |

## 1. 按科室预约与直接找医生

**尚医通证据**：[医院详情 `_hoscode.vue:57`](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/hospital/_hoscode.vue:57) 展示“选择科室”；64 行是大科室导航，82 行遍历 `item.children`，139–158 行选择具体科室后跳转 `/hospital/schedule?hoscode=...&depcode=...`。

**MedFlow 证据**：[booking.js:54](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:54) 提供按科室/找医生两个入口；69–98 行是平面科室卡片及按姓名/擅长搜索的医生卡片；79–85 行展示职称、擅长及简介；218 行拉取科室目录。

**比较与选择**：尚医通更适合科室很多的场景，分组能把内科大类与具体内分泌等门诊分开。MedFlow 的医生查找和完整简介更适合已知医生、复诊找原医生的患者，应该保留。当前只有少量演示科室时不必强做任意层级树；当“内科”确实成为分组而“消化内科”是可预约科室时，补一层临床分类即可，后台仍用 MySQL。

**轻量验收**：患者按科室与直接搜同一医生均能进入同一医生时段；切换科室会清除旧医生/旧号源；新增分类后不会把没有实际出诊的分组当成可预约科室。三例即可验证业务方向。

## 2. 日期聚合与可约状态：尚医通更直观，值得优先补

**尚医通证据**：[schedule.vue:40](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/hospital/schedule.vue:40) 的日期卡片同时展示日期、星期以及“有号/约满/无号/即将放号/停止挂号”。[本地 ScheduleServiceImpl.java:183](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_hosp/src/main/java/com/atguigu/yygh/hosp/service/impl/ScheduleServiceImpl.java:183) 生成预约日期序列；198–208 行按日期汇总科室医生出诊与余量；224–256 行补没有排班的日期和放号/停挂状态；260 行返回 `bookingScheduleList`。

**MedFlow 证据**：[booking.js:129](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:129) 是日期输入框；131–135 行仅生成未来七天按钮，没有加载各天余量；250–261 行只查当前医生、当前日的时段。101–118 行当天查询后才显示无号、约满和半日余量。

**比较与选择**：当前 MedFlow 需要患者逐日点开碰运气找号，这是明确业务体验短板。采用尚医通的“先看哪些日子有号”，但保留现有两种入口：按科室可看科室日期汇总，已选医生则看该医生日期汇总。不引入 MongoDB，单个 `GROUP BY work_date` 读模型即可。没有全量排班查询时不能在前端对某页十二个时段求和冒充全天余量。

**轻量验收**：准备无排班、全部约满、有剩余、已过预约截止四天；日期卡状态与点开时段一致；取消一条有效预约后刷新，余量回升；已满日不会因前端缓存仍允许提交。无需每日放号倒计时，先做当前已有规则下的可约摘要。

**不要照搬的细节**：尚医通 `docCount` 在 205 行实际是记录计数，并非 distinct 医生 ID；同一医生上午下午两条排班可能被计为两次。MedFlow 若显示“医生数”应按医生去重。

## 3. 日期 → 上午/下午 → 医生，与医生 → 日期应并存

**尚医通证据**：[schedule.vue:75](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/hospital/schedule.vue:75) 与 106 行分上午下午；79 行筛选 `workTime == 0`，110 行筛选 `workTime == 1`，83–94 行展示职称、医生、擅长、挂号费及余量，315–320 行跳到确认页。

**MedFlow 证据**：[booking.js:87](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:87) 要先选医生；123–135 行再选日期；107–118 行按具体时段起始小时分上午下午，展示具体起止时间、余量及截止。

**比较与选择**：尚医通方便“这个科室明天谁能看”，MedFlow 方便“我只找这名医生”。无需选一个淘汰另一个；按科室入口可增加日期优先/不限医生过滤，找医生入口继续医生优先。MedFlow 的具体时间段比只有上午/下午更清楚，应保留。不能让前端凭剩余数字决定最终可挂，服务端仍判可约时间、状态和余量。

**轻量验收**：同科室两名医生在同一天有不同班次，患者按日期能比较；找指定医生不会混入他人时段；换日期后旧时段不能被误提交；上午下午超过一页时不会把“本页没有”错误写为“当天没有”。

## 4. 确认页与就诊人：双方都有，MedFlow 应补完整信息

**尚医通证据**：[booking.vue:28](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/hospital/booking.vue:28) 是独立“确认挂号信息”；37–54 行选就诊人或去新增；94–113 行完整展示日期/星期/半日、医院、科室、医生、职称、擅长、费用；127 行是就诊人手机；205–221 行防止空患者和重复点击后提交。

**MedFlow 证据**：[app.js:645](E:/code/java/MedFlow/src/main/resources/static/assets/app.js:645) 打开预约弹窗；662–663 行医生、科室、日期时间只有一行 subtitle；665–676 行是家人/本人单选和脱敏手机；681–684 行主要内容仍为“号源 ID / 余号 / 预约截止”。728–745 行复用幂等键并读取创建后的当前状态，网络结果不明可以安全重试。

**比较与选择**：尚医通的确认信息层次更完整；MedFlow 的脱敏信息、明确家人关系、网络不明处理更好。采用完整摘要，但不用为了它引入 Vue：在原弹窗中展示“就诊人、医生/职称、科室/诊室、日期/具体时段、预约截止、患者自行确认说明”，把内部 ID 降为次要信息。没有挂号费业务时不要显示虚构金额或把药费放在挂号费栏。

**轻量验收**：为本人/家人各提交一次，摘要、成功后记录及医生预约名单指向同一个 patientId；提交中不可改人；模拟断网后同一意图重试只得到一条预约；未建档明确提供去创建入口。保留当前后台幂等设计，不能只学按钮变“正在提交”。

## 5. 挂号成功后到院凭证：尚医通的信息连续性更好

**尚医通证据**：[order/show.vue:51](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/order/show.vue:51) 到 76 行包含患者、日期、医院、科室、职称、费用、挂号单号与挂号时间；83–85 行强调本人信息、建议取号时间和退号截止。

**MedFlow 证据**：[app.js:770](E:/code/java/MedFlow/src/main/resources/static/assets/app.js:770) 的成功页只显示预约编号、状态、确认截止、取消截止；[app.js:1084](E:/code/java/MedFlow/src/main/resources/static/assets/app.js:1084) 的“预约详情”仍以就诊人 ID/号源 ID 为主。另一方面，[clinical.js:346](E:/code/java/MedFlow/src/main/resources/static/assets/clinical.js:346) 已经能显示就诊人、医生、科室、诊室、日期时段，355–357 行也有真实签到规则与按钮。

**比较与选择**：这是 MedFlow 最适合立即改的低复杂度问题。把成功摘要与预约详情统一成“本次预约凭证”，复用已有 visitInfo，不重新造后台状态机。卡片显示医生、诊室、时间、患者及确认/取消/签到条件。不要生造“预约号序等于候诊号”，MedFlow 当前队列按时段及签到时间排序；也不复制外院窗口地址。

**轻量验收**：从提交成功、预约列表、预约详情、就诊与病历四个入口看到同一患者/医生/诊室/时间；未确认显示自行确认，未到时间显示可签到窗口，签到成功显示待就诊；刷新不丢凭证；页面没有“请到一层114窗口”之类并不存在的设施提示。

## 6. 预约详情的状态、取消、支付：应保持状态职责清楚

**尚医通证据**：[order/index.vue:30](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/order/index.vue:30) 按就诊人/订单状态筛选，62–95 行是日期、医院、科室、费用及订单状态；[order/show.vue:90](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/order/show.vue:90) 订单 0/1 显示取消，0 显示支付；160–172 行取消确认；176–206 行打开微信码并每三秒查支付状态。

**MedFlow 证据**：[app.js:957](E:/code/java/MedFlow/src/main/resources/static/assets/app.js:957) 至 975 行列表显示日期/医生/诊室/患者、状态并按截止显示确认/取消；[clinical.js:331](E:/code/java/MedFlow/src/main/resources/static/assets/clinical.js:331) 开签到与就诊结果；[business.js:322](E:/code/java/MedFlow/src/main/resources/static/assets/business.js:322) 显示已提交处方，[business.js:353](E:/code/java/MedFlow/src/main/resources/static/assets/business.js:353) 至 376 行模拟药费登记，显式告知无实际扣款。

**比较与选择**：尚医通是挂号费支付，MedFlow 是诊后处方费用，不是缺一张二维码就不完整。保留三个独立维度：预约确认/取消、接诊待诊/进行/完成、处方账单未付/已付。在预约凭证中可链接本次处方与账单，但不要“付款后预约状态变成已完成”或“医生完成后自动已缴费”。用户已决定患者自行确认、签到，不增加管理员审批。

**轻量验收**：取消前后余量一致回收；签到后不再允许取消；医生完成而账单未付应显示“接诊完成/待缴费”；模拟缴费后仍保留本次病历完成时间，不重开接诊。不做真实微信支付、退款通道与轮询服务。

**尚医通前端缺点**：取消动作的 catch 把网络/接口失败也提示成“已取消取消预约”（show.vue:171–172），不宜学；扫码轮询并不适合本项目的模拟付款场景。

## 7. 患者历史、医生就诊和复诊：保留 MedFlow 已有实现

**尚医通证据**：已扫描的本地患者页面和 API 文本覆盖医院预约、就诊人管理、订单支付取消，未见接诊病历、药品处方、院内纵向病历或复诊预约页面；不能把“就诊人管理”当作院内病历。

**MedFlow 证据**：[patient-history.js:60](E:/code/java/MedFlow/src/main/resources/static/assets/patient-history.js:60) 开同一就诊人的跨医生档案；93–108 行按次显示医生、诊断摘要、处方及账单；127–140 行只展示已完成病历和已提交处方并分别显示预约/接诊时间线；[clinical.js:186](E:/code/java/MedFlow/src/main/resources/static/assets/clinical.js:186) 是候诊队列；241–293 行保存草稿、开始/完成接诊及处方入口。

**比较与选择**：MedFlow 在该业务目标上范围更完整，无需退回尚医通的挂号平台边界。复诊可轻量新增“基于这次已完成就诊，再次预约”的入口，预选相同患者、同一医生/科室，创建新的 Appointment 和 Encounter，并关联原就诊；不得复制旧诊断当新诊断，不重新打开旧病历。这是本项目后续设计建议，本地尚医通没有对应可照搬实现。

**轻量验收**：从一条已完成记录发起复诊，选新日期，生成新预约ID；医生看到前次记录为只读，新病历为空；完成第二次后患者历史显示两次且费用独立。未完成预约不应出现“复诊”动作。

## 8. 页面复杂度与错误处理：保留本项目已有保护

**尚医通证据**：[schedule.vue:190](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site/pages/hospital/schedule.vue:190) 和 215 行分别异步拉日期与某日医生，没有在这段体现快速切换后的请求序号保护；页面依靠路由重新加载切换确认/订单步骤。

**MedFlow 证据**：[booking.js:31](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:31) 有目录/时段票据，[booking.js:258](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:258) 校验页面仍存活、医生和日期仍一致；[app.js:737](E:/code/java/MedFlow/src/main/resources/static/assets/app.js:737) 区分创建成功与最新状态读取失败；[app.js:997](E:/code/java/MedFlow/src/main/resources/static/assets/app.js:997) 摘要加载失败不会假称预约为空。

**比较与选择**：借鉴尚医通的业务顺序与展示信息，保持 MedFlow 的异步竞态、防重、错误边界和数据权限，不为页面更像课程项目换整个前端技术栈。

**轻量验收**：快速切换两个医生/日期，后返回的旧请求不得覆盖当前结果；创建已成功而刷新失败仍展示成功结果；接口失败给可重试提示，不写“暂无号源/暂无预约”。

## 推荐落地顺序

1. **先补预约凭证和完整确认摘要**：复用 visitInfo，统一现在分散的“详情/就诊与病历”信息；业务变化小，患者立刻看懂。
2. **再补日期余量摘要与科室日期优先入口**：单个数据库聚合读接口，帮助用户找得到可挂的日子和医生。
3. **最后做轻量复诊关联**：新的预约关联已完成 Encounter，保留原病历不可改。
4. **临床分类按真实科室数量需要增加**：当前少量科室不必抢先引入复杂树权限和多医院模型。

以上是源码研读结论和候选后续任务，尚未实施；实现前需与本项目现行时间规则、身份权限和状态转换逐一核对。

