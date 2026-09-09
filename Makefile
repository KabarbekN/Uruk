.PHONY: dev test analyzers e2e index performance
ARTIFACT_HOST_PATH := $(CURDIR)/.runtime/artifacts
export ARTIFACT_HOST_PATH
dev:
	mkdir -p "$(ARTIFACT_HOST_PATH)"
	docker compose up -d --build
test:
	./mvnw -B -ntp spotless:check verify
	cd frontend && pnpm lint && pnpm test && pnpm build
analyzers:
	docker build -t semanticmap/java-spring:0.1.0 -f analyzers/java-spring/Dockerfile .
	docker build -t semanticmap/tree-sitter:0.1.0 -f analyzers/tree-sitter/Dockerfile .
	docker build -t semanticmap/postgresql:0.1.0 -f analyzers/postgresql/Dockerfile .
	docker build -t semanticmap/typescript-node:0.1.0 -f analyzers/typescript-node/Dockerfile .
e2e:
	node scripts/e2e.mjs
index:
	node scripts/index-project.mjs
performance:
	cd frontend && pnpm build && PLAYWRIGHT_PRODUCTION=true pnpm exec playwright test e2e/large-graph.spec.ts e2e/renderer-stress.spec.ts e2e/progressive-graph.spec.ts
