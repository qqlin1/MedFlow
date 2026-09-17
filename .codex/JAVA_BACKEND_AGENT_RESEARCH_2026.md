# 2026 年 27 届 Java 后端 × Agent 应用开发调研与 MedFlow 重排

> 调研截止：2026-09-07
>
> 适用对象：27 届、已有一段非头部公司实习、以 Java 后端为主线，同时准备 Agent/AI 应用开发岗位。
> 本文是“证据、优先级和执行说明”；动态完成状态只记在 `PROJECT_PROGRESS.md`。

## 0. 先给结论

1. **不能用场景题替代系统八股，也不能只背八股。** 近期面试最稳定的形态是：项目/实习开场，Java、MySQL、Redis、并发等基础筛选，再沿简历追到技术选型、失败窗口、容量、排障和改造方案。
2. **MedFlow 的业务范围不需要推倒重来。** 并发预约、幂等、状态竞争、索引与慢 SQL、缓存一致性、可靠消息、数据权限，正好承载高价值问题。需要重排的是学习顺序和学习深度。
3. **Security 要完整，但不再源码深挖。** 登录、密码哈希、JWT、401/403、RBAC、对象归属权限和越权验证要完成；FilterChain 内部源码、所有过滤器顺序和冷门扩展点只做到“能定位、会查文档”。
4. **Java 与 Agent 不是两条互不相干的赛道。** 当前 Agent 应用岗同样看编程、API、数据库、缓存、异步、稳定性和可观测性。正确定位是“强 Java 后端 + 可上线的 AI 应用”，不是把 Java 降级成 CRUD，也不是现在转向模型训练。
5. **前三周先做出可面试版本，后续再补完整工程证据。** 2026-09-27 前要形成 MySQL 正确性、并发与 SQL 优化三条可讲故事；Redis、MQ、部署继续迭代，但投递和模拟面试现在就开始。
6. **AI 可以生成模板代码，不能替你做关键决策。** 核心 SQL、事务边界、并发结果、索引选择、缓存失效、消息幂等、Agent 工具权限和评测设计必须由你解释并用实验验证。

## 1. 这次调研怎么做的

### 1.1 样本范围

- 24 份 2025-03 至 2026-09 的一手交流帖：23 份牛客原帖、1 份力扣中国原帖。
- 样本覆盖字节、美团、京东、腾讯、快手、滴滴、虾皮、携程、百度、得物及中小厂；包含实习、校招、Java 后端、后端和 AI 全栈等岗位。
- 另参考一位候选人对 43 场暑期实习面试的自报频次复盘，只作交叉校准，不把它当行业统计。
- Agent 方向另抽取 10 个 Agent 应用/平台/算法岗位，按 JD 明写内容逐项编码；另补充 3 个 2027 届官方/高校转发岗位，并用 Spring AI、MCP、OWASP 等一手技术资料校正学习内容。

### 1.2 纳入标准

- 有公司、岗位、轮次或时间线中的至少两项；
- 以第一人称记录实际问答，最好包含卡住点、追问、结果或评论互动；
- 能看到具体问题链，而不是只有“Java/MySQL/Redis 都问了”；
- 多轮写在同一帖时，按“这一帖是否出现某主题”判断，不按题目条数放大长帖权重。

### 1.3 明确排除

- “2026 Java 真题大全”“一文通关”“某厂必考”一类二次题库或培训软文；
- 没有原始轮次、把多家题目拼在一起的营销账号文章；
- 把招聘 JD 的“了解/加分项”直接改写成“一面必问”的内容；
- 无法区分 Java 通用岗与 Agent 专项岗的混合统计。

### 1.4 局限

- 这是定向质性样本，不是随机抽样，**不能推出全行业准确概率**。
- 牛客偏互联网公司和主动分享者，字节、美团、快手被过度代表，银行、国企和传统软件公司不足。
- 面经是候选人回忆，可能漏题、记错或转述错误；公司、部门和面试官无法独立核验。
- “帖子没写”不等于“面试没问”；有的作者只记录答不好的题。
- 题目强烈受简历触发：写了 DDD、Redis 锁、MQ、RAG、MCP，就必须接受对应深挖。
- 因此本文只使用“反复出现、常见、简历触发、岗位特定”等等级，不写无依据的“出现概率 > 80%”。

## 2. 一手面经来源台账

| 编号 | 公司/岗位/时间 | 原帖呈现的主要追问链 | 来源 |
|---|---|---|---|
| J01 | 字节后端，一二三面，发帖 2025-03-20，已 OC | 实习业务 → 分布式锁 → Redis 单点/ZK → MQ 选型、重复与幂等 → 持久化 → LRU/算法 | [牛客原帖](https://www.nowcoder.com/discuss/732234187624697856) |
| J02 | 字节国际电商 Java 后端一面，发帖 2025-03-19 | 上线迭代、数据库重构与兼容 → HashMap/Redis Set → 虚拟内存 → 表达式求值 | [牛客原帖](https://www.nowcoder.com/discuss/731844862831595520) |
| J03 | 美团后端暑期一面，发帖 2025-03-20，2025-03-25 已 OC | 项目角色/为什么做 → Redis-DB 一致性 → Kafka 解耦 → Spring → JVM/ThreadLocal → 索引和 SQL 执行 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/7a197c54ad294c5496eb6146a6f740d3) |
| J04 | 美团 Java 后端暑期一二面，面试 2025-03-27/31 | 定时任务执行器与 CPU 空转 → Spring Task/令牌桶 → 联合索引 → JVM/GC → 把算法实现改成线程安全单例 | [牛客原帖](https://www.nowcoder.com/discuss/737248953099436032) |
| J05 | 美团食杂零售 Java 后端一面，帖子显示 2025-04-02 | 线程池参数与 P95 → 500 万行索引/分表 → 隔离、锁、索引失效 → MQ 只消费一次/失败 → DCL/TCP | [牛客原帖](https://www.nowcoder.com/feed/main/detail/cea735f102df45c6aea96ad5af501658) |
| J06 | 京东 Java 后端实习一面，帖子显示 2025-03-27 | 秒杀扣减与 DB 一致性 → 单/多线程和线程池 → 何时落库 → Redis 分片主从 → IM 群发链路 | [牛客原帖](https://www.nowcoder.com/discuss/734872934639996928) |
| J07 | 京东零售 Java 暑期一面，发帖 2025-04-20 | 共享代码 → Sentinel/OpenFeign 选型 → Redis 穿透 → 线程池/连接池 → MQ 积压 → MVCC/低选择性字段索引 → DDD/算法 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/7c4d6fcd3aee467ab0ab907b7616350b) |
| J08 | 腾讯 Java 后端一面，帖子显示 2025-04-24 | HashMap/CHM/GC/线程池 → B+ 树、锁、MVCC、慢 SQL/深分页 → 网络 → 微服务、Nacos 故障 → ZSet/TopK | [牛客原帖](https://www.nowcoder.com/discuss/744849501931790336) |
| J09 | 快手后端三轮+HR，面试 2025-08-12/20/29，HR 09-08，OC | 并发 LRU → CHM/CAS → 线程池参数 → 数据库并发/死锁/索引 → 共享单车方案 → 事务/索引/Raft → AI 认识 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/da8e1d3f422d4aeba61d8ba7e422de98) |
| J10 | 滴滴 Java 后端日常一面，帖子显示 2025-09-24 | 不丢不重协议 → TCP/UDP → 分布式 ID → 并发读/MVCC → Redis 过期 → 项目 MQ 不丢不重 → 算法边界 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/5c64e6304790489e8e1f5001f547b649) |
| J11 | 美团 Java 后端一二面，面试 2025-09-05/09，意向 | 线上/离线 → JVM Full GC → ACID → AI Coding → Redisson 锁 → 数据迁移/缓存一致性 → 算法 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/3e8bab1e3bcf415684c3087596c8293f) |
| J12 | 虾皮 Java 后端一面，帖子显示 2025-11-22 | 实习项目未问；JDK、慢查询、ALTER/写盘、HashMap、TCP/完整链路、进程线程、AI 资料辨伪、算法 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/5923c86bf0554c88a7c1dc08b7befad3) |
| J13 | 快手商业化 Java 实习一二面，面试 2025-12-17/22 | 项目全流程 → Redis 与 MySQL 扣减失败 → 限流/建表/B+ 树 → 算法 → 800ms 到 150ms 指标真实性和瓶颈分布 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/97334752983c47c3b965f317ed9400cb) |
| J14 | 杭州数美 Java 后端实习一面，帖子显示 2026-03-02 | 分布式任务为什么需要 → Redis TopN → 微服务为什么这样拆 → 交易成功但扣库存失败怎么办 | [牛客原帖](https://www.nowcoder.com/discuss/858127377057001472) |
| J15 | 携程 Java 后端暑期一二面+AI 面，面试时间线 2026-04，OC | OOM/索引/线程池/HashMap/AI Skill → Redis 过载/事务失效/虚拟线程 → MQ vs 线程池、持久化、G1、Agent 记忆 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/308bb22d95d7462db6ba9273c15e69f7) |
| J16 | 滴滴 Java 一二面，面试 2026-04-16，后 OC | HashSet 去重与线程安全 → 锁/可见性/MySQL → 项目极端情况 → Full GC、线上排查、SQL 和算法 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/942e4dcc8da349f38ff7aa5c7ec16f0a) |
| J17 | 腾讯云智一面，帖子显示 2026-04-27 | 优惠券高并发全链路 → JWT 单/双 Token 与刷新权衡 → 万 QPS 防超发/DB 保护 → 幂等 → 慢查询/唯一索引 → AI 助手 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/c0ad742278d54e41ade1df77b2f0190c) |
| J18 | 字节全栈研发一面，发帖 2026-05-27 | 线程池 → Redis 持久化/缓存问题 → RabbitMQ 顺序/幂等 → ACID/日志/MVCC → AI/Hermes → RPC 计数审计设计 → 算法/思维题 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/6eb630c836144821abccb145eebd2856) |
| J19 | 字节 AI 全栈研发二面，发帖 2026-06-03 | DDD 划域/聚合根/收益缺点 → HashMap/线程池/Executors → B+ 树/覆盖索引 → Redis → 单例 Bean 线程安全 → AI → 会议室算法 | [牛客原帖](https://www.nowcoder.com/feed/main/detail/6ae6a0ac5a9f461ba941a439d4e24d96) |
| J20 | 快手 Java 后端一面，发帖 2026-06-26 | 算法 → MVCC vs 锁/隔离/RR 幻读 → B+ 树、索引失效、性别字段索引/全扫 → 慢 SQL → RabbitMQ → 循环依赖/DI | [牛客原帖](https://www.nowcoder.com/discuss/899984099421474816) |
| J21 | 百度 Java 后端实习一面，帖子显示 2026-07-10 | AQS/锁 → 类加载/内存/GC → Redis/缓存问题 → 设计模式 → Canal/ES 一致性与 Explain → Milvus/RAG Rerank → 算法 | [牛客原帖](https://www.nowcoder.com/discuss/905123127074582528) |
| J22 | 得物后端一面，帖子显示 2026-09-01 | 分布式 ID/RPC 选型 → 内存泄漏/日志 → 支付/接口幂等 → 百万导入导出 → 分布式事务/掉单 → 线程池项目用法 | [牛客原帖](https://www.nowcoder.com/discuss/926058684231225344) |
| J23 | 腾讯金融科技 Java 二面，发帖 2025-04-17 | Spring Boot/Tomcat/MyBatis → HashMap/代理 → HTTP → Redis 跳表和缓存边界 → 项目最大难点 | [力扣中国原帖](https://leetcode.cn/discuss/post/OHlVef/) |
| J24 | 得物 Java 一面，发帖 2026-09-03 | 项目与数据权限 → MySQL 锁/索引 → Redis → 线程池 → MQ 同步/异步取舍 | [牛客原帖](https://www.nowcoder.com/discuss/925044292312117248) |

## 3. 从样本里能得出的方向性结论

### 3.1 不是概率，而是稳定层级

| 层级 | 在样本中的表现 | 对你的含义 |
|---|---|---|
| 广泛反复 | 项目/实习深挖、MySQL、方案取舍和异常边界 | 每周都要产出一条可验证项目故事；MySQL 是 MedFlow 第一技术主线 |
| 高频 | Java 集合与并发、Redis、算法/SQL 手写 | 不能等项目做完再背；每天固定训练 |
| 常见但岗位波动较大 | Spring、JVM、MQ、网络/Linux、线上排障 | 做到能答主干并和项目连接；写进简历的部分升级为最高优先级 |
| 新增通用问法 | 平时如何用 AI、如何验证 AI 代码、怎么看 AI Coding | 所有 Java 候选人都应准备，但不等同于 Agent 专项面试 |
| 岗位/简历触发 | DDD、微服务、分布式事务、Redis Cluster、MCP、多 Agent、RAG 细节 | 没写在简历且项目不依赖时只建地图；不要为了“可能考”全做一遍 |

### 3.2 用一份 43 条记录的复盘交叉校准

一位候选人称自己共经历 45 场面试，但频次复盘按 43 篇/43 条面试记录整理，原文没有解释两条差额；因此下列次数的分母只能按 43 理解。其自报次数包括：线程池 14 场、索引/B+ 树 8 场、`synchronized` 7 场、MQ 选型 7 场、CAS/ConcurrentHashMap 6 场、事务/MVCC 6 场、Redis 分布式锁 6 场、限流 6 场、缓存一致性 5 场、ThreadLocal 5 场、`volatile` 5 场、G1/CMS 5 场。它与上面的独立帖子方向一致，但仍只是一个人的简历与求职样本，不能换算成全行业命中率。[原复盘](https://www.nowcoder.com/discuss/869996102093701120)

### 3.3 真正需要训练的是“追问链”

面试官很少满足于定义，常见链条是：

```text
知识点是什么
  -> 为什么需要
  -> 你的项目为什么选它
  -> 不选另一个方案的代价
  -> 并发/宕机/超时/重复时会怎样
  -> 你如何验证
  -> 数据量或流量扩大十倍先坏哪里
```

所以每个学习单元必须同时留下：代码或 SQL、失败实验、结果数据和两分钟口述。只看视频、只生成代码或只背标准答案都不计完成。

## 4. 三张截图的反查结果

| 截图 | 对应原帖 | 可以确认的结论 |
|---|---|---|
| 线程池、Redis、MQ、MySQL、RPC 计数审计、蚊香 | [字节全栈研发一面](https://www.nowcoder.com/feed/main/detail/6eb630c836144821abccb145eebd2856) | 题序与措辞高度一致；原帖还记录当晚约二面。截图可作为一手面经样本，不是通用概率。 |
| MVCC、低选择性字段索引、RabbitMQ、循环依赖/DI | [快手 Java 后端一面](https://www.nowcoder.com/discuss/899984099421474816) | 题序与截图一致；说明一场面试可以集中覆盖传统八股，并继续追问工程判断。 |
| DDD、HashMap、线程池、索引、Redis、Bean 线程安全、AI、会议室 | [字节 AI 全栈研发二面](https://www.nowcoder.com/feed/main/detail/6ae6a0ac5a9f461ba941a439d4e24d96) | 与截图逐项对应；DDD 是被候选人项目触发，不能据此提升为所有 Java 岗 P0。 |

三条均复用来源台账中的 J18—J20，不新增样本、不重复计数。

## 5. 27 届 Java 后端优先级

优先级同时考虑四件事：面经反复程度、实际开发价值、MedFlow 是否能形成证据、是否会被当前简历触发。

### P0：现在必须掌握并落地

| 主题 | 必须达到的深度 | MedFlow 证据 |
|---|---|---|
| 项目讲述与方案决策 | 说清业务问题、自己的责任、方案比较、失败窗口、验证结果、当前限制 | 每个里程碑一张项目故事卡；数字来自脚本和报告 |
| MySQL 索引与 SQL | B+ 树、联合索引顺序、回表/覆盖、低选择性字段、索引失效、`EXPLAIN`、慢 SQL | 预约列表与排班冲突查询；10 万数据索引前后报告 |
| 事务、MVCC 与锁 | ACID、隔离级别、ReadView/版本链、快照读/当前读、行锁/间隙锁、死锁、事务失效 | 扣号+建预约回滚；条件更新；取消/超时竞争；死锁实验 |
| Java 集合与并发 | HashMap/CHM、`equals/hashCode`、线程池参数/流程/拒绝、`synchronized`、`volatile`、CAS、ThreadLocal | 并发压测驱动器、批量提醒线程池方案、Trace 上下文传播 |
| Redis 核心与缓存 | 数据结构、为什么快、TTL/过期/淘汰、RDB/AOF；穿透/击穿/雪崩、一致性和故障降级 | 热点号源 Cache Aside；数据库仍是事实源；Redis 断开实验 |
| Spring 主干 | IOC/DI、MVC 链路、Bean 单例线程安全、AOP/代理、`@Transactional` 生效与失效、循环依赖主干 | Controller-Service-Repository；事务边界；无状态 Bean |
| 算法与 SQL 手写 | 数组/哈希、链表、滑窗、二分、堆、树、区间、基础 DP；常用查询和分页 | 每天 1 题，15—25 分钟写完并讲复杂度/边界 |

### P1：常问，紧跟 P0 完成

- RabbitMQ：结构、Exchange/Binding/Routing、Confirm、Ack、持久化、重复、顺序、积压、重试、DLX、Outbox；必须能比较“同步、线程池、MQ”。
- JVM 与排障：运行时区域、对象/类加载、GC 主干、G1、OOM、Full GC、CPU 飙高、`jstack/jcmd/jmap` 的基本排查顺序。
- 网络与 Linux：TCP/UDP、三次/四次、HTTP/HTTPS、状态码、一次请求链路、常用日志/端口/进程/资源命令。
- 工程化：Git、Maven、Flyway、Docker、配置隔离、日志/Trace、Actuator、测试层次、基本 CI。
- 系统场景：幂等、限流、批处理、导入导出、接口计数审计、数据迁移、缓存一致性、异步边界。

### P2：按简历或 JD 触发，不占当前主线

- Spring Security 内部 Filter 源码、三级缓存每个细节、AQS 源码逐行、JVM 调参公式；
- DDD 战术模式全套、微服务拆分、Nacos/Sentinel/Dubbo 深层原理；
- Redis Cluster/RedLock、分库分表、分布式事务协议、Raft/ZAB；
- Kafka/RocketMQ 内核、Netty 源码、Kubernetes；
- 写到简历时，该项立刻从 P2 升为“简历 P0”。

### Security 的准确定位

- **通用 Java 频率：P1/P2。** 会话/JWT、401/403、CSRF、认证与授权主干即可。
- **MedFlow 项目：P0。** 你写了 Spring Security + JWT + RBAC，就必须讲清完整请求流、账号状态、Token 失效、对象归属权限和越权失败。
- **学习上限：** 完成可运行闭环和测试后立即进入 MySQL/并发；不继续逐个阅读内置 Filter 源码。

## 6. MedFlow 场景任务重排

### 6.1 每个任务统一六步

```text
真实业务问题
  -> 相关高频八股
  -> 至少两种方案与取舍
  -> 实现核心路径
  -> 注入失败/竞争并收集证据
  -> 用 2—8 分钟面试口述
```

### 6.2 从 2026-09-07 开始的六周冲刺

| 时间 | Java/MedFlow 主任务 | 必须留下的证据 | 对应追问 |
|---|---|---|---|
| 09-07—09-09 过渡闸门 | 收口 Security：BCrypt、用户加载、登录/JWT、统一 401/403、RBAC、对象权限；清理当前 Git 挂账 | 编译；登录成功/失败、过期/伪造 Token、禁用账号、越权用例；一张请求链图 | JWT vs Session、401/403、单例 Bean 安全、为什么构造器注入、权限放哪层 |
| 09-10—09-14 S1 | 最小科室/医生/诊室、排班与 Slot；时间区间冲突、发布事务、必要索引 | 重叠边界表；并发创建同一排班只有一个结果；事务回滚；初版 `EXPLAIN` | 时间边界、唯一约束 vs 业务校验、锁什么、索引顺序为什么 |
| 09-15—09-20 S2 | MySQL 预约正确性：条件扣减、Idempotency-Key、业务唯一约束、状态 CAS、取消/超时竞争 | 扣减后插入失败整体回滚；同键重试；重复取消只归还一次；竞争终态 | ACID、MVCC、当前读、乐观/悲观锁、0 行更新、事务失效 |
| 09-21—09-27 S3 | 并发与 SQL 证据：500 抢 50、死锁复现/规避、10 万数据查询优化；形成简历 V1 | 成功数严格 50；库存不负；死锁日志；索引前后计划与耗时；三条项目故事 | 线程池怎么配、队列满了、慢 SQL 怎么查、低选择性字段是否建索引 |
| 09-28—10-04 S4 | Redis 热点号源查询缓存：Cache Aside、空值/穿透、TTL 抖动、热点击穿保护、写后失效、故障降级 | 命中/未命中/失效；并发回源次数；Redis 断开仍正确；一致性窗口说明 | 为什么不用本地缓存、先更新谁、缓存挂了、热点 Key、RDB/AOF |
| 10-05—10-11 S5 | RabbitMQ 超时关闭 + Outbox：Confirm/Ack、重试/DLX、消费幂等、恢复 | DB 与 Outbox 同事务；重复消息一次效果；Broker/消费者中断恢复；积压处理说明 | 为什么不用线程池、哪里会丢/重、顺序是否必要、同步能不能做 |
| 10-12—10-18 S6 | Docker/Actuator/日志/CI；OOM/CPU/慢 SQL 基础排障；README、演示、模拟面试 | 空环境启动；健康检查；一条故障定位链；五个项目故事和真实数字 | 请求全链路、502/504、JVM/数据库先查什么、流量十倍先坏哪里 |

**投递闸门不是 10 月 18 日。** 从现在开始投递；9 月 27 日只是“项目证据 V1”截止点，后面的 Redis/MQ/部署持续给简历加证据。

### 6.3 十个必须练成的场景

1. 两人抢最后一个号，为什么“先查再减”会超卖？
2. 同一请求超时重试，如何保证只创建一条预约？
3. 取消与超时同时到达，如何只归还一次号源？
4. 某条预约列表 SQL 越来越慢，如何从慢日志到 `EXPLAIN` 再到索引验证？
5. `status` 只有少数值，为什么仍可能出现在联合索引中？什么时候全表扫更便宜？
6. 一次给 5000 人发提醒，线程池如何配置？第三方每秒只允许 20 次怎么办？
7. 热点号源缓存刚过期，如何避免并发回源打垮数据库？Redis 挂了还能不能预约？
8. MQ 已确认但消费者重复执行，如何做到一次业务效果？
9. 预约事务提交了但超时消息没发出去，Outbox 如何恢复？
10. 接口变慢或 CPU/Full GC 异常，从指标、日志、线程、堆、SQL 到外部依赖按什么顺序定位？

## 7. 哪些让 AI 生成，哪些必须自己掌握

### 7.1 三种学习模式

| 模式 | 含义 | 验收方式 |
|---|---|---|
| `G-生成后审查` | AI 可生成样板和机械代码；你负责校验依赖版本、边界、异常、测试与安全 | 能指出关键入口/出口，运行通过，能解释为何接受或修改 |
| `D-亲自决策并实现核心` | AI 可讨论和 review，但核心规则、SQL、参数与方案由你决定并手打/改写 | 白板或空文件能写核心片段；失败实验可复现；能比较替代方案 |
| `R-理解与口述` | 不做源码考古，只建立机制图和排障入口 | 90 秒主干回答 + 2 个追问 + 知道去哪里查 |

### 7.2 具体边界

| 内容 | 模式 | 不能跳过的理解 |
|---|---|---|
| DTO、VO、普通 Controller、字段映射、OpenAPI 注解、基础 CRUD | G | 分层责任、校验位置、异常流、不能暴露的字段 |
| Security 配置、JWT Filter/入口点样板、Docker/CI 配置 | G + R | 请求链、认证/授权、401/403、Token 风险、环境差异；必须跑负例 |
| 数据表、唯一约束、联合索引、核心查询 SQL | D | 规则为何放数据库、索引顺序、回表、执行计划、失败时结果 |
| `@Transactional` 服务、条件更新、状态 CAS、幂等 | D | 事务边界、代理失效、并发交错、影响行数和回滚 |
| 线程池参数、队列与拒绝策略 | D | 任务性质、外部容量、内存上限、背压、监控和关闭 |
| Redis 缓存与一致性 | D | 数据事实源、失效时序、并发回源、故障降级 |
| RabbitMQ/Outbox | D | 每个丢失/重复窗口、Confirm 与 Ack 边界、恢复和幂等 |
| 测试样板、数据工厂、压测脚本骨架 | G + D | 场景和断言必须自己定；不能让 AI 自己证明自己生成的逻辑正确 |
| 算法、SQL 手写题 | D | 不依赖补全，能讲复杂度、边界和替代解法 |
| Spring/AQS/Redis/JVM 源码 | R | 只读与项目/排障直接相关的调用段；不做逐行背诵 |

AI 生成代码后的固定检查：

1. 它改了哪些业务规则、事务边界、锁范围或权限边界？
2. 正常、异常、边界、并发、重试、宕机各会怎样？
3. 是否引用了当前项目不存在的表、字段、Bean 或依赖版本？
4. 是否把本地 Demo 结果包装成生产 QPS？
5. 是否有越权、日志泄密、无限队列、无限重试、吞异常或伪幂等？
6. 证据是否由独立测试、SQL 结果和日志产生，而不是 AI 的文字结论？

## 8. Agent/AI 应用岗位调研

### 8.1 十个定向岗位的共同交集

> 这是用 Java+Agent 关键词刻意筛选的便利样本，不能代表全部 Agent 岗位。只有 JD 明写才计入；“严格 Java 后端基础”要求同时出现 Java 与 Spring/API/数据库/缓存/MQ/JVM/并发/Web 后端等信号。它能证明这条赛道真实存在、并帮助划定交集，不能用来推断整个市场的比例。

| 观察 | 样本表现 | 路线判断 |
|---|---|---|
| Java 可作为实现语言 | 9/10 | 确实存在 Java + Agent 应用/平台赛道，但这个比例受检索关键词影响 |
| 严格 Java 后端基础 | 6/10 | Java、数据库、缓存、API、异步、测试与稳定性不能降级 |
| Agent 编排/记忆/Workflow | 10/10 | Agent 构建或业务落地是核心职责，不是简历装饰 |
| RAG/Embedding/向量检索 | 9/10 | 学完整链路和评测；不只会调用向量库 |
| Tool/Function Calling | 8/10 | 要处理 Schema、权限、超时、错误、幂等和人工确认 |
| 评测/观测/安全至少一项 | 8/10 | Eval、Trace 与安全边界是项目可信度核心，不是收尾装饰 |
| MCP | 6/10 | 会做 client/server 与权限边界；不是先背协议源码 |
| 训练/微调是职责或硬要求 | 2/10 | 它集中在偏算法岗位，不是当前应用后端 P0 |

### 8.2 岗位来源

| 岗位 | 关键信号 | 来源 |
|---|---|---|
| 百度 AI 开放平台研发实习 J103363，2026-07-21 | Java/JVM/并发/Spring/MySQL/Redis/MQ 是底座；模型路由、上下文、流式、限流缓存、鉴权配额、日志监控；Agent/RAG/FC/MCP 加分 | [百度官方](https://talent.baidu.com/jobs/detail/INTERN/ef30e579-2d3d-4539-ad90-884521b815c9) |
| 百度 Agent 工程师（J100994），日常实习，2026-07-21 | Agent 实现与效果评估、Context Engineering、单/多 Agent 框架模块及工具；接受 Java，RAG/Memory/Skill/MCP 为经验项 | [百度官方](https://talent.baidu.com/jobs/detail/INTERN/3ddcb5a1-63d7-4596-b7cf-d636dad39f60) |
| 百度大模型应用/agent 算法工程师（J101345），日常实习，2026-07-21，硕士及以上 | RAG、工具与 Agent 策略，同时强调模型训练/微调；用于识别算法型岗位边界，不属于当前主攻线 | [百度官方](https://talent.baidu.com/jobs/detail/INTERN/1a0bfe96-f59c-4384-9525-79fdf324c67f) |
| 京东软件开发（AI 应用），2026-07-14，3 年+ | 作为生产级目标：Agent/Copilot/Workflow、RAG、工具权限和兜底、离线/回归/线上评测、Trace/延迟；仍要求 DB/缓存/异步/容器 | [京东官方](https://zhaopin.jd.com/web/job-info-detail?requementId=220736) |
| 携程 27 届 Agent 开发 | Python/Java、Agent 框架、记忆、评测、观测、注册/管理/发现，上线经历加分 | [牛客企业岗位](https://www.nowcoder.com/jobs/detail/463573) |
| 华为 27 届 AI Agent 开发 | Java/Python/C++、应用集成、RAG、效果评估；同时含模型微调要求，说明同名岗位也可能偏算法 | [牛客企业岗位](https://www.nowcoder.com/jobs/detail/460486) |
| 得物 27 届中间件 AI 开发（Java） | Java/JVM/并发和缓存/RPC/MQ/高可用，与 LLM Gateway/Agent/MCP/RAG 结合 | [职位聚合页，需回官网复核](https://www.shushuqiuzhi.com/position/463743) |
| 合合信息 27 届 Agent 开发 | RAG、Context、FC/MCP、工具/记忆/多轮；明确评测、调试、任务成功率、延迟、成本、安全 | [职位聚合页，需回官网复核](https://www.shushuqiuzhi.com/position/435773) |
| 海鼎 Java-AI 校招 | Java/Spring Cloud/MySQL/SQL 优化仍是底；RAG/向量库、MCP/A2A/AG-UI | [牛客企业岗位](https://www.nowcoder.com/jobs/detail/430329) |
| 医疗 AI 应用/RAG 工程师，2026-05-11 | Java/Python/Spring Boot；完整 RAG、混检/Rerank/评估、结构化输出、FC/MCP、观测 | [天津工业大学就业网](https://jobs.tiangong.edu.cn/correcruit/content/id/54911.html) |

补充 2027 届信号：

- 百度 AIDU 的 Agent 应用全栈岗明确列出 Planning/Acting/Reflection、Tool/API、记忆、状态、多 Agent、RAG 和成功率/成本/延迟评测：[百度官方校招](https://talent.baidu.com/jobs/detail/GRADUATE/6f9c3a86-6557-409d-8fa7-e6f4c68d6765)。
- OPPO 的 AI Agent 研发岗要求 MCP/CLI 工具链、上下文、任务编排、回归评测和 Trace，并接受 Java：[OPPO 官方](https://careers.oppo.com/university/oppo/campus/post/1850)。
- 拼多多 27 届 AI Agent 岗把高并发后端、缓存/MQ/异步、工具权限、沙箱、全链路评测与 Agent/RAG 放在同一职责中：[南开就业网转发](https://career.nankai.edu.cn/correcruit/content/id/116275.html)。

### 8.3 同名岗位的三种分叉

1. **应用/平台工程型（当前主攻）**：职责动词通常是接入、封装、编排、上线、治理和评测；Java/Spring、API、数据库、缓存/MQ、鉴权限额、RAG、Tool 和可观测性同时出现。
2. **应用算法混合型（选择性投递）**：允许 Java/Python，重点是 RAG、Context/Memory、Agent 框架和效果评测；是否深问后端取决于部门。
3. **模型/策略算法型（当前不主攻）**：职责动词通常是训练、微调和优化模型，常见 Python/PyTorch、Transformer、SFT/DPO/GRPO/RLHF 及硕士/论文要求。

不能只凭“AI Agent 开发”这个标题决定学习范围。投递前要看职责是在“封装/接入/上线”，还是“训练/优化模型”。

### 8.4 Agent 应用学习优先级

#### A-P0：必须实现并能解释

1. LLM API：同步/流式 SSE、结构化输出/JSON Schema、错误分类、超时、重试/退避、限流、熔断、模型路由、Token 与成本。
2. RAG：解析 → 切片 → Embedding → 向量+关键词混合检索 → Rerank → 引用/无答案；元数据权限、更新删除和固定评测集。
3. Tool Calling：模型只提出调用，应用执行；Schema/参数校验、白名单、SecurityContext、幂等、超时、最大步数、人工确认和失败降级。
4. Workflow/Agent：能比较 Chatbot、固定 Workflow 与 Agent；先用确定性 Router/状态机，再学 ReAct/Plan-and-Execute、终止条件与上下文压缩。
5. Eval/Observability：检索 Recall@k、引用/groundedness、工具选择与参数准确率、任务成功率、P95、Token/成本、Bad Case 分类和 Trace。
6. 安全：Prompt Injection、检索污染、敏感信息、越权工具、输出校验、最小权限和高风险操作确认。

#### A-P1：做一个可运行样例并能比较

- MCP client/server：理解它是工具/资源互操作协议，Function Calling 是模型侧调用机制；先暴露只读工具。
- Spring AI 选一个版本深入；LangGraph、Dify、LangChain4j 只需看懂概念和比较，不同时铺开。
- Python 补到能读生态代码、写 FastAPI/async/pytest 小服务，不转移 Java 主栈。
- 短期记忆、持久会话、流式 UI、模型降级和小规模异步任务。

#### A-P2：按 JD 再学

- SFT、LoRA、DPO/GRPO/RLHF、Transformer 数学推导；
- vLLM/CUDA/KV Cache 深层优化与训练推理框架；
- 多 Agent、A2A/AG-UI、GraphRAG/知识图谱；
- 自研向量索引或 Spring AI/LangChain 源码级研究。

## 9. MedFlow Agent 并行项目

### 9.1 产品边界

名称暂定：**MedFlow 预约规则与号源查询助手**。

- 不做诊断、用药、科室推荐或真实患者数据；只用虚构预约规则和测试数据。
- Java 可靠预约仍是主系统；Agent 是隔离扩展，不进入预约事务核心。
- 初版只读：查询预约规则、可用号源和本人预约状态。
- 预约/取消只能生成草稿；用户明确确认后，由原有 REST/Service 的幂等事务执行。
- 用户 ID 从服务器 `SecurityContext` 取得，模型和客户端不得传 `ownerId`，工具不得直接访问数据库。

### 9.2 四阶段任务

| 阶段 | 任务 | 验收 |
|---|---|---|
| A0 基础调用 | Spring AI `ChatClient` 或等价 API；流式输出、结构化结果、超时/重试、Token 记录 | 正常、限流、超时、非法 JSON、模型不可用有确定结果 |
| A1 规则 RAG | 虚构预约/取消/排班规则；切片、混合检索、Rerank、引用和无答案 | 30—40 条固定集；检索与回答分开评分；错误回答可定位到检索/生成 |
| A2 只读工具 | `searchAvailableSlots`、`getMyAppointmentStatus`；确定性 Router；最多 4—6 步 | 工具选择、参数、越权、超时、空结果、注入用例；所有越权由代码拦截 |
| A3 可靠性与作品化 | Trace、P95、Token/成本、Bad Case；写操作草稿+显式确认；可选 MCP 适配 | 40—60 条回归集；未确认写操作 100% 被确定性代码拒绝；演示和报告可复现 |

Agent 线每天 45 分钟，不能阻塞 MedFlow S1—S3。没有稳定的业务 API 时先用 Stub；接口完成后再接真实 Service，不提前改核心预约模型。

### 9.3 Agent 面试故事

最终至少能回答：

1. 为什么这个需求用 Workflow，而不是直接上自主 Agent 或多 Agent？
2. 模型如何选择工具、何时结束、结果太大怎么办、失败重试放工具内还是 Agent 外？
3. RAG 为什么需要混检/Rerank？准确率如何测？更新、删除、权限过滤怎么办？
4. 为什么模型不能直接传用户 ID、直接写 DB 或自主提交预约？
5. 如何限制最大步数、延迟、Token 和成本？
6. 如何区分检索错误、模型幻觉、工具错误和业务失败？
7. AI 生成的代码如何验证，哪些事务/并发/权限代码不会直接接受？

## 10. 每天四小时的固定节奏

```text
110 分钟  MedFlow 当前场景：设计、核心实现、失败验证
45 分钟   当天关联八股：定义 -> 机制 -> 取舍 -> 项目追问
30 分钟   算法或 SQL 手写：计时、复杂度、边界
45 分钟   Agent 并行线：一个可运行增量或一个评测增量
10 分钟   证据日志：结果、失败、数字、明日唯一动作
```

每周必须交付：

- 一段能运行的业务闭环；
- 一个正常用例、一个边界用例、一个失败或并发用例；
- 一份 SQL/日志/压测/评测证据；
- 一张“为什么这样选”的对比表；
- 一次 8 分钟项目拷打和一次 45 分钟综合模拟；
- 一次简历核查：未验证能力不写，真实数字不夸大。

## 11. 面试达标线

### Java 后端投递基线

- 90 秒介绍 MedFlow，3 分钟画出创建预约链路；
- 随机抽一个 P0 主题，90 秒主干回答后能接两层追问；
- 8 分钟讲清防超卖、幂等、状态竞争、缓存或可靠消息之一；
- 20 分钟完成一道中等算法或 SQL，并主动覆盖边界；
- 面对“流量十倍、Redis/MQ/DB 挂了、重复/超时/越权”能先澄清约束，再给方案和验证；
- 不把 AI 生成、尚未运行或未测的数据包装成自己的生产经验。

### Agent 应用岗投递基线

- 能画出请求、模型、RAG、Tool、业务服务、记忆、Eval/Trace 的边界；
- 有固定回归集和至少四类 Bad Case，而不是只演示一次成功对话；
- 能解释 Workflow vs Agent、Function Calling vs MCP、Chat History vs Memory；
- 能说明权限、确认、幂等、超时、重试、最大步数和成本限制分别放在哪一层；
- 同时接得住 Java、MySQL、Redis、并发、网络和算法的基础题。

## 12. 技术实现的一手校验资料

- [Spring AI 总 API](https://docs.spring.io/spring-ai/reference/api/)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)：模型提出调用，应用执行；包含审批、预算、循环与观测扩展点。
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)：工具参数和结果默认不导出，避免敏感数据泄露。
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [MCP 架构](https://modelcontextprotocol.io/specification/2026-07-28/architecture)
- [MCP Tools 与安全提示](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/resource/owasp-top-10-for-llm-applications-2025/)
- [Anthropic: Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)：固定任务优先简单可组合 Workflow，只有真正需要动态决策时再增加 Agent 自主性。

## 13. 后续更新规则

1. 每两周新增最近面经，先去重再调整等级；不因单场新题重排全路线。
2. 每次改简历后重新执行“简历触发审计”：出现的中间件、框架和指标都要有实现与证据。
3. 每投一个目标岗位，单独读取 JD：通用 P2 若在 JD/简历出现，临时升为该岗位 P0。
4. 新来源必须注明原帖/转载、公司、岗位、轮次及日期类型；不能确认是面试日还是发帖日时写“帖子显示日期”，不把相对日期伪装成精确面试日。
5. 本文不维护动态进度；唯一进度源是 `PROJECT_PROGRESS.md`。
