FROM eclipse-temurin:21.0.12_8-jdk-jammy@sha256:ce5767b7222312d42395f5bab033cd91f09e44032a2f21bdfd7b5b912dbe1e77
# Keep the JDK compiler used by OpenRewrite, but remove archive-download/build utilities.
RUN apt-get update \
    && apt-get purge --yes --auto-remove gnupg curl wget binutils \
    && apt-get upgrade --yes --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*
LABEL org.opencontainers.image.title="Semantic Business Map Java/Spring Analyzer" \
      org.opencontainers.image.version="0.1.0"
WORKDIR /app
COPY target/analyzer-java-spring.jar /app/analyzer-java-spring.jar
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/analyzer-java-spring.jar"]
