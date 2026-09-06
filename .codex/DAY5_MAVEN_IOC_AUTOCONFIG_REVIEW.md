# Day 5 复习卡：Maven、IOC 与自动配置

> 建立日期：2026-09-03
>
> 下次复习：2026-09-07（学习者要求晨间重测收口）
>
> 当前状态：`REVIEW_DUE`（2026-09-06 补讲后重答大体通过；保留两点见第 8 节）

## 1. 必须守住的四条边界

```text
Maven：下载依赖并构建项目。
IOC：管理 Bean 及其依赖关系。
自动配置：根据条件向 IOC 容器补充默认 Bean。
package 只生成 JAR，java -jar 或 spring-boot:run 才启动应用。
```

## 2. Maven 生命周期

默认生命周期的核心阶段：

```text
validate → compile → test → package → verify → install → deploy
```

- 执行某个阶段，会先执行同一生命周期中排在它前面的阶段。
- `mvn package` 只执行到 `package`，不会继续执行 `verify`、`install`、`deploy`。
- `mvn clean package` 先执行独立的 clean 生命周期删除 `target`，再执行 default 生命周期到 `package`。
- `spring-boot:run` 是 Spring Boot Maven 插件的 Goal，不是生命周期阶段。
- Maven Wrapper 固定项目使用的 Maven 版本；MedFlow 当前固定为 Maven 3.9.16。

## 3. IOC 与 DI

- IOC 是思想：对象创建、依赖组装和生命周期管理的控制权交给 Spring 容器。
- DI 是实现手段：Spring 根据 Bean 定义和构造方法参数，把匹配的依赖注入对象。
- `ApplicationContext` 是 Spring IOC 容器，Bean 是由它管理的对象。
- Controller、Service、Repository 等通常交给 Spring；DTO、Entity、VO 不必都成为 Bean。
- 自己 `new` 出来的 Service 没经过 Spring 容器，不能自动获得依赖注入、生命周期管理和 AOP 代理；其 `@Transactional` 通常不会生效。

## 4. Starter 与自动配置

- `spring-boot-starter-web` 是一组 Maven 依赖，把 Spring MVC、Tomcat、Jackson 等类带入 classpath。
- 自动配置不下载依赖。
- 自动配置检查 classpath、配置文件、应用类型和已有 Bean，条件满足时向同一个 IOC 容器注册默认 Bean。
- 常见条件：`@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty`、`@ConditionalOnWebApplication`。
- Starter 负责“把零件带进项目”，自动配置负责“按条件组装零件”。

## 5. MedFlow 的完整构建与启动链

构建阶段：

```text
.\mvnw.cmd clean package
→ Maven 读取 pom.xml
→ 解析依赖
→ 编译主代码和测试代码
→ 运行测试
→ 生成可执行 JAR
```

到这里应用还没有运行。

启动阶段：

```text
java -jar target/medflow-0.0.1-SNAPSHOT.jar
→ JVM 执行 main()
→ SpringApplication.run(...)
→ 创建 ApplicationContext
→ 扫描并注册业务 Bean
→ 解析依赖并注入
→ 自动配置注册 Tomcat、Spring MVC、Jackson 等基础设施 Bean
→ Tomcat 监听 8080，等待客户端请求
```

## 6. 首次口述诊断

已经掌握：

- Maven 默认生命周期的主顺序。
- clean 与 default 是不同生命周期。
- IOC 是控制权交给容器，DI 是依赖注入。

需要复习：

- `package` 到 `package` 为止，不会自动执行后面的阶段，也不会启动应用。
- Starter/Maven 负责依赖进入 classpath；自动配置只负责按条件注册 Bean。
- 手动 `new Service` 会绕开依赖注入、生命周期和 AOP/事务代理。
- 构建链与运行链必须分开讲，服务器监听端口不等于自动打开浏览器窗口。

## 7. 下一次固定复习题

1. `mvn package` 为什么会运行测试？和 `mvn clean package` 有什么区别？
2. IOC 与 DI 分别是什么？手动 `new AppointmentService` 会失去什么？
3. `starter-web` 与自动配置分别负责什么？
4. 从 `.\mvnw.cmd clean package` 到 Tomcat 监听 8080，完整讲出构建链和启动链。

## 8. 2026-09-06 补讲后重答记录

- 已能按序讲出：读 pom 解析依赖 → clean 独立清理 target → default 生命周期 validate→compile→test→package → `SpringApplication.run` 创建容器 → 组件扫描 → 依赖注入 → 自动配置 → Tomcat 监听 8080。顺序无错。
- 保留点一："打包后就可以启动"仍把两条链说成连续动作，没有明确说出断点——package 产物是 target/ 里的静态 JAR，必须由 `java -jar` 或 IDE 显式执行它。
- 保留点二：未把 springdoc 的 Bean 归属到启动链的"自动配置"一步。
- 处理：不标记 `ACCEPTED`；学习者自定 2026-09-07 晨间重测，两条链默写加上两个保留点，通过后本卡闭环。
