# MedFlow 门诊预约系统

B01–B04增量源码已修改，按用户要求尚未编译、测试、迁移或更新Docker；[源码改动与待验证记录](docs/YYGH_ADOPTION_SOURCE_CHANGELOG.md)单独跟踪。下文历史交付的通过数量不代表本轮新代码已验证。

本地尚医通学习产出：[两套真实源码逐项对比](docs/SHANGYITONG_CODE_COMPARISON.md)、[四项业务任务与点击验收](docs/SHANGYITONG_ADOPTION_PLAN.md)。包含双方方法位置、优劣/边界和采用决策；新增候选仍待开发，保留当前技术栈。

2026-10-21：[完整学习清单](docs/LEARNING_CHECKLIST.md)、[新窗口学习提示词](docs/NEW_WINDOW_LEARNING_PROMPT.md)、[手动点击验收](docs/MANUAL_ACCEPTANCE.md)。本次预约与院内档案改造见[交付说明](docs/APPOINTMENT_CHART_DELIVERY.md)和[测试报告](docs/05-test-report.md)。患者先选择科室/医生、阅读资料，再选日期时段；由患者自行确认、自行签到。患者和接诊医生均有同一就诊人的跨医生已完成病历入口。

2026-10-20新增：[医生详情Redis缓存六项实测](docs/DOCTOR_CACHE_DELIVERY.md)，包含首次回源、缓存命中、CLI TTL、事务提交后失效、空值缓存和停机降级。医生姓名编辑接口在该文档；其余Redis/MQ范围继续暂缓。当前已做/未做以[业务台账](docs/BUSINESS_PROGRESS.md)为准。

MedFlow 是 Java / Spring Boot 模块化单体项目，覆盖门诊排班、可靠预约、患者签到、医生接诊、电子病历、药品目录、简版处方与模拟缴费，并提供三张业务统计图。围绕有限号源和业务状态迁移，验证不超卖、重复请求只产生一次副作用、容量只归还一次以及数据不越权。项目使用虚构就诊人数据，模拟缴费不会实际扣款。

> 2026-10-20 范围更新：药品、处方、模拟收费和三图已纳入当前代码范围。当前实现与验证状态统一见 [业务台账](docs/BUSINESS_PROGRESS.md)，规则见 [处方与统计需求](docs/PRESCRIPTION_STATISTICS_REQUIREMENTS.md)，本轮结果见 [处方与统计交付报告](docs/PRESCRIPTION_STATISTICS_DELIVERY.md)。此前文档和测试数字保留各自日期，不代表本轮新增功能的验收结果。

## 前端入口

前端已集成到 Spring Boot，启动应用后访问 [MedFlow](http://127.0.0.1:8080/)，无需安装 Node.js 或单独启动前端服务。

- 患者：就诊工作台、科室或找医生两种预约入口、医生职称/擅长/简介、日期和上午下午号源、就诊人院内历史档案、预约创建与自确认、取消、自签到、已完成病历、本人已提交处方和模拟缴费。预约列表直接显示就诊日期、医生和科室。
- 医生：本人排班与预约、候诊队列、开始接诊、过号、病历草稿保存和完成接诊；就诊中保存、提交或放弃处方草稿，提交后冻结并生成账单。
- 管理员：科室/诊室/医生管理、医生账号开通、班次查看、服务端排班目录、1–7 日批量草稿、发布和受限取消出诊；药品启停与价格维护、预约趋势/科室完成就诊量/医生工作量。
- 页面适配桌面和手机，提供加载/空数据/失败提示、会话失效处理和预约安全重试。

使用方法、模拟缴费步骤和演示账号见 [前端说明](docs/FRONTEND.md)。2026-10-19 的接诊/病历范围与两天学习安排见 [两天交付需求](docs/TWO_DAY_DELIVERY_REQUIREMENTS.md)、[学习指南](docs/TWO_DAY_LEARNING_GUIDE.md)、[历史交付报告](docs/TWO_DAY_DELIVERY_REPORT.md)；2026-10-20 的增量规则及结果使用本页开头的新文档。

第一次使用请照[三角色演示指南](docs/DEMO_DATA_GUIDE.md)：Docker已补齐未来7天号源、今日候诊、病历与处方账单，admin/doctor/patient均可操作；数量和证据见[系统数据交付](docs/SYSTEM_DEMO_DELIVERY.md)。旧版单笔预约实例保留为历史记录。

## 当前范围

| 能力 | 当前状态 |
|---|---|
| 基础资源 | 管理员创建和查询科室、诊室、医生，读取班次模板 |
| 排班与号源 | 服务端目录/详情、1–7 日批量草稿、发布并初始化 Slot；仅允许取消未来未开始且无有效预约的出诊；患者按日期/科室/医生查询 |
| 患者与预约 | 就诊人归属校验，创建、确认、取消、详情、状态历史、账户分页列表 |
| 一致性 | MySQL 条件扣减、有效预约唯一约束、事务回滚、状态条件更新、幂等结果重放 |
| 查询优化 | V8 排序索引与单就诊人查询分支，已有 10 万预约对照实验 |
| 认证权限 | JWT 登录、角色权限、患者对象归属和统一 401/403 |
| 接诊与病历 | 患者签到、医生本人队列、接诊/过号、病历版本校验、完成后只读，患者仅能查看已完成正文 |
| 药品与处方 | 药品目录搜索、启停、价格和版本校验；本人就诊中处方草稿/提交/放弃，后端 BigDecimal 计价与快照，提交后冻结 |
| 模拟收费 | 每张已提交处方一张账单，仅本人模拟缴费；账单状态与唯一模拟支付记录同事务，重复请求不重复登记，无真实扣款 |
| 统计图表 | 按创建日期统计预约趋势并补0；按完成日期统计科室就诊量和医生工作量；默认7天、最多93天，三图附完整表 |
| 历史验证 | 2026-10-04：普通测试130/130、真实MySQL专项39/39、HTTP39项；10-19接诊和10-20处方/统计分别见各自交付报告 |
| 超时 | 默认启用定时数据库扫描；逐条事务、失败隔离、回绕重试；已验证真实自动触发与 Spring 应用上下文重新启动后的补处理 |
| Docker 交付 | 前后端应用镜像 + MySQL + Redis，三角色演示账号自动初始化，双击启停并保留数据库；见 Docker 使用说明 |
| 医生详情缓存 | Redis旁路缓存、正/空值TTL、事务提交后失效、超时与停机回源，六项独立实测见缓存报告 |
| 后续范围 | 真实支付、退款/撤方、药房库存、医保、已有预约的停诊通知/改约、补叫、其他 Redis 场景、RabbitMQ / Outbox |

完整设计见 [需求](MEDFLOW_REQUIREMENTS.md)，当前扩展验收状态见 [业务台账](docs/BUSINESS_PROGRESS.md)。旧学习过程见 [历史项目进度](.codex/PROJECT_PROGRESS.md)，游标/JWT 验证和计时口径见 [2026-10-02 报告](.codex/CURSOR_JWT_REPORT_2026-10-02.md)。历史并发数字保留各自运行日期。

自动超时的代码、失败/重启证据与六题集中学习见 [自动超时报告](.codex/AUTOMATIC_EXPIRATION_REPORT_2026-10-02.md)。

逐单延迟任务与数据库扫描的官方调研及真实对照见 [方案实验报告](.codex/EXPIRATION_STRATEGY_TEST_REPORT_2026-10-02.md)：新增JDK测试5项、MySQL对照7项，包含两种方案各自的独立JVM停止/启动。持久化逐单任务仍属于后续方案，未在本项目实测。

GitHub 真实实现核查见 [2026-10-03 源码调研](.codex/GITHUB_EXPIRATION_IMPLEMENTATIONS_2026-10-03.md)：litemall 的逐单内存任务与启动恢复、mall 的 RabbitMQ 延迟消息及默认未启用的扫描、WooCommerce 的全局数据库扫描。链接固定到提交，外部项目没有在本机运行。

历史整体基线见 [2026-10-03 项目审计](.codex/PROJECT_AUDIT_2026-10-03.md)。后续两项风险已复现/修复，见 [2026-10-04 执行报告](.codex/P0_P1_DELIVERY_REPORT_2026-10-04.md)。医生业务和排班管理已在本轮扩展，以上历史文档不代表当前功能边界。

## Docker 一键启动（推荐）

前后端打在同一个镜像里：前端是 `src/main/resources/static` 下的静态资源，由 Spring Boot 直接托管，不需要单独的 Nginx。当前Compose配置包含应用、MySQL8.4和Redis三个服务；Redis无数据卷或宿主机端口。Redis停止时医生详情降级读MySQL。

```powershell
.\docker-start.ps1
```

2026-10-21新镜像已重建运行，包含医生资料、预约流程、纵向档案、药品/处方/统计、Redis和系统演示数据。下次双击 **启动MedFlow.cmd**，停止用 **停止MedFlow.cmd**，数据保留；启动按当天补齐未来7天演示号源，管理员工作台也可补齐。终端日常命令是`.\docker-start.ps1 -NoBuild`和`.\docker-start.ps1 -Down`；改源码后用`.\docker-start.ps1`重建，见[Docker说明](docs/DOCKER.md)。

脚本会准备 `.env`（缺失且无旧数据卷时生成随机数据库密码与 JWT 密钥）、启动容器、检查健康和三角色登录。上面的默认命令会重新构建镜像，修改代码后使用；日常启动加 `-NoBuild` 即可。

| 角色 | 用户名 | 密码 |
|---|---|---|
| 管理员 | `admin` | `123456` |
| 医生 | `doctor` | `123456` |
| 患者 | `patient` | `123456` |

Docker 默认开启本机演示账号模式，doctor 已绑定医生档案；重复启动不会重复创建账号或档案。普通非 Docker 启动默认不开启该初始化。密码在数据库中保存为 BCrypt；应用账号密码与 `.env` 中的数据库密码不同。

| 操作 | 命令 |
|---|---|
| 启动 | `.\docker-start.ps1` |
| 停止（保留数据） | `.\docker-start.ps1 -Down` |
| 停止并清空数据库 | `.\docker-start.ps1 -Reset` |
| 不重新构建，直接启动 | `.\docker-start.ps1 -NoBuild` |
| 等价的 compose 命令 | `docker compose up -d --build` |

启动后：

- 前端 `http://127.0.0.1:8080/`
- 接口文档 `http://127.0.0.1:8080/swagger-ui/index.html`（由 `MEDFLOW_OPENAPI_ENABLED` 控制，对外部署请设为 `false`）
- MySQL `127.0.0.1:3307`，root 密码见 `.env`

配置在`.env`，模板见`.env.example`。Flyway执行V1–V14：V11药品、V12处方收费、V13演示去重标记、V14医生公开资料，旧库增量升级。数据在`medflow-mysql-data`卷，`-Down`保留，`-Reset`删除；请保留原`.env`。演示数据须同时开启账号与workflow两个开关。

> Docker默认演示模式自动准备排班、就诊人和业务示例：patient查明天即可挂号，doctor处理今日预置候诊；管理员可发布草稿、安排新日期。普通关闭演示模式的空库仍需自行准备资源并发布排班。

## 环境与本地启动


- JDK 25，`java -version` 与 `mvnw.cmd -version` 均应使用 Java 25。
- MySQL8.4；创建空`medflow`库，Flyway启动执行V1–V14，业务演示数据仅在两个演示开关均开启时生成。
- Maven Wrapper 已包含在仓库，首次构建需要下载依赖。
- 当前复现实验脚本使用 Windows PowerShell 和 MySQL 原生二进制；容器启动使用仓库中的 Docker Compose 配置。

在 MySQL 客户端执行：

```sql
CREATE DATABASE medflow
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

在项目根目录的 PowerShell 配置本地连接和签名密钥：

```powershell
$env:MEDFLOW_DB_URL = 'jdbc:mysql://127.0.0.1:3306/medflow?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:MEDFLOW_DB_USERNAME = 'root'
$env:MEDFLOW_DB_PASSWORD = Read-Host '本地 MySQL 密码'
$taskJwtBytes = New-Object byte[] 32
$taskJwtGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$taskJwtGenerator.GetBytes($taskJwtBytes)
$taskJwtGenerator.Dispose()
$env:MEDFLOW_JWT_SECRET = [Convert]::ToBase64String($taskJwtBytes)

.\mvnw.cmd clean package
java -jar .\target\medflow-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.address=127.0.0.1 --server.port=8080
```

以上 JWT 密钥为本次终端生成；重启要继续接受原有 Token 时，需要保留相同密钥。配置定义在 `src/main/resources/application.yml`，密码和密钥不写入仓库。非 `local` 环境默认关闭 OpenAPI 文档和开发检查接口。

另开终端检查应用：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/dev/web/success'
$taskLogin = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/auth/login' -ContentType 'application/json' -Body '{"username":"patient.li","password":"Patient@123"}'
$taskHeaders = @{ Authorization = "Bearer $($taskLogin.data.accessToken)" }
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/patients' -Headers $taskHeaders
$taskWorkDate = [DateTime]::Today.AddDays(1).ToString('yyyy-MM-dd')
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/patient/slots?workDate=$taskWorkDate&page=1&size=20" -Headers $taskHeaders
```

开发账号为 `admin / Admin@123`、`patient.li / Patient@123`。演示患者登录账号与 `med_patient` 就诊人记录是不同概念，空库中可通过 `POST /api/v1/patients` 建立归属。OpenAPI 页面为 `/swagger-ui/index.html`；完整发布到预约流程由下述 HTTP 脚本自动准备虚构数据。

## 测试与复现实验

普通测试不需要手工启动开发数据库；默认 `test` 不执行命名为 `*MySqlIT` 的专项。先清理旧报告，避免将已删除测试的 XML 混入统计：

```powershell
.\mvnw.cmd clean test
```

隔离 MySQL 脚本默认寻找 `C:\Program Files\MySQL\MySQL Server 8.4\bin`，可传 `-MySqlBin '实际目录'`。脚本启动属于本次运行的独立 MySQL 进程，在临时空库执行迁移，结束后关闭并清理本次临时库。专项测试拒绝普通业务库连接；不要手工将 `MEDFLOW_CONFIRMATION_TEST_URL` 指向开发库。

```powershell
# 预约生命周期、Controller 与真实 MySQL 回归
.\tools\test-confirmation.ps1

# 生命周期与真实定时调度、应用上下文重新启动补处理
.\tools\test-confirmation.ps1 -TestSelection 'AppointmentConfirmationMySqlIT,AppointmentExpirationSchedulerMySqlIT'

# 10-04 P0/P1：生命周期、调度、排班并发、容量和同键回归
.\tools\test-confirmation.ps1 -TestSelection 'AppointmentConfirmationMySqlIT,AppointmentExpirationSchedulerMySqlIT,ScheduleConcurrencyMySqlIT,AppointmentCapacityMySqlIT,AppointmentIdempotencyMySqlIT'

# 逐单延迟任务与扫描对照，含独立 JVM 终止/重新启动
.\mvnw.cmd '-Dtest=AppointmentTimerBehaviorTest' test -q
.\tools\test-confirmation.ps1 -TestSelection 'AppointmentExpirationStrategyMySqlIT'

# 三项并发/锁实验，各自有独立证据
.\tools\test-confirmation.ps1 -TestSelection AppointmentCapacityMySqlIT
.\tools\test-confirmation.ps1 -TestSelection AppointmentIdempotencyMySqlIT
.\tools\test-confirmation.ps1 -TestSelection AppointmentDeadlockMySqlIT

# 10 万预约、执行计划、旧新路径结果一致性
.\tools\test-confirmation.ps1 -TestSelection AppointmentSqlPerformanceMySqlIT

# 构建应用，在独立 HTTP / MySQL 进程中跑真实接口链路
.\tools\test-appointment-http.ps1
```

MySQL 日志保留在 `target/confirmation-mysql/`，HTTP 脚本日志保留在 `target/appointment-http/` 下的本次运行目录。最新测试数量以本次报告为准；不同命令会包含相同测试，不能简单相加。

## 已保存的历史证据

| 场景 | 已有结果及测量边界 | 原始证据 |
|---|---|---|
| 500 个请求争抢容量 50 | 2026-09-23，3 个独立临时库均成功 50、业务冲突 450、余量 0；Service + MySQL 一致性实验 | [第 1 轮](.codex/evidence/capacity-500-50-run1.json)、[第 2 轮](.codex/evidence/capacity-500-50-run2.json)、[第 3 轮](.codex/evidence/capacity-500-50-run3.json) |
| 同键并发重放 | 2026-09-23，修复后 2 轮各 32/32 返回同一完整结果，预约 / 历史 / SUCCESS 各 1，容量仅减 1 | [第 1 轮](.codex/evidence/idempotency-same-key-after-fix-run1.json)、[第 2 轮](.codex/evidence/idempotency-same-key-after-fix-run2.json) |
| 反序锁定死锁 | 2026-09-23，Slot / Appointment 反序产生 1213 / 40001，受害事务整体回滚；同序对照通过 | [死锁对照](.codex/evidence/deadlock-inverse-vs-fixed-2026-09-23.json) |
| 10 万预约 SQL | 2026-09-24，单就诊人首页列表路径 85.78 → 1.99 ms、深页 108.55 → 37.93 ms，5 轮中位数 | [原始结果](.codex/evidence/sql-100k-v8-final-2026-09-24.json)、[报告](.codex/APPOINTMENT_SQL_100K_REPORT_2026-09-24.md) |
| 普通测试基线 | 2026-09-24，58/58；这是历史基线 | [Surefire 汇总](.codex/evidence/sql-100k-v8-unit-surefire-2026-09-24.txt) |

历史 SQL 数字来自 MySQL 8.4.8、100,000 条预约的人工倾斜样本；单就诊人账户占 40,000 条。优化后计时含就诊人探测，不含 COUNT/HTTP。多就诊人仍用原路径，深 OFFSET 仍需越过前面的索引项，实验不代表生产 QPS。

2026-10-02 新游标对照：相同单就诊人深页 51 行、预热 2/交替测量 5 轮，JDBC 中位数 OFFSET 20000 **22.91 ms**、已知复合游标 **2.44 ms**，读取节点 actual rows **20051→51**。实际游标 Service（含探测/事务/映射）中位 **3.66 ms**。不含 HTTP、认证、COUNT 或获取深页游标的成本；没有给多人性能承诺。[结果与边界](.codex/CURSOR_JWT_REPORT_2026-10-02.md)

## 游标加载更多接口

首批 `GET /api/v1/appointments/cursor?size=50`；后续将 `data.nextCursor.createdAt/id` 原值作为 `afterCreatedAt/afterId` 成对传回。两个字段排序规则为 `created_at DESC,id DESC`，时间要求毫秒精度、ID 正整数。

```http
GET /api/v1/appointments/cursor?size=50&afterCreatedAt=2026-10-02T10:00:00.123&afterId=150
Authorization: Bearer <患者令牌>
```

响应 `data` 包含 `items/hasNext/nextCursor/size`，无 total；多查的一条只用于 hasNext，游标取实际返回的最后一条。无下一批时 hasNext=false、nextCursor=null。跨请求没有共享快照，游标支持连续翻页，任意页码跳转仍使用原页码接口。账号禁用/锁定或版本变化会使旧令牌在下一次认证读取后返回 401；该验证每次新增一次账号主键查询。

## 自动到期关闭

默认启动 5 秒后扫描；每轮结束再等 5 秒，每轮最多处理 100 条到期 `PENDING_CONFIRMATION`，以数据库时间判断。每条预约单独事务，关闭、归还容量和历史一起提交；失败项保留待确认状态，扫描游标继续向后、尾部回绕重试。应用恢复运行后重新扫描尚未完成的记录。

`MEDFLOW_EXPIRATION_ENABLED=false` 可关闭后台扫描；批量和间隔定义在 `medflow.appointment.expiration` 配置中。当前是数据库轮询；没有截止后 5 秒完成的保证。已验证完整 Spring 应用上下文停止/启动；后续同日对照还验证了截止前终止独立JVM、停机期间到期、新JVM扫描补处理，未注入事务提交中的崩溃。[最初验收](.codex/AUTOMATIC_EXPIRATION_REPORT_2026-10-02.md)、[独立JVM对照](.codex/EXPIRATION_STRATEGY_TEST_REPORT_2026-10-02.md)

## 核心代码入口

| 路径 | 责任 |
|---|---|
| `appointment/controller/AppointmentController.java` | 当前用户、请求校验、创建 / 确认 / 取消 / 查询入口 |
| `appointment/service/AppointmentService.java` | 创建事务、幂等重放、状态竞争、容量归还、单就诊人查询分支 |
| `appointment/service/AppointmentExpirationWorker.java` | 到期扫描、有限批次、逐条事务调用、失败隔离和游标回绕 |
| `appointment/service/AppointmentExpirationScheduler.java` | 定时触发与本批处理日志 |
| `scheduling/service/SlotBookingService.java` | 条件扣减、容量归还及锁顺序 |
| `identity/security/SecurityConfig.java` | JWT 校验链与角色授权 |
| `medication/service/DrugService.java` | 药品搜索、启停、价格边界和编辑版本 |
| `billing/service/PrescriptionService.java` | 本人处方保存/提交/放弃、价格快照、唯一账单和模拟缴费 |
| `statistics/StatisticsService.java` | 预约趋势补0、完成接诊聚合及有界日期范围 |
| `src/main/resources/mapper/appointment/AppointmentMapper.xml` | 归属查询、列表、COUNT、状态条件更新 |
| `src/main/resources/mapper/appointment/IdempotencyMapper.xml` | 唯一键记录、冻结结果、`FOR SHARE` 回查 |
| `src/main/resources/db/migration/` | Flyway 结构、约束和索引演进 |

表中 Java 路径均相对于 `src/main/java/com/qqlin/medflow/`。课程收口使用 [SQL 八题批量验收](.codex/SQL_INTERVIEW_BATCH_2026-10-02.md)，投递准备使用 [10 月 7 日计划](.codex/OCTOBER_7_APPLICATION_PLAN.md)。

## 三条可复用的简历表述

以下表述仅引用 2026-09-23 / 24 已保存的历史证据；在最新代码完成回归后固化为投递版本。

- 使用 MySQL 事务、条件扣减和唯一约束保护预约与号源一致性；在本机真实 MySQL 的 3 轮“500 个请求争抢 50 个号源”实验中，每轮成功 50、业务冲突 450、剩余容量 0。
- 通过 Idempotency-Key、请求哈希和冻结结果实现预约重放；定位重复键回查的共享锁升级死锁，将只读回查改为 `FOR SHARE`，2 轮各 32 次同键并发均返回同一业务结果，号源仅扣减一次。
- 基于 10 万条预约的 `EXPLAIN ANALYZE` 对照，结合单就诊人分支与联合排序索引优化列表读取；本机 5 轮中位数从 85.78 ms 降至 1.99 ms，计时含就诊人探测，不含 COUNT 与 HTTP。
