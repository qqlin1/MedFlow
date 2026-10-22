# 药品、处方模拟收费与统计需求

日期：2026-10-20。进度统一维护在 [业务台账](BUSINESS_PROGRESS.md)。沿用现有 Spring Boot / MyBatis 模块化单体、Result 信封、JWT 角色与对象归属，不变更已执行的历史迁移。

## 业务范围与规则

1. 管理员维护药品编码、名称、规格、单位、单价、启用状态；支持搜索、分页和版本校验编辑，不物理删除。医生只能查询启用药品。价格为正、最多两位小数。
2. 一次接诊最多一张处方、最多一张账单。医生只能在本人 IN_PROGRESS 接诊中创建/修改处方草稿。最多20项，药品不重复，数量为1–1000的整数，每项可填用法说明；所有数据仅作模拟业务。
3. 客户端只提交 drugId、quantity、instructions 和版本。药品名称/规格/单位/单价从数据库取得，使用 BigDecimal 计算行金额与总额，保存快照。草稿保存后版本递增，旧版本409。金额必须在数据库精度范围内。
4. 草稿保存时取得价格快照，提交沿用该已保存快照。提交前复核药品仍启用；药品价格后续变化不改写已有处方。提交后处方冻结，事务内同时生成唯一 UNPAID 账单。重复提交返回原结果。
5. 接诊完成时允许无处方、已提交处方或已放弃草稿；存在未提交草稿时拒绝完成并提示先提交或放弃。放弃仅允许DRAFT→DISCARDED，清空条目/金额并递增版本；重新保存沿用同一处方ID及递增版本，避免删除重建导致旧页面版本误命中。已提交处方不允许撤方。接诊完成和处方写入统一争用接诊行锁，防止完成后再写草稿。
6. 患者只查看本人就诊人的 ISSUED 处方与账单，不能读医生草稿。允许在医生完成接诊前查看已经提交的处方；病历正文仍按原规则仅完成后可见。
7. 模拟缴费仅患者本人执行：锁账单，UNPAID→PAID，登记一条唯一模拟支付记录，同事务提交；重复请求返回 PAID，不重复计费。数据库时间记录支付时间。管理员/其他患者不代缴。
8. 不接支付渠道，不做部分支付、退款、撤方、多处方合并结算、药房库存、采购或医保。开方、缴费与接诊完成分别表达，不要求先缴费才能完成接诊。

## 接口约定

药品对象：`id,code,name,specification,unit,unitPrice,status,version`。药品与账单金额在前端按两位小数展示。

| 方法 | 路径 | 请求/响应 |
| --- | --- | --- |
| GET | /api/v1/admin/drugs | keyword/status/page/size；items,total,page,size |
| POST | /api/v1/admin/drugs | code,name,specification,unit,unitPrice；返回药品 |
| PUT | /api/v1/admin/drugs/{id} | name,specification,unit,unitPrice,status,version；返回药品 |
| GET | /api/v1/doctor/drugs | keyword/page/size；只返回启用药品的分页 |
| GET | /api/v1/doctor/encounters/{id}/prescription | 返回详情，未建草稿时data=null |
| PUT | /api/v1/doctor/encounters/{id}/prescription | version初始0，items:[{drugId,quantity,instructions}]；返回详情 |
| POST | /api/v1/doctor/encounters/{id}/prescription/issue | {version}；返回详情及账单 |
| POST | /api/v1/doctor/encounters/{id}/prescription/discard | {version}；仅放弃未提交草稿，返回DISCARDED详情 |
| GET | /api/v1/patient/prescriptions | page/size；本人已提交处方分页 |
| GET | /api/v1/patient/prescriptions/{id} | 本人已提交处方详情与账单 |
| POST | /api/v1/patient/bills/{id}/pay | 无金额入参，返回处方详情；模拟缴费 |

处方详情：`{prescription,items,bill}`。prescription含`id,encounterId,appointmentId,patientName,doctorName,departmentName,status,totalAmount,version,createdAt,issuedAt`；items含`id,drugId,drugCode,drugName,specification,unit,quantity,instructions,unitPrice,lineAmount`；bill为空或`{id,prescriptionId,status,totalAmount,paidAt}`。患者列表items为prescription摘要并带bill，不返回草稿。

## 统计与页面约定

管理员 `GET /api/v1/admin/statistics?from=YYYY-MM-DD&to=YYYY-MM-DD`，默认近7天（含今天），范围最多93天。响应：`{from,to,appointmentTrend:[{date,count}],departmentVisits:[{departmentId,departmentName,count}],doctorWorkload:[{doctorId,doctorName,departmentName,count}]}`。

- 预约趋势：按预约created_at日期统计全部创建记录，取消/过期仍是已发生的创建；无记录日期补0。
- 科室就诊量与医生工作量：仅COMPLETED，按completed_at归属日期，使用左闭右开时间范围；不将BOOKED或签到人数算成完成量。
- 三图展示真实值与口径，空数据明确提示；同时提供可读表格，不依赖CDN。
- 管理端新增药品、统计菜单；医生接诊页进入处方草稿/提交；患者新增“处方与缴费”。原有三角色页面增加下一步引导和日期提示。

## 验收要求

- 药品编辑冲突、禁用药品拦截、后端计价与价格快照、本人权限、草稿不可被患者读取。
- 重复提交处方只一张账单；并发缴费只一条支付记录；未提交处方阻止完成，完成后不能再写。
- 统计排除未完成接诊、按完成日期而非预约日期计数、补0、日期边界和角色权限。
- 普通测试、真实隔离MySQL、HTTP链路、前端行为、真实浏览器和Docker增量迁移；逐层记录实际结果。
