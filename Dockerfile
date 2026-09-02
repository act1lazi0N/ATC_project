# BUILD
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

#RUNTIME
# Use Alpine-based image for significantly fewer vulnerabilities
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -g 10001 appgroup \
    && adduser -u 10001 -G appgroup -s /sbin/nologin -D appuser
USER appuser

COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
