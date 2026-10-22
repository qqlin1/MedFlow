# MedFlow 两天业务扩展交付与验收

验收日期：2026-10-19。本报告记录当前工作区实现及实际运行结果，不将历史性能实验、计划或模拟 DOM 测试当作本轮生产能力证明。需求见 [两天交付需求](TWO_DAY_DELIVERY_REQUIREMENTS.md)，学习和复述顺序见 [两天指南](TWO_DAY_LEARNING_GUIDE.md)。

> 2026-10-20 后续迭代：药品目录、处方草稿/提交/放弃、模拟缴费和三张统计图已增加到当前代码范围，规则见 [新增需求](PRESCRIPTION_STATISTICS_REQUIREMENTS.md)，状态见 [业务台账](BUSINESS_PROGRESS.md)，新增结果见 [处方与统计交付报告](PRESCRIPTION_STATISTICS_DELIVERY.md)。本文正文、测试数字和“本轮范围”均保留10-19快照，不作为新增功能的验收证据。

后续 Docker 交付已在同日完成真实构建、三账号登录和停止重启验收，新增结果见 [Docker 验收](DOCKER_ACCEPTANCE.md)；本文测试数量保留本轮业务扩展当时的快照。

## 完成交付

现在能演示：管理员开通医生账号和档案 → 批量创建排班草稿 → 发布号源 → 患者按科室/医生/日期查号、预约并确认 → 当日签到 → 医生查看本人队列、开始接诊 → 保存四字段病历 → 完成接诊 → 患者查看已完成结果。

| 业务 | 实现与边界 |
| --- | --- |
| 医生开户 | 管理员一次建立 BCrypt 登录账号与医生档案，固定 DOCTOR 角色，同事务提交；档案写入失败账号也回滚 |
| 排班管理 | 数据库分页目录、日期/科室/医生/状态筛选、排班及 Slot 详情；同资源 1–7 个不重复未来日期批量草稿，任一日冲突整批回滚 |
| 取消出诊 | 未来未开诊、无待确认或已确认预约才能取消，保留原因/操作人/时间；排班与号源状态同事务更新，不自动退约或改约 |
| 本人数据 | 医生身份由 JWT uid 映射档案；排班、预约、接诊 SQL 均限定本人。患者数据经就诊人 owner 校验 |
| 签到与队列 | 当天、开始前 30 分钟起至时段结束前可签到；预约唯一接诊；待就诊→就诊中→已完成，待就诊也可过号，过号本轮为终态 |
| 医生并发 | Doctor 行锁串行化开始接诊；生成列唯一约束兜底，医生同时最多一条 IN_PROGRESS |
| 电子病历 | 主诉、现病史、诊断、医嘱；草稿版本校验，完成至少需要已保存主诉及诊断；完成后只读，患者仅看到完成后正文 |
| 旧预约保护 | 签到与取消沿 Slot→Appointment 同序争锁；已有接诊不能取消。接诊完成/过号不归还容量，预约 BOOKED 与接诊状态分别表达 |
| 前端 | Spring Boot 静态托管原生 JS，三角色页面；加载/空态/错误/防重、病历冲突保留输入、先保存后完成、手机布局。移除旧本机排班缓存和无效页面代码 |

新增迁移 V9 创建接诊、病历和接诊历史三表；V10 添加取消出诊审计字段。未重写 V1–V8，也未引入外部前端依赖。

## 实际验证结果

以下各层独立统计，不把 HTTP 断言与 JUnit 数量相加作为一个测试总数。

| 层次 | 结果 | 本地输出 |
| --- | --- | --- |
| 普通 Service / Controller 测试 | 184 项，失败 0，错误 0 | [unit log](../target/two-day-unit.log) |
| 新接诊与排班真实 MySQL | 16 项：接诊 9、排班管理 7，全通过 | [new MySQL log](../target/two-day-mysql-it.log) |
| 真实 MySQL 业务回归 | 39 项：开户回滚 1、确认 32、容量 1、幂等 1、原排班并发 4，全通过 | [regression log](../target/two-day-regression-it.log) |
| 前端源码行为测试 | 102 项通过；已移除 3 项仅测旧本机排班缓存的测试 | [frontend log](../target/two-day-frontend.log) |
| 独立应用 HTTP | 50 条检查通过，使用真实 MySQL、Flyway V1–V10、JWT、角色和 HTTP 信封 | [HTTP log](../target/clinical-http-final.log)、[run metadata](../target/clinical-http/latest.json) |
| 浏览器人工操作 | 患者签到→医生接诊→草稿保存→完成→患者读取；管理端两日批量、刷新、发布、取消；1440px 工作台截图、760px 管理页和 390px 手机布局 | 下方截图 |
| 打包 | Maven package 成功，JAR 包含静态资源与新迁移 | [build log](../target/two-day-package.log) |

普通测试运行于 16:18，新增 MySQL 专项于 16:15，MySQL 回归于 16:21，HTTP 于 16:19（香港时间）。前端在清理死代码后重新运行。源码静态资源通过真实应用同源访问；测试不是双击 HTML。浏览器验收未见 JavaScript warning/error。表格在手机上使用内部横向滚动，页面主体无横向溢出。

真实故障与竞争包括：两个签到得到同一 Encounter、签到与取消互斥、两个不同患者竞争同一医生开始接诊、同版本病历保存竞争、完成后禁止写入、患者看不到草稿、批量后一天冲突回滚前一天、取消出诊与预约两种获胜次序、开户后档案故障回滚。锁等待测试在取得锁之后读取数据库时间，防止用等待前的时间绕过截止规则。

HTTP 测试为了在任意白天运行，会在**自己的隔离库**插入当天整日时段和虚构已确认预约；正常创建排班仍要求未来日期、使用班次拆分时段。额外保留一条当天预约用于浏览器操作。测试数据、端口及测试医生账号不属于正常业务库的种子数据。

## 浏览器证据

医生工作台：

![医生工作台](images/doctor-workspace.png)

管理端服务端目录（批量创建、发布再取消后）：

![管理排班](images/admin-schedules.png)

手机患者查看刚完成的病历：

![手机就诊结果](images/patient-visit-mobile.png)

更多页面：[医生手机页面](images/doctor-mobile.png)、[病历草稿](images/medical-record.png)。截图均为虚构验收数据。

## 复现命令

项目根目录运行，JDK 25，原生 MySQL 8.4 工具；前端测试另需 Node.js。以下 MySQL 脚本自建临时 MySQL 进程和限定库名，不连接现有应用库。

```powershell
.\mvnw.cmd test
node --test tools/test-frontend-api.cjs tools/test-frontend-dashboard.cjs tools/test-frontend-workflows.cjs tools/test-frontend-clinical.cjs
.\tools\test-confirmation.ps1 -TestSelection 'EncounterConcurrencyMySqlIT,ScheduleManagementMySqlIT'
.\tools\test-confirmation.ps1 -TestSelection 'DoctorAccountMySqlIT,AppointmentConfirmationMySqlIT,AppointmentCapacityMySqlIT,AppointmentIdempotencyMySqlIT,ScheduleConcurrencyMySqlIT'
.\tools\test-clinical-http.ps1
.\mvnw.cmd -DskipTests package
```

`test-clinical-http.ps1 -KeepRunning` 可保留临时环境做浏览器演示。脚本打印 READY 地址和 stop marker 路径；写入 stop 文件后脚本只关闭自己的应用/MySQL，保留 target 中数据与日志。其他 MySQL 专项脚本结束会清理自己的数据库。target 证据是本地输出，清理 target 后应重新执行命令获取。

正常本地启动和演示账号见 [前端说明](FRONTEND.md)。空库的医生演示种子不作为新账号密码承诺；通过管理员页面新建可控的医生账号。前后端打在同一个 JAR，数据库升级由 Flyway 自动完成。

本轮临时预览的应用和 MySQL 均已停止，确认两个所属 PID 已退出；数据与日志保留于 target。最终打包在退出预览后成功完成，避免 Windows 运行中 JAR 的文件占用。

## 源码阅读入口

- [DoctorAccountService](../src/main/java/com/qqlin/medflow/organization/provisioning/DoctorAccountService.java)：账号与档案事务。
- [ScheduleManagementService](../src/main/java/com/qqlin/medflow/scheduling/management/ScheduleManagementService.java)、[管理 SQL](../src/main/resources/mapper/scheduling/ScheduleManagementMapper.xml)：目录、批量和取消出诊。
- [EncounterService](../src/main/java/com/qqlin/medflow/encounter/service/EncounterService.java)、[接诊 SQL](../src/main/resources/mapper/encounter/EncounterMapper.xml)：签到、本人接诊、医生并发、病历版本和完成。
- [V9](../src/main/resources/db/migration/V9__create_encounter_tables.sql)、[V10](../src/main/resources/db/migration/V10__schedule_management.sql)：约束与审计。
- [clinical.js](../src/main/resources/static/assets/clinical.js)：医生、排班与患者就诊结果 UI；[api.js](../src/main/resources/static/assets/api.js) 封装请求。

## 范围限制

本轮完成可解释的预约到病历业务闭环。药品、处方收费、真实支付、库存、统计趋势图、停诊自动补偿/通知/改约、补叫、病历修订签章、FHIR、Redis/MQ 和微服务不属于本轮。已有预约时停诊明确拒绝；完成后的病历没有再编辑入口。Docker 的后续真实验收见本文开头链接。

OpenEMR/OpenMRS 用于理解 Appointment、Encounter、病历职责边界；本实现使用现有 Java/MyBatis 模块化单体，不是整套移植。固定版本参考在需求文档中。
