# 构建阶段：产出 management/dist/
FROM node:20-alpine AS build

WORKDIR /app

# 先只拷依赖清单再装依赖：源码改动时这一层仍然命中缓存，不必每次重装。
# 用 npm ci 而不是 install：它严格按 package-lock.json 还原，构建可复现。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# 设备要访问的服务器地址**不在这里内联**（早先用的是 build arg + Vite 内联，那样地址
# 会被冻死在产物里：镜像由 CI 构建之后，换地址就得重打 tag、重构建、再拉一次）。
# 现在它取「管理员访问后台用的 origin」—— 后端只监听回环、这层 nginx 是唯一入口，
# 所以那就是设备要用的地址。见 src/api/device.ts。
RUN npm run build


# 运行阶段：nginx 托管静态文件，并把 /api 反代到后端
FROM nginx:alpine

# 站点配置在**容器启动时**渲染（render-templates.sh，由 entrypoint 执行）：
# nginx.conf（SPA 回退 + /api 代理 + SSE 不缓冲 + listen ${FRONTEND_PORT}）
# → /etc/nginx/conf.d/default.conf，端口值来自 docker-compose 的 frontend.environment。
# 于是同一个镜像放到哪台机器上都对，换端口只要重建容器，不必重建镜像。
#
# 模板放 /etc/nginx/app-templates/ 而**不是**镜像默认的 /etc/nginx/templates/：
# 后者有自带的渲染脚本，会按「环境里有什么就替换什么」把模板整个过一遍，
# 那样 nginx 自己的 $host / $remote_addr 也会被吃掉（见 render-templates.sh 的说明）。
COPY nginx.conf /etc/nginx/app-templates/default.conf.template
COPY render-templates.sh /docker-entrypoint.d/40-render-templates.sh
RUN chmod +x /docker-entrypoint.d/40-render-templates.sh

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
