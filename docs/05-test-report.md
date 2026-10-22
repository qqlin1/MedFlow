# 预约与院内档案改造测试报告

> 后续B01–B04源码已修改，按用户要求尚未编译、测试、迁移或部署。本文数字只属于此前V14交付，不证明新增改动通过；新状态见[源码改动记录](YYGH_ADOPTION_SOURCE_CHANGELOG.md)。

最终运行核验：2026-10-21，工作区 `E:\code\java\MedFlow`。这是当前源码和正常Docker的增量报告；[M0报告](00-current-state.md)保留其当时的失败结果，不回写历史。

| 层次 | 实际结果 | 证据 |
| --- | --- | --- |
| 普通后端 | 321项通过，0失败/错误/跳过 | target/appointment-chart-unit-final.log |
| 前端行为 | 220项通过，0失败/跳过 | target/appointment-chart-frontend-final.log |
| 本轮业务MySQL | 43项通过 | target/appointment-chart-mysql.log；医生资料/目录、演示初始化、纵向档案、排班与处方回归 |
| 最终批量摘要MySQL | 3项通过，0跳过 | target/appointment-chart-summary-mysql.log；完整联表、同账号家属、跨账号过滤、数据库时间和已签到判断 |
| 医生缓存六项 | 全部通过 | target/appointment-chart-cache.log；回源、命中、CLI TTL、提交后删缓存/回滚保留、空值、停独立Redis后受控降级 |
| 正常Docker业务HTTP | 39项通过 | target/appointment-chart-live-final.log；docs/evidence/appointment-chart-live.json |
| 正常Docker冒烟 | 19项通过 | target/appointment-chart-smoke-final.log；target/docker-smoke.json |
| 本地JAR、Docker镜像 | 构建通过，三角色启动校验通过 | target/appointment-chart-package-final.log、appointment-chart-docker-build-final.log、appointment-chart-docker-start-final.log |

各层数量分别报告，不能相加包装成独立用户、覆盖率或性能数据。43项专项运行后增加的摘要接口另跑3项；没有声称在最后版本重新执行全部历史性能实验。原M0的MySQL错误是测试辅助函数误选另一医生历史ID，现已限制本人/当日/WAITING并在上述专项回归通过。

业务HTTP检查包括：4位医生及资料、目录搜索、明日可约号源；同一patientId跨医生病历、四字段正文、处方和账单、分页；不同家属详情404、无关联医生404、管理员病历403、匿名401；一次批量获取本页预约日期/医生/科室/诊室与参数/角色限制。跨owner批量过滤由隔离MySQL测试证明。

前端覆盖加载/空态/失败重试、切换科室医生日期、请求迟到、会话切换、XSS转义、提交结果不确定时保留幂等键、成功后号源刷新失败隔离、预约分页与游标及摘要单独失败重试、历史未保存草稿保护。不是只对HTML字符串快照做断言。

## 人工页面证据

- 管理员维护主医生简介，患者读取到修改内容；图片 `images/appointment-chart-admin-profile.png`。
- 患者按科室→医生→日期/时段→就诊人提交预约#10，并自行确认；日期为2026-10-21 08:30–09:00，属于当时实测记录，不是每次安装固定编号。图片 `images/appointment-chart-patient-created.png`。
- 患者同一档案打开林医生/周医生的已完成病历及处方；医生从本人预约打开相同历史只读。图片 `images/appointment-chart-record-detail.png`、`images/appointment-chart-doctor-history.png`。
- 历史详情390×844检查：文档宽度390、scrollWidth390，弹窗宽347，没有页面横向溢出。图片 `images/appointment-chart-history-mobile.png`。
- 最终列表、工作台和预约页面的桌面/手机复核补记在[交付说明](APPOINTMENT_CHART_DELIVERY.md)。完整点击步骤留给用户按[手动验收](MANUAL_ACCEPTANCE.md)自行完成，不把自动化结果当作用户已掌握。

## 当前部署和限制

应用、MySQL、Redis均healthy，入口 http://127.0.0.1:8080/，原MySQL卷保留，Flyway V14成功。2026-10-21 10:55数据库快照：44排班、336号源时段、16预约、12接诊、7病历、6处方。数据会随操作、每日补齐变化，不固定断言待诊人数。

隔离MySQL/Redis测试使用项目临时目录和独立端口，结束清理；正常Redis未为缓存停机测试关闭。备份见[交付说明](APPOINTMENT_CHART_DELIVERY.md)。本轮没有新增性能指标，没有宣称生产医疗合规；真实支付、退款、药房库存、医保、检查影像、复杂停诊改约、MQ继续未做。
