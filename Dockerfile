# syntax=docker/dockerfile:1
# 港口集装箱作业事件湖仓与航次对账服务 —— 一键构建镜像

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
# 使用阿里云 Maven 镜像加速依赖下载（可选，网络畅通时也可删除该行）
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY --from=build /app/target/port-lakehouse-1.0.0.jar app.jar
USER appuser
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
