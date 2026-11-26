# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
# mvnw verifies the pinned Maven .zip checksum, which needs unzip.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/shopvault-*.jar extract --layers --launcher --destination /extracted

# ---- runtime ----
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 shopvault \
    && useradd --system --uid 10001 --gid shopvault --no-create-home --shell /usr/sbin/nologin shopvault
WORKDIR /app
COPY --from=build --chown=shopvault:shopvault /extracted/dependencies/ ./
COPY --from=build --chown=shopvault:shopvault /extracted/spring-boot-loader/ ./
COPY --from=build --chown=shopvault:shopvault /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=shopvault:shopvault /extracted/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
