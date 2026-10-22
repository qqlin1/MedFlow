# 真实尚医通源码：支付、取号、接诊、复诊与 MedFlow 对照

日期：2026-10-21。只读取本次解压的源文件，没有运行、编译或导入尚医通程序/SQL，没有修改 MedFlow 业务代码。

## 一、检查对象和结论边界

尚医通根目录：`E:\BaiduNetdiskDownload\尚医通\尚医通\解压_真实源码\代码\02-尚医通后端代码\yygh_parent`。

下列 `Y/` 均指该根目录，`M/` 指 `E:\code\java\MedFlow\`。核心方法逐行阅读；再以 `rg` 在全后端 Java/XML 源文件搜索（排除所有 `target/`，共 210 个 Java/XML 文件）确认取号和临床模块边界。本报告只对这份本地源码负责，不断言课程其他版本、未提供的 HIS 或视频一定没有实现。

**这份尚医通是预约挂号平台示例，支付的是挂号订单中的“医事服务费”；并非完整医院临床系统。** 它实现了下单、对接医院预约、获取预约序号/取号指引、微信主动查单后更新支付、取消与退款；没有找到医生接诊、病历、药品处方、复诊关系、诊疗完成模块。

## 二、沿一次挂号订单跟踪到支付

1. `Y/service/service_order/src/main/java/com/atguigu/yygh/order/api/OrderApiController.java:30` 的 `savaOrders(scheduleId,patientId)` 调用 `OrderServiceImpl.saveOrder()`（同模块 `service/impl/OrderServiceImpl.java:52`）。
2. `saveOrder:54` 获取就诊人，`:57` 获取排班，`:60` 判断预约时间，`:69–81` 复制排班/就诊人信息并建 `UNPAID` 订单。
3. `saveOrder:91` 传给医院的是订单 `amount`；模型 `Y/model/src/main/java/com/atguigu/yygh/model/order/OrderInfo.java:97–99` 明确注释为“医事服务费”。它不是医生处方药费。
4. `saveOrder:115` 调 `/order/submitOrder`，医院返回 `hosRecordId、number、fetchTime、fetchAddress`，`:119–132` 存回平台订单。
5. `Y/service/service_order/src/main/java/com/atguigu/yygh/order/api/WeixinController.java:25` 生成二维码，委托 `service/impl/WeixinServiceImpl.createNative:44`。
6. `createNative:52–54` 查订单并创建支付记录；`:64` 注释掉真实金额乘 100，`:65` 为测试固定 `total_fee=1`。`:70–85` 调微信统一下单并返回二维码 URL。**不能把它当成已验证可直接上线的真实支付方案。**
7. `WeixinController.queryPayStatus:32–44` 主动调用微信查询；响应 `trade_state=SUCCESS` 才调用 `PaymentServiceImpl.paySuccess(out_trade_no,resultMap)`。
8. `Y/service/service_order/src/main/java/com/atguigu/yygh/order/service/impl/PaymentServiceImpl.java:62` 的 `paySuccess` 在 `:70–74` 记录支付状态、外部交易号、时间和内容；`:78–80` 更新挂号订单为 PAID；`:83–90` 通知医院 `/order/updatePayStatus`。

支付记录与订单分开是值得学的设计：挂号业务单据和渠道交易凭证不是同一个对象。原源码 `PaymentInfo.java:27–61` 有业务交易号、订单 ID、支付类型、渠道交易号、金额、状态和回调内容。但本地代码实际由主动查单调用 `paySuccess`，字段名 `callbackTime` 不等于已经实现异步回调。

## 三、两种“确认”不能混为一谈

### 3.1 真实取号/签到回调：本地未找到实现

- `Y/model/src/main/java/com/atguigu/yygh/enums/OrderStatusEnum.java:9–12` 定义 UNPAID、PAID、GET_NUMBER、CANCLE。
- **GET_NUMBER 的全源码搜索只命中枚举定义，未找到任何赋值、状态转换或确认取号接口。**
- `OrderInfo.java:89–95` 的 fetchTime/fetchAddress 是取号指引；`OrderServiceImpl.saveOrder:123–131` 保存这些指引，不会把订单变成已取号。
- `Y/hospital-manage/src/main/java/com/atguigu/hospital/controller/HospitalController.java:42、:62、:82` 暴露的业务入口为下单、更新支付、更新取消，没有取号确认端点。
- `Y/hospital-manage/src/main/java/com/atguigu/hospital/service/impl/HospitalServiceImpl.java:93、:126、:141` 仅设置 0、1、-1，没有设为 2。`HospitalService.java:13–25` 的接口合同也仅有下单、支付、取消。

因此，不能向用户汇报“尚医通已贯通取号→医生就诊”。这份源码只保留了已取号状态和指引字段，后段实际闭环缺失。MedFlow `AppointmentService.confirm()` 是患者确认预留，不是这里 GET_NUMBER；MedFlow 的 `EncounterService.checkIn()` 才是到院签到。

### 3.2 微信异步支付回调：配置了地址，本地未找到处理器

- `WeixinServiceImpl.java:67` 设置一个 `notify_url`，但全后端 Java/XML 搜索 `weixinNotify` 仅此一处命中。
- 本地 `WeixinController.java:24–47` 仅提供创建二维码、主动查询支付状态。
- `PaymentServiceImpl.paySuccess()` 虽然保存“回调”字段，却被主动查询控制器调用。

准确表述是：**有微信下单和主动查单更新状态示例；未找到该通知 URL 的本地回调实现，也没有在本轮验证任何外部支付渠道。** 不需要为 MedFlow 学习业务而接微信、证书、外网回调或 Redis 支付二维码缓存。

## 四、两侧具体方法 → 取舍 → 后续验收

| 业务点 | 尚医通具体实现与优缺点 | MedFlow 具体实现 | 取舍和适用场景 | 后续可点验/故障验收 |
| --- | --- | --- | --- | --- |
| 费用对象 | `OrderServiceImpl.saveOrder:69–91` 保存预约金额；模型 `OrderInfo:97` 为医事服务费。预约平台需要提前明确挂号成本 | `M/src/main/java/com/qqlin/medflow/billing/service/PrescriptionService.java:54–63` 按药品与数量生成药费；`:94` 处方提交出账 | 借鉴“费用是什么、何时产生”解释；不把现有药费账单改名挂号费。若后续做挂号费，用独立费用类型/单据，仍可模拟支付 | 未开处方时不应出现药费；挂号费若未实现应明确说明；价格与数量由后台生成 |
| 付款凭证独立 | `PaymentServiceImpl.savePaymentInfo:38–57` 独立支付记录；`paySuccess:62–90` 记录交易号并同步挂号单/医院。适用于外部支付渠道 | `PrescriptionService.pay:135–144` 锁本人账单，改 PAID，插 mock payment；`M/src/main/resources/db/migration/V12__prescription_and_billing.sql:60–68` 一账单一模拟支付记录 | 保留 MedFlow 的单体事务和模拟支付；借鉴“账单是应收、支付记录是实付凭证”，不搬支付 SDK/证书/回调栈 | 连点/重复支付只有一笔记录；其他账号不能支付；出错不能只改账单不落凭证 |
| 支付幂等与取消竞争 | `paySuccess:62–90` 未见已支付短路/条件状态更新及本方法事务；重复调用会再次更新并通知医院。`savePaymentInfo:43–45` 是先查后插，本方法不能独自证明并发唯一 | `PrescriptionService.pay:137–143` 行锁+PAID 短路+UNPAID 条件更新；SQL `BillingMapper.xml:78–89` 归属过滤/锁/原子状态更新 | 保留 MedFlow 边界；不复制“查到微信 SUCCESS 就无条件覆盖本地业务状态”的写法。真实支付晚到与取消竞争若未来实现再单独设计 | 两并发支付只成功记一次；重试不多记；未来取消/退款必须定义迟到通知如何处理 |
| 取消与退款 | `OrderServiceImpl.cancelOrder:213–266` 查 quitTime、调用医院取消、PAID 时退款，然后改 CANCLE。当前只有 PAID 分支改平台状态；UNPAID 分支可返回 true 却未改平台状态，不能照抄 | `M/src/main/java/com/qqlin/medflow/appointment/service/AppointmentService.java:313–375` 锁号源→预约，签到后拒绝；待确认/已确认取消退容量；已取消重试无副作用 | 借鉴“退号截止和已缴费退款要讲清”；保留当前真实占号/取消事务；不为学习额外开真实退款链 | 截止前取消释放一次名额；截止后失败；签到后失败；重复取消不重复释放；未来挂号收费才设计退款 |
| 取号指引与到院签到 | `OrderServiceImpl.saveOrder:119–131` 保存医院预约序号、建议取号时间和地点；GET_NUMBER 仅枚举，没找到执行链 | `M/src/main/java/com/qqlin/medflow/encounter/service/EncounterService.java:29–54` 当天窗口内本人签到创建 WAITING，`V9:18` 每预约一接诊 | 借鉴患者可见的“何时、到哪、下一步”；保留可验证的患者自行签到，不用未实现 GET_NUMBER 代替签到 | 未确认、前一天、过时段均不可签；合法窗口一次签到；重复不建第二条接诊；列表明确诊室和时间 |
| 身份与对象归属 | `PatientApiController.findAll:25–28`、`savePatient:34–38` 绑定当前用户是正确起点。但 `getPatient/update/remove:44–59` 仅按 ID；`OrderApiController.list:50` 虽设 userId，`OrderServiceImpl.selectPage:173–203` 没用它过滤；`getOrder:166–168`、`cancelOrder:213–215` 也只按 ID。网关 `AuthGlobalFilter:48–53` 只判登录，不保证对象属于本人 | `M/src/main/java/com/qqlin/medflow/patient/service/PatientService.java:70–85` owner+patientId；`AppointmentService.create:159–164` 明确校验；`PrescriptionService.pay:137` 及 SQL `BillingMapper.xml:78–83` 限本人；历史精确 patientId | 保留 MedFlow 对象归属。只学 user 与 patient 分离，不复制“带 auth 路径就是安全”的误解。此为本地源码审查结论，不是对运行外部系统做攻击 | A 登录猜 B 的 patient/appointment/bill ID 应返回不可见；同登录账号不同家属历史也不能串错 |
| 医生接诊与临床记录 | 全后端 Java/XML 没找到 Encounter、病历、诊断或 Prescription 模块；`OrderStatusEnum` 也没有接诊中/完成 | `EncounterService.start:86–103`、`saveMedicalRecord:117–131`、`complete:135–150`；同医生一名就诊中，主诉/诊断必填，版本校验，未处理处方草稿阻止完成 | 保留 MedFlow 已实现的临床链；不能因参考项目没做，就削成付款后“订单完成” | 医生只能处理自己患者；未开始不能写病历；双窗口旧版本拒绝；留草稿不能完成；患者看不到未完成病历 |
| 接诊完成与财务完成 | 本地模型只到已取号概念；不含临床完成。支付业务不能证明诊疗结束 | `EncounterService.complete:141–149` 检查处方非 DRAFT 和病历；不要求账单 PAID；`PrescriptionService.pay` 也不要求 encounter COMPLETED | 保留预约/接诊/处方/账单状态分离，用只读进度解释组合。不要强迫医师等待患者付药费才能记录已完成接诊 | ISSUED+UNPAID 时医生可完成；历史同时展示“接诊已完成/药费待支付”；付款后只改变账单 |
| 复诊与历史 | 全后端未找到复诊、follow-up、return-visit 关系/接口，普通 saveOrder 可再次下单，但不追溯前次就诊 | `M/src/main/java/com/qqlin/medflow/patient/history/PatientHistoryService.java:69–86` 聚合同患者历史；`PatientHistoryMapper.xml:8–17` 本医生预约锚点权限；当前也没有复诊关系 | “再次预约”能满足基础重复看病，但不是正式复诊模块。后续可轻量保存来源 encounterId/visitType，而不是照抄尚医通所谓复诊 | 从已完成记录发起新预约，只能同患者；仍走正常号源、确认、签到；新病历不能被旧病历覆盖或自动诊断 |
| 历史事实快照 | `OrderServiceImpl.saveOrder:71–79` 复制医院/科室/医生职称与患者姓名；`OrderInfo.java:40–79` 单据保留快照 | `PrescriptionService.save:59–63` 药品/价格快照已做；`M/src/main/resources/mapper/patienthistory/PatientHistoryMapper.xml:20–32` 医生/科室/诊室目前实时 JOIN | 借鉴单据必要历史快照，适合后续改名/转科仍保留过去展示。先定义是预约时还是接诊完成时冻结，不引入更多中间件 | 改药价不改旧账单；若补临床快照，医生转科后旧记录保留就诊当时科室；新预约显示新资料 |

`BillingMapper.xml` 完整路径为 `M/src/main/resources/mapper/billing/BillingMapper.xml`；`V9` 为 `M/src/main/resources/db/migration/V9__create_encounter_tables.sql`。表内尚医通 Java 简名均在前文明确的 order/user/model 路径下。

## 五、对“取号”和医院端真实性还需知道的边界

`Y/hospital-manage/src/main/java/com/atguigu/hospital/service/impl/HospitalServiceImpl.java` 虽是实际源文件，但仍有教学简化：

- `:59` 固定查排班 `"1L"`，`:85` 固定 `scheduleId=1L`。
- `savePatient:154–156` 写“业务：略”，固定返回患者 ID 1。
- `:89–105` 取号时间/地点是固定演示值，且保存值与返回值不完全相同。
- `:77–80` 读余量减一再更新，不能因有 `@Transactional` 就认定并发不超卖；且 `availableNumber > 0` 才成功会拒绝最后一个剩余名额。

这不否定学习价值：平台与医院的业务边界、医院预约号回执、服务费、退号截止、预约单快照都有参考意义。**它不能作为真实 HIS、签到叫号或临床接诊实现的证据，也不应拿来覆盖 MedFlow 已有的并发/归属/状态保护。**

## 六、只学业务时的推荐路线

1. 先画清本院一条真实记录：预约预留→患者确认→窗口内签到→医生接诊→病历/可选处方→临床完成；药费账单支付独立展示；再一次预约是新记录。
2. 向尚医通学预约单“患者是谁、医生/科室、日期上午下午、费用是什么、退号截止、到院指引、订单回执”，不照搬微服务、消息队列和微信支付。
3. 保留 MedFlow 现有患者自行确认/签到、精确患者归属、四套状态、病历版本、处方价格快照和模拟支付。
4. 后续最小增强候选为：只读“门诊进度与下一步”、显式区分费用类别、轻量复诊来源关系、必要的历史展示快照。此轮不实施，先让用户用三角色手动复现并能解释每个状态。

复述验收问题：创建时为什么已经占号？患者确认与签到为何是两步？挂号服务费和药费分别何时发生？医生完成为何不强制等付药费？同一患者第二次预约和来源复诊有什么差异？医院改科室名后老记录应展示什么？这些问题比新增技术栈更能检验是否吃透设计。
