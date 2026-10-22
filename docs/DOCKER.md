# MedFlow Docker 使用说明

前端、Java后端、数据库迁移打在`medflow-app:local`应用镜像中；MySQL使用`mysql:8.4`。2026-10-20追加`redis:7.4-alpine`医生详情缓存，当前Compose有三个服务，数据库仍保存在`medflow-mysql-data`命名卷；Redis可丢弃，不挂数据卷，不映射宿主机端口。日常运行只需Docker Desktop。

2026-10-21更新：预约与院内档案版本使用Flyway V14，保留原MySQL卷和`.env`。新增医生资料维护、科室/找医生预约入口、跨医生已完成病历及预约日期摘要，见[最新交付](APPOINTMENT_CHART_DELIVERY.md)和[手动验收](MANUAL_ACCEPTANCE.md)。三个容器的最终运行检查见[测试报告](05-test-report.md)。

## 下次启动

1. 启动 Docker Desktop，等引擎就绪。
2. 到项目目录，双击 **启动MedFlow.cmd**。
3. 脚本直接使用现有镜像，检查数据库、三个账号及角色业务接口，成功后自动打开网页。

默认地址：[http://127.0.0.1:8080/](http://127.0.0.1:8080/)。浏览器未自动打开时可手动访问。项目目录路径可以有中文；启动入口使用 Windows PowerShell 5.1。

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `123456` |
| 医生 | `doctor` | `123456` |
| 患者 | `patient` | `123456` |

登录页可一键填入三个账号。doctor 已绑定医生档案；演示模式为patient补齐虚构就诊人、预约、病历与处方样例。管理员仍可创建资源并发布自己的排班，医生查看被分配的本人安排，无需报名。患者按科室→医生资料→日期/时段→就诊人提交并自行确认；到实际签到窗口自行签到。

数据按数据库当天补齐未来7天号源和可操作样例，保留用户已完成、取消、过号、缴费的结果。另有内科/外科/皮肤科虚构医生目录及同一就诊人的两次跨医生历史。固定ID和过去日期只属于各次验收快照；当前请按[三角色指南](DEMO_DATA_GUIDE.md)和[手点清单](MANUAL_ACCEPTANCE.md)操作。午夜到首场开诊前不伪造未来签到，管理员可稍后补齐。

## 停止与更新

双击 **停止MedFlow.cmd** 会停止并移除本项目的Compose容器，保留镜像、数据库和 `.env`。下次启动会复用原来的数据。

终端也可在项目根目录执行：

```powershell
# 日常启动，使用已有镜像
.\docker-start.ps1 -NoBuild

# 停止，保留数据库
.\docker-start.ps1 -Down

# 修改后端或前端代码后重新构建并启动
.\docker-start.ps1

# 查看状态与日志
docker compose ps
docker compose logs --tail 80 app
```

`-Reset` / `docker compose down -v` 会删除数据库，不用于日常停止。本次交付和停止重启验收均没有使用该操作。

## 配置与账号初始化

保留项目根目录 `.env`，其中数据库密码和 JWT 密钥由启动脚本生成，不是应用账号的 123456。`.env` 不进入 Git 或 Docker 构建上下文。已有数据卷而 `.env` 丢失时，脚本会要求恢复原配置，防止生成错误的新数据库密码。

默认端口是应用 8080、MySQL 3307，两者只绑定本机 `127.0.0.1`。可在 `.env` 修改 `MEDFLOW_APP_PORT`、`MEDFLOW_MYSQL_PORT`；脚本按容器实际端口打开浏览器。端口被占用时不会替你关闭其他程序。

Docker Compose 默认传入 `MEDFLOW_DEMO_ACCOUNTS_ENABLED=true`；应用在 Flyway 完成后，用同一事务检查三个账号。仅账号密码、角色或状态需要纠正时才写入并失效旧 JWT；重复启动保持账号 ID、hash 和 tokenVersion。医生档案不会重复创建。密码使用 BCrypt 存储，普通新医生开户的至少八位规则仍然保留。

本模式会在每次启动时把这三个指定演示账号恢复为表中的凭证及角色。普通本地 Java 启动默认关闭；如不需要演示初始化，在 `.env` 写 `MEDFLOW_DEMO_ACCOUNTS_ENABLED=false`，再运行启动脚本重建容器配置。关闭后不会删除已有账号，也不会撤销已经调整的密码。

## 验收与迁移边界

Docker 验收记录见 [DOCKER_ACCEPTANCE.md](DOCKER_ACCEPTANCE.md)。可以再次运行只读检查：

```powershell
.\tools\test-docker-smoke.ps1
```

它验证前端静态资源、三个账号登录、角色、本人业务访问、越权拒绝和匿名拒绝，不输出 JWT。

当前交付是本机 Docker 镜像、Compose、启动入口和持久化数据库。迁移到另一台电脑时，需要复制源项目和配置并重新构建，或自行用 `docker save` / `docker load` 搬运两个镜像；业务数据需另外备份恢复。数据库不在应用镜像里，未上传镜像到公开仓库。
