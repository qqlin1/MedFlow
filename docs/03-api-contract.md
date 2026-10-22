# 核心 API 合同

完整接口清单见 [M0 API快照](audit/m0-api-inventory.md)；该快照标明当时源码/运行差异。当前可用路径以运行服务的 `/v3/api-docs` 为准，路由保护和资料归属不能只看Swagger是否能调用。

B01–B04的新接口和字段目前是源码合同，按用户要求尚未编译、测试或部署；旧运行容器不一定具有这些接口。实现与待验证范围见[源码改动记录](YYGH_ADOPTION_SOURCE_CHANGELOG.md)。

响应为 `{code,message,data}`，HTTP状态与业务错误同时保留；`X-Trace-Id` 用于定位日志。认证请求带 `Authorization: Bearer <token>`，不要把真实令牌粘贴到共享文档。

| 动作 | 路径/参数 | 角色与规则 |
| --- | --- | --- |
| 登录 | POST /api/v1/auth/login，username/password | 公开，返回accessToken |
| 科室 | GET /api/v1/patient/departments | PATIENT，启用目录 |
| 医生目录 | GET /api/v1/patient/doctor-directory?departmentId=&query= | PATIENT，可跨科室找姓名/擅长，query≤64；上限200，当前教学小目录使用 |
| 医生资料 | GET /api/v1/patient/doctors/{id} | PATIENT，公开职称/擅长/简介，旁路缓存 |
| 管理公开资料 | GET/PUT /api/v1/admin/doctors/{id}/profile | ADMIN；PUT name必填≤64，title≤64、specialty≤1000、introduction≤4000；省略可选字段保留，空串清空 |
| 可约时段 | GET /api/v1/patient/slots?workDate=YYYY-MM-DD&departmentId=&doctorId=&page=1&size=20 | PATIENT，size≤50；只显示后端可预约数据 |
| 日期余量摘要（新增源码） | GET /api/v1/patient/availability-days?departmentId=&doctorId=&from=&days=7 | PATIENT；默认DB今日、1–14天；两ID可选，指定时校验启用和归属；按天补齐AVAILABLE/FULL/NO_SCHEDULE/CLOSED |
| 当日可约医生（新增源码） | GET /api/v1/patient/available-doctors?departmentId=&workDate= | PATIENT；科室和日期必填；启用资源下当日有未截止余号的医生，附时段数和余量 |
| 创建预约 | POST /api/v1/appointments，Idempotency-Key必填≤64 | PATIENT，body={patientId,slotId,sourceEncounterId?}，owner校验和原子占位；可选来源只允许同人已完成接诊 |
| 复诊预填来源（新增源码） | GET /api/v1/patient/follow-up-sources/{encounterId} | PATIENT本人已完成来源；返回来源接诊/预约、同一就诊人、原医生/科室、日期/诊断，失败统一404 |
| 我的预约 | GET /api/v1/appointments?page=1&size=20；或/cursor | PATIENT，仅账号名下就诊人 |
| 批量就诊摘要 | GET /api/v1/appointments/visit-summaries?ids=1,2 | PATIENT，最多50个正整数输入、去重，按本人owner过滤；返回日期/医生/科室/诊室等实际信息，不逐行请求 |
| 本人预约凭证（新增源码） | GET /api/v1/appointments/{id}/voucher | PATIENT本人；visit、脱敏联系/关系、DB时间、签到窗口、可操作标记、独立进度与已提交处方账单 |
| 确认/取消 | POST /api/v1/appointments/{id}/confirm 或 /cancel | PATIENT本人；状态与截止校验；不需管理员审核 |
| 到院签到 | POST /api/v1/appointments/{id}/check-in | PATIENT本人、BOOKED、当天窗口；重复复用接诊 |
| 本次就诊 | GET /api/v1/appointments/{id}/encounter | PATIENT本人，未完成病历正文不返回 |
| 医生列表 | GET /api/v1/doctor/schedules；/appointments；/encounters | DOCTOR，服务端绑定本人医生，禁止传别人doctorId伪造权限 |
| 开始/过号/完成 | POST /api/v1/doctor/encounters/{id}/start、/skip、/complete | DOCTOR本人，complete带{version} |
| 保存病历 | PUT /api/v1/doctor/encounters/{id}/medical-record | DOCTOR本人IN_PROGRESS，四字段和version |
| 患者档案 | GET /api/v1/patients/{patientId}/history；/history/{appointmentId} | PATIENT owned profile，详情目标再次校验同patient |
| 医生档案 | GET /api/v1/doctor/appointments/{anchorId}/patient-history；/patient-history/{appointmentId} | DOCTOR本人有效关联锚点，历史只读；size≤50 |

创建请求示例（ID必须用你刚查到的真实值）：

```http
POST /api/v1/appointments
Authorization: Bearer <本地登录返回的token>
Idempotency-Key: learn-appointment-001
Content-Type: application/json

{"patientId": 1, "slotId": 12}
```

同用户同key同参数重试返回同一预约；同key改参数是冲突。同患者同slot用新key重复预约也会拒绝。响应PENDING_CONFIRMATION后，患者在响应confirmDeadline前自确认，不另扣容量。

复诊请求传`sourceEncounterId`后，该非空字段加入request_hash；普通请求保持原hash兼容。来源必须为当前账号下同一个patientId的COMPLETED接诊；创建新预约不复制旧病历，也不自动确认/签到。来源接诊ID使用严格整数解析，不能将小数静默截断为其他接诊。

日期摘要为`{workDate,status,availableSlotCount,remainingCapacity,doctorCount}[]`；doctorCount为有可约余号的去重医生数。无可用已发布班次为NO_SCHEDULE，存在但全部已截止/不可约为CLOSED；未截止开放时段均无余量为FULL。只描述查询时事实，最终占位仍以创建事务为准。

visit-info、批量摘要、患者/医生历史和接诊摘要增加复诊来源ID/预约ID/日期及`hasBookingSnapshot`。有快照优先显示预约时值；旧记录不回填，回退当前资料并说明。新凭证`progress`是只读展示值：待确认/确认期限已过、等待到院、可签到、待诊、接诊中、已完成、过号、错过签到窗口、取消、未确认超时；不会回写成新的预约状态。账单只显示ISSUED处方关联的单据，待缴费不阻止接诊完成。

错误场景：400参数不合法；401无有效会话；403角色错误；404不存在或不属于本人（避免泄露他人记录）；409号源耗尽/重复预约/不合法状态/版本冲突/过截止。具体code见 `ErrorCode.java`，前端保留code及TraceId。

历史页响应含patient脱敏信息、items/total/page/size；详情含visit、medicalRecord、prescription、appointmentHistory、encounterHistory。medicalRecord只返回COMPLETED，prescription只返回ISSUED，不能用详情接口读取他人草稿。

药品/处方/模拟缴费与统计合同见 [已有扩展需求](PRESCRIPTION_STATISTICS_REQUIREMENTS.md)。本文为契约说明，不把未部署接口当作运行验收通过；最终版本证据见业务台账与交付报告。
