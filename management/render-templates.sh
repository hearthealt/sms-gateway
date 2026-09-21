#!/bin/sh
#
# 容器启动时把 nginx 站点配置渲染出来（nginx 的 entrypoint 会按文件名顺序执行
# /docker-entrypoint.d/*.sh，这个排在镜像自带的 10-/20- 之后）。
#
# 所以要渲染，是因为「对外端口」和「后端端口」都得能在部署时改（docker/.env 里那两个），
# 而 nginx 不认环境变量 —— 只能启动时把值填进配置。
#
# 为什么不用 nginx 镜像自带的模板机制（/etc/nginx/templates + envsubst-on-templates.sh）：
# 它按「环境里有什么就替换什么」来渲染，而 default.conf 里还有 $host、$remote_addr、
# $scheme 这些 **nginx 自己的**变量 —— 不点名的话会被它一起吃掉，渲染成一个语法坏掉的
# 配置。所以模板放在 /etc/nginx/app-templates/（自带机制不扫这个目录），这里点名替换。
set -e

envsubst '${FRONTEND_PORT} ${BACKEND_PORT}' \
  < /etc/nginx/app-templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf
