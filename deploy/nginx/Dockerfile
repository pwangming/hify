# 多阶段：node 构建 Vue 产物 → 拷进 nginx。构建上下文必须是仓库根：
#   docker build -f deploy/nginx/Dockerfile .
# alpine 变体（对 deployment.md nginx:stable 的微调，spec 决策 4）：自带 busybox wget
# 可做 healthcheck（debian 版无任何 HTTP 工具），体积小一个量级，nginx 本体相同。
FROM node:24-slim AS build
# corepack 按 web/package.json 的 packageManager 字段锁定 pnpm 版本（与宿主机一致）
RUN corepack enable
WORKDIR /build
# pnpm-workspace.yaml 带 allowBuilds 白名单（esbuild 等 postinstall），缺了 pnpm11 会报
# ERR_PNPM_IGNORED_BUILDS 退出非零；.npmrc 带 engine-strict，都须在 install 前就位
COPY web/package.json web/pnpm-lock.yaml web/.npmrc web/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY web/ .
# vue-tsc 类型检查 + vite build，产物 → /build/dist（.env.production 的同源相对路径生效）
RUN pnpm build

FROM nginx:stable-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
