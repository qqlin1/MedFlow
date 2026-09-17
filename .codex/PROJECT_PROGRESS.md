# MedFlow 课程路线与项目进度

> 本文档是 MedFlow 的课程目录、项目看板和验收记录。
> 项目源码、Git、编译、测试和手工验收证据优先于文字记录；“写过”不等于“完成”。

## 1. 当前总览

| 项目 | 当前内容 |
|---|---|
| 项目名称 | MedFlow 门诊资源调度与可靠预约系统 |
| 项目定位 | 围绕有限号源，解决排班冲突、并发预约、幂等、状态竞争、可靠超时和数据权限 |
| 求职目标 | 27 届、已有一段非头部公司实习；Java 后端为主线，Agent/AI 应用平台工程为并行方向 |
| 简历角色 | 校招第一项目；OA 作为基础能力项目 |
| 课程重置日期 | 2026-08-30 |
| 主线周期 | 09-27 前形成可面试证据 V1，10-18 前补齐 Redis、MQ、部署与完整证据；保留原业务依赖顺序 |
| 每日基准时间 | 约 4 小时 |
| 学习方式 | 2026-09-17 起按学习者授权由助手直接实现并验证；随后按文件讲解业务、方法、失败边界与面试表达，区分快速了解和必须讲透的内容 |
| 当前课程 | S2 预约状态机：创建预约已有实现，本单元落地确认预约 PENDING_CONFIRMATION → BOOKED；课程材料见 APPOINTMENT_CONFIRMATION_LESSON.md |
| 当前里程碑 | M3 排班发布已有代码、验收待补；M4 预约创建与确认推进中；保留 M1/M2 验收挂账 |
| 当前总体状态 | `IN_PROGRESS` |
| 执行入口 | 当前唯一动作只在 §14 维护 |

## 2. 范围冻结

### 2.1 Java/MedFlow 核心主线

```text
三角色权限
  + 最小科室/医生/诊室
  + 排班与有限 Slot
  + 预约创建/确认/取消/超时
  + MySQL 事务、条件更新、幂等、状态 CAS
  + Redis 热点号源查询缓存
  + RabbitMQ + Outbox 超时关闭
  + 测试、SQL 优化、Docker 和面试证据
```

### 2.2 主线外内容

| 分类 | 内容 |
|---|---|
| 完成主线后最多选一个 | 简单签到排队、一次性随访、Redis Lua 库存方案、SSE、敏感访问审计 |
| Java 主项目不做 | 护士、完整叫号接诊、复杂随访、AI 诊断/用药/科室推荐、FHIR、MinIO、微服务、分库分表、完整监控大屏、完整前端 |
| 隔离并行线 | Agent 预约规则与号源查询助手 A0—A3；只用虚构数据，初版只读，不进入预约事务核心，不阻塞 S1—S3 |

Java 主项目的新增功能都要先回答：“它是否强化五个核心项目故事？”如果不能，就不进入 MedFlow 主线；Agent 扩展只按独立 A0—A3 验收门槛推进。

## 3. 固定教学规则

1. 先展示完整课程目录、当天唯一主题和下课条件，不用无止境追问代替教学。
2. 教学顺序固定为：当前目标与业务规则 → 助手实现和验证 → 按文件讲解调用流程/方法 → 面试重点与学习者口述 → 进度记录 → 下一单元。
3. 每个知识点必须绑定代码、SQL、测试、日志或故障现象；不为背名词增加业务模块。
4. 自动化测试框架的系统教学后置，验收证据本身不后置。2026-09-17 起助手可设计并执行必要测试，再向学习者解释场景、断言和证据边界；权限、事务、状态竞争及并发能力没有负例证据时不标记验收。
5. MySQL 预约未证明正确前不引入 Redis；幂等取消未证明正确前不引入 RabbitMQ。
6. Redis 主线只做查询缓存；RabbitMQ 主线只做预约超时关闭。
7. 简历只记录已经实现并验证过的能力和数字。
8. 之前关于行锁、条件更新、事务和幂等的零散问答只算预习，不计为正式完成。
9. 每周必须留下“问题—方案比较—失败处理—验证结果”的证据。
10. 项目主线不承担全部八股和算法；每天另留固定时间学习。
11. 概念讲解固定五段模板：一句话定义 → 没有它会怎样 → 工作机制（不超过 5 步）→ 真实样子（配置或代码）→ 边界（管什么、不管什么）。
12. 批改固定三段式：标准答案与思路 → 学习者答案与标准的差距 → 常见错误为什么不成立。
13. 学习优先级固定按“面试反复程度 × 实际开发价值 × 简历触发风险 × 项目依赖”排序，不再按框架源码目录顺序推进。
14. 每个新单元固定走：真实场景 → 关联八股 → 至少两种方案 → 核心实现 → 失败/竞争实验 → 2—8 分钟口述。
15. 代码默认由助手实现；DTO、基础映射、配置可快速了解；SQL、事务、锁、幂等、缓存、消息与权限必须结合真实代码和验证讲透，不能把代码生成记作学习者已经掌握。
16. Security 的完成上限是登录/JWT、统一 401/403、RBAC、对象权限和负例验证；闭环后不继续逐个阅读内置 Filter 源码。
17. Agent 线每天最多 45 分钟；没有稳定业务 API 时使用 Stub，不能提前改变 MedFlow 的核心模型或阻塞 S1—S3。

## 4. 进度状态定义

| 状态 | 含义 |
|---|---|
| `NOT_STARTED` | 尚未开始 |
| `IN_PROGRESS` | 正在学习、设计或手打 |
| `VERIFYING` | 正在编译、测试、口述或手工验收 |
| `BLOCKED` | 存在明确阻塞，且已记录解除条件 |
| `ACCEPTED` | 设计/代码、编译、测试、手工验收、口述和 Git 证据均满足要求 |

每个代码阶段分别记录：

```text
设计状态
代码状态
编译结果
自动化测试结果和数量
手工验收场景
面试口述结果
Git 提交 SHA
遗留问题
下一步唯一动作
```

## 5. 当前项目看板

| 里程碑/交付物 | 状态 | 当前证据或缺口 |
|---|---|---|
| M0 需求与设计 | `ACCEPTED` | 2026-09-02 过闸：五不变量、状态机、三表 DDL、接口与三时序图互相一致（Day 1–4 全部验收） |
| 项目定位与范围冻结 | `ACCEPTED` | 可靠预约主线、三角色及 P0/P1/P2 边界已在 M0 验收确认 |
| 五条不变量和核心用例 | `ACCEPTED` | 2026-08-31 Day 1 口述验收通过（初答 → 补讲重答 → 填空收口） |
| 预约与排班状态机 | `ACCEPTED` | 2026-09-01 状态迁移表与竞争场景验收通过（DAY2_STATE_MACHINE_WORKSHEET.html + 口述补考） |
| ER 图与数据库约束 | `ACCEPTED` | 2026-09-01 核心三表 DDL 与关系设计验收通过（DAY3_SCHEMA.sql，问答式设计） |
| API 与事务时序图 | `ACCEPTED` | 2026-09-02 接口清单/状态码/权限/三时序图验收通过（DAY4_API_DESIGN.md） |
| 2026 面经调研与任务重排 | `VERIFYING` | 已建立 `JAVA_BACKEND_AGENT_RESEARCH_2026.md`：24 份一手面经、1 份按 43 条记录统计的个人复盘、10 个定向 Agent 岗位及一手技术资料；待学习者审阅和 Git 固化 |
| M1 骨架与统一规范 | `IN_PROGRESS` | Day 5 工程初始化完成；已有 JDBC/Flyway、V1 迁移、统一响应/异常/Validation、Trace Filter、local 验收接口及 springdoc 配置；7 项实际 HTTP 验证通过，最新磁盘报告显示 8 个 MockMvc 测试通过；OpenAPI 双向验证、口述及本阶段提交仍挂账 |
| M2 认证与数据权限 | `IN_PROGRESS` | 登录/JWT、统一 401/403、角色级 RBAC 已完成：2026-09-08 编译、启动及正反 HTTP 证据通过。TokenVersion 对已签发 Bearer Token 的失效校验、禁用账号 Bearer 负例，以及医生/患者对象权限随 S1/S2 的真实领域接口滚动验收 |
| M3 排班与号源 | `IN_PROGRESS` | 已有组织资源、模板读取、排班快照、创建草稿、事务内发布与 Slot 初始化；查询、关闭/取消与并发冲突等验收仍待补 |
| M4 MySQL 预约 | `IN_PROGRESS` | 已有就诊人归属、创建幂等、条件扣号、有效预约唯一键和创建历史；本单元新增确认，取消/超时/查询尚未实现 |
| M5 并发与 SQL 证据 | `NOT_STARTED` | 无测试数据和报告 |
| M6 Redis 查询缓存 | `NOT_STARTED` | 无代码 |
| M7 RabbitMQ 可靠超时 | `NOT_STARTED` | 无代码 |
| M8 部署与面试证据 | `NOT_STARTED` | 无代码和报告 |
| Agent 并行线 A0—A3 | `NOT_STARTED` | 仅完成岗位调研、范围与验收设计；尚无 Agent、RAG、Tool 或评测代码 |

当前环境事实：

- 当前已有 M0 需求、状态机、DDL、API 与事务时序等设计产物，以及可运行的 Spring Boot 最小骨架。
- 当前目录已初始化为 `main` 分支 Git 仓库。
- 当前持久层已迁移至 MyBatis，存在 V1–V7 Flyway 迁移、统一 Web 规范和 local 验收接口。旧日期的 HTTP 验证不能代替当前工作树验收。
- 原 9 个测试的最近全套执行为 8 failures + 1 error：WebCheck 切片未加载正式安全链；contextLoads 使用默认本机数据库且连接失败。新增确认专项测试独立验证，具体结果见本单元记录，不能据此称全套测试通过。
- 工作树已有登录、角色权限、组织资源、就诊人、排班发布和预约创建代码；Redis/RabbitMQ/Outbox 尚未实现，当前多数业务代码仍未提交 Git。
- 本机 Git 2.51.0、Maven 3.9.16 和 Temurin JDK 25.0.3 可用。
- 项目编译目标、Maven 运行时和应用运行时均已统一为 Java 25。
- 2026-09-17 核对：MySQL 8.4 二进制可用但常规实例未运行；Docker CLI 存在但 daemon 未运行。确认专项使用隔离的临时 MySQL 进程，测试后清理，不依赖业务库。

## 6. 2026-09-07 重排后的交付总目录

| 阶段 | 日期 | Java/MedFlow 主任务 | 截止证据 |
|---|---|---|---|
| 已完成基础 | 08-30 至 09-06 | M0 设计、M1 骨架与统一 Web 规范 | 设计产物、可运行骨架、HTTP/MockMvc 既有证据；未完成项继续挂账 |
| 过渡闸门 | 09-07 至 09-09 | Security 最小完整闭环：用户加载、登录/JWT、统一 401/403、RBAC、对象权限 | 正反登录、伪造/过期 Token、禁用账号和越权；不再深挖 Filter 源码 |
| S1 | 09-10 至 09-14 | 最小科室/医生/诊室、排班与 Slot，处理时间重叠、发布事务和必要索引 | 重叠边界、并发冲突、整体回滚和初版 `EXPLAIN` |
| S2 | 09-15 至 09-20 | MySQL 预约正确性：条件扣减、幂等键、唯一约束、状态 CAS、取消/超时竞争 | 回滚、同键重试、重复取消和竞争终态证据 |
| S3 | 09-21 至 09-27 | 500 抢 50、死锁复现/规避、10 万数据 SQL 优化，形成简历 V1 | 成功数严格等于容量、死锁记录、索引前后计划/耗时、三条项目故事 |
| S4 | 09-28 至 10-04 | Redis 热点号源查询缓存 | 命中/失效/并发回源和 Redis 故障降级证据 |
| S5 | 10-05 至 10-11 | RabbitMQ 超时关闭 + Outbox | 重复消息一次效果、Broker/消费者中断恢复和积压处理说明 |
| S6 | 10-12 至 10-18 | Docker、Actuator、日志/CI、基础 JVM/SQL 排障、README 与模拟面试 | 空环境启动、故障定位链、五个项目故事和可核验数字 |

从现在开始投递和模拟面试；09-27 是证据 V1 截止，不是开始投递的日期。某阶段未验收时保留缺口，但不通过虚构结果、删掉失败验证或无限延后面试准备来赶日期。完整任务和来源见 `JAVA_BACKEND_AGENT_RESEARCH_2026.md`。

## 7. 校招知识学习地图

### 7.0 基于真实面经的执行优先级

这里的 P0/P1/P2 是“求职学习优先级”，不是 `MEDFLOW_REQUIREMENTS.md` 中的“产品交付范围”。

| 优先级 | 内容 | 执行规则 |
|---|---|---|
| P0 | 项目/实习深挖、MySQL 索引与 SQL、事务/MVCC/锁、Java 集合与并发、Redis 核心、Spring 主干、算法/SQL 手写 | 每天推进；必须绑定代码/SQL、失败证据和口述 |
| P1 | RabbitMQ、JVM/排障、网络/Linux、工程化和常见系统场景 | 紧跟 P0；写进简历或本周项目使用时立即升为 P0 |
| P2 | Security/AQS/框架源码逐行、DDD 全套、微服务治理、分布式协议、MQ 内核、Kubernetes | 只建机制图和检索入口；被简历或目标 JD 触发时再升级 |

八股不能等项目做完再补，项目也不能退化成“背题演示器”。每天用当前业务场景串起定义、机制、取舍、故障和验证。

### 7.1 Java

- W1：面向对象、封装、枚举、异常、泛型和集合。
- W3：`LocalDate`、`LocalTime`、`LocalDateTime` 和时间边界。
- W5：线程状态、线程池、JMM、`volatile`、`synchronized`、Lock、CAS 和 happens-before。
- 全程：`equals/hashCode`、不可变对象、Stream 的边界和异常处理。

### 7.2 Spring

- IOC、DI、Bean 生命周期和自动配置。
- Spring MVC 请求链路、参数绑定、Validation 和异常处理。
- AOP、代理、`@Transactional` 生效与失效。
- 单元测试、切片测试和 Spring 集成测试的区别。

### 7.3 安全

- FilterChain、Authentication、SecurityContext 的请求主干；不逐个背内置 Filter 源码。
- BCrypt、JWT 签名和失效。
- 认证与授权、401 与 403。
- RBAC 与对象级数据权限；以伪造/过期 Token、禁用账号和越权负例收口。

### 7.4 MySQL

- 字段类型、主键、外键取舍、唯一约束和联合索引。
- B+ 树、最左前缀、回表、覆盖索引和 `EXPLAIN`。
- ACID、隔离级别、MVCC、快照读与当前读。
- 行锁、间隙锁、死锁、条件更新和事务边界。

### 7.5 Redis

- String、Hash、Set、ZSet 的典型使用边界。
- TTL、过期删除、淘汰、RDB 与 AOF。
- Cache Aside、穿透、击穿、雪崩和一致性。
- 为什么缓存不能替代数据库事实。

### 7.6 RabbitMQ

- Exchange、Queue、Binding、Routing Key。
- Publisher Confirm、Consumer Ack、重试和死信。
- 至少一次投递、重复消费、消费幂等和 Outbox。

### 7.7 JVM 与工程

- JVM 运行时区域、对象创建、类加载和常见 GC 思路。
- OOM、CPU 飙高和频繁 Full GC 的基础排查。
- Git、Maven、Flyway、JUnit、MockMvc、Testcontainers。
- Docker、Linux、Trace ID、健康检查和 CI。

### 7.8 独立算法线

每天 20—30 分钟，按数组/哈希、链表、栈队列、树、二分、堆、图和动态规划推进。算法进度不通过给 MedFlow 增加业务功能来替代。

### 7.9 Agent/AI 应用并行线

- 当前主攻“应用/平台工程型”：LLM API、RAG、Tool Calling、确定性 Workflow、Eval/Trace 和安全边界。
- A0—A3 独立推进，初版只读；模型不传可信用户 ID、不直接访问数据库、不自主提交预约。
- Spring AI 选一套深入；MCP 做一次 client/server；多 Agent、模型微调和训练推理框架按 JD 再学。
- 详细岗位样本、任务和验收见 `JAVA_BACKEND_AGENT_RESEARCH_2026.md`。

## 8. W1 逐日课表

| 日期 | 课程唯一主题 | 当天项目产出 | 当天八股 | 下课标准 |
|---|---|---|---|---|
| 08-30 Day 1 | 新定位、领域对象、五条不变量 | 项目一句话、范围边界、核心预约成功/失败流程 | 对象职责、事务原子性、幂等、状态机概念 | 能脱稿讲清项目和创建预约流程 |
| 08-31 Day 2 | 预约与排班状态机 | 状态迁移表、非法迁移和竞争场景 | 枚举、异常体系、CAS 思想 | 取消与超时竞争解释正确 |
| 09-01 Day 3 | ER 模型和数据库约束 | 12 张表草图、唯一约束、联合索引 | 表关系、唯一约束、索引基础 | 业务规则能在数据库兜底 |
| 09-02 Day 4 | API、权限与事务时序 | 首批接口、HTTP 状态码、三张时序图 | DTO/VO、401/403/409、事务边界 | M0 设计互相一致 |
| 09-03 Day 5 | Git、Maven 与 Spring Boot | Git、Wrapper、Java 25 项目骨架 | Maven 生命周期、IOC、自动配置 | 应用和上下文测试通过 |
| 09-04 Day 6 | Flyway 与统一 Web 规范 | 配置、迁移、Result、异常、Validation、Trace ID | Profile、连接池、MVC 调用链 | 空库启动和异常测试通过 |
| 09-05 Day 7 | OpenAPI、测试和周验收 | 接口文档、测试清单、首个规范提交 | 分层职责、测试金字塔 | M0/M1 按闸门验收 |

## 9. Day 1 正式课程范围

### 9.1 今天必须学会

1. 为什么 MedFlow 不是医院 CRUD，而是资源竞争与状态一致性项目。
2. `User`、`Patient`、`Doctor`、`Schedule`、`Slot`、`Appointment` 的职责。
3. 五条不变量：

   - 号源不超卖。
   - 请求不重复创建。
   - 号源不重复归还。
   - 状态不非法迁移。
   - 数据不越权访问。

4. 创建预约需要检查什么、修改什么、在哪些位置失败。
5. 为什么 MySQL 是事实源，Redis 和 MQ 要晚于正确性版本。

### 9.2 今天暂时不学

- 具体 Spring 注解和完整代码。
- Redis Lua、MQ 配置和 Docker。
- 排队、接诊、随访、AI 与微服务。
- 所有锁实现细节；今天只建立问题地图。

### 9.3 Day 1 验收题

学习者需要脱稿回答：

1. 用一句话介绍 MedFlow。
2. Schedule、Slot 和 Appointment 为什么要分开？
3. 创建预约的请求中至少需要哪些信息？
4. 创建预约会修改哪几类数据库记录？
5. 为什么重复点击和两个人抢最后一个号是两类不同问题？
6. 为什么“先查剩余数量再减一”可能超卖？
7. 预约插入失败时，已经扣减的号源为什么必须恢复？
8. 取消与超时同时发生时，怎样确保只归还一次？

只有能讲清业务顺序和核心原因，Day 1 才标记为 `ACCEPTED`。

## 10. 每天固定节奏

```text
110 分钟：MedFlow 当前场景——设计、核心实现、失败验证
45 分钟：当天关联八股——定义、机制、取舍、项目追问
30 分钟：算法或 SQL 手写——计时、复杂度、边界
45 分钟：Agent 并行线——一个可运行增量或一个评测增量
10 分钟：证据日志——结果、失败、数字、明日唯一动作
```

每周额外交付：一段业务闭环、至少一个边界和一个失败/并发用例、一份可复查证据、一张方案比较表、一次 8 分钟项目拷打、一次 45 分钟综合模拟，以及一次“未验证能力不写简历”的核查。

每次课程开头固定展示：

```text
课程：第几周 / 第几天
项目：当前里程碑和状态
上节课：完成内容和证据
今天：唯一学习主题
下课条件：做到哪里停止
```

每次课程结束固定记录：

```text
今天学会了什么
产生了什么文件或代码
编译结果
自动化测试名称和数量
手工验收结果
口述验收结果
Git 提交 SHA
遗留问题
明天唯一主题
```

## 11. 阶段验收闸门

### M0 需求与设计

- 项目定位、范围、不变量、角色和核心用例一致。
- 排班、预约状态机和竞争结果明确。
- ER 图能用约束支撑业务规则。
- API、权限和事务时序一致。
- 学习者能脱稿说明创建预约和取消/超时竞争。

### M1 骨架与规范

- Java 25、Maven Wrapper 和 Spring Boot 应用可重复启动。
- 空库通过 Flyway 初始化。
- 统一异常、校验、Trace ID、OpenAPI 和基础测试通过。
- 完成首次规范 Git 提交。

### M2 认证与数据权限

- 登录、伪造/过期 Token、禁用账号、401 和 403 有测试。
- 患者 A 不能访问患者 B 数据。
- 医生 A 不能访问医生 B 排班预约。

### M3 排班与号源

- 时间重叠规则边界测试通过。
- 并发创建相同医生或诊室重叠排班时只有一个有效结果。
- 发布排班和生成 Slot 同时成功或同时回滚。

### M4 MySQL 预约

- 创建、确认、取消、状态历史和 Idempotency-Key 完成。
- 扣减成功但插入失败时整个事务回滚。
- 重复预约由业务检查和唯一约束共同阻止。
- 并发重复取消只归还一次。

### M5 并发与 SQL

- 容量 50、500 并发成功数恰好 50。
- 取消与超时竞争只有一个终态。
- 10 万条数据保存索引前后 `EXPLAIN` 和耗时。

### M6 Redis

- 热点 Slot 查询缓存命中和失效正确。
- 空值缓存或等价防穿透方案有测试。
- Redis 不可用时可降级，预约正确性不依赖缓存。

### M7 RabbitMQ

- Outbox 与预约事务同时提交。
- Relay、Confirm、Ack、有限重试和死信完整。
- 重复消息只产生一次业务效果。
- Broker 或消费者中断后能够恢复。

### M8 部署与面试

- Docker Compose 和 Flyway 可从空环境启动。
- Actuator、Trace ID 和日志可定位失败。
- README、ER 图、状态图、时序图、测试报告和演示脚本完整。
- 五个项目故事和简历数字均可现场复现。

## 12. 核心证据追踪表

| 证据 | 状态 | 结果/文件 |
|---|---|---|
| 预约事务回滚 | `NOT_STARTED` | — |
| 同幂等键并发 | `NOT_STARTED` | — |
| 500 抢 50 | `NOT_STARTED` | — |
| 并发重复取消 | `NOT_STARTED` | — |
| 取消与超时竞争 | `NOT_STARTED` | — |
| 患者/医生越权 | `NOT_STARTED` | — |
| 排班并发冲突 | `NOT_STARTED` | — |
| 10 万数据 `EXPLAIN` | `NOT_STARTED` | — |
| Redis 故障降级 | `NOT_STARTED` | — |
| MQ 重复消费 | `NOT_STARTED` | — |
| Outbox 故障恢复 | `NOT_STARTED` | — |
| Docker 空环境启动 | `NOT_STARTED` | — |
| 五个项目故事口述 | `NOT_STARTED` | — |

## 13. 学习记录

### 2026-08-29：第一次课程整理

- 发现原教学过于松散，建立课程目录、日课表和验收状态。
- 提前接触了预约输入、幂等键、条件更新、事务回滚和 MySQL 行锁。
- 因范围仍然过大，没有把这些预习标记为正式完成。

### 2026-08-30：项目重新定位

- 将项目从“智慧门诊资源调度与患者随访平台”改为“门诊资源调度与可靠预约系统”。
- 角色由五类缩减为患者、医生、管理员三类。
- 核心表控制为 12 张，接口约 20 个。
- 主线只保留排班、Slot、预约确认/取消/超时。
- Redis 只保留热点号源查询缓存。
- RabbitMQ 只保留 Outbox 超时关闭。
- 排队、叫号、接诊、随访、AI、FHIR 和微服务退出八周主线。
- 建立 15 项硬性测试证据和五个面试故事。
- 当前没有源码或 Git；项目状态仍是 M0 `IN_PROGRESS`。

### 2026-08-31：Day 1 口述验收通过

- 五条不变量、领域对象三分、创建预约流程与失败点地图完成三轮口述验收：初答 → 补讲重答 → 填空收口，Day 1 标记 `ACCEPTED`。
- 已能独立讲出：事务原子性与回滚、先查再减的竞态窗口、条件更新、受影响行数判定赢家（1 还号 / 0 放弃）、取消与超时的竞争终态剧情。
- 遗留四个术语修正（Day 2 开课复述检验）：互斥（不是"并发"）、原子扣减（不是"加锁"）、slotId（不是泛称"请求参数"）、病根是"判断与动手分离、动手时依据过期"（不是"没有行锁"）。
- 建立 Day 1 收获卡：五条不变量 × 违反现象 × 保证手段，以及术语到"防的事故"的映射。

### 2026-09-01：Day 2 状态机验收通过

- 预约状态迁移表 15 格判定全部正确；取消与超时竞争、竞争终态、受影响行数 1/0 判定、迟到的超时员均能独立讲出，Day 2 标记 `ACCEPTED`。
- 作业工具：`.codex/DAY2_STATE_MACHINE_WORKSHEET.html`（15 行迁移表 + 四步剧情，后续复用为 M4 编码图纸）。
- 关键设计决策：CONFIRMED × 取消 = 拒绝（确认后锁定号源防浪费）；学习者已理解"业务规则可自定义但必须给出理由"。
- 遗留检查项：状态与事件的区分仍需巩固——曾把"已超时"当状态。正确 5 状态：待确认、已确认、已取消、已关闭、已完成；4 事件：确认、取消、超时、完成就诊。Day 3 开课先默写。

### 2026-09-01：Day 3 数据库设计验收通过

- 关系链 appointment → slot → schedule → doctor 独立画通；"多的一方存一的一方的 id"规则已内化（初答曾把方向反成"存自己的 id"）。
- appointment 字段决策独立完成：删除 confirmed_at（状态历史表已覆盖）、doctor_name（可经链路推导，防改名脏数据）、remark（不服务查询不防事故）；patient_id/slot_id/status/created_at 全部 NOT NULL。
- 唯一约束自主推导出 active_flag + NULL 方案支持"取消后重约"，理解维护责任（CAS 同事务更新 flag）；纠正"status 直接进唯一键会撞车"的变体。
- 索引：schedule 联合唯一键兼做医生自查索引，另建 idx_date 服务全院查询；appointment 单列 idx_patient 足够（InnoDB 二级索引自动携带主键，W4/W5 展开）。
- 产物：`.codex/DAY3_SCHEMA.sql`（schedule / slot / appointment 三表 DDL，Day 6 转 Flyway）。
- 遗留确认项（Day 4 开课）：外键立场一句话（默认：不建外键约束、关联列建索引）。

### 2026-09-02：Day 4 API 与时序设计验收通过，M0 过闸

- 状态码配对 5/6（409 与 404 边界补讲："没这货"vs"现在不行"）；409 两道防线（唯一约束 vs 条件更新）已理解。
- 创建预约排序 7/8：全流程独立排出，C/A 顺序原则补讲（事务内先做最可能失败的步骤）。
- 权限两层（角色级/对象级）全对；习得"资源枚举防护"术语与 404/403 折中。
- 思考题自主推出 Outbox 两条罪状：事务内外部调用捏行锁拖垮并发、幽灵消息。
- 外键立场三段式完成：S 锁机制（FK 校验父行加 S 锁持至提交）、"外键只校验存在性不校验业务"为本人原创洞察；纠正 CASCADE 默认行为（默认 RESTRICT）。
- 覆盖索引概念修正：为高频查询定制联合索引免回表，不是全字段建索引。
- 产物：`.codex/DAY4_API_DESIGN.md`。
- **M0 需求与设计里程碑整体过闸**：五不变量/状态机/三表 DDL/接口/三时序图互相一致。

### 2026-09-02：Day 5 工程初始化完成

- 已扫描全部 Markdown 文档并确认 Day 1–4 验收证据完整，课程进入 M1。
- 技术栈已锁定为 Temurin JDK 25.0.3、Spring Boot 3.5.16、Maven Wrapper 3.9.16。
- 已初始化 `main` 分支 Git 仓库，创建 `.gitignore`、`.gitattributes`、POM、启动类、`application.yml` 和上下文测试。
- 首次测试暴露 Java 25 下 Mockito 动态代理无法自挂载；按 Mockito 官方方案通过 Maven Surefire 在测试 JVM 启动时显式加载 `mockito-core` Java Agent，复测通过。
- 自动化测试：`MedFlowApplicationTests.contextLoads`，1 个测试，0 失败，0 错误，0 跳过。
- `mvnw.cmd clean package` 通过，生成可执行 JAR；JAR 在 8080 启动成功，无业务接口时根路径返回预期 404，随后完成优雅停机。
- 首次规范 Git 提交：`7cbfdf7`（`chore: initialize MedFlow Spring Boot project`）。
- Day 5 工程部分已完成，课程状态进入 `VERIFYING`；仍需完成 Maven 生命周期、IOC 与自动配置口述验收。
- 2026-09-03 首次口述结果：Maven 生命周期和 IOC/DI 主体方向正确；仍混淆 `package` 与启动、Starter 与自动配置、手动 `new` 对事务代理的影响，暂不标记 `ACCEPTED`。
- 已建立复习卡 `.codex/DAY5_MAVEN_IOC_AUTOCONFIG_REVIEW.md`，计划于 2026-09-04 再次复习并口述验收。

### 2026-09-03：进入 Day 6（Day 5 复习并行保留）

- Day 5 的工程验收已经完成，知识口述仍保持 `VERIFYING`，不标记为 `ACCEPTED`。
- 按学习者决定进入 Day 6；Day 5 通过次日复习和后续启动链实践继续巩固，不再作为 Day 6 的硬阻塞。
- Day 6 固定顺序：配置与数据库接入 → Flyway 迁移 → 统一响应/校验/异常 → Trace ID 与测试。
- 当前第一步：理解 Profile、数据源、连接池和 Flyway 的职责边界，再开始修改工程。

### 2026-09-05：Day 6 Web 实际 HTTP 验证通过，进入自动化测试学习

- 源码：`ErrorCode`、`BusinessException`、`Result`、`GlobalExceptionHandler`、`WebCheckRequest`、`WebCheckController` 已由学习者手打；`patientId` 字段及号源正数校验提示已修正。
- 包归属：跨功能共用的响应与异常代码保留在 `shared.web` / `shared.exception`；本地实验代码放 `devtools.controller` / `devtools.dto`，Controller 使用 `@Profile("local")`。正式业务后续按业务模块组织，在模块内部保留 Controller、Service、Mapper、DTO、Entity、VO 的职责划分；不预建空目录。
- 编译：本次 `.\mvnw.cmd -DskipTests compile` 为 `BUILD SUCCESS`；编译通过不等于自动化测试通过。
- HTTP 验证：使用已有的 local MedFlow 服务，通过真实请求检查状态码和响应 JSON 的 `code`、`message`、`data`，7 项全部通过。模拟 500 请求由 Global 转换为通用错误响应。

| 场景 | HTTP 状态 | 业务码 | 结果 |
|---|---|---|---|
| GET /dev/web/success | 200 | SUCCESS | PASS，data 为 Web链路正常 |
| GET /dev/web/sold-out | 409 | SLOT_SOLD_OUT | PASS，message 为 该号源已约满 |
| GET /dev/web/unexpected | 500 | INTERNAL_SERVER_ERROR | PASS，message 为 服务器内部错误 |
| POST /dev/web/validate，patientId=1、slotId=10 | 200 | SUCCESS | PASS，返回对应数据 |
| POST /dev/web/validate，缺少 patientId | 400 | VALIDATION_FAILED | PASS，message 为 就诊人ID不能为空 |
| POST /dev/web/validate，slotId=0 | 400 | VALIDATION_FAILED | PASS，message 为 号源ID必须大于0 |
| POST /dev/web/validate，patientId 为 abc 字符串 | 400 | MALFORMED_REQUEST_BODY | PASS，message 为 请求体格式不正确 |

- 测试边界：上述接口没有调用预约业务或执行数据库写入；不能据此认定预约、权限、事务或并发已验收。
- 学习边界：已讲解枚举/异常对象/响应对象、ResponseEntity 的状态与 body、DTO 与 Entity 及包结构。学习者仍需在代码中巩固；未追加独立口述通过记录，Day 5 与 Day 6 均未因此标为 `ACCEPTED`。
- 遗留：MockMvc 测试尚待手打与运行，随后学习 Trace ID；M1 还需 OpenAPI、周验收与本阶段 Git 提交。本次未提交代码。

### 2026-09-06：MockMvc 七项测试通过，进入 Trace ID

- 学习者已手打 `src/test/java/com/qqlin/medflow/devtools/controller/WebCheckControllerTest.java` 并报告成功。
- 已核对本地源码和 `target/surefire-reports/com.qqlin.medflow.devtools.controller.WebCheckControllerTest.txt`（报告时间 2026-09-06 00:17）：7 tests、0 failures、0 errors、0 skipped，耗时 1.764 秒；本轮没有重复执行已通过的测试。
- 覆盖成功响应、业务 409、未知 500、合法请求体、缺失就诊人 ID、非正号源 ID、数字字段类型错误；这属于 Web 切片验证，不代表预约业务或并发已验收。
- 下一段 Trace ID 使用方案：当前同步请求由服务器生成 UUID，通过 `X-Trace-Id` 响应头返回；同一请求线程通过 MDC 的 `traceId` 关联日志；Filter 在 finally 中清理 MDC。`Result` 继续保持 code/message/data 三字段。
- Trace ID 源码、日志配置和专用测试尚待学习者手打及验收；此时不标记 Day 6 整体 ACCEPTED，也不声明具备跨服务或异步链路追踪。

### 2026-09-06：Trace ID 已手打，500 响应头已获得手工证据

- 已检查 RequestTraceFilter 与 application.yml：服务器生成 UUID、设置 X-Trace-Id、MDC 日志格式以及 finally 清理均已写入；本轮未修改项目源码。
- 学习者提供的 curl 输出为 HTTP 500，包含 X-Trace-Id: 1792438d-4291-49ba-899e-0678637470ae。该片段未包含响应体和服务器日志，因此不据此认定日志关联与全部验收通过。
- 下一步提供独立过滤器单元测试：请求处理期间响应头与 MDC 一致，正常返回后清理，不同请求生成不同 ID，下游抛异常后仍清理。测试待学习者手打及执行，本轮未宣称通过。

### 2026-09-06：调整教学顺序，测试专题后置

- 学习者明确要求先按主项目进度推进，之后集中学习如何自己设计与编写测试。本次停止安排新增测试代码，不删除已有测试，不把后置事项记为通过。
- 进入 Day 7 OpenAPI 接口文档接入；完成文档页面及现有接口手工调用后，继续 M2 认证与数据权限。
- 后续测试专题从业务规则提取场景开始，学习正常/异常/边界用例、断言、单元/切片/集成测试选择，再补 Trace ID、权限、事务和并发测试。具体时间待后续安排，非定时提醒。
- 本轮只调整课程文档，源码与配置由学习者手打；OpenAPI 尚未接入或验收。

### 2026-09-06：Day 5 复习债重答，Day 7 手打进行中

- Day 5 复习卡按"初答 → 补讲 → 重答"推进：重答已能按序讲出构建链（读 pom 解析依赖、clean 独立清理 target、validate→compile→test→package）与启动链（创建容器 → 组件扫描 → 依赖注入 → 自动配置 → Tomcat 监听 8080），顺序无错。
- 保留两点，不标记 `ACCEPTED`：未明确说出两条链之间的断点（package 产物是 target/ 内的静态 JAR，需显式执行）；未把 springdoc 的 Bean 归属到启动链"自动配置"一步。学习者自定 2026-09-07 晨间重测收口。
- Day 7：学习者已手打 pom.xml 引入 `springdoc-openapi-starter-webmvc-ui:2.8.17`。经学习者明确要求，本轮由助手代改 application.yml：全局 springdoc 开关改为 false，追加 `on-profile: local` 文档块仅 local 开启，代改过程已逐行讲解；编译、Swagger UI 正反验证仍由学习者执行，此后源码恢复学习者手打规则。
- Day 7 剩余验收：编译、local 下 Swagger UI 打开并手工调用 /dev/web 接口、非 local 下文档与 /dev 接口均 404、规范 Git 提交、分层职责口述。
- 2026-09-06 晚助手代跑 `-DskipTests compile`：BUILD SUCCESS，springdoc 2.8.17 依赖解析正常，该项验收通过。检测到 8080 存在旧进程（启动于 01:50，早于 02:46 的配置修改），其 /v3/api-docs 与 swagger-ui 返回 200 属旧配置行为，不作为新配置证据；在旧进程上复查 6 项 HTTP 与 Day 6 记录一致，Day 6 基线无回归。
- 新配置证据待学习者重启取得：local 启动后经 Swagger UI 页面 Try it out 调用接口；非 local 启动确认 /v3/api-docs、/swagger-ui/index.html、/dev/web/* 均 404。

### 2026-09-06：Day 7 验收挂账，进入 Day 8 M2 认证

- 学习者表示已了解 Profile/OpenAPI 内容，要求暂缓重启验证与口述，继续推进课程。Day 7 三项挂账，不标记通过：① 新配置的 Swagger UI 双向验证（local 可用、非 local 全 404）；② Day 6+7 统一规范 Git 提交；③ 分层职责口述。
- 已完成：编译 BUILD SUCCESS（springdoc 2.8.17 解析正常）；旧进程 6 项 HTTP 复查与 Day 6 基线一致。
- 按 2026-09-03 进入 Day 6 的先例，验证债不阻塞开课。Day 8 主题：Spring Security 全貌与请求链路（FilterChain 位置、认证/授权、SecurityContext、能力边界），当日任务为设计与口述，暂不手打。
- Day 8 开课后学习者纠正教学方式：应先完整讲授知识点并给出代码示例，边写边讲、随讲随问，最后统一作答；"先布置题目、后教学"的顺序不再使用。已同步更新学习方式记录。
- 讲授纠错：第一版链路图与文字互相矛盾，学习者当场抓出。2026-09-08 重新核对实际源码：`RequestTraceFilter` 已显式标注 `@Order(Ordered.HIGHEST_PRECEDENCE)`，因此当前设计目标是 Trace Filter 先进入、Security FilterChain（默认注册 order=-100）后执行，使 Security 产生的 401/403 也能获得 Trace ID；最终顺序仍以 M2 的实际响应头和日志实验为准。

### 2026-09-07：Maven 镜像修复，Day 8 继续讲授

- 学习者自行将 spring-boot-starter-security 加入 pom.xml（原计划 Day 9 添加），构建报中央仓库连接超时。诊断为无 settings.xml 直连 repo.maven.apache.org 超时；经学习者要求由助手创建 `~/.m2/settings.xml` 配置阿里云镜像（mirrorOf 仅 central）。
- 验证：`-DskipTests compile` BUILD SUCCESS，日志显示全部经 aliyun-central 下载；spring-boot-starter-security 3.5.16 与 spring-security-config 6.5.11 已入本地仓库。环境配置类改动由助手完成，项目源码仍由学习者手打。
- Day 8 继续讲授：lambda DSL 读法、SessionCreationPolicy 四档、STATELESS 与 JWT 的关系、CSRF 攻击原理与关闭条件。附带现象预告：security 依赖已进 classpath，下次启动全接口默认上锁。
- Day 8 收问：❻ 通过（STATELESS 下每次请求由 Authorization 头 Bearer Token 重新解析身份）。❼ 结论正确并补讲机制：无 Token 失败的根因是浏览器不自动携带该头且同源策略限制；偷到 Token 则属凭证失窃威胁而非 CSRF，防线是 HTTPS 与短有效期。❶~❺ 未作答，转入口述债，不阻塞推进。
- 助手经 jshell 实测 spring-security-crypto 6.5.11 的 BCryptPasswordEncoder：同一密码两次 encode 哈希不同（随机盐）、密文 60 字符、matches 对错分明，作为 Day 9 课堂证据；并生成种子账号 admin/Admin@123 的真实哈希供 V2 迁移使用。
- Day 9 手打任务发布：①规范提交 Day 6+7（清 M1 提交挂账）；②V2__create_user_table.sql 建 `sys_user` 表并插入 admin 种子；③SecurityConfig（规则表 + STATELESS + 关 CSRF + PasswordEncoder Bean）；④DbUserDetailsService（通过仓库查询 `sys_user`）；⑤启动后观察并记录无凭证访问的拒绝形态，供 Day 10 定制 401/403 JSON 入口点时对比。
- 应学习者要求，Day 9 任务④升级为真实业务分层风格并以 V2.1 需求基线校准：新增 `identity` 模块（UserAccount record + UserRepository，SQL 只出现在仓库层，返回 Optional）；DbUserDetailsService 与 SecurityConfig 归入 `identity.security`；auth DTO/Service/Controller 留待登录/JWT 阶段建立。

### 2026-09-07：真实面经调研完成，Java 后端 × Agent 路线重排

- 新增 `JAVA_BACKEND_AGENT_RESEARCH_2026.md`，纳入 24 份 2025-03 至 2026-09 的一手面经、1 份声称共 45 场但按 43 条记录统计且未解释差额的个人复盘，以及 10 个定向 Agent 岗位；明确排除培训题库、无原始轮次的汇总和无分母“命中率”。
- 三张截图已反查到对应原帖：字节全栈研发一面、快手 Java 后端一面、字节 AI 全栈研发二面。截图用于核验真实题序，不外推为全行业概率。
- 路线结论：传统八股没有过时，但必须转为“项目/实习开场 + Java/MySQL/Redis/并发基础 + 方案/失败/规模追问”；Security 以可运行闭环收口，MySQL/并发/SQL 证据提前到 09-27 前。
- 新增 Agent 隔离并行线，定位为 Java Agent 应用/平台工程，不转向模型训练；每天 45 分钟，先做 API、RAG、Tool、Workflow、Eval/Trace 与安全。
- 本次只调整文档，不修改业务源码；现有 `pom.xml`、`SecurityConfig` 和其他工作区改动均保留原状。调研文档及本次路线修改尚未 Git 固化，因此看板状态为 `VERIFYING`。

### 2026-09-08：按 V2.1 基线复核，继续 Day 9 身份加载链

- 已按修改时间检查最新三份核心文档及当前源码。路线以 `MEDFLOW_REQUIREMENTS.md` 的稳定产品基线、`PROJECT_PROGRESS.md` 的动态进度和 `JAVA_BACKEND_AGENT_RESEARCH_2026.md` 的优先级依据分工解释。
- 纠正文档遗留漂移：身份表统一使用需求基线中的 `sys_user`，认证接口统一使用 `/api/v1/auth/login`，身份相关源码归入 `identity` 模块；不再沿用旧写法 `med_user`、`/api/auth/login` 或顶层 user 模块。
- 当前源码仍只有 Security 依赖和初版 `SecurityConfig`；V2 迁移、UserAccount、UserRepository、DbUserDetailsService 尚未创建。本课进入完整讲授和学习者手打阶段，未记录编译或运行通过。

### 2026-09-08：身份加载链完成，进入登录/JWT 接链

- 工作区已出现 `sys_user` V2 迁移、UserRole/UserStatus、UserAccount、UserRepository、DbUserDetailsService、JWT 依赖和初版 JwtService；尚未出现登录 DTO/VO、AuthService、AuthController。
- 助手只读验证 `mvnw -DskipTests compile`：15 个主源码文件，Java 25，BUILD SUCCESS。该结果不代表应用启动或 Security 行为验收。
- 通过 MySQL JDBC 只读核对：Flyway V1/V2 均 success；`sys_user` 表存在；admin 为 ADMIN/ENABLED、token_version=0、BCrypt 长度 60。3306 正在监听，8080 未监听。
- 当前结构缺口：SecurityConfig 文件物理路径仍在 `com/qqlin/medflow/config`，其 package 已声明 `com.qqlin.medflow.identity.security`，需要移动以保持目录与包一致。
- 当前运行缺口：JwtService 依赖 SecretKey，但 SecurityConfig 尚无 SecretKey/JwtDecoder/Resource Server/AuthenticationManager 接链；JWT 配置当前只位于 local YAML 文档中。下一步先形成登录签发与 Bearer 验证闭环，再补统一 401/403、账号/Token 失效和权限负例。
- 依赖顺序校正：当前没有患者、医生、排班或预约真实接口，不提前搭建伪对象权限接口。当前 Security 闸门先验收认证基础设施与角色级 RBAC；医生只能访问自己排班在 S1 验收，患者只能访问本人就诊人与预约在 S2 验收，M2 在滚动证据齐备前保持 IN_PROGRESS。

### 2026-09-08：登录/JWT 与统一安全响应基础闭环完成

- 助手按学习者明确授权，直接新增 `SecurityErrorResponseWriter`、`RestAuthenticationEntryPoint`、`RestAccessDeniedHandler`，并将它们接入 `SecurityConfig` 的 `exceptionHandling` 和 OAuth2 Resource Server；`JwtDecoder` 同时显式接入 Resource Server。
- `mvnw -DskipTests compile`：22 个主源码文件、Java 25、`BUILD SUCCESS`；`git diff --check` 无空白错误。local 应用启动成功，Flyway 校验 V1/V2 且数据库无需迁移。
- 最小 HTTP 证据：`admin/Admin@123` 登录成功；错误密码为 401；无 Token 与篡改 Token 均为 `401 {"code":"UNAUTHORIZED"...}`；合法 ADMIN Token 访问 `/api/v1/doctor/**` 为 `403 {"code":"FORBIDDEN"...}`；响应均保留 Trace ID，401 带 `WWW-Authenticate: Bearer`。
- 不把未做的内容写成完成：未等待真实过期 Token、未实现 tokenVersion 对每次 Bearer 请求的失效检查、未验证禁用账号持旧 Token 的拒绝；对象级数据权限必须等 S1/S2 真实资源出现后验收。
- 按范围冻结规则停止深挖内置 Filter 源码。下一学习单元进入 S1：最小组织数据、排班时间冲突、发布与 Slot 同事务、必要索引。

## 14. 当前唯一下一步

现有代码已经越过 S1 开始阶段。确认预约本单元的代码与验证记录独立维护，未完成的全套测试、医生对象权限、tokenVersion 失效和 Git 固化仍保留。

**当前唯一下一步：** 确认预约专项完成后，实现取消与超时关闭；两者必须在同一事务中执行状态 CAS、仅获胜者归还一次号源、追加状态历史。先完成 MySQL 业务操作与竞争验证，再接入 MQ。号源/预约查询随后补齐患者演示闭环。
