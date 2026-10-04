# =============================================================================
# 后端镜像（多阶段构建）—— v0.15
#
# 构建：在**项目根目录**执行
#   docker build -f deploy/docker/backend.Dockerfile -t campus-trade-backend:v0.15 .
#
# 设计要点：
#   ① 先 COPY pom.xml 再 COPY src —— 只要依赖没变，改 Java 代码不会重新下载依赖（层缓存）；
#   ② Maven 使用阿里云镜像（国内构建速度从"分钟级"降到"十几秒"）；
#   ③ 运行阶段用 JRE + 非 root 用户，镜像更小、权限更安全；
#   ④ 健康检查用 busybox 自带的 wget，不需要额外安装 curl。
# =============================================================================

# ---------------------------- 阶段一：编译打包 ----------------------------
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# ① Maven 阿里云镜像：避免从 Maven Central 拉取依赖过慢
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
    '<settings>' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>aliyun</id>' \
    '      <name>aliyun maven</name>' \
    '      <url>https://maven.aliyun.com/repository/public</url>' \
    '      <mirrorOf>central</mirrorOf>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' > /root/.m2/settings.xml

# ② 先只拷 pom，让依赖层可复用
COPY backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

# ③ 再拷源码编译（-DskipTests：镜像构建不跑单测，测试在 CI/本地跑）
COPY backend/src ./src
RUN mvn -B -q -DskipTests package && \
    cp target/*.jar /build/app.jar && \
    java -Djarmode=layertools -jar /build/app.jar list > /dev/null 2>&1 || true

# ---------------------------- 阶段二：运行 ----------------------------
FROM eclipse-temurin:17-jre-alpine

# 时区（日志与业务时间都用东八区）
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 非 root 用户运行（最小权限原则）
RUN addgroup -S campus && adduser -S campus -G campus

WORKDIR /app
RUN mkdir -p /app/uploads /app/logs && chown -R campus:campus /app

COPY --from=builder --chown=campus:campus /build/app.jar /app/app.jar

USER campus
EXPOSE 8080

# 容器可观测性：JVM 内存上限与 OOM 时导出堆快照
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs"
ENV SPRING_PROFILES_ACTIVE=prod

# 健康检查：调用应用自带的存活探针（busybox wget，无需额外安装）
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
