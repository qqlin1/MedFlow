-- ============================================================
-- MedFlow 实验 01：把"防超卖"里所有抽象概念变成看得见的现象
--
-- 用法：开两个 MySQL 连接（两个终端窗口，或 DataGrip/Navicat 开两个 console），
--       按 [连接 A] / [连接 B] 的标注交替执行，观察每一步的返回。
--
-- 前提：MySQL 5.7+ / 8.0+，默认隔离级别 REPEATABLE READ，默认 autocommit=1。
-- 每个实验开始前先跑一次「重置数据」，保证起点一致。
-- ============================================================

-- ---------- 建表（只需跑一次） ----------
DROP DATABASE IF EXISTS medflow_lab;
CREATE DATABASE medflow_lab;
USE medflow_lab;

CREATE TABLE slot (
  id                 BIGINT PRIMARY KEY,
  total_capacity     INT NOT NULL,
  remaining_capacity INT NOT NULL
) ENGINE = InnoDB;

-- ---------- 重置数据（每个实验前跑一次） ----------
-- DELETE FROM slot;
-- INSERT INTO slot VALUES (1, 1, 1);      -- 实验 1、2、3
-- INSERT INTO slot VALUES (1, 50, 50);    -- 实验 5


-- ============================================================
-- 实验 1：快照读 vs 当前读
-- 目标：亲眼看到「同一个事务里，SELECT 看到 10，UPDATE 却算出 4」
-- ============================================================

-- 准备：容量 10
DELETE FROM slot;
INSERT INTO slot VALUES (1, 10, 10);

-- [连接 A]
START TRANSACTION;
SELECT remaining_capacity FROM slot WHERE id = 1;      -- 看到 10，记住这个数

-- [连接 B]（别的事务把 10 改成 5，然后提交）
START TRANSACTION;
UPDATE slot SET remaining_capacity = 5 WHERE id = 1;
COMMIT;

-- [连接 A] 继续，注意：A 还没提交，它不知道 B 改过
SELECT remaining_capacity FROM slot WHERE id = 1;      -- 还是 10！看的是照片（快照读）

-- [连接 A] 动手改，注意结果是几
UPDATE slot SET remaining_capacity = remaining_capacity - 1 WHERE id = 1;
-- 现在查自己
SELECT remaining_capacity FROM slot WHERE id = 1;      -- 4，不是 9
COMMIT;

-- 预期结论：
--   SELECT 用的是事务开始时的照片，UPDATE 用的是此刻的真实值。
--   所以"我在事务里先查了一次所以安全"是错的——查到的值和改时用的值不是同一次读。


-- ============================================================
-- 实验 2：先查再更 —— 容量 1，却卖出 2 个号
-- 目标：亲眼看到超卖
-- ============================================================

DELETE FROM slot;
INSERT INTO slot VALUES (1, 1, 1);   -- 只剩 1 个号

-- [连接 A]
START TRANSACTION;
SELECT remaining_capacity FROM slot WHERE id = 1;      -- 看到 1，应用算出新值 0

-- [连接 B]
START TRANSACTION;
SELECT remaining_capacity FROM slot WHERE id = 1;      -- 也看到 1，应用也算出 0

-- [连接 A] 用"自己算好的值"写入
UPDATE slot SET remaining_capacity = 0 WHERE id = 1;   -- 1 row affected
COMMIT;

-- [连接 B] 也用"自己算好的值"写入，同样成功
UPDATE slot SET remaining_capacity = 0 WHERE id = 1;   -- 1 row affected，竟然也成功
COMMIT;

-- [任意连接] 看结果
SELECT * FROM slot WHERE id = 1;                        -- remaining = 0
-- 但两个请求都返回了"预约成功"，一个号卖给了两个人 —— 超卖。

-- 预期结论：
--   病根是"判断与动手分离，动手时依据已经过期"。
--   关键区分：
--     SET remaining = 0              （应用算好的值）→ 不安全，lost update
--     SET remaining = remaining - 1  （引用列值）    → 安全，MySQL 内部当前读


-- ============================================================
-- 实验 3：条件更新 —— 第二个请求是"等"，然后拿到 0 行
-- 目标：亲眼看到 affected rows 判胜负，以及锁等待
-- ============================================================

DELETE FROM slot;
INSERT INTO slot VALUES (1, 1, 1);

-- [连接 A]
START TRANSACTION;
UPDATE slot
   SET remaining_capacity = remaining_capacity - 1
 WHERE id = 1
   AND remaining_capacity > 0;                          -- 返回 1 row affected，A 抢到了
-- 先别 COMMIT，让 B 进来抢

-- [连接 B]
START TRANSACTION;
UPDATE slot
   SET remaining_capacity = remaining_capacity - 1
 WHERE id = 1
   AND remaining_capacity > 0;
-- ↑ 这一句会"卡住"，不会立刻返回 —— 这就是锁等待。
--   它在排队等 A 释放这一行的排他锁（X 锁）。

-- [连接 A]
COMMIT;                                                 -- 锁释放

-- [连接 B] 立刻返回：0 rows affected
--   拿到锁后做当前读，发现 remaining 已经是 0，WHERE 条件不成立，一行都没改。
--   应用层据此判定"没抢到"，回滚。
ROLLBACK;

-- [任意连接]
SELECT * FROM slot WHERE id = 1;                        -- remaining = 0，没有变成 -1

-- 预期结论：
--   1. 并发请求是排队等锁，不是立刻失败。默认 innodb_lock_wait_timeout = 50 秒。
--   2. affected rows 就是 MySQL 告诉你输赢的方式：1 = 抢到，0 = 没抢到。
--   3. 即使 WHERE 条件不成立、一行都没改，锁也已经加上了（两阶段锁协议）。


-- ============================================================
-- 实验 4：锁等待超时 —— 1205 长什么样（可选，约 5 秒）
-- ============================================================

DELETE FROM slot;
INSERT INTO slot VALUES (1, 1, 1);

-- [连接 A]
SET SESSION innodb_lock_wait_timeout = 5;   -- 生产上一般设 3~5 秒，默认 50 秒太长
START TRANSACTION;
UPDATE slot SET remaining_capacity = remaining_capacity - 1 WHERE id = 1 AND remaining_capacity > 0;

-- [连接 B]
SET SESSION innodb_lock_wait_timeout = 5;
START TRANSACTION;
UPDATE slot SET remaining_capacity = remaining_capacity - 1 WHERE id = 1 AND remaining_capacity > 0;
-- 等 5 秒后报错：
--   ERROR 1205 (HY000): Lock wait timeout exceeded; try restarting transaction

-- [连接 A]
ROLLBACK;

-- 预期结论：
--   默认 50 秒太长，高并发下会把连接池打满引发雪崩。
--   生产上设 3~5 秒，超时后让客户端重试。这是简历里可以说的一个真实数字。


-- ============================================================
-- 实验 5（进阶，等进入 M5 再做）：500 并发抢 50
-- 用 Java 线程池或压测脚本打，不要用 SQL 手敲。
-- 记录三件事：成功数、失败数、总耗时。
--
-- 观察重点：
--   1. 成功数是否恰好 50，remaining 是否恰好 0，有没有负数。
--   2. 总耗时 —— 你会发现它约等于 500 个事务在单行上"串行通过"的时间，
--      这就是"单行热点"：几千个事务排队等同一行的 X 锁，TPS 被锁等待吃掉。
--   3. 把 innodb_lock_wait_timeout 设成 5 秒，看有多少请求变成 1205。
--
-- 缓解方向（按推荐顺序，先别急着上 Redis）：
--   分段库存：把 1 行拆成 10 行，随机选桶扣减，热点分散 10 倍 —— 纯 MySQL 就能做
--   限流 / 削峰
--   Redis 预扣 + 异步落库 + 定时对账（代价最大，最后才考虑）
-- ============================================================


-- ============================================================
-- 每个实验都要记下来的四件事（写进 .codex/PROJECT_PROGRESS.md 的学习记录）
--   1. 现象：你看到了什么输出
--   2. 数据：数据库里的实际数字
--   3. 解释：为什么会这样（用一句话，不用术语）
--   4. 对应：它证明了哪条不变量 / 防住了什么事故
-- ============================================================
