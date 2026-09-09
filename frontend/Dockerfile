FROM node:24.19.0-alpine AS build
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10.27.0 --activate
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml /app/frontend/
WORKDIR /app/frontend
RUN pnpm install --frozen-lockfile
COPY docs/api-contract.yaml /app/docs/api-contract.yaml
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts frontend/playwright.config.ts /app/frontend/
COPY frontend/public /app/frontend/public
COPY frontend/src /app/frontend/src
# Type-check test imports in the build stage; only dist reaches the runtime image.
COPY frontend/e2e /app/frontend/e2e
RUN pnpm build

FROM nginx:1.30.4-alpine3.24-slim@sha256:ddde39c6e51f02fde7410c2e9c234cf2d0a4c7bdbbe176aeb37d8ad7ab4eb58c AS nginx-base

FROM nginx-base AS runtime
RUN apk upgrade --no-cache
# Alpine's nginx package changes the include context; retain the official image configuration.
COPY --from=nginx-base /etc/nginx/nginx.conf /etc/nginx/nginx.conf
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/frontend/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
