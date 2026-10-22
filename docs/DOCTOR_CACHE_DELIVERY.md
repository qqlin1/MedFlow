# 医生详情 Redis 旁路缓存：六项执行记录

日期：2026-10-20。用户明确追加本功能，覆盖先前文档中“Redis 暂缓”的医生详情单点范围；其他 Redis 场景与 MQ 继续暂缓。总进度见 [业务台账](BUSINESS_PROGRESS.md)。

## 六项验收

以下六项来自真实Spring Boot HTTP、独立MySQL8.4、Windows Memurai4.1.2（Redis兼容实例）及memurai-cli。随后正常Docker已部署原生Redis7.4.11，并实测详情JSON回填及CLI TTL254秒，见[系统验收](SYSTEM_DEMO_DELIVERY.md)；该追加检查不冒充六项均在容器重复执行。

| 清单 | 结果与证据 |
| --- | --- |
| [x] 第一次查 MySQL 并回填 Redis | HTTP 200，缓存 JSON 存在；医生详情 Repository 的真实 SQL 调用计数为1 |
| [x] 第二次查询命中 Redis | 两次响应相同；医生详情 SQL 计数仍为1；有 `source=redis` 日志 |
| [x] TTL 并用 CLI 检查 | CLI `TTL medflow:doctor:detail:v1:1` 实测30秒；这是联调缩短配置，正常默认300秒；额外通过 PEXPIRE 验证到期后重新读库 |
| [x] 资料修改后提交再删缓存 | 事务内缓存仍是旧值，提交后 key 不存在，下次读到新姓名；回滚时原数据库值和缓存均保留；HTTP 修改仅管理员允许 |
| [x] 不存在 ID 的空值缓存 | ID 9000000001 连续两次404，详情 SQL 只查一次；CLI GET为 `__NULL__`，TTL≤10秒（联调配置，正常默认30秒） |
| [x] 停 Redis 并受控降级 | 真正停止测试脚本创建的独立 Redis 进程；HTTP 仍200并从 MySQL读取，首次218ms、下一次6ms；Redis停止时修改资料也能成功提交 |

这是一条包含六项检查的集成测试，不将六个检查冒充六个 JUnit 测试。证据：[联调日志](../target/doctor-cache-integration.log)、[测试源码](../src/test/java/com/qqlin/medflow/organization/detail/DoctorCacheRedisMySqlIT.java)。SQL计数专指医生详情SELECT：JWT校验等其他路径仍可能查询MySQL，不能声称整个HTTP请求不访问任何MySQL表。

当前完整后端普通测试280项通过（缓存Service8、Redis适配8、Controller5共新增21项）；前端142项通过。普通结果见[日志](../target/doctor-cache-unit.log)。集成实验所有测试进程均已停止，只保留日志，不占用正常Redis服务。

## 接口及规则

- 患者：`GET /api/v1/patient/doctors/{id}`，返回 `{id,departmentId,name,departmentName}`，沿用Result信封。不返回账号、密码、用户ID、病历等信息。医生或科室未启用时与不存在同样404；非法ID为400。
- 管理员：`PUT /api/v1/admin/doctors/{id}/profile`，JSON `{"name":"王医生"}`。本次资料编辑只开放已有的医生显示姓名，1–64字；不变更科室或账号绑定，避免连带改变排班与历史业务归属。并发姓名编辑采用后提交覆盖。
- key：`medflow:doctor:detail:v1:{id}`。普通值是JSON，空值使用独立标记 `__NULL__`，避免将缓存未命中和不存在混淆。SET携带TTL，读取不续期。
- `DoctorDetailService.get`：Redis命中→返回；未命中→MySQL→回填→返回；MySQL确认不存在→写短TTL空值→404。数据库故障直接报错，不能缓存成“医生不存在”。
- `updateName`：Spring代理开启MySQL事务，更新姓名，注册`TransactionSynchronization.afterCommit`删除key；回滚不执行删除。删除失败不把已提交的MySQL更新伪装为失败。
- 降级：连接和命令超时均200ms；缓存读取/回填异常返回数据库结果，首次异常后本节点5秒跳过缓存，减少连续超时和重复日志。缓存数据损坏则删除并重新读库。提供开关完全绕过缓存。
- 失效删除失败：本节点绕过缓存直到现有最长TTL经过，避免立即重连读回旧值。其他应用实例可能仍读到旧缓存，最长受TTL约束。当前是最终一致性，不能宣称强一致或已经解决并发旧值回填窗口。

## 配置和复验

`application.yml` 中可通过环境变量调整：

| 变量 | 默认值 |
| --- | --- |
| MEDFLOW_REDIS_HOST / MEDFLOW_REDIS_PORT | 127.0.0.1 / 6379 |
| MEDFLOW_REDIS_PASSWORD | 空，本机或受保护容器网络 |
| MEDFLOW_DOCTOR_CACHE_ENABLED | true |
| MEDFLOW_DOCTOR_CACHE_TTL | PT5M |
| MEDFLOW_DOCTOR_CACHE_NULL_TTL | PT30S |

在项目根目录执行，脚本启动随机端口的独立实例，只停止自己创建的PID；不对现有Redis执行SHUTDOWN/FLUSHALL，不操作正常MySQL库：

```powershell
.\mvnw.cmd test
.\tools\test-doctor-cache.ps1
Select-String -Path .\target\doctor-cache-integration.log -Pattern 'CHECK [1-6]'
```

若需把复验输出留到上述日志，用 `.\tools\test-doctor-cache.ps1 *> target/doctor-cache-integration.log`。脚本默认要求已安装的MySQL8.4及`C:\Program Files\Memurai`；也可显式传`-RedisBin`和`-MySqlBin`。

Compose中的`redis:7.4-alpine`现已运行，内部网络供应用访问，无宿主机端口和数据卷。它是可丢弃缓存；应用仅依赖MySQL健康，不要求Redis健康才能启动。可检查：

```powershell
docker compose exec redis redis-cli TTL medflow:doctor:detail:v1:1
docker compose exec redis redis-cli GET medflow:doctor:detail:v1:1
```

不要在正常演示栈随意停Redis来复现故障；上述隔离脚本已经包含安全的停机实验。

## 阅读顺序与边界

先读 [Controller](../src/main/java/com/qqlin/medflow/organization/detail/DoctorDetailController.java) → [Service](../src/main/java/com/qqlin/medflow/organization/detail/DoctorDetailService.java) → [Cache](../src/main/java/com/qqlin/medflow/organization/detail/DoctorDetailCache.java) → [Repository](../src/main/java/com/qqlin/medflow/organization/detail/DoctorDetailRepository.java)，再对照联调测试中的六个CHECK。

没有增加分布式锁、热点重建互斥、布隆过滤器、MQ失效重试或跨节点强一致。冷key并发可能同时查询数据库；故障期间全部请求回源，尚未做数据库全局限流。新增/绑定医生遇到提前缓存的不存在ID，最多等待空值TTL；当前创建路径没有额外的负缓存失效逻辑。未来若增加科室改名/停用或医生停用写接口，也必须补对应缓存失效；直接改SQL会绕过现有失效机制。

实现参考：[Redis cache-aside](https://redis.io/docs/latest/develop/use-cases/cache-aside/)、[SET与TTL](https://redis.io/docs/latest/commands/set/)、[Spring事务提交回调](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit())。本项目显式编写读取/回填/降级流程，方便逐步复述，不依赖注解隐藏执行顺序。
