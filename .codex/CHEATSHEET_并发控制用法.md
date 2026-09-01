# 并发控制用法速查表

> 回答一个问题：**这些概念在代码里到底长什么样，我该怎么用。**
> 每个概念只给「代码形态 + 什么时候用 + 用错的后果」，不讲原理（原理在 `INTERVIEW_DEEP_DIVE.md`）。

---

## 一、五个概念的速查表

| 概念 | 在代码里长这样 | 什么时候用 | 用错的后果 |
|---|---|---|---|
| **快照读** | `SELECT * FROM med_slot WHERE id = ?` | 只是**展示**数据，不拿它来计算 | 拿它算新值 → 丢失更新 |
| **当前读** | `UPDATE` / `DELETE` / `SELECT ... FOR UPDATE` | **要改数据**，或要拿最新值来算 | 该用的时候不用 → 基于旧值改 |
| **行 X 锁** | `UPDATE ... WHERE id = ?` 会**自动**加<br>`SELECT ... FOR UPDATE` **手动**加 | 保证"我改这行的时候没人插进来" | 显式加锁但没用上 → 白锁 |
| **affected rows** | `int rows = mapper.updateXxx();` | 让数据库**告诉你输赢** | 不看返回值 → 不知道有没有成功 |
| **丢失更新** | `UPDATE t SET v = #{appComputed}` | —— 这是 bug，不要写 | 并发下更新被覆盖 |

---

### 共享锁（S 锁）与排他锁（X 锁）的兼容关系

一句话：**读读共享，读写互斥，写写互斥。**

- **S 锁（共享锁）** = "我在看这行，你们也可以看，但**谁都别改**"
- **X 锁（排他锁）** = "我在改这行，**你们谁都别碰**，看也不行、改也不行"

| 我已加 ↓ ＼ 别人要加 → | 别人想加 S 锁 | 别人想加 X 锁 |
|---|---|---|
| **我已经加了 S 锁** | **兼容**，都能加，互不阻塞 | **冲突**，要等我释放 |
| **我已经加了 X 锁** | **冲突**，要等我释放 | **冲突**，要等我释放 |

#### 为什么 S 锁之间不互斥（反着想就懂了）

**如果 S 锁之间也互斥会怎样？** 100 个人同时查同一个号源的剩余数量，就得排成一队，
一个查完下一个才能查 —— 数据库退化成串行执行，多核 CPU 全白给，吞吐量直接崩掉。
而这些读操作之间**不可能产生任何冲突**：A 读到的和 B 读到的是同一份数据，谁也不改变谁。

> **S 锁保护的是「别在我看的时候偷偷改」，不是「别让别人也来看」。**

#### 易混：「插入预约」对两张表做了两件不同的事

| 对哪张表 | 做了什么 | 加什么锁 | 会不会冲突 |
|---|---|---|---|
| `med_appointment` | **写**（新增一行预约） | 新行带 X 锁 | 不会 —— 每个事务插的是**不同的新行**，各写各的 |
| `med_slot` | **只读**（确认父行存在） | **S 锁** | 不会 —— S 锁之间兼容 |

「插入 = 写 = 应该互斥」这个直觉**在子表上成立、在父表上不成立**。
外键检查对父表只是"读一下确认存在"，不是写。

**哪些语句加锁（重要）**：

| 语句 | 加什么锁 |
|---|---|
| `SELECT ...`（普通） | **不加锁**（RR 下走快照读 / MVCC） |
| `SELECT ... LOCK IN SHARE MODE` | S 锁 |
| `SELECT ... FOR UPDATE` | X 锁 |
| `UPDATE` / `DELETE` | X 锁 |
| `INSERT` | 新行带 X 锁 |
| **外键检查（你没写的那句）** | **父表那一行加 S 锁** |

### 外键为什么讨厌：它加的锁你看不见

你写 `INSERT INTO med_appointment (slot_id, ...) VALUES (88, ...)`，
若 `slot_id` 有外键指向 `med_slot.id`，数据库实际执行**两条**：

```text
① SELECT ... FROM med_slot WHERE id = 88   ← 你没写，还给它加了 S 锁
② INSERT INTO med_appointment ...          ← 你写的这条
```

**外键检查只查"存在"，不查业务状态**（关键）：

```sql
-- 外键偷偷做的：只确认这一行在不在
SELECT ... FROM med_slot WHERE id = 88

-- 你自己写的：确认在 + 状态是 OPEN + 还有余量，并且改掉
UPDATE med_slot SET remaining_capacity = remaining_capacity - 1
 WHERE id = 88 AND status = 'OPEN' AND remaining_capacity > 0
```

**外键不知道什么叫"还有号源"。** 即使 `remaining_capacity` 已经是 0，外键检查照样通过。
这正是"外键只能检查存在、检查不了业务状态"的具体体现。

### 外键的锁代价：取决于代码顺序（重要修正）

**顺序 A（MedFlow 该用的）：先扣减，后插预约**

```text
BEGIN
① UPDATE med_slot SET remaining = remaining - 1 WHERE ...  → 拿到 slot 88 的 X 锁
② INSERT INTO med_appointment ...   → 外键检查要 S 锁，但我已持有 X 锁
                                       （X 权限更大），直接通过，不加新锁
COMMIT
```

**这种情况下外键检查没有额外加锁。** 所以"外键一定把锁窗口拉长"这个说法不准确。

**顺序 B：先插预约，后扣减**

```text
BEGIN
① INSERT INTO med_appointment ...  → 外键检查拿 slot 88 的 S 锁
② UPDATE med_slot SET ...          → 要 X 锁，得把 S 锁"升级"
COMMIT
```

S 锁从步骤 ① 就持有着。两个并发事务都走到这一步 → 互相等对方释放 S 锁 → **死锁 1213**。

### 外键的真实代价（重新排序，这才是该背的）

| 排序 | 代价 | 说明 |
|---|---|---|
| **① 重灾区** | **删除父表行时全表扫描加锁** | 删一个号源，数据库必须检查所有子表有没有引用它。子表关联列没索引 → **全表扫描 + 给扫过的行加锁**。预约表几十万行，删一个号源等于锁全表 |
| **② 易被忽略** | **只插不改的路径照样加锁** | 批量导入预约、管理员补录、数据修复脚本 —— 只插 `med_appointment`、不改 `med_slot`。它们的 S 锁持有到 COMMIT，**把正常预约的扣减堵在门外**。平时没人注意，一跑就是事故 |
| **③** | **级联不理解业务规则** | `ON DELETE CASCADE` 下误删号源 → 几十条预约静默消失 |
| **④** | **锁看不见，排查困难** | 你写的两条 SQL 毫无冲突，数据库却给了你 1213 |
| **⑤** | **迁移与分库分表** | 批量修数据要 `SET FOREIGN_KEY_CHECKS = 0`；跨库外键没法用 |

**立场不变（不建外键），但最强理由是 ①②③，不要只讲"锁窗口被拉长"。**

**推论**：`book()` 里"先扣减、后插预约"这个顺序，不只是业务要求，
也是在**把 X 锁的获取时刻尽量往后推、把锁窗口压到最短**。代码顺序不是随便排的。

### 另外两个易错认知

- **不存在"锁父行 = 锁住所有子行"** —— MySQL 没有这种机制，S 锁只锁父表那一行。
- **S 锁之间兼容** —— 100 个事务同时插引用同一号源的预约，互不阻塞；
  真正被堵的是想 `UPDATE` 那个号源的事务（要 X 锁）。

---

## 二、同一个需求，四种写法

**需求**：扣减 `med_slot` 的余量。容量 1，两个请求同时进来抢。

### 写法 1：丢失更新（错）

```java
// Service
public void book(Long slotId) {
    Slot slot = slotMapper.selectById(slotId);            // 快照读
    if (slot.getRemainingCapacity() <= 0) {
        throw new SoldOutException();
    }
    int newValue = slot.getRemainingCapacity() - 1;        // 应用算新值
    slotMapper.updateRemaining(slotId, newValue);          // 写常量
}
```

```sql
SELECT * FROM med_slot WHERE id = #{slotId};                          -- 快照读
UPDATE med_slot SET remaining_capacity = #{newValue} WHERE id = #{id}; -- 写常量
```

**为什么错**：`newValue` 是应用算好的常量。两个请求都读到 1、都算出 0、都写 0，都返回成功。一个号卖给了两个人。

**一句话定性**：改的时候用了读取时候的旧数据。

---

### 写法 2：SELECT ... FOR UPDATE（对，但很容易写不全）

```java
@Transactional
public void book(Long slotId) {
    Slot slot = slotMapper.selectByIdForUpdate(slotId);   // 当前读 + 行 X 锁
    if (slot.getRemainingCapacity() <= 0) {               // 必须在锁内重新判断
        throw new SoldOutException();
    }
    slotMapper.updateRemaining(slotId, slot.getRemainingCapacity() - 1);
}
```

```sql
SELECT * FROM med_slot WHERE id = #{slotId} FOR UPDATE;   -- 当前读，加行 X 锁
UPDATE med_slot SET remaining_capacity = #{newValue} WHERE id = #{id};
```

**为什么这样是对的**：`FOR UPDATE` 让并发请求排队，第二个拿到锁时是**当前读**，会看到第一个改完的值，于是 `if` 判断拦住了它。

**两个必须记住的坑**：

1. **这个 `if` 不能省。** FOR UPDATE 本身不防超卖，它只保证"我加锁期间没人动这行"。省掉 if，一行超卖都防不住。
2. **新值必须用加锁之后读到的值算。** 下面这样写，锁加了也是白加：

```java
// 错误示范：锁加了，但值还是用加锁之前的旧数据算的
Slot slot = slotMapper.selectById(slotId);        // 快照读，读到 10
slotMapper.selectByIdForUpdate(slotId);           // 加锁了，但没用它的返回值
slotMapper.updateRemaining(slotId, slot.getRemainingCapacity() - 1);  // 还是用 10 算
```

---

### 写法 3：条件更新（MedFlow 采用，推荐）

```java
@Transactional
public void book(Long slotId) {
    int rows = slotMapper.decreaseIfAvailable(slotId);   // 一条语句搞定
    if (rows == 0) {
        throw new SoldOutException();                     // → 409
    }
    // rows == 1 才继续：插入预约、写状态历史、写 Outbox
}
```

```xml
<update id="decreaseIfAvailable">
  UPDATE med_slot
     SET remaining_capacity = remaining_capacity - 1
   WHERE id = #{slotId}
     AND status = 'OPEN'
     AND remaining_capacity > 0
</update>
```

**为什么这样是对的**，三件事同时成立：

1. `remaining_capacity - 1` —— 减 1 由**数据库**在改的那一刻算，用的是最新值
2. `AND remaining_capacity > 0` —— 判断也交给了数据库，不存在"判断与动手分离"
3. `UPDATE` 会自动加**行 X 锁** —— 想改同一行的人得排队

**为什么 `rows` 必须检查**：MySQL 用 affected rows 告诉你输赢。1 = 你抢到了，0 = 没抢到。不看这个数字，你就不知道自己赢了还是输了。

---

### 写法 4：只查不改（快照读的正确用法）

```java
public SlotVO getSlot(Long slotId) {
    Slot slot = slotMapper.selectById(slotId);   // 快照读完全没问题
    return SlotVO.from(slot);                     // 只展示，不拿它计算
}
```

**快照读不是坏东西。** 它只是不适合"读了要用来算新值"这个场景。展示、列表查询、导出，都该用它——因为它快（不加锁、不走当前读）。

---

## 三、一个万能判据（一句话判断会不会丢更新）

> **看你 `UPDATE` 的 `SET` 右边，有没有出现列名。**

```sql
UPDATE t SET v = v - 1 WHERE ...;   -- 右边出现了列名 v → 数据库算的 → 安全
UPDATE t SET v = 9     WHERE ...;   -- 右边是常量   → 应用算的   → 需要额外保证
```

**为什么这个判据成立**：右边出现列名，意味着"读当前值 → 算 → 写"这三步全在数据库内部一次完成，中间没有缝隙（见前面的图）。右边是常量，意味着计算发生在应用里，读和写之间隔着一次网络往返。

---

## 四、自检三问（写完一段更新代码，问自己）

| # | 问题 | 危险答案 |
|---|---|---|
| 1 | 我写进去的值，是常量还是引用列值？ | 常量 |
| 2 | 这个常量是多久之前算的？ | 隔着一次数据库往返 |
| 3 | 算完到写入这段时间，别人能不能改这行？ | 能 |

**三个都是危险答案 → 这就是丢失更新。**

注意第 3 问：如果中间没人改，旧数据就是新数据，不会出问题。所以这个 bug 只在并发下暴露——**这也是为什么它平时测不出来，上线才炸**。

---

## 五、那什么时候该用 FOR UPDATE

条件更新不是万能的，它只能表达**数值型条件**。遇到下面这些情况，才需要 FOR UPDATE：

| 场景 | 为什么条件更新不行 | 怎么做 |
|---|---|---|
| 判断依据不是本行的值 | 比如"这个医生今天有没有排班"，值在**另一张表** | 锁住医生那一行，再查排班 |
| 一次要改多行且互相有关联 | 条件更新只能判断自己这行 | 按**固定顺序**锁多行，否则死锁 |
| 要先读一堆数据做复杂业务判断，再决定改不改 | 判断逻辑没法塞进 WHERE | 锁住那一行，判断完再改 |

**MedFlow 里的实际例子 —— 排班冲突检查**：

```sql
-- 条件更新表达不了"存不存在时间重叠的记录"，所以要锁
SELECT * FROM med_schedule
 WHERE doctor_id = #{doctorId}
   AND work_date = #{date}
   AND status IN ('DRAFT','PUBLISHED')
   FOR UPDATE;                    -- 锁住这个医生这一天的排班范围
-- 然后在应用里判断时间是否重叠，不重叠才插入
```

**固定锁顺序的规矩**：如果一次要锁医生 + 诊室，所有代码路径都必须**先锁医生再锁诊室**。顺序不一致就是死锁。

---

## 六、对照：MedFlow 每个地方用的是哪种

| 位置 | 用哪种 | 原因 |
|---|---|---|
| 预约扣减号源 | 条件更新 | 数值条件，一行搞定，最可靠 |
| 取消归还号源 | 条件更新（带上限） | `AND remaining < total_capacity` |
| 预约状态变更 | 条件更新（CAS） | `WHERE id = ? AND status = 'PENDING'` |
| 排班冲突检查 | `SELECT ... FOR UPDATE` | 判断依据在别的行 |
| 号源列表查询 | 快照读 | 只展示 |
| 预约详情查询 | 快照读 + WHERE 权限条件 | 只展示，权限写进 SQL |
| Outbox Relay 扫描 | `FOR UPDATE SKIP LOCKED` | 多实例并发扫描，各自跳过被锁的行 |

---

## 七、等锁超时 vs 死锁：回滚范围不一样（极易搞混）

| | 锁等待超时 | 死锁 |
|---|---|---|
| 错误码 | `1205 Lock wait timeout exceeded` | `1213 Deadlock found` |
| 触发条件 | 等锁超过 `innodb_lock_wait_timeout`（默认 **50 秒**） | 多个事务互相等待成环，被 MySQL 主动检测出来 |
| **回滚范围** | **只回滚卡住的那一条语句**（默认） | **回滚整个事务** |
| 谁被牺牲 | 等不及的那个 | MySQL 挑一个（通常改得少的） |

**关键参数**：`innodb_rollback_on_timeout`

- 默认 `OFF` → 超时**只回滚那一条语句**，事务还开着
- 设为 `ON` → 超时回滚**整个事务**

**为什么这个区别很重要**：默认行为下，等锁超时后事务还活着。如果应用代码 catch 了异常却不继续处理，就会在一个"部分失败"的事务里继续跑——后面的操作基于一个错误的前提。

**注意这三条治的是谁**：它们全部在治**乙的岔路**。超卖这件事消除不掉——只要乙去抢甲持有的行锁，乙就必须排队，排队就可能排满超时，这是排队的代价不是 bug。三条做法真正治的是**超时之后乙那个做了一半的事务会不会被 COMMIT 出去**。甲的病（一直不提交）这三条一条都不管，见第八节。

**正确做法**（三条，按推荐顺序）：

1. **靠 Spring**：`@Transactional` 默认对 `RuntimeException` / `Error` 回滚。所以 catch 之后**必须把异常继续抛出去**，事务才会整体回滚。
2. **不 catch**：让异常直接冒泡到事务边界，交给 Spring 处理。最简单也最不容易错。
3. 设 `innodb_rollback_on_timeout=ON`：让 MySQL 兜底。但它是全局的，会影响所有超时场景。

**最常见的坑**：

```java
@Transactional
public void cancel(...) {
    try {
        appointmentMapper.cancelIfActive(id);
    } catch (Exception e) {
        log.error("取消失败", e);
        // 异常被吞了！Spring 认为方法正常返回 → 事务提交 → 数据部分写入
    }
}
```

这就是七个破坏实验里的第 7 个。**catch 了不抛 = 事务不回滚。**

### 顺带澄清：超时的是谁

场景：事务甲 `UPDATE` 成功但**不提交**，事务乙执行同一行的 `UPDATE`。

- **甲不会超时，也不会被回滚。** 它只是卡着不提交，锁一直攥在手里，直到自己 COMMIT / ROLLBACK / 连接断开。
- **超时的是乙。** 乙等满 50 秒后报 1205。

所以"甲超时导致乙回滚"是错误理解——甲毫发无损，**乙的超时是 MySQL 主动让乙失败，好让它别无限等下去**。

而甲如果一直不提交，谁能治它？**没有任何数据库机制会自动杀掉它**（除了连接断开）。所以生产上必须在应用层加事务超时，比如 `@Transactional(timeout = 5)`，或连接池的 `removeAbandoned`。

---

## 八、事务甲出问题的完整分类（按"谁来收尾"分）

**先回答：MySQL 有没有约束甲的处理时间？没有，零约束。**

MySQL 分不清"这条事务在跑 3 小时的批量对账"和"这条事务的应用崩了"，它不敢替你做决定。三个常被误以为能管住甲的参数：

| 参数 | 默认值 | 实际管的是谁 |
|---|---|---|
| `innodb_lock_wait_timeout` | 50s | 只管**等锁的人（乙）**，管不着持锁的甲 |
| `wait_timeout` / `interactive_timeout` | 28800s（8 小时） | 只管**空闲连接**，不管开着的事务 |
| `innodb_rollback_on_timeout` | OFF | 只管**超时后回滚多少**，不限制任何人 |

所以一条不提交的事务最长能挂 **8 小时**，这期间行锁一直攥着。**约束只能加在应用层**：`@Transactional(timeout)`、连接池 `leak-detection-threshold`、调低 `wait_timeout`。

### 分类：出问题后谁给这条事务收尾

| 类别 | 具体场景 | MySQL / 框架的行为 | 应用层要做什么 |
|---|---|---|---|
| **① 甲自己失败** | 业务校验不通过、`rows == 0` 售罄 | 不管，等甲自己决定 | 抛 `RuntimeException`，Spring 回滚。**正常流程** |
| **② 甲被裁定** | 死锁，MySQL 挑甲做受害者 | 报 **1213**，回滚甲的**整个事务** | 捕获后**有界重试**（3 次 + 退避） |
| **③ 甲僵住** | 既不成功也不失败 | **没人管它，也不报错**，锁一直攥着 | 见下面的三种僵法 |

**关键结论：只有 ② 数据库会主动帮你回滚，其余全部靠应用层。**

### ③ 甲僵住的三种僵法

> **编号说明**：下面的 3a / 3b / 3c 只是本文档自己编的序号（"第 ③ 类的第 1/2/3 种"），
> **不是行业术语**。面试时不要说"3a"，要说具体场景。

| 僵法 | 具体是什么 | 锁持有多久 | 怎么治 |
|---|---|---|---|
| **3a 事务里跑慢 SQL** | 没走索引的 UPDATE 扫全表、一次改几万行 | 语句跑多久锁多久 | 加索引；把这条 UPDATE 挪到事务最后一句 |
| **3b 事务里做外部 IO** | 发短信、调支付、直连 MQ 发消息 | 网络卡多久锁多久 | **Outbox**——把 IO 移出事务 |
| **3c 应用假死** | Full GC、线程池打满、TCP 半开 | 直到连接被回收，**最长 8 小时** | **`@Transactional(timeout)` 基本无效**（见下），靠连接池强制回收 + `wait_timeout` 兜底 |

**注意：下面这段曾被我说得过于乐观，已修正。**

### 三个解法各自治什么（别高估 `@Transactional(timeout)`）

`@Transactional(timeout = 5)` 是**客户端（JVM）计时**的：Spring 把值透传给 JDBC，驱动起一个定时器线程，到点发一条 `KILL QUERY` 给 MySQL。而 3c 恰恰是**客户端自己出了事**，所以它对 3c 基本无效：

- **Full GC**：JVM 所有线程全停（Stop The World），**定时器线程也停**。GC 30 秒，锁就多攥 30 秒，GC 结束后 `KILL QUERY` 才发得出去。
- **线程池打满**：定时器要发网络请求，若它自己就卡在同一个打满的池子里，请求发不出去。
- **TCP 半开**：网络断了，`KILL QUERY` 根本送不到 MySQL。

正确的对应关系：

| 僵法 | `@Transactional(timeout)` | 真正管用的是 |
|---|---|---|
| 3a 事务里跑慢 SQL | **有效，这是它的主战场** | 索引 + 把 UPDATE 挪到事务最后 |
| 3b 事务里做外部 IO | 部分有效 | 别在事务里做（Outbox，见路线四） |
| 3c 应用假死 | **基本无效** | 连接池强制回收 + `wait_timeout` 兜底 + 监控告警 |

三个手段的真实分工：

1. **`@Transactional(timeout = 5)`** —— 治**单条 SQL 跑太久**。客户端自己挂了它就失灵。
2. **连接池泄漏检测** —— **只报警，不治病**。HikariCP 的 `leak-detection-threshold` 超时只打一条 WARN 日志（`Apparent connection leak detected`），**不会强制回收**。Druid 的 `removeAbandoned=true` 才会真的 `close()` 掉连接触发回滚，但可能误杀正在跑慢查询的合法连接。
3. **调低 `wait_timeout`** —— 治 **3c 的孤儿连接**，**唯一由 MySQL 自己兜底**的手段。它只对"闲着不说话"的连接计时，正在跑 SQL 的不受影响——这正好是 3c 的特征（`trx_query` 为 NULL）。默认 8 小时太长。

**但别指望 `wait_timeout` 治 3c，它是第 4 层兜底，不是主力。** 现实手段的排序：

1. `@Transactional(timeout)` 治 3a 慢 SQL（主战场）
2. `leak-detection-threshold` **发现**泄漏（只报警）
3. 监控 `information_schema.innodb_trx` 找出长事务（发现 3c）
4. `wait_timeout` 兜底（避免锁被攥 8 小时）

**改 `wait_timeout` 必须和连接池配套（极易踩坑）**：HikariCP 的 `maxLifetime` 默认 30 分钟，规则是 **`wait_timeout` 必须大于 `maxLifetime` 并留余量**。反过来（MySQL 10 分钟掐断、连接池以为能用 30 分钟）会导致应用拿到死连接，报 `Connection is not available`。所以：

```yaml
spring:
  datasource:
    hikari:
      max-lifetime: 540000        # 9 分钟，必须小于 MySQL 的 wait_timeout
```
```ini
[mysqld]
wait_timeout = 600               # 10 分钟，比 max-lifetime 多留 1 分钟
```

设置方式：`SET GLOBAL wait_timeout = 600`（**只影响之后新建的连接**，重启 MySQL 即失效）；永久生效要写进 `my.cnf` 的 `[mysqld]` 段。**`wait_timeout` 和 `interactive_timeout` 要一起改**（应用走 JDBC 用前者，命令行客户端用后者）。

### 三个超时参数的区别（极易混淆，必背）

| 参数 | 计的是**谁的时间** | 默认 | 到点了会怎样 |
|---|---|---|---|
| `innodb_lock_wait_timeout` | **一条 SQL 排队等锁**等了多久 | 50 秒 | 报 1205，只回滚那条语句 |
| `wait_timeout` | **整个连接**有多久没收到任何 SQL | 28800 秒（8 小时） | MySQL 主动断开这个连接 |
| `Statement.setQueryTimeout`（即 `@Transactional(timeout)`） | **每一条 SQL** 从发出到返回 | 无默认 | JDBC 发 `KILL QUERY` 掐掉这条 SQL |

**一句话区分：`wait_timeout` 计的是「连接有多久没说话」，不是「SQL 跑了多久」。** 一条 SQL 跑 7 小时，只要它一直在执行，`wait_timeout` 的计数器就一直是 0，永远不会掐它。

**`innodb_lock_wait_timeout` 与"事务能不能跑完"无关。** 20 条 SQL、每条 10 毫秒、都不撞锁 → 事务总时长 200 毫秒，设成 1 秒也不会触发；只有 1 条 SQL 但撞了锁 → 等满 50 秒报 1205。

### 该设多少，以及为什么

**不是"几分钟"，业界常见 3~10 秒，很多团队规范 3~5 秒。**

理由**不是**"不多不少刚好够业务跑完"，而是：**它是一个告警阈值，不是时间预算。**

健康系统里持锁时间是毫秒级，等锁自然也是毫秒级，3~5 秒是上百倍余量。一旦有请求等锁超过 3 秒，说明系统里存在持锁 3 秒以上的事务——**那是 bug，应该让它快速失败并暴露出来**，而不是排队 50 秒耗尽连接池。

**雪崩的完整链条**（背下来）：

1. 某个事务持锁 60 秒（比如事务里调了慢接口）
2. 后面 100 个请求全部**排队等这一把锁**
3. 连接池只有 50 个连接 → **全部被占满，而且全在等锁**
4. 第 51 个请求**根本不需要那把锁**（只是想查医生列表），但**连连接都拿不到**
5. 整个应用不可用 —— **一把号源的锁，拖垮了整个系统**

### 孤儿连接

**应用那边已经不管这条连接了，但 MySQL 这边连接对象还在。** 比喻：家长把孩子留在学校然后不见了，学校只能等到放学时间（`wait_timeout`）才清场。

来源：TCP 半开（主因）、应用假死、代码漏了 close。

**危险不是占内存，是它可能还开着事务、还攥着行锁**——一个孤儿连接能把一行号源锁 8 小时。

这三种僵法的共同点：**MySQL 都认为这条事务活着且正常，只是暂时没说话**，而"暂时没说话"在 MySQL 那里不是异常。

**3c 里反直觉的一点**：进程直接崩掉反而是**安全的**——操作系统关掉 socket，MySQL 立刻感知连接断开，马上回滚释放锁。真正危险的是**进程还活着但卡住了**：socket 还在，MySQL 以为这条事务好端端跑着，老老实实替它留着锁。

排查"锁一直不释放"第一件事不是查 SQL，是查**持锁的连接还活着没有**：

```sql
SELECT trx_id, trx_started, trx_mysql_thread_id, trx_query, trx_rows_locked
  FROM information_schema.innodb_trx
 ORDER BY trx_started LIMIT 5;
```

`trx_started` 是几分钟前、`trx_query` 是 NULL —— 就是 3c：应用在事务里卡住，什么都没干。

### 两个坑

**坑一：`@Transactional(timeout = 5)` 不是"整个事务最多 5 秒"**

Spring 把它透传给 JDBC 的 `Statement.setQueryTimeout`，**每条 SQL 各自计时**。10 条 SQL 每条 3 秒，事务总时长 30 秒，一条都不会超时。它是防"单条 SQL 卡死"的，不是防"事务变长"的。防事务整体变长要靠连接池的 `leak-detection-threshold`（只打日志告警，不杀事务）或自己写切面。

**坑二（直通路线二幂等）：唯一键冲突 1062 也只回滚那一条语句**

和 1205 是同一条规则：**单条 SQL 报错 ≠ 事务回滚**。

这条对 MedFlow 特别重要——幂等方案（唯一索引 + 捕获 `DuplicateKeyException` 后转去查已有记录）**完全建立在这个行为上**。如果 1062 会回滚整个事务，catch 了也没用，那套方案根本写不出来。**路线一和路线二在这件事上是同一块地基。**

### 口述模板（"事务一直不提交怎么办"）

> "MySQL 这边没有任何机制会主动杀掉长事务，`innodb_lock_wait_timeout` 管的是等锁的人，`wait_timeout` 管的是空闲连接，都不管持锁的人。所以约束必须加在应用层：一是 `@Transactional(timeout)`，不过它走的是 JDBC 的 `Statement.setQueryTimeout`，按单条 SQL 计时不是按事务计时，这个我踩过；二是连接池的泄漏检测；三是代码规范上把外部 IO 移出事务——我们用 Outbox 就是干这个的，顺带还把锁窗口缩短了。"

---

## 九、口述自查（讲给面试官听的时候，检查这几条）

1. 说得出**为什么先查再更会超卖**（不要用术语）
2. 说得出**条件更新为什么不会**（SET 右边有列名 + 自动加行锁）
3. 说得出 **affected rows 是判胜负的方式**（1 抢到，0 没抢到）
4. 说得出 **FOR UPDATE 不直接防超卖**（还得在锁内写 if），以及**锁都是到 COMMIT 才释放**
5. 说得出 **1205 只回滚语句、1213 才回滚事务**，以及默认 50 秒太长应设 3–5 秒
6. 说得出 **MySQL 没有任何机制约束持锁者**，长事务只能应用层兜底（`@Transactional(timeout)` 的坑、Outbox 缩短锁窗口）
7. 说得出 **1062 唯一键冲突也只回滚语句**——这是幂等方案的地基
