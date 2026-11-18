FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /w
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home app
COPY --from=build /w/target/core-0.1.0.jar /app/core.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/core.jar"]
