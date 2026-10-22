# 预约与患者院内档案开发手册

制定日期：2026-10-20。来源：用户实际试用反馈。本文约束本次开发和验收；实现与验证结果持续记在 BUSINESS_PROGRESS.md，未验证不记为完成。

## 真实项目参考与取舍

本次实际查阅的是官方文档与源码，学习业务关系，Java 代码按本项目现有分层独立实现。

| 参考 | 可核对证据 | MedFlow 的取舍 |
| --- | --- | --- |
| OpenEMR 患者预约 | [portal/add_edit_event_user.php](https://github.com/openemr/openemr/blob/master/portal/add_edit_event_user.php#L641)：选择 Provider、See Availability；换医生清空时间；日期/时间缺失禁止保存 | 医生资料→可用日期/时段→预约核对，换医生清掉旧时段；保留现有数据库容量校验 |
| Bahmni 创建预约 | [官方创建说明](https://bahmni.atlassian.net/wiki/spaces/BAH/pages/138182707)：Patient、Service、Date，按 Speciality/Provider 筛选及时间可用性 | 它包含工作人员预约流程；本项目做患者自助预约，不搬入工作人员越过冲突警告的能力 |
| Bahmni 术语 | [官方概念说明](https://bahmni.atlassian.net/wiki/spaces/BAH/pages/138280979)：Speciality 可对应科室，Service 对应具体医疗服务 | 内科、外科、皮肤科就是科室；当前不再增加重复的“项目→科室”层。未来透析等专项服务另行建模 |
| OpenMRS Java 模型 | [Encounter.java](https://github.com/openmrs/openmrs-core/blob/master/api/src/main/java/org/openmrs/Encounter.java#L66)、[Visit.java](https://github.com/openmrs/openmrs-core/blob/master/api/src/main/java/org/openmrs/Visit.java#L44)：Patient 关联 Visits/Encounters、orders、diagnoses | 同一 patientId 形成纵向档案，每次接诊仍有独立病历、处方和状态；家属档案不混合 |

OpenEMR 仓库页面本次显示约 5.5k stars，仅作为社区规模参考，不据此认定适用国内全部医院流程。这里选择的是适合本项目学习和演示的简化门诊流程，不宣称覆盖所有医院。

## 患者预约

默认路径：选择科室 → 选择医生并阅读资料 → 选择日期与可用时段 → 选择就诊人 → 核对并提交 → 截止前确认预约。快捷路径：找医生（全院姓名/擅长搜索，可筛科室）→ 医生资料 → 同样的后续流程。

- 医生目录独立于当天号源；无排班的医生仍能查看公开资料，页面应明确当天无可约时段。
- 公开资料：姓名、科室、职称、擅长、个人简介。缺失内容明确显示“暂未维护”，不推断资历。
- 管理员可维护公开资料。保存走 MySQL 事务，提交后删除既有医生详情缓存；失败回滚不删除。
- 切换科室清空所选医生和时段；切换医生/日期清空旧时段。旧请求迟到不能覆盖新选择。
- 初始不直接铺全院时段；选定医生、日期才请求号源。默认明日，可选择今天或未来日期。
- 时段仅显示后端允许预约的数据。余号会变化，提交必须经过原有条件扣减与幂等逻辑；满号、停诊、过截止须明确反馈。
- 不凭空显示挂号费，不把药品处方费用当挂号费。当前提交后仍有确认期限，界面需说明。
- 页面有加载、失败重试、空目录、无号源、无就诊人的实际处理。

## 同一患者的院内档案

身份单位为 patientId。一个 patient 账号下的本人和家人是不同档案，不按登录账号混合病历。

档案列表按就诊日期、时段、预约 ID 稳定倒序分页。包括预约状态、接诊状态、医生/科室/诊室；详情包含该次预约过程、签到/接诊过程、已完成病历的主诉/现病史/诊断/医嘱、已提交处方的药品快照/数量/单价/总额及缴费状态。

- 患者只能看自己账号名下就诊人的档案。
- 医生从属于自己的有效预约或已有接诊进入，才能读该就诊人的院内档案；禁止任意传入 patientId 读取全库。
- 跨医生已完成病历允许只读。未完成病历和草稿处方在档案接口统一隐藏；当前接诊的编辑继续走原接口。
- 历史详情再次校验目标预约属于当前档案，禁止混入其他患者的预约 ID。
- 管理员维护组织和排班，不默认开放病历正文权限。
- 当前没有化验、检查、影像结果模型；页面不伪造报告。过敏史、既往史长期摘要、跨院档案、病历打印签章另列后续需求。
- 打开历史前有未保存病历必须提示保存，不能静默丢稿。

接口：

| 角色 | 列表 | 单次详情 |
| --- | --- | --- |
| 患者 | GET /api/v1/patients/{patientId}/history?page=1&size=10 | GET /api/v1/patients/{patientId}/history/{appointmentId} |
| 医生 | GET /api/v1/doctor/appointments/{anchorAppointmentId}/patient-history?page=1&size=10 | GET /api/v1/doctor/appointments/{anchorAppointmentId}/patient-history/{appointmentId} |

## 演示数据与验收

演示目录至少可区分内科、外科、皮肤科；医生公开介绍明确标注虚构。保留原 doctor 绑定和已有业务数据，新增资源通过 registry 幂等标记，只在两个演示开关开启时补齐，不重置用户已完成的操作。

至少一个当前候诊患者有不同日期、不同医生的已完成历史病历和处方，用于验证新医生确实能够查看院内既往记录。

验收必须覆盖：先选科室/医生才查时段；换上游清下游；迟到响应不串医生；无号换日；提交沿用现有幂等；患者自有权限；无关联医生与跨患者详情拒绝；跨医生完成病历可读；草稿隐藏；真实 MySQL；浏览器桌面/手机；正常 Docker 卷升级。
