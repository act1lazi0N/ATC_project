# BUILD
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

#RUNTIME
FROM eclipse-temurin:21-jre
WORKDIR /app

# Use a dedicated high UID/GID to avoid colliding with accounts supplied by
# the base image, such as Ubuntu's UID/GID 1000 user.
RUN groupadd --gid 10001 appgroup \
    && useradd --uid 10001 --gid appgroup --no-create-home \
      --shell /usr/sbin/nologin appuser
USER appuser

COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
