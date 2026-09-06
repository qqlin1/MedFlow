# MedFlow 课程路线与项目进度

> 本文档是 MedFlow 的课程目录、项目看板和验收记录。
> 项目源码、Git、编译、测试和手工验收证据优先于文字记录；“写过”不等于“完成”。

## 1. 当前总览

| 项目 | 当前内容 |
|---|---|
| 项目名称 | MedFlow 门诊资源调度与可靠预约系统 |
| 项目定位 | 围绕有限号源，解决排班冲突、并发预约、幂等、状态竞争、可靠超时和数据权限 |
| 简历角色 | 校招第一项目；OA 作为基础能力项目 |
| 课程重置日期 | 2026-08-30 |
| 主线周期 | 8 周；之后最多选择一个扩展 |
| 每日基准时间 | 约 4 小时 |
| 学习方式 | 学习者本人手打源码；助手默认不直接修改项目源码 |
| 当前课程 | W1 Day 7：进入 OpenAPI 接口文档；按学习者要求后置测试代码专题，Day 5 口述与 Trace ID 验证债保留 |
| 当前里程碑 | M1：项目骨架与统一规范 |
| 当前总体状态 | `IN_PROGRESS` |
| 下一步唯一动作 | 接入 springdoc，在 local 环境打开 Swagger UI 并手工调用现有接口 |

## 2. 范围冻结

### 2.1 八周主线

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
| 当前删除 | 护士、完整叫号接诊、复杂随访、AI/RAG、FHIR、MinIO、微服务、分库分表、完整监控大屏、完整前端 |

任何新增功能都要先回答：“它是否强化五个核心项目故事？”如果不能，就不进入当前路线。

## 3. 固定教学规则

1. 先展示完整课程目录、当天唯一主题和下课条件，不用无止境追问代替教学。
2. 教学顺序固定为：知识全貌 → 项目作用 → 数据/调用流程 → 学习者手打或设计 → 测试验收 → 当天八股 → 进度记录 → 次日预告。
3. 每个知识点必须绑定代码、SQL、测试、日志或故障现象；不为背名词增加业务模块。
4. 2026-09-06 按学习者要求调整：自动化测试代码与测试设计思路后置为独立专题，不阻塞当前主线；已有测试保留，功能开发仍做必要编译和手工验证。权限、事务、状态竞争及并发证据列为待补，未验证能力不标记已验收。
5. MySQL 预约未证明正确前不引入 Redis；幂等取消未证明正确前不引入 RabbitMQ。
6. Redis 主线只做查询缓存；RabbitMQ 主线只做预约超时关闭。
7. 简历只记录已经实现并验证过的能力和数字。
8. 之前关于行锁、条件更新、事务和幂等的零散问答只算预习，不计为正式完成。
9. 每周必须留下“问题—方案比较—失败处理—验证结果”的证据。
10. 项目主线不承担全部八股和算法；每天另留固定时间学习。
11. 概念讲解固定五段模板：一句话定义 → 没有它会怎样 → 工作机制（不超过 5 步）→ 真实样子（配置或代码）→ 边界（管什么、不管什么）。
12. 批改固定三段式：标准答案与思路 → 学习者答案与标准的差距 → 常见错误为什么不成立。

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
| M1 骨架与统一规范 | `IN_PROGRESS` | Day 5 工程初始化完成；Day 6 已有 JDBC/Flyway 配置与 V1 迁移文件、统一响应/异常/Validation 和 local 验收接口；7 项实际 HTTP 验证通过，2026-09-06 核实 7 个 MockMvc 测试通过；Trace ID、OpenAPI、口述及本阶段提交待完成 |
| M2 认证与数据权限 | `NOT_STARTED` | 无代码 |
| M3 排班与号源 | `NOT_STARTED` | 无代码 |
| M4 MySQL 预约 | `NOT_STARTED` | 无代码 |
| M5 并发与 SQL 证据 | `NOT_STARTED` | 无测试数据和报告 |
| M6 Redis 查询缓存 | `NOT_STARTED` | 无代码 |
| M7 RabbitMQ 可靠超时 | `NOT_STARTED` | 无代码 |
| M8 部署与面试证据 | `NOT_STARTED` | 无代码和报告 |

当前环境事实：

- 当前已有 M0 需求、状态机、DDL、API 与事务时序等设计产物，以及可运行的 Spring Boot 最小骨架。
- 当前目录已初始化为 `main` 分支 Git 仓库。
- 当前已具备 POM、Maven Wrapper、启动类、JDBC/MySQL/Flyway 配置与 V1 迁移文件、统一 Web 规范和 local 验收接口。2026-09-05 确认本地 MySQL 3306 与 MedFlow 8080 正在监听，并验证 7 项实际 HTTP 请求。
- 当前 JUnit 测试源码包括 `MedFlowApplicationTests.contextLoads` 和 `WebCheckControllerTest` 的 7 个测试。2026-09-06 核实后者报告为 7 tests、0 failures、0 errors、0 skipped；上下文测试沿用此前通过记录，本次没有重新执行全套测试。
- 本机 Git 2.51.0、Maven 3.9.16 和 Temurin JDK 25.0.3 可用。
- 项目编译目标、Maven 运行时和应用运行时均已统一为 Java 25。
- 当前没有 Docker；W1 只记录依赖，最迟在进入 Testcontainers 前完成环境准备。

## 6. 八周课程总目录

| 周次 | 日期 | 项目章节 | 核心知识 | 周末交付物 |
|---|---|---|---|---|
| W1 | 08-30 至 09-05 | M0 设计 + M1 骨架 | 对象职责、HTTP、Git、Maven、IOC、MVC、异常、Flyway | 范围冻结，设计一致，应用启动，空库迁移和基础测试通过 |
| W2 | 09-06 至 09-12 | M2 认证与数据权限 | Spring Security、BCrypt、JWT、RBAC、401/403、所有权校验 | 登录、账号状态和越权测试通过 |
| W3 | 09-13 至 09-19 | M3 排班与号源 | Java 时间、区间重叠、事务、行锁、索引、状态机 | 排班冲突、发布和 Slot 生成测试通过 |
| W4 | 09-20 至 09-26 | M4 MySQL 预约 | 幂等、条件更新、唯一约束、CAS、MVCC、事务回滚 | 创建、确认、取消和状态历史验收 |
| W5 | 09-27 至 10-03 | M5 并发与 SQL | 线程池、竞态、锁、死锁、B+ 树、`EXPLAIN` | 500 抢 50 与 10 万数据优化证据 |
| W6 | 10-04 至 10-10 | M6 Redis 查询缓存 | Cache Aside、TTL、穿透、雪崩、写后失效、降级 | 热点号源缓存与故障测试 |
| W7 | 10-11 至 10-17 | M7 RabbitMQ 可靠超时 | Confirm、Ack、重试、DLX、幂等、Outbox | 重复消息和中断恢复测试 |
| W8 | 10-18 至 10-24 | M8 部署与面试 | Docker、Linux、Actuator、日志、CI、项目口述 | 一键启动、证据包、README、简历和演示 |

如果某周没有通过验收，顺延当前周，不通过删测试或故障验证来赶日期。

## 7. 校招知识学习地图

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

- FilterChain、Authentication、SecurityContext。
- BCrypt、JWT 签名和失效。
- 认证与授权、401 与 403。
- RBAC 与对象级数据权限。

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
50 分钟：当天完整知识课程
120 分钟：项目手打或设计
30 分钟：自动化测试或设计验收
20 分钟：当天八股脱稿口述
20 分钟：算法练习
```

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

## 14. 当前唯一下一步

Day 6 的统一响应、异常和参数校验已通过 7 项实际 HTTP 验证及 7 个 MockMvc 测试；Day 5 知识口述作为并行复习债保留。

当前唯一下一步：接入 springdoc OpenAPI 与 Swagger UI，仅在 local Profile 开启文档，通过页面手工调用现有接口。测试代码专题后置；实际日志与响应头 ID 一致性仍待核对，M1 不因教学推进自动标记 ACCEPTED。
