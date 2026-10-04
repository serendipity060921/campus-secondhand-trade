# =============================================================================
# 前端镜像（多阶段构建）—— v0.15
#
# 构建：在**项目根目录**执行
#   docker build -f deploy/docker/frontend.Dockerfile -t campus-trade-frontend:v0.15 .
#
# 设计要点：
#   ① 构建阶段用 Node 20 + pnpm（npm 走 npmmirror 镜像，国内安装速度快）；
#   ② 运行阶段用 nginx:alpine 托管静态文件，镜像最终只有 ~50MB；
#   ③ 站点配置里同时处理了 SPA 路由回退、/api 反向代理与 /ws 协议升级。
# =============================================================================

# ---------------------------- 阶段一：构建 ----------------------------
FROM node:20-alpine AS builder

WORKDIR /app

# npm 淘宝镜像（装 pnpm 与依赖都走镜像）
RUN npm config set registry https://registry.npmmirror.com && \
    npm install -g pnpm@9

# 先装依赖（利用层缓存：package.json 没变就不重装）
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --registry=https://registry.npmmirror.com

# 再拷源码构建
COPY frontend/ ./
RUN pnpm build

# ---------------------------- 阶段二：运行 ----------------------------
FROM nginx:1.27-alpine

RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 站点配置（SPA 回退 + /api 反代 + /ws 升级）
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf

# 前端产物
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1/ > /dev/null || exit 1

CMD ["nginx", "-g", "daemon off;"]
