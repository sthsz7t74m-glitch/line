FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -Dmaven.test.skip=true dependency:go-offline
COPY src src
RUN sh -c 'mvn -B -Dmaven.test.skip=true package > /tmp/maven-build.log 2>&1 || { echo "===== COMPILATION ERRORS ====="; grep -E "\[ERROR\]|COMPILATION ERROR|cannot find symbol|incompatible types|not applicable|does not exist|is already defined" /tmp/maven-build.log | tail -n 80; echo "===== END COMPILATION ERRORS ====="; exit 1; }'

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/my-line-assistant-0.2.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]