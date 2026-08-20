# Build the executable Spring Boot JAR with the repository's Maven Wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw && ./mvnw -B package -DskipTests

# Keep the runtime image small and run the service without root privileges.
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system --gid 10001 spring && useradd --system --uid 10001 --gid spring spring \
    && mkdir -p /app/uploads /app/logs \
    && chown -R spring:spring /app

COPY --from=build --chown=spring:spring /workspace/target/*.jar /app/app.jar

ENV PORT=3000 \
    UPLOAD_DIR=/app/uploads \
    LOG_PATH=/app/logs
EXPOSE 3000
VOLUME ["/app/uploads", "/app/logs"]
USER spring

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
