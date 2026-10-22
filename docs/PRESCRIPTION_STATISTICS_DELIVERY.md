# 药品、处方与模拟收费交付记录

日期：2026-10-20。需求见 [规则与接口](PRESCRIPTION_STATISTICS_REQUIREMENTS.md)，已做/未做统一见 [业务台账](BUSINESS_PROGRESS.md)。用户随后追加的Redis优先任务见 [医生缓存六项实测](DOCTOR_CACHE_DELIVERY.md)。

## 已实现

管理员药品编码/名称/规格/单位/价格、搜索分页、启停用、版本冲突；医生处方草稿、提交冻结、放弃草稿、价格快照；患者本人已提交处方、唯一账单、模拟缴费；管理员预约创建趋势、科室完成就诊量、医生完成工作量；三角色操作引导和管理端医生登录账号展示。

处方与接诊完成共享Encounter行锁，未提交草稿阻止完成。金额用BigDecimal在后端计算，不接受前端报价。提交处方和建账单同事务，账单和模拟支付均有唯一约束。目录价格变化不改写已保存快照；提交前重新检查启用状态。仅模拟状态登记，没有实际扣款。

新增V11药品目录、V12处方/条目/账单/模拟支付四表，不改历史迁移。前端无新增npm、CDN或独立部署依赖。

## 已验证与尚未验证

| 层次 | 本次结果 |
| --- | --- |
| 后端普通测试 | 此轮业务259项全过；追加Redis后的完整数量见缓存报告/台账 |
| 前端行为测试 | 当前142项全过，含处方保存、只读、缴费、防重复、统计口径与异步结果、保存后分页禁用状态恢复 |
| 隔离MySQL | 35项专项覆盖药品4、处方11、统计3、医生目录1、既有接诊9/排班7；首轮仅1项约束异常类型断言不符，修正后药品4项复验全部通过 |
| 独立HTTP | 81项通过，真实Flyway V1–V12、角色JWT、药品/处方/缴费及统计链路 |
| 真实浏览器 | 虚构患者许安然：保存病历→保存处方19.98元→提交账单→完成接诊→患者模拟缴费已通过；管理员新增药品/医生登录账号显示通过；统计1280桌面、390手机通过，页面无横向溢出 |
| Docker | 后续已构建部署并增量迁移至V13，三个容器healthy；正常库三角色数据/界面已通过，见[系统验收](SYSTEM_DEMO_DELIVERY.md) |

日志：[普通测试](../target/business-unit.log)、[缓存阶段普通测试280项](../target/doctor-cache-unit.log)、[MySQL首轮](../target/business-mysql-it.log)、[药品复验](../target/business-mysql-recheck.log)、[前端](../target/business-frontend.log)、[HTTP81项](../target/business-http-final.log)。各层数字独立记录。后续系统数据扩展已部署新Docker镜像，最新281/149及正常库结果见[系统交付](SYSTEM_DEMO_DELIVERY.md)。

浏览器使用独立临时库中的软件测试数据；当日00:00–23:59时段仅用于隔离验收，不代表正常排班规则变化。相关临时应用和MySQL已经停止，数据与日志保留在target。没有把虚构完成量或支付记录写入正常Docker业务库。

![医生提交后的处方及账单](images/business-doctor-prescription.png)

![患者已模拟缴费](images/business-patient-paid.png)

![统计桌面验收](images/business-statistics-desktop.png)

![统计手机验收](images/business-statistics-mobile.png)

## 复验及学习

```powershell
.\mvnw.cmd test
node --test tools/test-frontend-api.cjs tools/test-frontend-dashboard.cjs tools/test-frontend-workflows.cjs tools/test-frontend-clinical.cjs tools/test-frontend-business.cjs
.\tools\test-confirmation.ps1 -TestSelection 'DrugCatalogMySqlIT,PrescriptionBillingMySqlIT,StatisticsMySqlIT,AdminDoctorDirectoryMySqlIT,EncounterConcurrencyMySqlIT,ScheduleManagementMySqlIT'
.\tools\test-clinical-http.ps1 -WithBusiness
```

先沿 `PrescriptionService.save → issue → pay` 讲清：为何保存价格快照、谁计算金额、重复提交为何不多建账单、医生完成与草稿写入如何串行、为何缴费失败不能重复登记；再读StatisticsRepository的两个日期口径。药品库存、退款、真实支付、医保、停诊通知/改约、补叫与MQ仍未实现。
