# 数据库设计阅读指南

数据库以 MySQL 为最终事实来源。完整表名、迁移逐版本清单、ER逻辑关系及行号证据在 [M0第3节](00-current-state.md)。本页补学习时必须关注的责任与约束，不重复创建概念同名表。

| 数据 | 责任 | 不能混淆的内容 |
| --- | --- | --- |
| sys_user | 登录身份、角色、BCrypt、token_version | 不等于医生档案或就诊人 |
| med_patient | owner账号下本人/家属 | 一账号多档案，历史按patient_id |
| med_doctor / department / clinic_room | 医生账户映射和组织目录 | 医生个人简介是公开目录，不含账号口令 |
| med_shift_template | 默认时间与容量规则 | 排班保存快照，不随模板变化改旧班 |
| med_schedule / med_slot | 出诊与有限可约容量 | 发布后才有可约slot；日期/开始/结束/截止不等价 |
| med_appointment | 预约主事实 | 创建占位，确认不重复扣容量 |
| sys_idempotency_record | 一次客户端命令及冻结结果 | 同key重试与新key重复挂号是两类问题 |
| med_appointment_status_log | 状态历史和操作者 | 与状态变化同事务 |
| med_encounter / med_medical_record | 实际接诊和四字段病历 | 完成接诊不改预约成COMPLETED |
| med_prescription / item / bill / mock_payment | 简版处方快照与模拟账单 | 不代表库存、发药或真实支付 |
| med_demo_seed_registry | 记录演示种子的身份/日期 | 重启不重置已完成/已缴费状态 |

重点读 V1 的 active_flag 生成列与有效预约唯一键、V7 的用户/操作/幂等键唯一键、V9 每预约一接诊/每接诊一病历/每医生一条就诊中、V12 每接诊一处方/每处方一账单/每账单一支付。

索引重点：预约 `(patient_id,created_at,id)` 供本人全局排序/游标；状态日志 `(appointment_id,created_at,id)`；超时扫描 `(status,confirm_deadline)`；号源 `(work_date,status,start_time)`。索引是否命中以实际SQL/EXPLAIN为准，不能仅凭字段名宣传性能。

原迁移设计使用逻辑关联，正常库没有物理外键；唯一键/CHECK不等于FK。此轮不为新增FK批量改写旧迁移。后续若增加，要先查孤儿数据和验证删除顺序，再写新版本迁移。

V14只增加医生title/specialty/introduction，不删除旧字段、不重建数据。已经执行的Flyway脚本禁止改checksum；正常卷升级前备份，保留.env，不用清库解决迁移差异。

本轮新增源码迁移`V15__appointment_follow_up_and_booking_snapshot.sql`，**尚未执行**：med_appointment增加可空source_encounter_id、五个预约时姓名/职称快照及booking_snapshot_at。新创建事务写入来源和快照，已存在的行不批量回填；booking_snapshot_at为NULL表示使用有说明的当前资料回退。来源指向同患者已完成接诊的规则由服务端owner和状态校验，不增加物理FK或复用旧接诊。普通预约不带来源，原预约/游标投影和幂等摘要保持兼容。变更现状见[源码改动记录](YYGH_ADOPTION_SOURCE_CHANGELOG.md)。
