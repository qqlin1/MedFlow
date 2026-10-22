# Docker 系统演示数据交付

本文是2026-10-20首轮数据快照，数量会随用户操作和跨天补齐变化。2026-10-21的V14预约/医生简介/纵向档案和最新运行结果见[新交付](APPOINTMENT_CHART_DELIVERY.md)、[测试报告](05-test-report.md)；当前操作使用[手点清单](MANUAL_ACCEPTANCE.md)。

验收日期2026-10-20。已在正常Docker持久卷部署并补齐数据，入口[MedFlow](http://127.0.0.1:8080/)，步骤见[三角色指南](DEMO_DATA_GUIDE.md)。

## 当前数据

| 项目 | 正常Docker库实测 |
| --- | --- |
| 登录 | admin / doctor / patient，密码均123456，各为管理员/医生/患者 |
| 医生与科室 | doctor对应档案1“演示医生”，科室1“演示门诊”；原绑定保留 |
| 诊室 | 原“演示一诊室”保留，另有“演示诊室（虚构）” |
| 排班与时段 | 共16场：15已发布、1草稿；120时段；2026-10-21至10-27每天上午/下午均可约 |
| 明天可约 | 2026-10-21共16时段，首段余9，其余通常余10；患者默认查明天 |
| 就诊人/预约 | patient有7档案、7预约，均含原有1条；已准备明天确认预约 |
| 今日队列 | 待诊2、完成2、过号1，无预占就诊中；候诊一/二可直接开始接诊 |
| 病历/处方/账单 | 各2份；待缴费1、已模拟缴费1，金额各19.80元；模拟支付1条 |
| 药品与管理员练习 | 5种DEMO模拟药品；预留可发布草稿；可新建排班、管理药品和查看统计 |

当前快照：[25项三角色核验](evidence/system-demo-current.json)。正常库的待诊和未支付示例未被验收消耗；实际接诊→病历→处方→完成→模拟缴费在隔离MySQL测试中执行通过。

**排班由管理员分配，doctor自动看到本人安排，无需报名或重复绑定。** 明天新挂的号仍须明天在规定窗口签到；今日候诊是明确虚构的预置夹具，可直接供医生操作。

## 保留、幂等与时间

原库先备份到`target/backups/medflow-before-system-demo-20261020.sql`（30960字节），沿用原`.env`、`medflow-mysql-data`增量迁移至V13；原排班1、预约1及就诊人均保留，未清库。

V13新增`med_demo_seed_registry`，标记与种子同事务，通过管理员行锁串行化。重启/点击补齐只补缺，不恢复已取消、完成、过号或已缴费状态，尊重资源冲突与停用药；全部演示药停用时跳过新处方收费并显示说明。

正常库连续两次补齐的所有Created计数均0，浏览器再次点击也未重建业务。[无令牌记录](evidence/system-demo-refresh-idempotence.json)。

Docker默认同时开启账号与workflow两个演示开关；普通运行默认关闭。每次启动/管理员点击，按数据库当天检查未来7天及当日样例；常驻跨天可点击补齐，没有新增每日调度任务。同日样例用完不会重置历史。

正常门诊开始前不能提前接诊；完成样例不落未来，必要时放入过去正常门诊。今日候诊为预置状态，07:50前启动可能显示稍后的签到时间，这是演示夹具边界；普通签到接口仍严格校验时间。本次20:00左右验收的签到与完成时间均已过去。

## 验证证据

| 层次 | 结果 |
| --- | --- |
| 后端普通 | 281通过；[日志](../target/system-demo-unit.log) |
| 前端行为 | 149通过，含补齐按钮、双开关、重复点击、错误重试和摘要；[日志](../target/system-demo-frontend.log) |
| 隔离MySQL | 11通过（账号5+业务6），含完整操作链、容量、历史不重叠、保留用户状态、停用药；[日志](../target/system-demo-mysql.log) |
| 正常Docker | 三角色数据25项、接口权限19项通过；[数据日志](../target/system-demo-acceptance.log)、[接口日志](../target/system-demo-docker-smoke.log) |
| 浏览器 | admin实际补齐；doctor今日2待诊且开始按钮可用；patient默认明日16时段及两种缴费状态 |
| 原生Redis | 7.4.11 PONG，实际医生详情JSON、CLI TTL254秒（默认300秒内） |

最终镜像`medflow-app:local`：`sha256:e01b6cc4c7ec98dd78bf2736837abe45dd2e1395f430980e10eec3363fd45163`。[构建](../target/system-demo-build-final.log)、[启动](../target/system-demo-start.log)。三个容器均healthy并保留运行，浏览器停留patient可挂号页面。各层数字不相加当作一个总测试数。

```powershell
.\tools\test-system-demo.ps1
.\tools\test-docker-smoke.ps1
.\tools\test-confirmation.ps1 -TestSelection 'DemoWorkflowMySqlIT,DemoAccountBootstrapMySqlIT'
```

只读核验脚本检查初始数量；用户消耗样例后部分数量减少是正常业务变化。

![患者可预约号源](images/system-demo-patient-slots.png)

![doctor今日队列](images/system-demo-doctor-queue.png)

![管理员补齐入口](images/system-demo-admin.png)

![患者处方账单](images/system-demo-patient-bills.png)
