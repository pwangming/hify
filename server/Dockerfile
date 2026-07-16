# 多阶段构建（spec 决策 2）：编译也在 Docker 里，镜像产出不依赖宿主机 JDK。
# 阶段1：maven 编译。BuildKit 缓存挂载 /root/.m2，依赖不变时重建只剩编译时间。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 构建专用 Maven 镜像仓库配置。不放 /root/.m2——缓存挂载会在 RUN 期间把它盖住，
# 所以放构建目录并用 -s 显式指定。
COPY maven-settings.xml .
COPY pom.xml .
# 依赖单独成层：pom 不变不重下（多阶段"首次慢"的主要缓解）
RUN --mount=type=cache,target=/root/.m2 mvn -q -s maven-settings.xml dependency:go-offline
COPY src ./src
# 跳过测试：测试属于开发/CI 环节，构建镜像时重复跑只拖慢发布
RUN --mount=type=cache,target=/root/.m2 mvn -q -s maven-settings.xml -DskipTests package

# 阶段2：仅 JRE + jar，镜像小、攻击面小
FROM eclipse-temurin:21-jre
# healthcheck 探 Actuator 用；JRE 镜像无任何 HTTP 客户端
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 非 root 运行（与 sandbox 同一安全姿态）
RUN useradd --system hify
USER hify
COPY --from=build /build/target/*.jar /app.jar
EXPOSE 8080
# JAVA_OPTS 由 compose 注入（如 -Xmx4g），本地 docker run 不传则用 JVM 默认
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
