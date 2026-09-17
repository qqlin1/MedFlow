# 预约确认：实现、验收与面试讲解

本单元：`POST /api/v1/appointments/{appointmentId}/confirm`。

从本单元开始，按学习者授权由助手实现代码、执行验证，再逐项讲解；学习者重点审查业务规则、理解失败边界并练习口述。

## 1. 这次解决什么问题

创建预约时已经扣掉一个 Slot 名额，并写入 `PENDING_CONFIRMATION`。确认操作表示患者保留这份预约，状态变成 `BOOKED`，容量保持不变。

| 请求时的情况 | 结果 | 确认历史 | Slot 容量 |
|---|---|---|---|
| 本人预约、待确认、未截止 | 200，BOOKED | 新增一条 CONFIRMED | 不变 |
| 本人预约、已经 BOOKED | 200，BOOKED | 不再新增 | 不变 |
| 已 CANCELLED 或 EXPIRED | 409 | 不新增 | 不变 |
| PENDING 但已超过确认截止 | 409 | 不新增 | 不变 |
| 不存在或属于别人 | 404 | 不新增 | 不变 |

过期拒绝与自动释放容量是两个业务动作。此接口只拒绝过期确认；后续 `expire` 必须把状态、容量归还和历史放入同一事务，不能只修改 EXPIRED。

## 2. 按调用顺序读代码

1. [AppointmentController](../src/main/java/com/qqlin/medflow/appointment/controller/AppointmentController.java)：`confirm` 从已校验的 JWT 取用户 ID；URL 只接收 appointmentId；返回统一 Result。
2. [ConfirmAppointmentResponse](../src/main/java/com/qqlin/medflow/appointment/dto/ConfirmAppointmentResponse.java)：只向客户端返回 appointmentId/status。
3. [AppointmentService](../src/main/java/com/qqlin/medflow/appointment/service/AppointmentService.java)：`confirm` 定义事务，安排归属锁读、重复确认处理、状态校验、CAS、历史写入。
4. [AppointmentRepository](../src/main/java/com/qqlin/medflow/appointment/repository/AppointmentRepository.java)：把 Mapper 的 null 转成 Optional，把受影响行数转换为 boolean，并要求确认日志确实插入一行。
5. [AppointmentMapper](../src/main/java/com/qqlin/medflow/appointment/mapper/AppointmentMapper.java)：声明三个持久层方法，方法名与 XML 的 id 对应。
6. [AppointmentMapper.xml](../src/main/resources/mapper/appointment/AppointmentMapper.xml)：实现带所有权的锁读、条件更新和确认历史 INSERT；显式映射不可变 Appointment record。
7. [ErrorCode](../src/main/java/com/qqlin/medflow/shared/exception/ErrorCode.java)：新增预约不存在、不允许确认、确认截止三类业务错误。
8. [GlobalExceptionHandler](../src/main/java/com/qqlin/medflow/shared/exception/GlobalExceptionHandler.java)：补充参数类型不匹配、缺少请求头的 400；Controller 使用 MVC 内置方法校验，非法路径参数不落入未知 500。

现有 Flyway 表和枚举已经支持 BOOKED/CONFIRMED，因此不新增迁移，也不修改旧迁移。确认时间可以在状态历史的 created_at 中查到；当前没有 confirmed_at 字段。

## 3. 必须讲清楚的四个细节

### 所有权必须沿真实数据关系检查

```text
JWT.uid
  ↕ 必须一致
Patient.ownerUserId ← Appointment.patientId
```

登录成功只证明账号身份。SQL 必须通过预约的 patient_id 关联 med_patient，再匹配 owner_user_id；不能让前端拿自己的 patientId 搭配别人的 appointmentId 蒙混过关。

### 行锁、条件更新各负责什么

先执行带所有权条件的 `SELECT ... FOR UPDATE`，同一预约的状态操作需要等待当前事务结束。它是当前读，后来的确认请求能看到前一个事务提交后的 BOOKED。

再执行：

```sql
UPDATE med_appointment appointment
INNER JOIN med_patient patient ON patient.id = appointment.patient_id
SET appointment.status = 'BOOKED'
WHERE appointment.id = ?
  AND patient.owner_user_id = ?
  AND appointment.status = 'PENDING_CONFIRMATION'
  AND appointment.confirm_deadline > CURRENT_TIMESTAMP(3);
```

CAS 在这里指“只有数据库里的旧状态符合预期才更新”。MySQL 条件 UPDATE 会用到数据库锁；不能把它说成 Java AtomicInteger 那种无锁自旋。

本方案先拿锁，再发第二条 UPDATE。截止判断使用第二条语句的数据库时间，不沿用等待锁之前取得的 Java 时间。不能把这一点简化成“任何 SQL 改用 NOW 就能解决锁等待跨截止”，关键还在语句执行顺序。

JOIN 锁读也可能锁住匹配的 Patient 行；未来取消/超时要统一资源访问顺序，不能声称绝对不会发生死锁。

### 重复确认为什么不需要再扣号

库存已经在创建预约事务里扣过。第一次确认修改状态并写一条 CONFIRMED；后续请求读到 BOOKED 直接返回，既不修改容量，也不重复写历史。

这里是状态操作的幂等：已 BOOKED 时再次确认没有新增效果。如果预约随后被取消，再来确认应返回冲突，不能把取消状态重新变成 BOOKED。它与创建预约的“幂等键冻结首次成功响应”语义不同。

### 事务必须覆盖历史写入

确认状态 UPDATE 成功后，如果历史 INSERT 失败，异常向外抛出，Spring 事务代理回滚 BOOKED 状态。Controller 外部调用 Service 才会走事务代理；单纯 Mockito 测试不能证明数据库回滚。

## 4. 学习投入怎么分配

| 深度 | 文件/知识 | 学到什么程度 |
|---|---|---|
| 快速了解 | Response record、Controller 注解 | 能说明请求参数从哪来、响应长什么样 |
| 快速了解 | Mapper 接口、薄 Repository | 能追踪方法如何到 XML，知道 Optional 和影响行数的含义 |
| 结合已有知识巩固 | ErrorCode 与统一异常 | 能区分 HTTP 400/401/403/404/409 与数据库状态 |
| 必须讲透 | 预约所有权 JOIN | 能说明合法 JWT 为什么仍然可能越权 |
| 必须讲透 | @Transactional、FOR UPDATE、CAS | 能推演两个请求的等待、提交、回滚和最终状态 |
| 必须讲透 | 截止时间与日志一致性 | 能说明等锁后过期怎么办、日志失败为什么状态也回滚 |

## 5. 面试表达

> 创建预约时先占用号源，确认时只把待确认状态改为已预约。我从 JWT 获取当前用户，沿预约与就诊人的关系检查归属，并在事务中锁住预约，再用状态和截止时间做条件更新。只有这次更新成功才追加确认历史；已确认的重复请求直接返回当前结果，不再次扣减容量、不重复写历史。历史写入失败会使状态更新一起回滚。

面试官继续问“为什么同时有锁和 CAS”时：锁用于按当前状态处理重试、串行化同一预约操作；CAS 把允许的迁移和截止规则保留在最终写入语句。正确的 CAS 方案也可以不显式先锁，本项目选择先锁以便清晰处理当前状态及等待后的截止判断，代价是增加一次 SQL 与锁等待。

## 6. 验证入口与证据边界

无需 MySQL 的 Service 与 Web 测试：

```powershell
.\mvnw.cmd '-Dtest=AppointmentServiceTest,AppointmentControllerTest' test
```

包含真实 MySQL 的专项验收：

```powershell
.\tools\test-confirmation.ps1
```

脚本使用本机 MySQL 8.4 二进制，创建临时数据目录与随机 loopback 端口。PID 检查确认连接的是本次进程后，才建立 medflow_confirmation_test 并执行 Flyway。结束后停止本次进程、删除本次临时数据目录，日志保留在 target/confirmation-mysql。可用 `-MySqlBin` 指定其他安装目录。

Mockito 测试证明分支；Web 测试使用真实签发/验证的 JWT 和正式角色规则；MySQL 集成测试证明 XML 映射、数据库状态、确认历史、容量不变、并发与回滚。完整测试仓库还保留此前 WebCheck/默认数据源配置问题，专项通过不等于全套通过。

当前专项运行结果以 PROJECT_PROGRESS.md 本单元记录及 target/surefire-reports 为准。

下一单元：取消预约与超时关闭，保证只有状态迁移成功的一方归还一次号源。
