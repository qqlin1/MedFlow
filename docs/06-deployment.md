# 启动、保留数据与排错

Windows项目目录：`E:\code\java\MedFlow`。先启动Docker Desktop，再双击项目根目录 `启动MedFlow.cmd`，或执行：

```powershell
.\docker-start.ps1
```

已经构建并交付当前镜像时可用 `.\docker-start.ps1 -NoBuild`。正常入口以脚本输出为准，默认 http://127.0.0.1:8080/ 。前后端一个容器，MySQL和Redis分别一个容器。Compose已绑定本机地址。

演示账号admin/doctor/patient，密码均123456；附加虚构医生账号映射见 [手动验收](MANUAL_ACCEPTANCE.md)。角色切换使用退出登录或独立浏览器会话。

主要环境变量：MEDFLOW_DB_URL/USERNAME/PASSWORD、MEDFLOW_JWT_SECRET、MEDFLOW_REDIS_HOST/PORT/PASSWORD、MEDFLOW_DEMO_ACCOUNTS_ENABLED、MEDFLOW_DEMO_WORKFLOW_ENABLED。`.env`缺失且无旧卷时启动脚本生成随机DB口令和JWT密钥；已有旧数据卷而.env丢失时必须恢复原配置，不能生成新密码后清库。

```powershell
docker compose ps
docker compose logs --tail 100 app
docker compose logs --tail 60 mysql
docker compose exec redis redis-cli ping
```

停止使用 `停止MedFlow.cmd` 或 `docker compose down`。`down -v`会删除持久数据，不用它做普通停止、重启或测试复位。MySQL卷名medflow-mysql-data；Redis为可丢失缓存，不存病历事实。

排错顺序：Docker是否运行→容器health→实际端口→登录/角色→是否有医生档案→排班是否PUBLISHED→日期是否未来及有余号→预约是否BOOKED→签到时间窗口→当前医生是否匹配。空列表不能一律归因于没启动。

升级前备份数据库，保留旧镜像和.env；使用Flyway新版本增量迁移，禁止修改旧脚本。Dockerfile跳过测试，必须先运行对应测试再构建，不把容器healthy当业务全通过。

完整部署说明见 [DOCKER.md](DOCKER.md)。旧版验收和M0均为历史快照，当前构建/迁移/测试结果看最新交付报告和 [业务台账](BUSINESS_PROGRESS.md)。
