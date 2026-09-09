FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 AS build
WORKDIR /src
COPY pom.xml ./
COPY analyzer-contract ./analyzer-contract
COPY analyzers/java-spring/pom.xml ./analyzers/java-spring/pom.xml
COPY analyzers/java-spring/src ./analyzers/java-spring/src
# Maven reads every declared reactor module before selecting the analyzer.
COPY backend/platform/pom.xml ./backend/platform/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl analyzers/java-spring -am package -DskipTests

FROM eclipse-temurin:21.0.12_8-jdk-jammy@sha256:ce5767b7222312d42395f5bab033cd91f09e44032a2f21bdfd7b5b912dbe1e77
# Keep the JDK compiler used by OpenRewrite, but remove archive-download/build utilities.
RUN apt-get update \
    && apt-get purge --yes --auto-remove gnupg curl wget binutils \
    && apt-get upgrade --yes --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*
LABEL org.opencontainers.image.title="Semantic Business Map Java/Spring Analyzer" \
      org.opencontainers.image.version="0.1.0"
WORKDIR /app
COPY --from=build /src/analyzers/java-spring/target/analyzer-java-spring.jar /app/analyzer-java-spring.jar
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/analyzer-java-spring.jar"]
