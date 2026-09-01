# MedFlow 五条面试主线的深度梳理

> 本文只回答面试官真正会追问的那一层：原理、SQL、并发时序、失效边界。
> 每条路线的结构固定为：**问题本质 → 方案比较 → 正确实现 → 竞争时序 → 失效边界 → 口述模板**。
> 口述模板要能脱稿讲，讲的时候带上"我一开始是怎么错的"。

---

## 路线一：防超卖

### 1.1 问题的本质不是"没加锁"，是"判断与动手分离"

```sql
-- 错误写法
SELECT remaining_capacity FROM med_slot WHERE id = 1001;   -- 读到 1
--  ↑ 窗口：别的事务在这期间已经把 1 改成 0 并提交
UPDATE med_slot SET remaining_capacity = 0 WHERE id = 1001; -- 基于过期依据动手
```

病根一句话：**判断与动手分离，动手时依据已经过期。**

#### 一个必须先澄清的误解：为什么 `SELECT` 在同一事务里也救不了你

| 写法 | 是否安全 | 原因 |
|---|---|---|
| `UPDATE slot SET remaining = remaining - 1 WHERE id=?` | 安全 | 引用列值，InnoDB 在更新时会对该行加 X 锁并**重新读最新版本**（当前读），计算和更新是一个原子步骤 |
| `UPDATE slot SET remaining = #{appComputed} WHERE id=?` | 不安全 | 应用层算好再写入，两个事务各自算出 0，互相覆盖，即 lost update |

关键点：`UPDATE` 永远是**当前读**，不受事务快照的约束。所以"我在事务里先查了一次所以安全"是错的——**你查到的值，和你 UPDATE 时用上的值，不是同一次读**。

### 1.2 四种方案的比较（这是面试最值钱的部分）

| 方案 | 做法 | 优点 | 缺点 / 失效场景 |
|---|---|---|---|
| 先查再更 | SELECT 然后 UPDATE | 简单、无锁 | 存在竞态窗口，必然超卖 |
| 悲观锁 | `SELECT ... FOR UPDATE` | 直观，能锁住一整段业务逻辑 | 锁持有时间 = 整个事务；RR 下走非唯一索引会加间隙锁；多资源需固定加锁顺序否则死锁；**它不解决超卖，只是把并发串行化** |
| 条件更新 CAS | `UPDATE ... WHERE remaining > 0` | 原子；锁持有时间只有一条语句；单行操作无死锁风险；吞吐高 | 单行热点争用；只能表达"数值型"条件 |
| Redis 预扣 | Lua `DECR` | 极快，能扛高并发 | 引入 Redis 与 MySQL 双写；Redis 不是事实源；需要补偿、对账、降级 |

#### "为什么不用 FOR UPDATE"的标准答法

#### 先纠正一个流传很广的夸张说法

网上常说"FOR UPDATE 锁住整个事务，条件更新只锁一条语句，所以吞吐差一个数量级"——**这是错的**。

两种方案的锁都是**到 COMMIT 才释放**（两阶段锁协议：锁一旦获取就持有到事务结束，不能中途释放）。
而在 MedFlow 的流程里，扣减号源之后还要插入预约、写状态历史、写 Outbox，这些**都在锁内**，两种方案一样。

真正的区别在**加锁时刻**：

| 方案 | 锁从何时开始 | 锁到何时结束 |
|---|---|---|
| FOR UPDATE | 事务里第一条 `SELECT ... FOR UPDATE` 执行时（在所有校验之前） | COMMIT |
| 条件更新 | 执行到那条 `UPDATE` 时（在所有校验之后） | COMMIT |

所以条件更新省下的只是「**校验阶段的数据库往返 + 一次 SELECT 往返**」，大概是 10–20% 的临界区，不是数量级。**面试时别说成数量级，会被追问穿。**

#### 那么 FOR UPDATE 真正的劣势是什么（四条，按重要性排序）

1. **最关键的：FOR UPDATE 不能直接解决超卖。** 它只保证"在我加锁和提交之间没人能动这行"，**你仍然要在应用代码里判断 `remaining > 0` 再决定要不要更新**。如果忘了这个 if，FOR UPDATE 一行超卖都防不住。而条件更新把判断写进 SQL 的 `WHERE` 里，**正确性由数据库保证，而不是靠程序员记得写 if**。
2. **多一次数据库往返。** FOR UPDATE 是「先 SELECT 加锁拿值 → 应用判断 → 再 UPDATE」，两次交互；条件更新一条语句搞定。
3. **RR 下的间隙锁。** FOR UPDATE 若查询条件走非唯一索引，会额外加 Gap Lock 锁住一个范围，死锁概率显著上升；走主键或唯一索引时才退化为纯 record lock。
4. **多资源必须固定锁顺序。** 排班场景要锁医生、诊室、Slot 三处，顺序不一致就死锁。条件更新是单行语句，天然没有这个问题。

> 反直觉但重要的结论：**FOR UPDATE 不是"更安全的方案"，是"看起来更像在加锁的方案"。**
> 它把并发串行化了，但判断和动手仍然是两步；条件更新把两步合并成一步，少一个犯错的机会。

### 1.3 并发执行时 InnoDB 到底在做什么

```sql
UPDATE med_slot
   SET remaining_capacity = remaining_capacity - 1
 WHERE id = 1001
   AND status = 'OPEN'
   AND remaining_capacity > 0;
```

前提：`id` 是主键，走聚簇索引。

1. 定位 `id = 1001` 的聚簇索引记录。
2. 对这条记录加**排他锁（X Lock，record lock）**。这是**当前读**：若别的事务已持有该行锁，则进入锁等待队列；拿到锁后读的是**最新已提交版本**，不是事务开始时的快照。
3. 用当前读到的最新值判断 WHERE 条件。
4. 条件成立 → 修改，写 undo / redo，返回 `affected rows = 1`。
5. 条件不成立 → 不修改，返回 `affected rows = 0`。**注意：即使不修改，锁也已经加上了**（两阶段锁协议：锁在需要时获取，事务结束时才释放）。

#### 第二个请求是等待，不是立刻失败

- 它排在锁等待队列里。
- 默认 `innodb_lock_wait_timeout = 50` 秒，超时抛 `ERROR 1205 (HY000): Lock wait timeout exceeded`。
- 默认开启死锁检测（`innodb_deadlock_detect = ON`），发现等待环就回滚其中一个事务（报 1213）。**死锁检测本身在高并发下也是开销**，这是单行热点场景的隐藏成本。
- A 提交后 B 拿到锁，重新读最新值，若此时 `remaining = 0`，则 WHERE 不成立，返回 0 → 应用层判定"没抢到"，回滚。

**推论**：500 并发抢 50 个号，实际耗时 ≈ 500 个事务在单行上**串行通过**的时间。这就是单行热点问题的本质。

```java
int rows = slotMapper.decreaseIfAvailable(slotId);
if (rows == 0) {
    throw new SlotSoldOutException();   // → 409 Conflict
}
```

### 1.4 为什么不直接在 Redis 里扣

核心矛盾：**Redis 扣减成功 ≠ 预约成功，而 MySQL 才是最终事实源。**

四个必须讲得出的失效场景：

1. **双写不一致。** Redis 扣成功，随后 MySQL 事务失败回滚 → 号凭空消失。你要反向补偿（INCR 回去），而补偿本身也会失败。
2. **Redis 持久化不是强一致。** AOF 默认 `everysec`，宕机丢 1 秒；主从异步复制，主库扣完没同步就挂，从库升主 → 扣减记录丢失 → 超卖。
3. **计数器表达不了业务约束。** "这个患者在这个 Slot 有没有有效预约""这个 Slot 是否关闭""是否过了截止时间"——这些都要查库。所以**最终还是要在事务里查 MySQL**，Redis 只多了一道前置过滤。
4. **对账成本。** 真上生产必须定时比对 Redis 与 MySQL 的余量，不一致以 MySQL 为准修正。这是一整套系统性成本。

> 正确说法：**Redis 不是不能用，它换来的是吞吐，代价是一整套补偿 / 对账 / 降级机制。**
> 在 MySQL 正确性尚未证明之前引入它，会把"并发正确性"和"缓存一致性"两个问题搅在一起，两个都验证不清。这正是需求文档里"MySQL 正确性未证明前不引入 Redis"这条规则的真正理由。

### 1.5 你的方案什么时候会不行（必须准备）

1. **单行热点。** 一个爆款专家的号，几千个事务排队等同一行的 X 锁。TPS 被串行化的锁等待吃掉，大量请求 1205 超时。
   - 缓解方案（按推荐顺序）：
     - **分段库存**：把一行库存拆成 N 行（比如 10 个桶），随机或按用户哈希选桶扣减，热点分散 N 倍。**纯 MySQL 就能做，不需要引入 Redis**
     - 限流 / 削峰 / 排队
     - 最后才是 Redis 预扣 + 异步落库 + 对账
   - 面试时说出"分段"很加分，因为它说明你知道热点问题的解法不止"上 Redis"一种。
2. **锁等待超时不可控。** 默认 50 秒太长，会让连接池被打满引发雪崩。生产上一般设 3–5 秒 + 前端重试。**这是你项目里可以说的一个真实数字。**
3. **条件更新只能表达数值条件。** "这个患者在这个 Slot 不能有第二条有效预约"是跨行存在性约束，一行表达不了，**必须靠唯一索引兜底**（→ 路线二）。
4. **归还侧也要加条件。** 取消归还要 `AND remaining_capacity < total_capacity`，否则重复归还会让余量超过总量。虽然 CAS 已保证只归还一次，但这是纵深防御。

### 1.6 口述模板

> 防超卖我用的是 MySQL 条件更新。最初我写的是先查余量再减一，压测发现会超卖，根因是判断与动手分离、动手时依据已经过期。
>
> 我比较过四种方案：先查再更会超卖；FOR UPDATE 其实不直接防超卖，它只保证加锁期间没人能动这行，我还得在代码里再判断一次 remaining 是否大于 0，忘了这个 if 就一行都防不住，而且它多一次数据库往返、RR 下走非唯一索引还会加间隙锁、多资源要固定锁顺序；Redis 预扣要引入双写一致性和对账。
>
> 补充一句容易搞错的地方：两种方案的锁都是到 COMMIT 才释放，条件更新并不是"只锁一条语句"。真正的区别是加锁时刻——FOR UPDATE 在所有校验之前就锁了，条件更新是执行到那条 UPDATE 才锁，省下的是校验阶段的往返，大概 10% 到 20%，不是数量级。
>
> 最终我用 `UPDATE ... SET remaining = remaining - 1 WHERE id = ? AND remaining > 0`，靠 affected rows 判胜负：1 就是抢到，0 就是没抢到。这条语句在 InnoDB 里是当前读，会加行 X 锁，并发请求是排队等锁而不是立刻失败，默认 50 秒超时。锁持有时间只覆盖这一条语句，临界区很短。
>
> 这个方案的边界是单行热点：几千个事务排队等同一行的 X 锁，TPS 会被锁等待吃掉。真到那一步我会先做分段库存把热点打散，而不是先上 Redis。
>
> 另外一个坑是：条件更新只能表达数值条件，"同一患者同一 Slot 只能有一条有效预约"这种存在性约束必须靠唯一索引兜底，所以幂等和唯一约束是另一条独立的防线。

---

## 路线二：幂等

### 2.1 先分清：幂等解决的是两个不同的问题

| 问题 | 特征 | 靠什么解决 |
|---|---|---|
| **重复提交** | 用户手抖点两次 / 网络超时前端重试 / 网关重放。同一个业务意图，带同一个 Idempotency-Key | Idempotency-Key + 唯一索引 |
| **业务重复** | 用户换了 key 重新提交，但业务上不允许（同一就诊人同一 Slot 只能有一条有效预约） | **只能靠数据库唯一约束**，幂等键管不了 |

很多人把这两个混为一谈，面试官一句话就能拆穿。

### 2.2 表结构

```sql
CREATE TABLE sys_idempotency_record (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT       NOT NULL,
  operation_type  VARCHAR(32)  NOT NULL,
  idempotency_key VARCHAR(64)  NOT NULL,
  status          VARCHAR(16)  NOT NULL,   -- PROCESSING / SUCCESS
  result_json     TEXT,                    -- 成功时缓存的业务响应
  created_at      DATETIME     NOT NULL,
  updated_at      DATETIME     NOT NULL,
  UNIQUE KEY uk_user_op_key (user_id, operation_type, idempotency_key)
);
```

### 2.3 两个请求同时带同一个 key：完整时序

| 时刻 | 请求 A | 请求 B |
|---|---|---|
| t1 | BEGIN | BEGIN |
| t2 | INSERT 幂等记录（PROCESSING）成功 | — |
| t3 | — | INSERT 幂等记录 → 唯一索引冲突，**阻塞等待 A 的锁**（不是立刻报错） |
| t4 | 校验 Slot、扣减号源、插入预约 | 阻塞中 |
| t5 | UPDATE 幂等记录 → SUCCESS + result_json | 阻塞中 |
| t6 | COMMIT | — |
| t7 | — | 拿到锁，唯一索引冲突报错（1062 Duplicate entry） |
| t8 | — | catch 后**回查**幂等记录：SUCCESS → 直接返回 result_json |

#### 三个必须答对的细节

**(a) B 在 t3 是阻塞，不是立刻失败。** 唯一索引的冲突检测在插入时就要加锁；A 未提交，B 必须等。机制和 `SELECT ... FOR UPDATE` 的锁等待是同一类。

> **推论（与路线一联动，面试加分）**：既然是同一类锁等待，那它就受 `innodb_lock_wait_timeout` 管辖。
> **如果 A 卡住一直不提交，B 等满超时报的是 `1205`，不是 `1062`。**
> 所以 catch 分支必须能分辨这两种：1062 = 确实重复了，走回查；1205 = 前面的事务卡住了，走快速失败。
> 另外别忘了：**1062 只回滚那一条 INSERT，事务还活着**——正因为如此才能 catch 后继续走回查分支。
> 如果 1062 会回滚整个事务，这套方案根本写不出来。（这条规则与路线一的 1205 是同一条）

**(b) B 在 t8 必须回查，不能只返回 409。** 客户端关心的是"我这次预约到底成没成"。Idempotency-Key 的语义要求：**相同 key 的重复请求，返回相同的响应**（同一个 appointmentId）。

**(c) 回查时 A 还是 PROCESSING 怎么办 —— 这是真正的坑。** 回查要分三种情况：

| 回查结果 | 含义 | 处理 |
|---|---|---|
| 记录不存在 | A 的事务已回滚（比如号源不足），记录随之消失 | **重试整个业务逻辑** |
| PROCESSING | A 还在跑 | 返回 409，让客户端稍后重试 |
| SUCCESS | A 已成功 | 返回缓存的 result_json |

**回查必须用 `SELECT ... FOR UPDATE`（当前读）**，否则在 RR 快照读下可能读到旧版本甚至读不到。这个细节说出来很加分。

> **为什么必须用当前读**（这是路线一知识的直接兑现）：
> B 的事务很可能在抢锁之前执行过一次 `SELECT`（预检查），那一次就在 RR 下**建立了 read view**。
> 之后无论 A 提没提交，B 的快照读看到的都是建立 read view 那一刻的旧版本——
> **A 刚提交成功的记录，B 的快照读根本看不见**。
> 快照读看历史，当前读看最新。回查要看最新，所以必须 `FOR UPDATE`。

#### 回查的 Mapper 长什么样

```xml
<!-- ① 占坑：直接 INSERT，让唯一索引当裁判 -->
<insert id="insertAndGet">
  INSERT INTO sys_idempotency_record
    (user_id, operation_type, idempotency_key, status, created_at, updated_at)
  VALUES (#{userId}, #{op}, #{key}, 'PROCESSING', NOW(), NOW())
</insert>

<!-- ② 这就是「回查」。注意最后那行 FOR UPDATE -->
<select id="selectForUpdate" resultType="IdempotencyRecord">
  SELECT id, user_id, operation_type, idempotency_key, status, result_json
    FROM sys_idempotency_record
   WHERE user_id         = #{userId}
     AND operation_type  = #{op}
     AND idempotency_key = #{key}
     FOR UPDATE
</select>

<!-- ③ 业务成功后固化结果 -->
<update id="markSuccess">
  UPDATE sys_idempotency_record
     SET status = 'SUCCESS', result_json = #{json}, updated_at = NOW()
   WHERE id = #{id}
</update>
```

#### 完整流程（标注回查在哪一步）

```java
@Transactional
public Appointment book(BookCommand cmd) {
    IdempotencyRecord rec;
    try {
        rec = idempotencyMapper.insertAndGet(cmd.userId(), "BOOK", cmd.requestId());
    } catch (DuplicateKeyException e) {
        rec = idempotencyMapper.selectForUpdate(cmd.userId(), "BOOK", cmd.requestId()); // ← 回查
        if (rec == null) {
            rec = idempotencyMapper.insertAndGet(cmd.userId(), "BOOK", cmd.requestId()); // A 回滚了，重跑
        } else if ("PROCESSING".equals(rec.getStatus())) {
            throw new ConflictException("上一次请求还在处理中");
        } else {
            return deserialize(rec.getResultJson());   // 返回与首次完全一致的响应
        }
    }
    int rows = slotMapper.decreaseIfAvailable(cmd.slotId());
    if (rows == 0) throw new SoldOutException();   // 抛异常 → 整个事务回滚 → 幂等记录自动消失
    Appointment appt = ...;
    appointmentMapper.insert(appt);
    idempotencyMapper.markSuccess(rec.getId(), toJson(appt));
    return appt;
}
```

#### 关键前提：requestId 是谁生成的

**由前端生成，且"一次预约意图"只生成一次。** 这是区分两件事的基础：

| | 场景 | requestId | 谁挡 |
|---|---|---|---|
| **重复提交** | 用户双击、前端超时自动重试、网关重放 | **复用同一个** | 防线 ① 幂等表唯一索引 |
| **业务重复** | 约成功后又回列表页选**同一个号源**再约一次 | **新生成一个** | 防线 ② 预约表唯一索引 |

判断标准：**前端认为这是不是同一次点击。** 是 → 复用 key；不是 → 新 key。

### 2.4 第一次失败了，同 key 重试应该成功还是失败（真决策）

| 设计 | 做法 | 后果 |
|---|---|---|
| **A：只缓存成功结果** | 失败时幂等记录随业务事务一起回滚，自动消失 | 用户重试会真正重跑一次业务。号源不足时重试可能就抢到了 |
| **B：失败结果也缓存** | 记录 FAILED 状态和错误信息 | 用户重试永远返回同一个失败 |

**MedFlow 应该选 A**，理由要说清楚：

> 号源是动态资源，失败原因通常是瞬时的（sold out、锁等待超时）。如果缓存失败结果，用户就永远抢不到这个号，违背业务目标。所以我的设计是：幂等记录与业务在同一个事务里，业务失败则整个事务回滚，幂等记录随之消失，用户可以用**同一个 key** 再次发起，真正重跑一遍业务。只有成功的业务结果才固化到幂等记录里。

**这个答案的价值在于：它证明你不是在套模板，而是针对这个业务的特性做了取舍。**

#### A 的边界与配套设计

- 如果失败是**永久性的**（"就诊人不属于当前用户""Slot 不存在"），重试多少次都失败，应当返回 4xx 让客户端改参数，而不是无限重试。
- 解法是**把确定性校验放到幂等之前，状态性校验放到幂等之后**：

```text
认证 → 参数校验(400) → 所有权校验(404) → 获取/占有幂等记录 → 状态与容量校验(409) → 业务写入
       └──────── 确定性：换 key 也无效 ────────┘                 └── 状态性：换时间可能有效 ──┘
```

判断标准很简单：**"换个 key 重试，结果会不会不一样？"** 会不一样 → 放幂等之后；不会 → 放幂等之前。这个区分是高级答案。

### 2.5 幂等记录和预约必须在同一个事务吗

**必须。** 不在同一事务的两种坏法：

| 顺序 | 坏法 | 后果 |
|---|---|---|
| 幂等记录先独立提交 | 记录已 SUCCESS，但预约未提交时进程崩溃 | 用户重试拿到缓存的"成功"，数据库里根本没有预约。**承诺了没做到，比超卖更严重** |
| 业务先提交，幂等后写 | 业务提交后崩溃，幂等记录没写 | 用户重试 → 又建一条预约。幂等失效 |

**推论**：既然幂等与业务同事务，那"失败即回滚"就是自动的——业务抛异常 → 事务回滚 → 幂等记录自动消失 → 用户重试重跑。**设计 A 天然成立，这也是它比 B 更简洁的原因。**

代价：事务变大，幂等记录那行的锁要持有到 commit。可以接受，因为幂等记录按 key 分散，不是热点行。

### 2.6 唯一约束为什么不能少

Service 预检查有竞态窗口，和"先查再减"完全同构：两个并发请求都查到"不存在"，都通过，都插入 → 两条有效预约。

MySQL 没有部分唯一索引，你文档里的方案是**生成列 + NULL 技巧**：

```sql
ALTER TABLE med_appointment
  ADD COLUMN active_flag TINYINT
      AS (CASE WHEN status IN ('PENDING_CONFIRMATION','BOOKED') THEN 1 ELSE NULL END) VIRTUAL,
  ADD UNIQUE KEY uk_patient_slot_active (patient_id, slot_id, active_flag);
```

原理：**MySQL 唯一索引允许多个 NULL**，所以已取消 / 已超时的预约（active_flag = NULL）可以有多条，而有效预约（active_flag = 1）只能有一条。

#### active_flag 到底是什么：一个为索引服务的技术字段

**它不承载任何业务含义，存在的唯一目的是让唯一索引"选择性生效"。**

**没有它会出什么 bug**：只建 `(patient_id, slot_id)` 唯一索引时 ——
张三约了 8 号 → 取消（记录还在，只是改 status）→ 后悔想再约 → 插入 `(1001, 88)` → **1062，永远约不了**。
病根：业务规则是"**有效的**预约只能有一条"，而 MySQL 的唯一索引是一刀切的，它只看值重不重复，**不知道什么叫"有效"**。

| 记录状态 | active_flag | 唯一索引管不管 |
|---|---|---|
| PENDING_CONFIRMATION / BOOKED | `1` | **管** → `(1001, 88, 1)` 只能有一条 |
| CANCELLED / EXPIRED | `NULL` | **不管** → `(1001, 88, NULL)` 可以有无数条 |

取消的记录还在表里（要留做历史），但不再占用那个"只能有一条"的名额。

**为什么用生成列而不是手动存一列**：`active_flag` 完全由 `status` 决定，手动维护早晚出现
`status='CANCELLED'` 但 `active_flag=1` 的不一致——那会直接导致"取消后还是约不了"。
生成列让数据库自己算，不可能不一致；`VIRTUAL` 不占存储。

**判断一张表要不要 active_flag，只问一句：失效的记录是「删掉」还是「留着改状态」？**

| | 失效时怎么做 | 需不需要 active_flag |
|---|---|---|
| `med_appointment` | **逻辑标记**——改 status，记录留在表里做历史 | **需要**，让失效记录"隐身" |
| `sys_idempotency_record` | **物理删除**——业务失败 → 事务回滚 → 记录直接消失 | **不需要**，记录都没有了 |

幂等记录失效是**回滚掉**的，连行都不存在，自然不需要字段让它"不被管"。
**这不是套路，是业务决定的** —— 能说出这个对比，就说明不是背模板。

#### 三个字段，一个都不能少

数据库判断的是"**这三列的值组合起来有没有重复**"：

| 字段 | 作用 | 少了 / 换掉会怎样 |
|---|---|---|
| `patient_id` | 约束的对象是**就诊人** | 换成 `user_id`：一个账号给全家人挂号就全挂不了了 |
| `slot_id` | 约束的范围是**这个号源** | 它已唯一确定"某医生 + 某天 + 某时段"，不用再加 `doctor_id` / `work_date` |
| `active_flag` | 只约束**处于有效状态**的预约 | 少了：取消一次之后就永远约不了这个号源了 |

以 `patient_id = 1001`、`slot_id = 88` 为例：

| patient_id | slot_id | active_flag | status | 能插进去吗 |
|---|---|---|---|---|
| 1001 | 88 | 1 | BOOKED | 能，第一条 |
| 1001 | 88 | 1 | BOOKED | **不能，1062** |
| 1001 | 88 | NULL | CANCELLED | 能，NULL 不参与唯一性判断 |
| 1001 | 88 | NULL | CANCELLED | 能，第二条历史 |
| 1001 | 99 | 1 | BOOKED | 能，换个号源 |
| 1002 | 88 | 1 | BOOKED | 能，换个就诊人 |

**准确表述**：不是"一个人不能有多个同一号源 id"，而是
**同一个就诊人在同一个号源上，最多只能有一条处于有效状态的预约**；取消的、超时的历史记录可以有任意多条。

**`patient_id` ≠ `user_id`**：`user_id` 是登录账号，`patient_id` 是就诊人。一个人可以登录自己的账号给父母、孩子挂号。约束建在 `user_id` 上是错的——同一个账号下的不同就诊人就不能约同一个号源了。（这个区分在路线五权限里会再碰到。）

**扩展：如果业务规则是"同一就诊人同一天只能约一次"**，那 `(patient_id, slot_id, active_flag)` 管不住（不同时段是不同 slot_id）。需要再加 `(patient_id, work_date, active_flag)`，但 `work_date` 在号源表不在预约表 → 预约表要**冗余存一份 work_date**（插入时从 slot 带过来，不可变）才能建索引。
**"为了加约束而冗余一个字段"本身就是个面试故事**——它体现你愿意为保证业务规则付出存储代价，而不是把校验甩给应用层。

行为要点（会被追问）：
- 生成列需 MySQL 5.7+；`VIRTUAL` 不占存储，插入时计算
- 依赖「NULL 不参与唯一性判断」这一行为
- 缺点：状态语义变化（新增一个"有效"状态）要改生成列定义；可读性差，必须写注释
- 替代方案：单开一张 `med_appointment_active(patient_id, slot_id, appointment_id)`，有效时插入、失效时删除，主键即唯一约束。**更好懂，但多一处维护且必须同事务**

这两个方案的取舍本身就是一个现成的面试故事。

### 2.7 口述模板

> 幂等我要分两件事讲。
>
> 第一件是重复提交：用户点两次或网关重放，同一个 Idempotency-Key。我的实现是幂等记录和业务在同一个事务里，用 `(user_id, operation_type, idempotency_key)` 唯一索引抢锁。两个并发请求进来，第二个会阻塞在唯一索引冲突上，等第一个提交后报 1062，然后回查幂等记录拿到首次的响应原样返回——注意回查必须用当前读，否则 RR 快照读可能读不到。回查还有第三种情况：记录不存在，说明第一个事务回滚了，这时要重试整个业务而不是返回失败。
>
> 第二件是业务重复：用户换个 key 再提交。这个幂等键管不了，只能靠数据库唯一约束。MySQL 没有部分唯一索引，我用生成列加 NULL 技巧，让已取消、已超时的记录 active_flag 为 NULL 从而不受唯一索引约束。
>
> 一个我做过的设计决策：失败结果我不缓存。因为号源是动态的，第一次满了，重试时可能有人取消了。如果缓存失败结果，用户就永远抢不到。所以业务失败就整个事务回滚，幂等记录自动消失，同 key 可以真正重跑。对应的，我把"就诊人不属于当前用户"这类确定性校验放到抢幂等记录之前，因为换 key 也无效；把"号源是否充足"这类状态性校验放到之后。

---

## 路线三：状态竞争

### 3.1 CAS 的 SQL 长什么样

```sql
-- 确认
UPDATE med_appointment SET status = 'BOOKED',    confirmed_at = NOW()
 WHERE id = ? AND status = 'PENDING_CONFIRMATION';

-- 主动取消
UPDATE med_appointment SET status = 'CANCELLED', cancelled_at = NOW()
 WHERE id = ? AND status IN ('PENDING_CONFIRMATION', 'BOOKED');

-- 超时关闭
UPDATE med_appointment SET status = 'EXPIRED',   expired_at = NOW()
 WHERE id = ? AND status = 'PENDING_CONFIRMATION';
```

共同点：**WHERE 里带原状态作为乐观锁条件**。这就是 CAS（Compare-And-Set）在 SQL 里的形态，也叫条件更新 / 乐观并发控制。

### 3.2 归还号源和状态 CAS 必须在同一个事务，且顺序不能反

```java
@Transactional
public void cancel(Long appointmentId, Long userId) {
    Appointment appt = mapper.selectByIdAndOwner(appointmentId, userId);  // 顺带做数据权限
    int rows = mapper.cancelIfActive(appointmentId);                      // CAS：资格判定
    if (rows == 0) throw new ConflictException("预约已不在可取消状态");     // → 409
    slotMapper.increaseIfNotFull(appt.getSlotId());                       // 副作用：归还
    statusLogMapper.insert(...);
}
```

- **必须同事务**：CAS 成功但归还前崩溃 → 预约显示已取消但号源没还 → 号永久丢失。
- **顺序不能反**：先 CAS 后归还。CAS 是资格判定，归还是副作用；若先归还再 CAS，CAS 失败时号源已经加回去了，还得再减回来，凭空多一个失败点。
- 归还也要带条件：`AND remaining_capacity < total_capacity`。

### 3.3 取消 vs 超时：竞争时序

| 时刻 | 请求 X（用户主动取消） | 请求 Y（MQ 超时消息） |
|---|---|---|
| t1 | BEGIN | BEGIN |
| t2 | CAS `PENDING → CANCELLED`，rows = 1，**拿到行 X 锁** | — |
| t3 | — | CAS `PENDING → EXPIRED` → **阻塞等同一行的锁** |
| t4 | `remaining + 1`（带上限条件） | 阻塞中 |
| t5 | 写状态历史 | 阻塞中 |
| t6 | COMMIT | 阻塞中 |
| t7 | — | 拿到锁，当前读发现 status 已是 CANCELLED，WHERE 不成立，rows = 0 |
| t8 | — | **不归还号源**，回滚；**Ack 消息** |

这就是"数据库状态条件更新决定唯一获胜操作"的具体含义：

- 胜者由谁先拿到行锁决定；**归还动作只有胜者执行**（rows == 1 才继续）
- 输者 rows == 0，**绝不归还**
- **输者必须 Ack，不能 Nack 重试**——"没抢到"不是失败，是别人先做了。Nack 会无限循环

### 3.4 返回 0 的请求回什么状态码

| 场景 | 处理 |
|---|---|
| 用户主动取消遇到 rows = 0 | `409 Conflict`，body 带当前状态 |
| 更友好的做法（幂等取消） | 先查当前状态：已是 CANCELLED → 返回 200 + 当前状态（用户的目标已达成）；是 EXPIRED / 冲突态 → 409 |
| MQ 消费者遇到 rows = 0 | 不是 HTTP，直接 **Ack**。但必须打日志（info，不是 error），否则排查时一片黑 |

### 3.5 超时任务扫到一条已经 BOOKED 的预约

三种情况，处理完全一致：

| 当前状态 | CAS 结果 | 处理 |
|---|---|---|
| BOOKED（用户已确认） | rows = 0 | 不归还、不写历史、直接 Ack |
| CANCELLED（用户已取消） | rows = 0 | 同上 |
| EXPIRED（重复消息） | rows = 0 | 同上 |

**无论 rows 是 0 还是 1，都 Ack；只有异常才 Nack。**

```java
@RabbitListener(queues = "...")
public void handle(TimeoutMessage msg) {
    int inserted = consumedMapper.insertIgnore("appt-timeout", msg.getMessageId());
    if (inserted == 0) { ack(); return; }            // 重复消息
    try {
        appointmentService.expireIfPending(msg.getAppointmentId());   // 内部 CAS
    } catch (Exception e) {
        consumedMapper.delete("appt-timeout", msg.getMessageId());    // 允许下次重试
        throw e;                                                      // Nack
    }
    ack();
}
```

#### 一个诚实的层次区分（高级答案）

- **消费幂等**（`sys_consumed_message`）：防同一条消息被处理两次
- **业务 CAS**（`WHERE status = ...`）：防不同操作路径（取消 vs 超时）互相踩

**必须诚实的结论**：在这个特定场景下，**CAS 本身已经能挡住重复消息**（第二次 CAS 时已处于终态，rows = 0）。`consumed_message` 的真实价值是：

1. 避免无谓的数据库写和锁竞争（事务外就能去重）
2. 当消费者的副作用不只是 CAS（发短信、调外部接口）时，CAS 挡不住
3. 面向未来的通用防御层

**这个诚实回答比硬背"消费幂等必须做"要高级得多**，而且能引出下一个追问"那什么时候它才是必需的"。

### 3.6 口述模板

> 取消和超时同时发生，靠的是把状态 CAS 和号源归还放在同一个事务里，并且顺序固定为先 CAS 后归还。
>
> SQL 是 `UPDATE ... SET status = 'CANCELLED' WHERE id = ? AND status IN ('PENDING','BOOKED')`，用 affected rows 判胜负。两个请求并发时，后到的会阻塞在行锁上，等先到的提交后当前读发现状态已经变了，WHERE 不成立，rows 返回 0，于是它不归还号源、直接回滚。
>
> 这里有个容易踩的坑：输的那个请求必须 Ack，不能 Nack 重试，因为"没抢到"不是失败，是别人先做了。Nack 会无限循环。
>
> 关于消费幂等表，说实话在这个场景里 CAS 已经足够防重复投递了，重复消息第二次 CAS 就是 0。consumed_message 表的价值更多是避免无谓的锁竞争，以及将来消费者的副作用不止数据库操作时它能兜住。

---

## 路线四：Outbox / MQ

### 4.1 为什么不直接在事务里发消息

这是经典的"数据库与 MQ 双写"问题。**三种写法都错**：

**(a) 提交前发**
```
BEGIN → 扣号源 / 插预约 → send(超时消息)  ← 已发出
      → 插入状态历史失败 → ROLLBACK
```
预约不存在了，但超时消息已投递，消费者去关一个不存在的预约。

**(b) 提交后发**
```
BEGIN → 扣号源 / 插预约 → COMMIT → send(...)  ← 进程在这里崩了
```
预约存在，但超时消息永远没了 → 预约永久卡在 PENDING_CONFIRMATION，号源被永久占用。**这是静默的数据损坏，最危险的一种。**

**(c) 靠 MQ 事务（txSelect / txCommit）**
RabbitMQ 的事务和 MySQL 事务是两个独立事务，没有 2PC，仍然可能一个成功一个失败。

**结论：本地事务和消息发送无法原子化，除非引入 2PC / XA（重、慢、复杂）。**

**Outbox 的本质：把"要发的消息"当数据写进同一张业务表，从而借用本地事务的原子性，换取"消息最终一定会被发出"的保证（at-least-once）。**

### 4.2 Outbox 表与 Relay

```sql
CREATE TABLE sys_outbox_event (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id   BIGINT      NOT NULL,
  event_type     VARCHAR(64) NOT NULL,
  payload_json   TEXT        NOT NULL,
  status         VARCHAR(16) NOT NULL,      -- PENDING / SENT / FAILED
  retry_count    INT         NOT NULL DEFAULT 0,
  next_retry_at  DATETIME    NOT NULL,
  created_at     DATETIME    NOT NULL,
  updated_at     DATETIME    NOT NULL,
  KEY idx_scan (status, next_retry_at)      -- Relay 扫描索引，必须有
);
```

```text
loop:
  BEGIN
    SELECT * FROM sys_outbox_event
     WHERE status IN ('PENDING','FAILED') AND next_retry_at <= NOW()
     ORDER BY id LIMIT 100
     FOR UPDATE SKIP LOCKED          ← 多实例的关键
  for each event:
     send 并等待 Publisher Confirm
     成功 → UPDATE status = 'SENT'
     失败 → retry_count++，next_retry_at = NOW() + backoff，超阈值 → 告警 / 死信
```

`FOR UPDATE SKIP LOCKED`（MySQL 8.0+）：多个 Relay 实例并发扫描时各自跳过被别人锁住的行，互不阻塞，天然分片。

### 4.3 Relay 多实例会不会重复投递

**会，但这不是问题。**

- `FOR UPDATE SKIP LOCKED` 保证同一时刻只有一个实例持有某一行
- 但实例 A 发消息成功后、**在 UPDATE status='SENT' 之前崩溃** → 行还是 PENDING → 实例 B 稍后扫到 → 再发一次
- 所以 Outbox 提供的是 **at-least-once**，不是 exactly-once

**标准答案：投递侧做到 at-least-once，消费侧做到幂等，两者合起来实现 effectively-once。**

#### Publisher Confirm 的边界（常被问穿）

- 开启 `publisher-confirm-type: correlated`，异步回调告知 broker 是否收到
- **Confirm 只保证 broker 收到了，不保证消息进了队列**：routing key 匹配不上队列时会静默丢弃，要同时开 `publisher-returns: true` 处理不可路由消息
- 只有 Confirm 成功才 UPDATE status='SENT'

### 4.4 延迟消息：TTL + DLX 的坑

```text
queue.normal（x-message-ttl=900000，x-dead-letter-exchange=dlx）
  → 消息 15 分钟无人消费，过期
  → 投递到死信交换机 dlx
  → 路由到 queue.timeout
  → 消费者消费 = "这个预约超时了"
```

#### 必考的坑：队列是 FIFO 的，队头不过期，后面的就出不来

RabbitMQ 只在**消息到达队头时**才检查 TTL 并投递到 DLX（惰性过期检查）。

举例：
- M1 先入队，TTL 30 分钟
- M2 后入队，TTL 5 分钟

M2 虽然 5 分钟后就该过期，但它排在 M1 后面，**要等到 M1 的 30 分钟到期才被投递**——延迟了 25 分钟。

三种解法：

| 方案 | 说明 | 适用 |
|---|---|---|
| **每个延迟档位一个队列** | 15 分钟档一个队列、30 分钟档一个队列 | MedFlow 的确认超时时长是**一个常量**，**正好可以用单个队列，这个坑天然不存在** |
| `rabbitmq_delayed_message_exchange` 插件 | 每条消息独立延迟，不受 FIFO 影响 | 缺点：非官方核心组件、集群下性能一般、消息量大时内存压力大 |
| **定时扫描**（不用延迟消息） | `WHERE status='PENDING' AND confirm_deadline < NOW()` | 简单、可靠、可观测；延迟 = 扫描间隔，有索引就不是全表扫描 |

**给 MedFlow 的建议：先做定时扫描验证业务正确性，再换成 TTL+DLX。** 因为超时时长是固定常量，TTL+DLX 的 FIFO 坑天然不存在。这个判断本身就是好答案——它说明你不是无脑上 MQ，而是先看约束。

### 4.5 失败与恢复

| 故障 | 表现 | 处理 |
|---|---|---|
| 消费者抛异常 | 业务失败 | 本地有限重试 3 次 → 仍失败则 Nack 且 requeue=false → 进死信队列 → 人工 / 定时任务处理 |
| Broker 挂了 | 发送失败 | retry_count++ 指数退避；消息仍在 Outbox 表里，broker 恢复后自动继续 |
| Relay 进程崩溃 | 事件停在 PENDING | 重启后重新扫描，`FOR UPDATE SKIP LOCKED` 保证不重复处理进行中的行 |
| Outbox 堆积 | 事件积压 | 监控 `status='PENDING' AND next_retry_at < NOW()` 的数量（文档里的少量业务指标之一） |
| 表无限增长 | 历史数据 | 定期清理已 SENT 且超过 N 天的事件 |

### 4.6 口述模板

> 超时关闭我没有直接在事务里发消息，因为数据库和 MQ 没法原子化。提交前发会出现"消息发了但事务回滚"，提交后发会出现"事务提交了但进程崩了导致消息永久丢失"——后一种是静默的数据损坏，预约会永远卡在待确认状态，号源被永久占用。
>
> 所以我用 Outbox：把要发的消息当数据插进业务库同一张表，和预约在同一个本地事务里提交。然后由 Relay 扫描投递，扫描用 `FOR UPDATE SKIP LOCKED`，这样多实例并发时互不阻塞。
>
> Relay 提供的是 at-least-once，因为发出去之后还没来得及标记 SENT 就崩了的话，另一个实例会再发一次。重复投递靠消费端幂等吸收。另外 Confirm 只保证 broker 收到，不保证进了队列，所以还要开 publisher-returns 处理不可路由的消息。
>
> 延迟消息我用的是 TTL 加死信交换机。这里有个坑：队列是 FIFO 的，队头消息不过期，后面的就算过期了也出不来。不过我的场景里确认超时时长是固定常量，所以一个队列就够，这个坑不存在。我打算先用定时扫描把业务正确性验证掉，再换成 TTL+DLX。

---

## 路线五：权限

### 5.1 先分清两个维度

| 维度 | 问题 | 例子 |
|---|---|---|
| **角色权限（RBAC）** | 这个角色能不能调这类接口 | 医生不能调 `POST /admin/schedules` |
| **数据权限（对象级）** | 这个用户能不能操作**这一条**数据 | 患者 A 不能看患者 B 的预约，即使两人都是 PATIENT |

面试官问"数据权限写在哪一层"，其实是在问你有没有分清这两件事。

### 5.2 为什么不在 Controller（三个理由，从弱到强）

1. **Controller 不该持有业务上下文。** 判断"医生 X 能不能看这个预约"需要知道预约属于哪个排班、排班属于哪个医生——这是业务关系，Controller 要判断就得在 Controller 里写业务逻辑。
2. **Controller 不是唯一入口（最硬的理由）。** Service 还会被**定时任务、MQ 消费者、其他 Service、未来的 RPC** 调用。权限只写在 Controller，这些入口全是裸奔的。**安全边界必须放在最内层、所有入口都会经过的地方——也就是 Service。**
3. **不可测试。** 写在 Controller 的权限逻辑只能靠 MockMvc 测；写在 Service 的，单元测试就能覆盖，快得多。

#### 一个很加分的真实答案

> 我一开始试过用 AOP + 自定义注解在 Service 方法上做数据权限（`@DataScope`）。做了一版后放弃了，因为不同接口的归属判断差别太大：有的是 `appointment.patient.ownerUserId == currentUserId`，有的是 `appointment.slot.schedule.doctor.userId == currentUserId`，还有管理员的特例。硬要统一成注解，注解会变得极其复杂，而且每次都要先把对象 load 出来再判断，白白多一次查询。
>
> 最后我改成：**把所有权条件直接写进 SQL 的 WHERE 里。**

### 5.3 推荐做法：把权限下推到 SQL

```sql
-- 患者查自己的预约
SELECT a.* FROM med_appointment a
 WHERE a.id = #{appointmentId}
   AND a.patient_id IN (SELECT id FROM med_patient WHERE owner_user_id = #{currentUserId});

-- 医生查自己排班的预约
SELECT a.* FROM med_appointment a
  JOIN med_slot     s   ON a.slot_id     = s.id
  JOIN med_schedule sch ON s.schedule_id = sch.id
  JOIN med_doctor   d   ON sch.doctor_id = d.id
 WHERE a.id = #{appointmentId}
   AND d.user_id = #{currentUserId};
```

好处：

- **权限判断和数据读取是一次数据库交互**，不是"先查出来再判断"。少了竞态窗口（查出来到判断之间数据可能被改），也少一次往返。
- **查不到就返回 404，而不是 403。** 返回 403 等于告诉攻击者"这个 ID 存在，只是你没权限"，造成 ID 枚举泄露。404 让攻击者分不清"不存在"和"没权限"。

#### 需要说出来的权衡

404 对用户不友好（用户看到"预约不存在"会以为系统有 bug）。常见折中：

- 对**自己的资源列表**返回 404（枚举风险高）
- 对**明确的越权操作**（操作别人的就诊人）返回 403
- 或统一返回 404，但日志里记 403 便于排查

### 5.4 为什么不直接信前端传的 patientId

- HTTP 请求完全可以被篡改（curl、Burp、浏览器 DevTools 改请求体）。**前端隐藏按钮、前端校验只是 UX，不是安全。**
- `med_patient.owner_user_id` 是正确的数据权限依据。
- 延伸：自增 ID 暴露给前端是可枚举的，可以用雪花 ID 或 UUID。但这也是"不要过度设计"的边界——小项目自增 ID + 后端权限校验已经足够。

### 5.5 认证与授权的三个层次

```text
SecurityContext（Filter 层）        → 认证：你是谁（JWT 解析、验签、过期、账号状态）
      ↓
SecurityConfig / @PreAuthorize      → 角色权限：这个角色能不能进这个 URL
      ↓
Service / SQL WHERE                 → 数据权限：这一条记录是不是你的
```

#### JWT 失效（必问题）

JWT 无状态，签发后无法单方面作废。三种方案：

| 方案 | 做法 | 评价 |
|---|---|---|
| 短 TTL + Refresh Token | access token 5–15 分钟 | 最简单；失效窗口可接受 |
| **Token 版本号** | `sys_user.token_version`，登出 / 禁用时 +1，校验时比对 | **推荐**：不引入新中间件；与你需求文档 8.1 预留的"令牌失效版本"字段一致 |
| Redis 黑名单 | 登出时把 jti 放入黑名单，TTL = token 剩余有效期 | 引入对 Redis 的强依赖（Redis 挂了怎么办），且和"Redis 只做查询缓存"的范围冻结冲突 |

选**方案 2**，它顺便把"登出后 token 为何失效"这个必问题答掉了。

### 5.6 口述模板

> 权限我分两层：角色权限和数据权限。角色权限在 Security 配置里按 URL 拦，比如医生进不了管理员的排班接口；数据权限我放在 Service，而且是直接写进 SQL 的 WHERE 里。
>
> 为什么不在 Controller：一是判断归属需要业务关系，写在 Controller 就等于在 Controller 写业务逻辑；二是 Controller 不是唯一入口，定时任务和 MQ 消费者也会调 Service，权限只在 Controller 的话这些入口全是裸奔的，安全边界必须放在所有入口都会经过的最内层；三是写在 Service 里单元测试就能覆盖，比 MockMvc 快得多。
>
> 我一开始试过用 AOP 加自定义注解做数据权限，放弃了，因为不同接口的归属判断差别太大，硬统一会让注解变得很复杂，而且每次都要先把对象查出来再判断，多一次查询。改成写进 WHERE 之后，权限判断和取数据变成一次查询，还顺带消掉了"查出来到判断之间数据被改"的窗口。
>
> 另外一个细节：越权我返回 404 而不是 403，因为 403 等于告诉对方这个 ID 确实存在，可以用来枚举。代价是用户体验差一点，所以我在日志里记 403 方便排查。

---

## 附录：五个必答的"送命题"准备卡

| 问题 | 你的答案必须包含 |
|---|---|
| 你这项目最大的缺陷是什么 | 一个**真实且有深度**的缺陷。推荐："超时完全依赖消息投递，如果 Relay 长时间挂掉，预约会一直卡在 PENDING 占着号源，我目前没有兜底的定时扫描。定时扫描其实是我的 Plan B，但还没实现。" |
| 有没有出现预料之外的 bug | 从七个破坏实验里挑一个真实的，讲"现象 → 数据库里的实际数据 → 定位过程 → 修法" |
| 500 并发里失败的 450 个返回什么 | 409 Conflict + 明确的错误码；用户看到"该时段号源已约满"；**目前没做限流**（诚实） |
| 测试数据是在什么机器上跑的 | 诚实说出机器配置和 JDK 版本；**绝不能说成 QPS 或生产级高并发** |
| 一万人抢 20 个号会怎样 | 单行热点：所有事务排队等同一行 X 锁，大量 1205 锁等待超时，连接池被打满。缓解顺序：分段库存 → 限流削峰 → 最后才考虑 Redis 预扣 + 对账 |

---

## 附录：七个破坏实验（不能让 AI 代做）

| # | 怎么破坏 | 你会看到什么 | 对应路线 |
|---|---|---|---|
| 1 | 去掉 `@Transactional` | 扣减成功、插入失败，号源凭空消失 | 一 |
| 2 | 条件更新改回先查再更，跑 500 并发 | 亲眼看到超卖，remaining 变负 | 一 |
| 3 | 去掉 CAS 的 `AND status = ...` | 取消与超时并发时号源被归还两次 | 三 |
| 4 | 删掉有效预约唯一索引 | 同一患者同一 Slot 出现两条有效预约 | 二 |
| 5 | 把 Outbox 移出事务 | 预约已提交但超时消息丢失，预约永久卡在 PENDING | 四 |
| 6 | 把 `@Transactional` 方法改成同类内自调用 | 事务静默失效，无任何报错 | 一 |
| 7 | 在 Service 里 catch 掉异常不抛出 | 事务不回滚，数据部分提交 | 一 |

每个实验记录：**现象 → 数据库里的实际数据 → 你的解释 → 修法**。这七个做完，L2 和 L3 的追问就撑得住了。
