MedFlow 校招级项目改造开发手册 v0.1
项目定位：单院门诊预约与就诊管理系统（教学演示版）
参考系统：尚医通（预约挂号业务）、OpenEMR（预约和就诊的职责边界）
技术路线：以现有 MedFlow 为底座，增量改造，不重新生成一套新项目。
本文是源码审计前的改造基线：只确认参考仓库的公开代码，没有读取你电脑上的 MedFlow 最新版本。凡标记“已存在？”的功能必须由 Agent 检查实际代码，不能凭印象标成已完成。

1. 参考项目：看什么、不看什么
尚医通（课程型业务参考）
- 可阅读的 Java 版本：https://github.com/XueZhengXiang/yygh （包含 sql/yygh表结构.sql 和初始化数据）
- 另一份结构直观的版本：https://github.com/wyj41/yygh_parent
- 重点研究：service_hosp（医院/科室/排班）、service_user（就诊人）、service_order（下单/取消）、hospital_manage（模拟医院接口）和 SQL。
- 用途：学习业务闭环、模块依赖、跨模块数据流；并不以照搬课程项目代码为目标。
- 警惕：所检查版本基于 Java 8、Spring Boot 2.2.1.RELEASE、旧版 Spring Cloud；配置文件存在硬编码的连接信息和第三方支付配置。不能原样复制配置、依赖、密钥或对其安全性作生产级背书。特别要审查远程调用失败后订单状态如何补偿。
OpenEMR（真实医疗业务领域参考）
- 仓库：https://github.com/openemr/openemr
- src/Services/AppointmentService.php：预约；src/Services/EncounterService.php：就诊；src/Services/PatientTrackerService.php：到院/离院状态。
- 用途：借鉴预约 Appointment 与就诊 Encounter 分离、就诊流程和状态历史。不做 PHP → Java 的全量移植，也不复制 FHIR、处方、医保和计费子系统。
- 若直接改编/复用受 GPL 约束的代码，应核对并履行适用许可证义务；更简单的方式是参考业务概念，自行实现原创 Java 代码。
2. 项目边界（冻结，不轻易扩展）
2.1 一句话定位
这是一套供单家门诊演示的预约挂号与就诊管理系统：管理员配置科室、医生与号源；患者查询并预约；工作人员确认、签到；医生记录就诊结果；患者查询历史。

不是全国多医院挂号平台、完整电子病历系统、医院真实 HIS、线上收费平台或分布式微服务实验室。使用模拟姓名与虚构数据，禁止引入真实患者隐私信息。
2.2 角色与能力
角色	必须具备的能力	不允许的能力
管理员 ADMIN	管理科室/医生、发布号源、确认/取消待确认预约、查询预约管理列表	替医生填写诊疗结论
患者 PATIENT	登录、管理自己名下就诊人、查询排班、发起和取消预约、查看自己的预约与就诊记录	查询其他患者隐私、篡改号源
医生 DOCTOR	查看分配给自己的就诊/排班、处理签到后的就诊、填写简版诊疗记录	修改其他医生的就诊记录、修改号源库存


医生不进行复杂处方开具；前台签到可以由管理员代办，暂不单独引入收费员/护士第四角色。
2.3 最小闭环（MVP 必做）
1. 管理员创建科室与医生；配置某医生某天的可预约号源 slot。
2. 患者登录，为自己添加一条就诊人记录；查看科室 → 医生 → 可用号源。
3. 患者预约（带 Idempotency-Key），系统核验就诊人归属、号源开放与库存、预约冲突，原子扣减并创建预约记录和状态历史。
4. 系统将预约标为 PENDING_CONFIRMATION；管理员执行确认或拒绝。如果决定改为自动确认，必须修改状态机、测试与文档，不能保留一个永远没人处理的 PENDING 状态。
5. 已确认的患者在就诊当天签到；医生从待诊列表打开预约，开始就诊，保存简版就诊记录，结束就诊。
6. 患者端能查看预约状态、历史时间线和自己的就诊摘要；管理员与医生只看各自有权限的数据。
7. 取消/拒绝能按规则准确释放一次号源；重复请求或并发请求不能多退库存。
阶段性可选：无支付的预约提醒（RocketMQ/Outbox）、Redis 缓存、统计面板、Docker Compose 一键部署。
2.4 明确不做
- Spring Cloud、Nacos、Gateway、Feign 多服务拆分（当前单体更符合目标）。
- MongoDB；MySQL 足够存储目前业务关系。
- 微信支付/退款、真实短信、微信登录、阿里云 OSS、医保对接。
- FHIR、复杂病历模板、真实药品与处方、诊断编码、病人敏感数据交换。
- 秒杀级高并发承诺、虚构 QPS、虚构线上用户数。
3. 架构设计（MVP 推荐）
Vue3 管理/患者演示页面
         │ HTTPS/HTTP + JWT
         ▼
  Spring Boot 单体应用 (JDK 21)
    ├── auth         登录鉴权/RBAC
    ├── patient      就诊人归属校验
    ├── catalog      科室与医生
    ├── schedule     排班与号源
    ├── appointment  预约/取消/确认/签到/历史
    ├── encounter    实际就诊/摘要
    ├── common       统一响应/异常/TraceId
    └── notification (可选异步)
         │
         ├── MySQL 8.x：业务事实与事务一致性
         ├── Redis（可选）：可重建缓存/简单限流，**不做最终库存正确性来源**
         └── RocketMQ（可选）：异步提醒，失败可补偿
数据库是预约业务的最终事实来源，号源扣减和预约记录写入放在一个本地事务中。避免为了显得复杂而使用分布式锁和分布式事务。保留现有项目的 Spring Security、JWT、全局异常、RequestTraceFilter、Flyway、幂等记录与状态历史，先审计再复用。
4. 数据模型（基于已有 Flyway 脚本调整，不破坏旧数据）
建议的概念实体如下；实际表名由现有项目为准，禁止让 Agent 直接重复建表。
概念表	关键字段/约束	责任
user_account	id / username / password_hash / role / status	登录与权限
patient	id / owner_user_id / name / phone(演示数据)	就诊人及其归属
department	id / name / enabled	科室
doctor	id / department_id / user_id / enabled	医生与账号映射
schedule_slot	id / doctor_id / start_time / capacity / remaining_capacity / status	原子库存与时间规则
appointment	id / slot_id / patient_id / status / created_at / version(可选)	预约主事实
appointment_status_history	appointment_id / from_status / to_status / operator_id / reason / created_at	可追溯状态历史
idempotency_record	user_id / biz_type / idem_key 唯一 / request_hash / response_ref	处理重复请求
encounter	id / appointment_id UNIQUE / doctor_id / patient_id / status / started_at / completed_at	一次实际就诊
clinical_note	encounter_id UNIQUE / chief_complaint / assessment / plan	简化诊疗摘要
outbox_event（可选）	event_id UNIQUE / aggregate_id / payload / status / retry_count	保证异步通知最终投递


务必落实：关联外键/索引、每个状态的合法迁移、医生与就诊人归属、重复预约规则、退号只执行一次。不存在的字段由新版本 Flyway 迁移补充；不要修改已经应用过的旧 migration。
4.1 预约状态机（冻结版）
PENDING_CONFIRMATION --确认--> CONFIRMED --签到--> CHECKED_IN
       │                         │                    │
       ├--拒绝--> REJECTED      ├--取消--> CANCELED   └--开始就诊--> IN_PROGRESS
       └--取消--> CANCELED      │                                        │
                                                                         └--结束--> COMPLETED
- REJECTED / CANCELED：终态，不可再签到；按库存回补规则只释放一次。
- COMPLETED：终态，不允许取消或退号。
- PENDING_CONFIRMATION 如果当前项目已有到期自动取消任务，需要决定保留并定义超时规则，避免永久挂起。
- 每次状态改变同一事务写状态历史。更新使用旧状态条件防止重复取消/重复确认：UPDATE appointment SET status=? WHERE id=? AND status=?，根据影响行数判定执行权。
- slot 库存扣减使用 UPDATE schedule_slot SET remaining_capacity=remaining_capacity-1 WHERE id=? AND status='OPEN' AND remaining_capacity>0 AND start_time>NOW()；判断受影响行数为 1。准确使用项目数据库字段名。
- 释放库存必须与状态迁移在同一事务中；若原状态不允许释放或状态 CAS 未成功，就不能加库存。
- 对已经取消后重新预约同一个号源的规则，应依据现有唯一约束作出明确选择；如果支持重新预约，设计仅对有效预约唯一的约束或等价事务保护，别用一个永久 UNIQUE(patient_id, slot_id) 阻断合理重约。
5. 关键 API 合约（示例，不要求推倒现有路径）
行为	建议 API	权限
登录	POST /api/v1/auth/login	公开
科室/医生列表	GET /api/v1/departments、GET /api/v1/doctors?departmentId=	已登录/公开按现有实现
可用号源	GET /api/v1/slots?doctorId=&date=	已登录/公开按现有实现
新建预约	POST /api/v1/appointments，Header Idempotency-Key	PATIENT
我的预约	GET /api/v1/appointments/me	PATIENT
确认/拒绝	POST /api/v1/appointments/{id}/confirm、.../{id}/reject	ADMIN
取消预约	POST /api/v1/appointments/{id}/cancel	预约本人/授权管理员
签到	POST /api/v1/appointments/{id}/check-in	ADMIN（最小版）
医生待诊	GET /api/v1/doctor/appointments?date=	DOCTOR，仅自己
开始/结束就诊	POST /api/v1/encounters、POST /api/v1/encounters/{id}/complete	DOCTOR，仅自己
就诊摘要	GET /api/v1/encounters/{id}	所属患者/接诊医生/授权管理员（敏感字段最小化）
预约时间线	GET /api/v1/appointments/{id}/timeline	有权访问该预约的人


所有接口需有：入参校验、统一响应、错误码、状态校验、权限校验、Swagger/OpenAPI 示例、至少一条自动化测试。不要暴露密码散列、完整证件号和内部密钥。
6. 第一个版本必须通过的验收场景
1. 端到端链路：管理员发布号源 → 患者预约 → 管理员确认/签到 → 医生完成就诊 → 患者查询完整记录。全程前后端可操作，不依赖手动改数据库状态。
2. 库存不超卖：最后一个号源，至少 20 个不同用户/不同幂等键并发竞争，最终至多一个真实有效预约；库存永不为负。压测方法与结果归档。
3. 请求幂等：相同用户、同 key、同参数重复提交返回同一个预约 ID；同 key 改参数报业务错误。
4. 重复取消：连续或并发取消只回补库存一次；状态历史不出现重复成功记录。
5. 事务原子性：预约数据库插入或状态历史插入被人为注入故障时，库存回滚。
6. 越权防护：患者 A 无法访问患者 B 的预约/病历；医生甲不能操作医生乙的预约。
7. 错误场景：已关闭号源、时间已过、医生不存在、就诊人非本人、非法状态跳转分别返回清晰错误码。
8. 可复现部署：从空数据库执行 Flyway、启动后端和前端，使用演示账号可以完整跑通；README 有明确版本、命令和环境变量。
质量门禁：单元/集成测试通过、演示数据脚本可重复使用、至少一次全链路手工演示录屏或截图、无明文凭据入库、关键接口的并发测试记录真实可复现。
7. 改造实施顺序（先闭环，再技术亮点）
里程碑	本阶段只做什么	完成证据
M0 现状审计	导出模块树、现有接口、表/迁移、状态机、能运行的功能、阻塞问题；建立差距清单	docs/00-current-state.md，编译/测试日志
M1 预约基础闭环	修复登录、医生/科室/号源、我的就诊人、预约创建与查询	患者能预约并回看，库存准确
M2 全状态闭环	确认/拒绝、取消/回补、签到、时间线	状态迁移和历史一致；越权被拒绝
M3 就诊闭环	医生工作台、创建 Encounter、完成就诊、患者查摘要	从预约到实际就诊整链演示
M4 工程化	完善测试、异常码、OpenAPI、数据库迁移、Docker/运行说明	新电脑/全新数据库可复现启动
M5 加分项（最多选一项）	RocketMQ + Outbox 或 Redis 缓存/限流，只选一条做深	故障模拟、恢复/补偿日志、面试说得清


严禁：M0 未审计完就重新生成几十个实体；M1 未跑通就引入 Nacos、MongoDB、微信支付；M4 未完成就将项目称为生产可用。
8. Agent 开发协议（直接复制给 Agent）
你担任现有 MedFlow 仓库的代码审计员和结对开发者。目标是在不推倒现有 JDK21/Spring Boot 项目的前提下，把它改造成“单院门诊预约与就诊管理系统（教学演示版）”，符合本文件的 M0～M4 边界。尚医通仅作为预约业务参考，OpenEMR 仅作为预约/就诊边界参考，不复制老依赖或完整项目。
第一步：只审计，不修改任何业务代码
1. 扫描仓库的 pom、主应用、Security、Controller/Service/Mapper、Flyway、实体、前端、测试与 Docker/配置。
2. 输出：①模块树；②真实 API 列表；③真实数据库表与关系；④预约状态机；⑤可运行功能清单；⑥目前失败的编译/测试/启动问题；⑦与 M0～M4 的差距及风险等级；⑧建议保留、重构、删除、新增的文件清单。
3. 每条结论注明真实文件路径和行号，无法确认时写“待验证”；不能把未运行过的东西标为“已验证”。
4. 如果基础环境能运行，执行编译与测试并如实报告；如不能运行，说明阻塞点、复现命令和最小修复方案。
5. 审计报告交付前禁止批量生成新模块。
第二步：执行经审计得到的最小任务
- 每次只处理一个可测试的纵向功能切片，例如“预约确认 + 状态历史 + 接口测试 + 前端按钮”。
- 提交前说明“为什么要改、有哪些文件变化、改变了什么接口/表/状态、如何验证、是否引入兼容风险”。
- 绝不编造生产经历、线上并发量、QPS、真实支付能力或用户数据。
- 密钥、口令、第三方 API 凭据必须通过环境变量注入；发现旧仓库中的凭据只报告风险，不复制。
- 修改状态机时同时修改接口文档、前端展示、集成测试与 Flyway 迁移。
9. 简历与面试叙事边界
如实介绍：“我在原有 Spring Boot 医疗预约项目基础上，参考尚医通的业务流程和 OpenEMR 的 Appointment/Encounter 模型，独立梳理并实现了从排班、预约、号源扣减到签到与就诊记录的业务闭环。重点处理了预约接口幂等、MySQL 条件更新与事务、状态历史、越权校验和自动化测试。”

不宣称：参与过 OpenEMR/尚医通真实团队开发、系统接入医院、实际交易量、高可用集群/生产上线。只有真实完成并测过的能力才写进简历。

面试必须能不用 AI 连续解释：
- 为什么是单体？什么时候才值得拆微服务？
- 为什么号源与预约落同一 MySQL 事务？
- 最后一个号源如何避免超卖？
- 同一幂等键与相同业务请求分别解决什么问题？
- 取消与确认并发发生，谁获胜？如何避免回补两次？
- 为什么需要 appointment_status_history？
- 为什么 appointment 和 encounter 分离？
- 服务如何部署、启动、观察日志和回滚？
10. 建议放进项目仓库的交付物
README.md                     # 安装、启动、演示账号、系统价值及限制
docs/
  00-current-state.md         # 真实现状审计与差距（Agent 先生成）
  01-scope-and-workflow.md    # 产品边界、角色、完整业务链、状态机
  02-database-design.md       # ER图、索引、约束、迁移历史
  03-api-contract.md          # 接口鉴权、参数、错误码、返回示例
  04-architecture.md          # 单体部署图、调用链、取舍说明
  05-test-report.md           # 功能/并发/幂等/故障/权限真实测试结果
  06-deployment.md            # 环境变量、初始化、备份与启动排错
  07-interview-notes.md       # 项目介绍与面试追问，所有描述均与代码一致
“今天”的唯一任务
把此开发手册交给 Agent，要求它只做 M0 现状审计。 根据真实代码清单，决定下一步先修预约闭环的哪一个缺口。不要再开一个名为“Java 版尚医通”的平行项目。
参考源码：
- https://github.com/XueZhengXiang/yygh
- https://github.com/wyj41/yygh_parent
- https://github.com/openemr/openemr