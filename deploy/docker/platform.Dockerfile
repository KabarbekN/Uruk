# Docker CLI v29.7.2 signed tag resolves to this commit. Rebuild its vendored
# source with patched Go; the upstream binary still embeds vulnerable Go 1.26.5.
FROM golang:1.26.8-alpine3.24@sha256:ce864e7223ac17b1775e6fd0b4c0db580c2eb50e7953a427916379e4b92a1628 AS docker-cli-build
ENV GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off CGO_ENABLED=0 GOMAXPROCS=2 \
    GOFLAGS="-trimpath -buildvcs=false" GO_STRIP=1 GO_BUILDTAGS="osusergo netgo" \
    VERSION=29.7.2 GITCOMMIT=a7dcaa6fdb6ed04aacbfdc76357fdae01605609e BUILDTIME=2026-08-05T18:26:21Z
ADD --checksum=sha256:6e5c91d3a5a79db78cf989d07727d00e757aa0da4d135a3ce4b86061b83fb511 https://codeload.github.com/docker/cli/tar.gz/a7dcaa6fdb6ed04aacbfdc76357fdae01605609e /tmp/docker-cli.tar.gz
WORKDIR /go/src/github.com/docker/cli
RUN --network=none tar -xzf /tmp/docker-cli.tar.gz --strip-components=1 -C . \
    && rm /tmp/docker-cli.tar.gz
RUN --network=none --mount=type=cache,id=semanticmap-docker-cli-go-1.26.8,target=/root/.cache/go-build \
    TARGET=/out ./scripts/build/binary \
    && cp -L /out/docker /usr/local/bin/docker \
    && go version -m /usr/local/bin/docker > /out/docker.buildinfo \
    && grep -F 'go1.26.8' /out/docker.buildinfo \
    && /usr/local/bin/docker --version \
    && sha256sum /usr/local/bin/docker > /out/docker.sha256

FROM scratch AS docker-cli
COPY --from=docker-cli-build /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli-build /out/docker.buildinfo /out/docker.sha256 /usr/local/share/docker-cli/
ENTRYPOINT ["/usr/local/bin/docker"]

FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 AS build
WORKDIR /src
COPY pom.xml ./
COPY analyzer-contract ./analyzer-contract
COPY analyzers/java-spring ./analyzers/java-spring
COPY backend ./backend
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl backend/platform -am package -DskipTests

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
# Temurin used GnuPG to verify its archive; it is not needed by the running platform.
RUN apk del --no-cache gnupg \
    && apk upgrade --no-cache \
    && apk add --no-cache git openssh-client-default \
    && addgroup -g 10001 semantic \
    && adduser -D -u 10001 -G semantic semantic \
    && mkdir /artifacts \
    && chown semantic:semantic /artifacts
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/share/docker-cli /usr/local/share/docker-cli
COPY --from=build /src/backend/platform/target/platform-0.1.0-SNAPSHOT.jar /app/platform.jar
USER 10001:10001
WORKDIR /repository
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/platform.jar"]
