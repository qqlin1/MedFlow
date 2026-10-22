# Docker 交付验收：2026-10-19

> 本文保留旧版快照。2026-10-20含Redis与整套演示数据的新镜像已部署，当前证据见[系统数据交付](SYSTEM_DEMO_DELIVERY.md)。

已在当前 Windows 主机的 Docker Desktop Linux 引擎真实构建、启动并停止重启。应用容器包含 Java 后端、静态前端及 Flyway V1–V10；MySQL 8.4 使用原 `medflow-mysql-data` 命名卷。未清空原数据库，也未操作 OA 项目的容器。

| 项目 | 实际结果 |
| --- | --- |
| 应用镜像 | `medflow-app:local`，Linux amd64，构建成功 |
| 镜像 ID | `sha256:8276a7a851df5fedf844169c0e7ad9d705dd7779b4ddc7d76640dee6fc0ec13f` |
| MySQL 镜像 | `mysql:8.4`，Linux amd64 |
| 运行状态 | `medflow-app`、`medflow-mysql` 均 healthy；交付时保留运行 |
| 本机访问 | 应用 `127.0.0.1:8080`，MySQL `127.0.0.1:3307` |
| 管理员 | `admin / 123456`，JWT ADMIN；管理员接口成功，医生接口 403 |
| 医生 | `doctor / 123456`，JWT DOCTOR；本人排班接口成功，管理员接口 403；绑定启用医生档案 |
| 患者 | `patient / 123456`，JWT PATIENT；患者目录成功，医生接口 403 |
| 前端静态资源 | 首页、app.js、clinical.js、clinical.css 均 200 |
| 启动入口 | 真实 Windows PowerShell 5.1 执行 `docker-start.ps1 -NoBuild` 成功，含三角色业务检查 |
| 停止重启 | 执行 `-Down` 后再 `-NoBuild`；重新登录和权限检查通过，三账号 ID/角色/tokenVersion 保持一致 |
| 浏览器 | Docker 地址显示三个快捷账号；点击 doctor 后实际进入医生工作台，真实空数据正常显示 |

## 测试结果

- 普通后端测试：195 项通过，0 失败/错误。[本地输出](../target/docker-delivery-unit.log)
- 演示初始化真实 MySQL：5 项通过，验证 BCrypt、角色、重复初始化、权限纠正后 tokenVersion 变化、医生绑定修复。[本地输出](../target/docker-demo-mysql-it.log)
- 前端源码行为测试：108 项通过。[本地输出](../target/docker-delivery-frontend.log)
- 实际 Docker HTTP：19 条检查通过；停止重启后再运行同一套 19 条均通过。[脚本](../tools/test-docker-smoke.ps1)、[输出](../target/docker-smoke.log)、[不含令牌的结果](../target/docker-smoke.json)

[镜像构建日志](../target/docker-build.log)、[首次启动](../target/docker-start-validation.log)、[Windows PowerShell 启动](../target/docker-windows-start.log)、[停止](../target/docker-stop-validation.log)、[重新启动](../target/docker-restart-validation.log) 保存在 target，清理 target 后可按 [使用说明](DOCKER.md) 重跑。

改动现有账号前，已备份原 Docker 数据库：`target/docker-backup/before-demo-accounts-20261019-164915.sql`，22894 字节。备份可能含本地业务信息，未放入镜像或 Git。

## 页面证据

![Docker 三角色登录](images/docker-login.png)

[Docker 医生工作台](images/docker-doctor.png) 使用的是本次创建的 doctor 账号，未给它编造排班、预约和病历数据。

## 交付范围

这是当前主机上的 Docker 镜像、Compose、持久化数据卷和双击启停入口。未推送公共镜像仓库，也未导出离线镜像 tar 包。数据库密码和 JWT 密钥保留在不提交的 `.env` 中；三个 123456 是本机演示应用账号。操作和配置说明见 [DOCKER.md](DOCKER.md)。
