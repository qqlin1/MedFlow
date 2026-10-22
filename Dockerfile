# ---------------------------------------------------------------------------
# MedFlow 镜像：前后端同一个 jar（前端在 src/main/resources/static，由 Spring Boot 直接托管）
# 构建：docker build -t medflow-app:local .
# ---------------------------------------------------------------------------

# ---------- 阶段一：编译打包 ----------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# 先单独拷 pom.xml 预热依赖层：只改源码时这一层能命中缓存
COPY pom.xml ./
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src

# 镜像构建跳过测试编译/执行；单元与隔离数据库测试在构建镜像前单独验收
# wagon 重试参数用于抵消 Maven Central 偶发的连接中断
RUN mvn -B -ntp -Dmaven.test.skip=true \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.httpconnectionManager.ttlSeconds=60 \
        package

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:25-jre

# curl 仅用于容器健康检查；装完立刻清缓存
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 不用 root 跑应用
RUN groupadd --system medflow \
    && useradd --system --gid medflow --create-home --home-dir /app medflow

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
RUN chown -R medflow:medflow /app

USER medflow
EXPOSE 8080

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

# 探活直接请求首页：既证明应用起来了，也证明前端静态资源被正确托管
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/ >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
