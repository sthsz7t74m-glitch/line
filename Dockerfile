FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN sh -c 'mvn -B -DskipTests package > /tmp/maven-build.log 2>&1 || { echo "===== MAVEN BUILD ERROR (last 200 lines) ====="; tail -n 200 /tmp/maven-build.log; exit 1; }'

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/my-line-assistant-0.2.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]