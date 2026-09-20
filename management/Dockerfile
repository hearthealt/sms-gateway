# 构建阶段：产出 management/dist/
FROM node:20-alpine AS build

WORKDIR /app

# 先只拷依赖清单再装依赖：源码改动时这一层仍然命中缓存，不必每次重装。
# 用 npm ci 而不是 install：它严格按 package-lock.json 还原，构建可复现。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# VITE_DEVICE_SERVER_URL 是**构建期**变量：Vite 会把 import.meta.env.* 直接内联进产物，
# 所以只能在这里以 build arg 传进来 —— 运行时在容器上设环境变量是没有用的。
# 值由 docker-compose 的 build.args 从 docker/.env 传过来。
ARG VITE_DEVICE_SERVER_URL
ENV VITE_DEVICE_SERVER_URL=${VITE_DEVICE_SERVER_URL}

RUN npm run build


# 运行阶段：nginx 托管静态文件，并把 /api 反代到后端
FROM nginx:alpine

# 覆盖默认站点配置：SPA 回退 + /api 代理 + SSE 不缓冲（见 nginx.conf）
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
