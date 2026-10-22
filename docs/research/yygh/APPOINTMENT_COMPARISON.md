# 真实本地尚医通与 MedFlow：预约、号源、取消、幂等及对象权限对照

日期：2026-10-21。范围：仅静态读取解压后的真实本地平台源码及当前 MedFlow，未启动、编译、运行参考系统，未改任何业务代码。以下风险是代码审阅结论，不是已完成攻击/并发压测的声明。

尚医通真实源码根（下称 YY）：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent`。

MedFlow 根（下称 MF）：`E:/code/java/MedFlow`。

## 0. 证据文件索引（绝对路径）

- YY-OrderController：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_order/src/main/java/com/atguigu/yygh/order/api/OrderApiController.java`
- YY-OrderService：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_order/src/main/java/com/atguigu/yygh/order/service/impl/OrderServiceImpl.java`
- YY-HospitalReceiver：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_hosp/src/main/java/com/atguigu/yygh/hosp/receiver/HospitalReceiver.java`
- YY-ScheduleService：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_hosp/src/main/java/com/atguigu/yygh/hosp/service/impl/ScheduleServiceImpl.java`
- YY-PatientController：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_user/src/main/java/com/atguigu/yygh/user/api/PatientApiController.java`
- YY-PatientService：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_user/src/main/java/com/atguigu/yygh/user/service/impl/PatientServiceImpl.java`
- YY-Gateway：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service_gateway/src/main/java/com/atguigu/yygh/gateway/filter/AuthGlobalFilter.java`
- YY-OrderInfo：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/model/src/main/java/com/atguigu/yygh/model/order/OrderInfo.java`
- YY-OrderStatus：`E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/model/src/main/java/com/atguigu/yygh/enums/OrderStatusEnum.java`
- MF-AppointmentController：`E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/appointment/controller/AppointmentController.java`
- MF-AppointmentService：`E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/appointment/service/AppointmentService.java`
- MF-AppointmentSQL：`E:/code/java/MedFlow/src/main/resources/mapper/appointment/AppointmentMapper.xml`
- MF-SlotSQL：`E:/code/java/MedFlow/src/main/resources/mapper/scheduling/SlotMapper.xml`
- MF-PatientService：`E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/patient/service/PatientService.java`
- MF-EncounterService：`E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/encounter/service/EncounterService.java`
- MF-VisitInfoMapper：`E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/appointment/query/AppointmentVisitInfoMapper.java`

索引后的“文件别名:行号”均对应本次真实本地文件，便于避免把模拟器、教案或公开其他版本的实现混进来。

## 1. 预约成功凭证与订单快照

**尚医通实际实现**：YY-OrderService `saveOrder():52-161`。先取就诊人和排班信息，`:69-81` 复制排班、患者名/手机、状态及交易号保存订单；`:115-132` 调院端成功后补院端记录号、预约序号、取号时间和地点。YY-OrderInfo `:36-104` 保留医院名、科室名、职称、日期时段、患者姓名/手机、医事服务费、退号截止、院端记录号及取号提示。

**MedFlow 实际实现**：MF-AppointmentService `create():152-256` 保存预约身份、slot、预约状态及两个截止时间；MF-VisitInfoMapper `findOwned():9-23` 与 `findOwnedBatch():25-43` 通过现有患者、医生、科室、诊室与排班 JOIN 展示可读摘要，同时返回 `can_check_in`。

**业务优劣/权衡**：尚医通订单快照有利于保留“当时挂的是哪个科室、当时报价、当时登记的患者信息”，后续改基础资料不会直接改旧订单展示。MedFlow 当前关联查询更简单，但 `p.name/d.name/dept.name/r.name` 是实时资料；医生改科室或人员改名会改变旧预约显示，这属于历史语义需要明确的地方。快照不意味着复制全部敏感字段；也不意味着冻结所有未来计划信息，已发预约的诊室变更应有明确通知。

**决策**：采用“可读预约凭证 + 下一步操作提示”的业务表达；建议下一阶段设计最小历史快照（医生/科室/诊室显示名、当时出诊时段），先明确变更规则再迁移。不采用跨医院记录号或多系统交易结构；MedFlow 单院使用自身预约 ID 已足够。本次未加快照表/字段。

**验收场景**：预约后看凭证应看到日期时段、医生、科室、诊室、确认/取消/签到截止及下一步；更改医生简介不影响旧就诊资料；如未来改名/转科，验证旧记录按约定展示历史信息而不是悄悄漂移。

## 2. 下单事务边界与远程调用

**尚医通实际实现**：YY-OrderService `saveOrder():52-161`，`:81` 先插入平台订单，`:115` HTTP 请求院端，`:132` 回写预约凭证，`:157` 发送号源/短信消息。类/方法上没有 `@Transactional`，没有下单失败删除/回滚该订单、远程成功后本地失败补偿的代码。`:159` 抛错并不能自动撤销已提交平台插入，更不能撤销院端预约。

**MedFlow 实际实现**：MF-AppointmentService `create():152-256` 使用一个本地事务包含幂等记录、slot 扣减、预约写入和状态日志；`:225-230` 唯一键失败抛出业务异常离开事务；`:246-253` 幂等成功更新失败同样让整笔回滚。业务链没有院端 HTTP，不需要引入跨服务事务。

**业务优劣/权衡**：尚医通面对“平台与各医院独立系统”的真实边界，设计这个接口边界是合理的；这份教学实现的失败恢复不足，不适合复制。MedFlow 单院单库用本地事务更符合当前范围，便于解释和复现。

**决策**：保留 MedFlow 单体/单库本地事务；不采用院端 Feign/HTTP、MQ、分布式事务或网关拆分。

**验收场景**：在预约插入/幂等成功保存阶段制造异常，应没有预约、没有新增成功日志且余量复原；HTTP 失败重试不应多扣一个名额。此处列出应学习/保留的验收要求，本次未重新执行故障测试。

## 3. 号源最终扣减与余量展示

**尚医通实际实现**：YY-OrderService `:134-157` 使用院端成功响应中的 `reservedNumber/availableNumber` 发消息；YY-HospitalReceiver `receiver():34-40` 以排班 ID 找平台排班，再覆盖总量/余量并保存。平台不是在自己的订单 Service 中进行原子号源扣减，它依赖院端裁决。

**MedFlow 实际实现**：MF-AppointmentService `:180-205` 锁 slot 与父排班后读取数据库时间、校验状态/截止、查重复、条件扣减。MF-SlotSQL `lockBookingSnapshot():50-64` 是 slot 独占锁、schedule 共享锁；`decreaseRemainingCapacityIfBookable():73-80` 执行 `remaining_capacity=remaining_capacity-1`，要求原余量 `>0`、slot OPEN、截止未过。`:90-95` 回补不能超过总量。

**业务优劣/权衡**：外部医院权威库存与平台展示库存分开是尚医通的业务场景；带快照的异步覆盖可能受消息乱序影响，receiver 代码没有版本号检查。MedFlow 同库即时变更避免两份库存，当前规则直接允许最后 1 个名额变成 0。

**决策**：保留 MedFlow 的单一库存真相和条件更新，不采用 MQ 同步余量。模拟器“余量减一后必须 >0”的最后一号缺陷只属于此前分析的医院 mock，**不得外推为真实平台订单 Service 的逻辑**。

**验收场景**：余量为 1 时两个不同患者竞争，只能一人成功，最终余量 0；患者点击前列表有号、提交时被抢完，应显示明确售罄；停诊/关号与下单竞争应有明确胜者且不超卖；不可只看页面余量证明安全。

## 4. 下单幂等与重复预约

**尚医通实际实现**：YY-OrderController `savaOrders():30-34` 仅 scheduleId、patientId；YY-OrderService `:73-81` 每次用毫秒值加两位随机数生成交易号并 insert。该方法没有读取“同一业务请求”记录，没有患者-排班已有有效预约查询，也没有锁定返回已成功结果的分支。随机交易号不是请求幂等机制。

**MedFlow 实际实现**：MF-AppointmentController `create():111-135` 强制 `Idempotency-Key` 并从 JWT 提取用户；MF-AppointmentService `:157-175` 校验患者归属、计算请求 hash、获得幂等记录，成功重放返回原结果；`acquireIdempotencyRecord():458-498` 锁唯一键，hash 不同报 key 复用，同 hash 成功返回原 result；`:192-199` 防有效重复预约。`src/main/resources/db/migration/V7__create_appointment_idempotency_and_history_tables.sql:38-63` 有 user+operation+key 唯一约束；`V1__create_scheduling_core_tables.sql:88-101` 有患者+slot+active_flag 唯一约束。

**业务优劣/权衡**：MedFlow多一张幂等表，但这是对应患者重复点击、网络超时重试的真实业务风险；与不必要技术栈无关。幂等重放的是创建当时结果，前端仍需读取当前状态，不能把旧的待确认响应当作当前状态。

**决策**：保留现有 MedFlow 幂等设计，不照搬尚医通随机交易号作为防重方案。

**验收场景**：相同用户相同 key/相同请求重复提交，得到同一预约 ID，仅扣 1；相同 key 换 patient/slot 报冲突；不同 key 同患者同 slot 报有效重复；同 key 不同用户各自隔离；已取消后重放创建响应不能覆盖当前取消状态。

## 5. 预约确认、签到和状态划分

**尚医通实际实现**：YY-OrderStatus `:8-13` 定义 `UNPAID(0)/PAID(1)/GET_NUMBER(2)/CANCLE(-1)`。YY-OrderService `saveOrder():80` 创建未支付，取消方法会写取消；支付服务另写已支付。对真实后端根排除 target 全文搜索 `confirmGetNumber`、`GET_NUMBER`、`getNumber`、`取号`，未找到 `confirmGetNumber` 方法或写 `GET_NUMBER` 的业务动作；唯一 GET_NUMBER 命中是枚举第 11 行。取号提示写回不等于签到动作。不能将其他版本/教案中的接口当作本地已实现。

**MedFlow 实际实现**：`src/main/java/com/qqlin/medflow/appointment/domain/AppointmentStatus.java:3-11` 把待确认、已预约、取消、超时放在 Appointment；MF-AppointmentService `confirm():264-304` 患者自己的预约加锁，已 BOOKED 幂等返回，其他终态拒绝，数据库当前时间检验确认截止，确认不再扣号。MF-EncounterService `checkIn():28-55` 患者签到，锁 slot→appointment、检验归属、只允许 BOOKED、当日、开始前30分钟至时段结束前；重复签到返回原 encounter，无额外扣号。

**业务优劣/权衡**：尚医通以预约挂号收费为主，将支付与取号放在预约订单状态中；本地并未呈现完整签到实现。MedFlow 已包含院内接诊，拆开 Appointment/Encounter/账单更适合当前业务。患者自己确认、自己签到是已接受的项目规则，不改成管理员审批。

**决策**：保留 MedFlow 患者自确认、自签到、不同状态机；不为了模仿尚医通增加挂号费支付前置，也不把 GET_NUMBER 枚举当作要复制的签到逻辑。

**验收场景**：确认重试不扣名额；超时不能确认；不能提前一天签到；开始前30分钟边界按统一规则；同预约多次签到只一个 encounter；签到与取消并发只能一方先成功，签到后取消提示已签到，取消后不能签到。

## 6. 取消、退款与库存回补

**尚医通实际实现**：YY-OrderController `cancelOrder():64-67` 用 GET 改状态；YY-OrderService `cancelOrder():213-267` 先查本地订单、检验退号截止，`:233-234` 通知院端取消；仅 `:240` 订单已支付分支才调用退款，`:246-247` 写本地 CANCEL，`:249-263` 发取消消息。未支付分支会在院端取消成功后直接返回 true，但不更新本地状态/不发回补消息。YY-HospitalReceiver `:41-46` 以“消息 availableNumber 为空”推断取消，直接余量+1；未见事件 ID 去重或容量上限条件。院端已取消但退款失败时本地仍可能保持原状态，此方法也无补偿分支。

**MedFlow 实际实现**：MF-AppointmentController `cancel():155-169` 使用 POST 和 JWT 当前用户；MF-AppointmentService `cancel():312-375`先归属查询，已取消幂等返回；slot→appointment 加锁，`:344-346` 拒绝已有 encounter；允许待确认/已预约且截止前，`:368-373` 回补一次并写状态日志，全部同事务。MF-AppointmentSQL `markCancelledIfActiveBeforeDeadline():313-322`含归属、有效状态和数据库截止条件。MF-SlotSQL `:90-95` 限制余量不超总量。`expirePending():383-423` 相同锁序，只有成功超时迁移者回补。

**业务优劣/权衡**：尚医通有真实退款场景值得理解，但实现复杂边界不完整；MedFlow当前取消预约和处方账单是独立概念，不必引入挂号费退款。不要把“取消成功”与“钱已退到账”合成一个无法表达中间失败的布尔值。

**决策**：保留本地原子取消/超时释放，不采用未付/已付混杂单分支、消息空字段判断取消、无上限+1；真实支付退款继续留在范围外。

**验收场景**：连续点击取消仅回补一次；取消和过期扫描竞争仅回补一次；已确认取消也正确回补；已签到不取消；超过取消截止不取消；回补后不超过总量；终态不得被旧请求重新激活。

## 7. 登录身份、患者归属和订单对象权限

**尚医通实际实现**：YY-Gateway `filter():41-55` 阻止外部 inner 路由，auth 路由检查 token 可解析用户；这是登录校验，不是对象归属校验。YY-OrderController `savaOrders():30-34` 未传当前用户，仅任意 patientId；YY-OrderService `:54` 直接内部取该患者，`:76` 用患者自身 owner 写订单，未核对调用者。YY-PatientController `getPatientOrder():64-67` → YY-PatientService `getPatientId():40-41` 仅按 id 取，未限制 owner。YY-OrderController详情 `:38-41`、取消`:64-67` 未传当前 user；Service详情`:166-168`、取消`:215` 仅 selectById。

还有一个容易漏的点：YY-OrderController `list():50` 虽 `orderQueryVo.setUserId(当前用户)`，但 YY-OrderService `selectPage():173-203` 只拼医院、patientId、状态、日期等条件，**没有把 userId 加入 QueryWrapper**。因此不能把“Controller 设置过 userId”当作完成隔离。结论限定当前本地实现，未经动态利用验证。

**MedFlow 实际实现**：`src/main/java/com/qqlin/medflow/identity/security/SecurityConfig.java:63-75` 按 admin/doctor/patient 路由分角色；MF-AppointmentController `:52-85/:111-169` 每次传 JWT user。MF-PatientService `getOwnedPatient():70-86`按 id+owner 查询，外人和不存在都返回同类404。MF-AppointmentSQL `findOwnedById():122-135`、`lockOwnedByIdForUpdate():103-120`、`findPageOwnedByUser():145-158` 在SQL中 JOIN patient 并限制 owner；确认/取消 SQL 也有 owner。MF-VisitInfoMapper `:21/:38` 批量摘要同样带 owner。

**业务优劣/权衡**：MedFlow当前对象级校验更完整，应作为自己项目亮点讲清楚：JWT 用户、就诊人ID、医生业务ID是不同概念，角色正确仍需校验资源归属。列表、详情、写动作、批量摘要都需要一致隔离。

**决策**：保留 MedFlow 对象权限实现，不复制尚医通“只在网关确认登录”；课堂代码也需要审阅，不因课程权威而放松验证。

**验收场景**：患者 A 用患者 B 的 patientId 下单应404；读取/确认/取消 B预约应404；A列表不能因漏 WHERE 显示 B；批量摘要混入 B ID时省略 B；管理员不应仅靠自己登录就调用患者业务路由；doctor账号不能改别的doctor encounter。

## 8. 日历可约状态与服务端规则

**尚医通实际实现**：YY-ScheduleService `getBookingScheduleRule():183-280`读取医院规则，生成日期列表并汇总科室总量/余量，区分无排班（余量-1）、正常、即将放号、当天停止挂号；`:263-278` 返回科室名、年月、放号/停号时间。`getListDate():349-379` 按预约周期生成日期，最多每页7天。`getScheduleOrderVo():292-338` 计算退号截止、预约开始/结束时间；YY-OrderService `:59-63` 检查返回的预约起止时间。

**MedFlow 实际实现**：MF-AppointmentService `assertBookable():515-536` 同时检验排班 PUBLISHED、slot OPEN、截止、余量；MF-SlotSQL `:73-80` 再用条件更新兜底。MF-VisitInfoMapper `:13-15/:30-32` 计算当前签到按钮可用性，但真正写入仍由 EncounterService 服务端再次判断。

**业务优劣/权衡**：值得学习尚医通把“日期是否放号、无排班、已满、停挂”显示给患者，而不让所有情况都退化为一个空列表。规则展示必须和实际POST校验同源。尚医通本地 `getScheduleOrderVo():335-337`计算 stopTime 后未使用，且开始/结束的日期按“当前日期/当前+cycle”而非具体排班日期，不能仅因看见时间校验就断言所有边界正确。

**决策**：采用日期卡片的清晰业务状态和统一预约须知；当前单院先保留固定截止规则，不增加医院级多套规则引擎，不需要 MongoDB、Nginx或微服务。

**验收场景**：日期无排班/有排班已满/当天过挂号截止/暂未开放应有不同可理解提示；页面显示可约但提交前规则变化时，服务端拒绝并引导刷新；截止瞬间以数据库事务内时间为准。

## 最终采用清单

1. **保留**：MedFlow 单体、单一 MySQL 库、患者自行确认/签到、Appointment/Encounter/账单分离、本地事务、SQL 条件扣量、幂等、对象权限与日志。
2. **采用业务表达**：清晰预约凭证、到院下一步、日期无号原因、规则显式展示；最小历史快照可进入后续需求评审。
3. **不采用**：多医院同步、Feign/MQ/网关/微服务、挂号费真实支付退款、教学源码里的状态覆盖/无去重+1/不校验归属。
4. **没有事实依据的内容不能记为实现**：本地代码未找到 confirmGetNumber；GET_NUMBER枚举和取号提示不能证明签到；医院mock最后1号缺陷不外推平台。

