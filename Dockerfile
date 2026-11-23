# One Dockerfile for every service. Build with: docker build --build-arg MODULE=banking-core -t fincore/banking-core .

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace
COPY . .
# -am also builds the common / common-data modules the service depends on
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
ARG MODULE
RUN useradd --system --uid 1001 fincore
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar app.jar
USER fincore
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
