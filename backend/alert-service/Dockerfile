# Multi-stage: build with Maven, run on a slim JRE as a non-root user.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S autoops && adduser -S autoops -G autoops
USER autoops
WORKDIR /app
COPY --from=build /build/target/alert-service-*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8091
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
