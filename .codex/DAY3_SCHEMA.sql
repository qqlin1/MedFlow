-- ============================================================
-- MedFlow Day 3 设计产物：核心三表 DDL（schedule / slot / appointment）
-- 决策记录：联合唯一键、索引、active_flag 方案由学习者本人拍板（2026-09-01）
-- 外键立场：默认不建外键约束、关联列建索引（Day 4 开课最终确认）
-- 注意：本文件是设计草图；W1 Day 6 将改写为 Flyway 迁移脚本
-- ============================================================

-- 排班表：一个医生有 N 个排班（多的一方存 doctor_id），同时归属诊室（room_id）
CREATE TABLE schedule (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  doctor_id     BIGINT UNSIGNED NOT NULL COMMENT '谁的排班 → doctor.id',
  room_id       BIGINT UNSIGNED NOT NULL COMMENT '哪个诊室 → room.id',
  schedule_date DATE NOT NULL COMMENT '排班日期',
  start_time    TIME NOT NULL COMMENT '开始时段',
  end_time      TIME NOT NULL COMMENT '结束时段',
  status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/STOPPED（Day 2 状态机落点）',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 不变量兜底：同医生同天同时段只许一个班（M3 并发冲突的数据库防线）
  -- 最左两列 doctor_id + schedule_date 顺便服务"医生查自己某天的排班"
  UNIQUE KEY uk_doctor_date_start (doctor_id, schedule_date, start_time),
  -- 管理员查"某天全院排班"：WHERE schedule_date = ?（唯一键最左是 doctor_id，帮不上这个查询）
  KEY idx_date (schedule_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排班';

-- 号源池表：一个排班切出一个号池（多的一方存 schedule_id）
-- 设计说明：采用号池模型（一场排班一个池子，容量 N）；W5 压测时与"每个号一行"模型对比
CREATE TABLE slot (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT UNSIGNED NOT NULL COMMENT '所属排班 → schedule.id',
  period      VARCHAR(16) NOT NULL COMMENT '时段：AM/PM',
  start_time  TIME NOT NULL,
  end_time    TIME NOT NULL,
  total_count INT NOT NULL COMMENT '本场总号数',
  remaining   INT NOT NULL COMMENT '剩余号数——不变量①主战场',
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN可约/LOCKED停诊',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_schedule_period (schedule_id, period),
  KEY idx_status (status),
  -- 不变量①最后一道闸：就算代码写错，数据库也拒绝把 remaining 扣成负数
  CONSTRAINT chk_remaining CHECK (remaining >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号源池';

-- 预约表：患者每次占号（patient 与 slot 的多对多关系实体化，自带生命周期）
CREATE TABLE appointment (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  patient_id  BIGINT UNSIGNED NOT NULL COMMENT '谁的预约 → patient.id',
  slot_id     BIGINT UNSIGNED NOT NULL COMMENT '占的哪个号池 → slot.id',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/CANCELLED/CLOSED/COMPLETED（Day 2 状态机落点）',
  active_flag TINYINT UNSIGNED NULL COMMENT '1=有效预约；CANCELLED/CLOSED 时置 NULL（必须与状态变更同事务更新）',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 不变量②兜底：同一患者同一号池最多一条有效预约
  -- 取消行 active_flag=NULL，唯一索引允许多行 NULL → 支持"取消后重约"（学习者自主推导方案）
  UNIQUE KEY uk_slot_patient_active (slot_id, patient_id, active_flag),
  -- 患者查"我的预约"：WHERE patient_id = ?（InnoDB 二级索引自动携带主键，无需 (patient_id, id)）
  KEY idx_patient (patient_id),
  -- 后台看某个号池的预约情况：WHERE slot_id = ?
  KEY idx_slot (slot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约';
