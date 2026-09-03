FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS runtime-base
RUN apk upgrade --no-cache \
    && apk add --no-cache nodejs=24.18.1-r0 libssl3=3.5.8-r0 libcrypto3=3.5.8-r0
ENV NODE_ENV=production
# Verify the loaded library, not just the installed APK or scanner inventory.
RUN node -e "const a = require('node:assert/strict'); a.equal(process.versions.node, '24.18.1'); a.equal(process.versions.openssl, '3.5.8'); a.equal(process.config.variables.node_shared_openssl, true); const libs = process.report.getReport().sharedObjects; for (const name of ['libssl.so.3', 'libcrypto.so.3']) a.ok(libs.some(p => p.endsWith('/' + name)), name);"
ADD --checksum=sha256:148eacf7863ef4329224a29398623077200a27194aa075569faf4a0a85566ca5 --chmod=0444 https://raw.githubusercontent.com/nodejs/node/v24.18.1/LICENSE /usr/local/share/licenses/node/LICENSE
RUN chmod 0755 /usr/local/share/licenses /usr/local/share/licenses/node

FROM runtime-base AS dependencies
RUN apk add --no-cache npm=11.12.1-r0
WORKDIR /opt/analyzers/typescript-node
COPY analyzers/typescript-node/package*.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund

FROM runtime-base
WORKDIR /opt/analyzers/typescript-node
# npm and its dependency tree are build-only; the analyzer never installs packages.
COPY --from=dependencies /opt/analyzers/typescript-node/node_modules ./node_modules
COPY analyzer-contract/schemas/v1 /opt/analyzer-contract/schemas/v1
COPY analyzers/shared/runtime.mjs /opt/analyzers/shared/runtime.mjs
COPY analyzers/typescript-node /opt/analyzers/typescript-node
RUN chmod 0555 semantic-analyzer.mjs && ln -s /opt/analyzers/typescript-node/semantic-analyzer.mjs /usr/local/bin/semantic-analyzer
USER 65532:65532
ENTRYPOINT ["semantic-analyzer"]
