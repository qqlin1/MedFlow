# MedFlow 门诊预约与接诊：两天交付需求

日期：2026-10-19。本文是本轮新增业务的执行基线，覆盖旧需求中将医生接诊与简版病历排除在外的范围约定。用户已授权直接实现后端、前端和验证；已有预约正确性机制继续保留。

> 2026-10-20 增量覆盖说明：本文保留10-19需求快照。原“药品、处方与模拟收费、统计图后续再做”的范围限制，已由 [处方与统计需求](PRESCRIPTION_STATISTICS_REQUIREMENTS.md) 覆盖；当前状态见 [业务台账](BUSINESS_PROGRESS.md)，新增验证见 [处方与统计交付报告](PRESCRIPTION_STATISTICS_DELIVERY.md)。原接诊、权限和容量规则继续适用；真实支付、库存、医保、通知改约、补叫和Redis/MQ仍暂缓。

## 目标与日程

第一天：完成医生账号开通、医生数据权限、服务端排班目录/详情、有界批量排班、受限取消出诊、签到和接诊状态机的后端与测试。

第二天：完成病历保存与完成、患者结果查询、三角色页面联调、真实 MySQL 并发验收、构建与演示文档。两天是交付和学习安排；代码编写、验证现在开始连续执行，不依赖定时唤醒。

交付主线：管理员开通医生并发布排班 → 患者分步预约确认 → 当日签到 → 医生看到本人队列并接诊 → 保存病历并完成 → 患者查看结果。

## 角色与功能

| 角色 | 本轮能力 |
| --- | --- |
| 管理员 | 创建医生账号及档案；按日期/科室/医生/状态分页查询排班；查看时段及余量；最多7日期的原子批量草稿创建；发布；无有效预约时取消出诊 |
| 医生 | 当前身份映射到医生档案；仅查询本人排班、预约与接诊；开始/过号；保存病历；完成接诊 |
| 患者 | 读取可预约科室与医生目录；按科室/医生/日期选号；查询真实预约展示信息；当日签到；查看本人就诊人已完成病历 |

沿用现有 JWT 三角色门禁、账号状态校验和患者 owner_user_id 归属。医生身份只能从 JWT uid 映射；请求参数不得指定操作身份。管理员资源权限不授予病历正文读取权限。

## 业务规则

1. 医生开通：用户名、初始密码、姓名、科室；BCrypt；角色固定DOCTOR；账号和医生档案同事务，重复用户名全部回滚。不改动已有绑定账号接口。
2. 排班目录必须查询数据库全部状态，不能再以浏览器缓存充当目录。详情含真实医生、科室、诊室、时段、容量和有效预约数。
3. 批量：同一医生/诊室/班次，1至7个不重复未来日期；统一事务，任一日期冲突全部回滚；冲突信息指出日期。客户端重试应先查目录确认结果，不宣称批量接口具备请求结果重放。
4. 受限取消出诊：只允许开诊前，PENDING_CONFIRMATION与BOOKED任一存在即409；不自动取消已有预约。重复取消成功。取消与预约必须在统一锁协议下复核状态。取消操作记录原因、操作人、时间。暂停新预约但继续接诊属于后续能力，不把它混同取消出诊。
5. 签到：只允许本人已确认预约，且数据库时间处于就诊当日、预约开始前30分钟至该时段结束前。签到时间判断在获得锁后读取数据库时间。重复签到返回原接诊；每预约最多一条接诊。
6. 签到与原取消流程统一以Slot→Appointment锁顺序串行；有接诊记录后患者不能取消预约。完成、过号均不归还号源容量。预约BOOKED保留预订成功事实，接诊状态独立。
7. 接诊：WAITING→IN_PROGRESS→COMPLETED；WAITING→SKIPPED。本版本过号为终态，不做插队/补叫。队列按时段开始、签到时间、ID排序；仅当时段已开始才能开始接诊。一名医生同时最多一个IN_PROGRESS；数据库约束与事务保证并发安全。
8. 病历：主诉chiefComplaint、现病史presentIllness、诊断diagnosis、医嘱advice；只在本人IN_PROGRESS接诊下保存。初始version=0，保存返回递增版本；旧版本返回409。完成需要已保存病历、主诉和诊断非空、版本一致；完成和历史同事务。重复完成返回原结果，完成后病历只读。患者仅能看到已完成病历。
9. 所有列表分页size≤50，日期范围有界；越权详情统一404；状态竞争409；日志不输出密码或病历正文。

## 数据与接口契约

新建med_encounter（appointment_id唯一、doctor_id、状态、签到/开始/完成时间、版本）、med_medical_record（encounter_id唯一、四字段、版本）、med_encounter_status_log（状态、操作人、时间）。医生同时接诊唯一约束使用仅IN_PROGRESS时非空的生成列。新迁移不得修改V1—V8。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | /api/v1/admin/doctor-accounts | 账号及档案原子开通 |
| GET | /api/v1/patient/departments | 患者只读启用科室目录 |
| GET | /api/v1/patient/doctors?departmentId | 患者只读启用医生目录 |
| GET | /api/v1/admin/schedules | from/to/departmentId/doctorId/status/page/size |
| GET | /api/v1/admin/schedules/{id} | 排班与全部时段 |
| POST | /api/v1/admin/schedules/batch | doctorId/clinicRoomId/shiftTemplateId/workDates |
| POST | /api/v1/admin/schedules/{id}/cancel | reason |
| GET | /api/v1/doctor/schedules | 本人目录，from/to/status/page/size |
| GET | /api/v1/doctor/appointments | 本人预约，workDate/page/size |
| GET | /api/v1/appointments/{id}/visit-info | 本人预约的医生/时间/接诊摘要 |
| POST | /api/v1/appointments/{id}/check-in | 签到 |
| GET | /api/v1/appointments/{id}/encounter | 本人接诊，病历仅完成后返回 |
| GET | /api/v1/doctor/encounters | workDate/status/page/size |
| GET | /api/v1/doctor/encounters/{id} | 本人接诊及病历 |
| POST | /api/v1/doctor/encounters/{id}/start | 开始接诊 |
| POST | /api/v1/doctor/encounters/{id}/skip | 标记过号 |
| PUT | /api/v1/doctor/encounters/{id}/medical-record | 四字段及version |
| POST | /api/v1/doctor/encounters/{id}/complete | version |

统一Result信封不变。日期/时间遵循现有Asia/Shanghai业务时间（与本地用户香港同偏移）。前端提供医生工作台、本人排班、候诊与病历弹窗；管理端展示服务端排班；患者页面提供签到及就诊结果。登录成功根据角色跳转，不制造演示统计。

## 验收

- 医生A不能查看/接诊/改写医生B的数据；患者不能查看其他账户记录及未完成病历。
- 同预约并发签到只生成一条Encounter；签到与取消不能同时成功；完成不增加容量。
- 同一医生并发开始两次，最多一个就诊中；状态与历史一同提交。
- 同版本病历两次保存，一次成功一次409；完成后不能写；未保存或版本落后的病历不能完成。
- 批量有一日冲突，整批无新增；取消出诊和预约竞争不产生已取消排班上的新预约。
- 单元/Controller测试、真实隔离MySQL/HTTP业务链路、前端自动测试、桌面及手机浏览器、JAR构建。
- 保留可复现命令与当前结果；虚构当天排班供签到演示，不修改正常业务库。

## 后续范围

药品、处方与模拟收费、已有预约患者的停诊补偿/通知/改约、复杂队列/补叫、真实支付、医保、药房库存、FHIR、微服务不纳入本轮。统计图表在核心交付验收后追加，不能挤占正确性验证。

## 设计参考

- OpenEMR Appointment/Encounter/ SOAP边界：https://github.com/openemr/openemr/tree/6c352d57c6d0a4b091f3022e0ebdb6f5e3b5d625
- OpenMRS Encounter Java领域模型：https://github.com/openmrs/openmrs-core/tree/cfb51a8fe70dbc8518edbc5d01ea272e225b3b5d
- 参考领域职责，采用MedFlow现有Spring Boot/MyBatis分层和数据库一致性方案；不逐行迁移外部项目。
