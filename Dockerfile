FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN sh gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar /app/fullcount.jar
ENTRYPOINT ["java", "-jar", "/app/fullcount.jar"]
