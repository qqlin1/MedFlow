# 尚医通与MedFlow真实源码对比及取舍

日期：2026-10-21。此文件是对比结论与决策入口，详细逐方法证据在下方三份附录。不是将教案、医院模拟器或其他公开版本当作完整平台源码。

本文记录B01–B04实施前的比较基线。用户随后已授权源码改动，最新变更和未测试状态见[源码改动记录](YYGH_ADOPTION_SOURCE_CHANGELOG.md)；不要把下表“当前缺口”当作改造后已验收结论。

**决策：学习尚医通的患者业务表达——科室日期导航、完整核对页、到院凭证和预约信息快照；保留MedFlow的事务占号、幂等、归属权限、签到接诊和病历账单分离。两边都没有独立复诊关联，复诊需在当前系统另做轻量设计。**

## 1 本次到底比较了什么

| 对象 | 实际使用位置与范围 |
| --- | --- |
| 尚医通后端 | [本地yygh_parent](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent)，183份Java源文件，排除target编译物；重点逐行追踪医院、用户、订单、支付和号源同步 |
| 尚医通患者端 | [本地yygh-site](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/03-尚医通前端代码/patient-source/yygh-site)，内部ZIP已展开，18份Vue页面/组件及API脚本 |
| MedFlow | `E:\code\java\MedFlow`当前Java、MyBatis XML、迁移与原生JS源码 |
| 资料.zip | 配套模型/SQL和医院接口模拟器，用于核对表结构、业务合同；不再用它代替完整平台 |

外层真实源码已解出1300个文件。患者端内部ZIP再展开102个目录/文件条目。管理端模板ZIP仍被防护拦截，本次没有运行或继续放行它，不影响对订单后端和患者流程的逐项分析。

临时处理脚本已经结束并移除；最新检查中杀毒、实时防护、篡改防护均开启，ZIP临时排除已恢复。这里的“已解压”不代表认定所有下载内容安全，本轮没有启动、编译或运行解压程序、依赖、SQL或支付请求。

公开课程仓库另作过交叉核查，但与本地包不同：本地`OrderServiceImpl`的SHA256为`687A5E1944D0B907B46C7774D6DE06662D4A786058A4D0AE312F257E6A737C23`。公开下载文件哈希不同，公开版本有`confirmGetNumber`，本地没有。本报告全部实现判断以本地版本为准。清单见[源码证据](evidence/yygh-study/comparison-source-manifest.json)、[解压结果](evidence/yygh-study/main-source-extraction.json)。

## 2 逐场景对比和明确选择

下表的优劣针对单院、可解释、可手点验收的MedFlow目标。多医院对接是尚医通的范围，不因它模块更多就判定更适合我们。

| 业务场景 | 尚医通这版代码 | MedFlow当前代码 | 优劣与取舍 |
| --- | --- | --- | --- |
| 我知道科室，想找明天有号的医生 | 科室树→日期状态→当日AM/PM医生列表；`ScheduleServiceImpl.getBookingScheduleRule`与`schedule.vue` | 科室→先选医生→日期→具体slot；另有全院找医生 | 尚医通更便于同日比较医生；MedFlow更便于指定医生。采用科室日期优先浏览，同时保留找医生入口 |
| 哪天有号，哪天约满 | 后端按日期聚合余量，补无排班日和放号/停挂状态 | 七天快捷按钮只有日期；点某日后才查该医生余量 | 尚医通信息更充分。借鉴聚合读模型，用MySQL实现；不搬MongoDB，也不凭某一页slot冒充全天余量 |
| 提交前能否核对清楚 | `booking.vue`独立核对就诊人、医生、职称、日期、科室和费用 | 弹窗已有家属选择、脱敏和医生时间副标题，主要摘要仍含号源ID | 借鉴完整确认摘要；保留MedFlow家属归属、脱敏和安全重试。不为复制页面更换前端框架 |
| 成功后到哪里、何时签到 | 订单详情连续展示预约凭证、取号时间/地点和退号截止 | 创建成功和预约详情偏编号；可读医生/诊室/时间及签到在另一“就诊与病历”弹窗 | 尚医通的就医指引更连续。优先统一MedFlow凭证和下一步提示，复用已有visit-info |
| 医生资料从哪里来 | 每条排班带docname/title/skill，适合接收不同医院上传数据 | 独立医生档案、登录账号绑定、公开简介维护、自己的排班与接诊 | MedFlow更适合单院医生完整业务。保留独立doctor身份，不能退回只有排班上的医生文本 |
| 点击提交代表什么 | 直接生成“预约成功待支付”订单 | 创建占位后进入待确认，患者15分钟内自确认，不重复扣容量 | 费用与状态语义不同，不能照搬。MedFlow多一次动作有解释成本，先在页面讲清保留名额/自确认期限，不增加管理员审批 |
| 重复点击与网络重试 | 前端按钮防重；本地`saveOrder`没有幂等键，先插平台订单再HTTP请求医院，无本方法事务/补偿 | owner校验、幂等记录、扣号、预约、日志同事务，条件扣减与唯一约束兜底 | 保留MedFlow。参考代码简单易演示，但网络不明和部分成功时边界不完整；不把按钮禁用当后端幂等 |
| A能否操作B的预约 | 列表Controller设置userId，Service查询却未使用；详情/取消按ID查，无对应owner过滤 | 患者、预约、账单、历史均按JWT owner和具体资源限制 | 保留MedFlow对象权限。登录校验不能代替患者资料归属检查；这是静态源码缺口，不是本轮攻击实测 |
| 取消后是否退一次名额 | 本地取消调用医院后，只在已支付分支更新本地取消状态；未支付可返回true却不更新。消息消费者取消直接余量+1 | 锁号源/预约，状态条件更新，回补和日志同事务；重复取消不再回补；签到后禁止取消 | 保留MedFlow。借鉴退号截止表达，不复制未支付分支遗漏或无去重消息回补 |
| 以后改医生/科室名字，旧预约如何显示 | 平台订单保存医院/科室、职称、患者姓名、日期金额等当时信息 | 处方有价格快照；历史医生/科室/诊室主要实时JOIN当前资料 | 尚医通的单据快照更适合保留历史。后续补最少必要预约快照；旧数据不能伪称已保存历史值 |
| 缴费与退款 | 独立PaymentInfo/RefundInfo；微信统一下单、主动查单、通知医院和退款示例；测试金额固定1分 | 医生提交处方生成药费账单，患者模拟缴费，一账单一模拟支付记录 | 借鉴业务单与支付凭证分离。当前药费不是挂号费；不加真实支付/退款链。不能把支付SDK示例当完整可靠财务系统 |
| 签到到医生完诊 | 本地仅GET_NUMBER枚举与取号指引，未找到赋值/确认取号链、医生接诊、病历、处方模块 | 自签到创建WAITING→医生IN_PROGRESS→病历版本保存→COMPLETED；处方/账单独立 | 这是业务范围差异，不能照搬不存在的后半程。保留MedFlow已实现链路，页面同时呈现接诊与账单状态 |
| 复诊怎样与前次联系 | 可以再次下单，未找到前次就诊关联或复诊模型 | 可以再次预约并看院内历史，也没有专用复诊关联 | 双方共同缺口。自行设计“新预约关联原已完成接诊”，不重开旧病历、不自动占号 |

## 3 四组可以直接读代码验证的差异

以下摘录省略无关参数封装，保留决定业务行为的真实分支；完整位置均可点击。

### A 设置了userId 不等于SQL已经隔离

尚医通[控制器:50](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_order/src/main/java/com/atguigu/yygh/order/api/OrderApiController.java:50)写了：

```java
orderQueryVo.setUserId(AuthContextHolder.getUserId(request));
```

但[selectPage:173](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_order/src/main/java/com/atguigu/yygh/order/service/impl/OrderServiceImpl.java:173)构造条件时只处理医院名、患者ID、状态、日期等，没有加入`user_id`。因此“路由含auth、Controller已经取当前用户”不足以保证列表只属于本人。

MedFlow在[create:159](E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/appointment/service/AppointmentService.java:159)先调用`patientService.getOwnedPatient(currentUserId, command.patientId())`，查询及历史SQL继续绑定owner。**选择：保留MedFlow，学习时必须一直追到SQL。**

### B 取消成功与本地实际改变可能不一致

尚医通[cancelOrder:239](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_order/src/main/java/com/atguigu/yygh/order/service/impl/OrderServiceImpl.java:239)在医院返回成功后：

```java
if (orderInfo.getOrderStatus().intValue() == OrderStatusEnum.PAID.getStatus().intValue()) {
    Boolean isRefund = weixinService.refund(orderId);
    // 退款失败抛异常；以下状态更新和发消息也在这个if内
    orderInfo.setOrderStatus(OrderStatusEnum.CANCLE.getStatus());
    baseMapper.updateById(orderInfo);
    rabbitService.sendMessage(...);
}
return true;
```

未支付订单绕开整个分支，本地不变却返回true。这里不是单纯页面文案问题。

MedFlow[cancel:344](E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/appointment/service/AppointmentService.java:344)依次检查签到、已取消重放、可取消状态，再条件更新、回补一次并记日志，处于同一事务。**选择：借鉴可见的取消截止，保留我们的取消实现。**

### C 一次请求成功与完整业务原子性不同

尚医通[saveOrder:52](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_order/src/main/java/com/atguigu/yygh/order/service/impl/OrderServiceImpl.java:52)在81行先插平台订单，115行请求医院，161行前发送消息。这个本地方法没有事务/补偿；医院成功、后续平台更新或消息失败时，没有统一恢复流程。即使加本地事务，也不能撤回已经成功的医院HTTP操作。

MedFlow[create:151](E:/code/java/MedFlow/src/main/java/com/qqlin/medflow/appointment/service/AppointmentService.java:151)在单个MySQL事务内保存幂等结果、扣号、预约和日志，[SlotMapper:72](E:/code/java/MedFlow/src/main/resources/mapper/scheduling/SlotMapper.xml:72)用`remaining_capacity > 0`条件扣减。**选择：单院继续使用本地事务，避免主动引入跨系统失败面。**

### D 日期页面好不好用 有对应的数据设计

尚医通[getBookingScheduleRule:183](E:/BaiduNetdiskDownload/尚医通/尚医通/解压_真实源码/代码/02-尚医通后端代码/yygh_parent/service/service_hosp/src/main/java/com/atguigu/yygh/hosp/service/impl/ScheduleServiceImpl.java:183)生成日期序列、按天汇总余量、补无排班日；不是单纯给按钮换颜色。其docCount使用记录数，同一医生AM/PM可能算两次，不宜照搬成“医生人数”。

MedFlow[booking.js:129](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:129)只有日期选择与快捷按钮，[250行](E:/code/java/MedFlow/src/main/resources/static/assets/booking.js:250)选定医生/日期才请求slot。**选择：新增MySQL日期摘要读查询；保留当前更细的具体时段、最终提交校验和异步请求防串。**

## 4 这轮形成的可执行产出

1. [业务改造任务与验收](SHANGYITONG_ADOPTION_PLAN.md)：四个有边界的任务，写明页面、接口/数据设计、沿用机制、成功/失败点击验收；全部标注待开发。
2. [预约、容量、权限详细对照](research/yygh/APPOINTMENT_COMPARISON.md)：八项，精确到两侧方法和SQL。
3. [患者页面详细对照](research/yygh/PATIENT_UI_COMPARISON.md)：六组代码表达式、八个业务场景和点击验收。
4. [支付与临床详细对照](research/yygh/BILLING_CLINICAL_COMPARISON.md)：十一项，覆盖费用、取号、身份、完诊和复诊边界。

本轮没有把建议自动改进业务源码，也没有复用尚医通的Nginx、微服务、MongoDB、MQ或支付SDK。上述风险来自静态审阅，未对参考系统发起请求或声称性能/并发实测。

## 5 你后续学习时怎么使用

按具体场景同时打开两侧方法，每次只回答三个问题：它把业务规则放在哪里、失败会留下什么数据、我们为何采用或不采用。

- 第一次：`booking.vue`对`app.js openBooking`，自己画出完整确认单和就诊凭证。
- 第二次：`ScheduleServiceImpl.getBookingScheduleRule`对`booking.js`与slot查询，说明为何日期状态需要聚合接口。
- 第三次：`OrderServiceImpl.saveOrder/cancelOrder`对`AppointmentService.create/cancel`及SQL，复述幂等、锁、回滚和回补。
- 第四次：`OrderInfo/PaymentInfo`对`Encounter/Prescription/Bill`，分清挂号、到院、诊疗和财务四类事实，再解释复诊为什么应另建一次接诊。

每轮用[手动清单](MANUAL_ACCEPTANCE.md)操作当前MedFlow。参考项目没有启动，不要求为了比较业务安装其整套依赖。历史初轮[资料研读](SHANGYITONG_BUSINESS_STUDY.md)只作来源记录，当前选择以本对照和任务单为准。
