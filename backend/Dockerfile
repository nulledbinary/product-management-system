# syntax=docker/dockerfile:1.7
# Multi-stage build for HopePMS Spring Boot API.
# Stage 1 builds with Maven, stage 2 unpacks Spring Boot's layered jar
# so ECS only re-downloads the application layer on each deploy.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package
RUN java -Djarmode=layertools -jar target/hopepms-backend.jar extract --destination target/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hope && adduser -S hope -G hope
WORKDIR /opt/hopepms
COPY --from=build --chown=hope:hope /workspace/target/extracted/dependencies/         ./
COPY --from=build --chown=hope:hope /workspace/target/extracted/spring-boot-loader/   ./
COPY --from=build --chown=hope:hope /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=hope:hope /workspace/target/extracted/application/          ./

USER hope:hope
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=20s --timeout=4s --retries=3 --start-period=30s \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
