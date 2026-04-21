# ============ Stage 1: Build frontend ============
FROM node:20-alpine AS builder

# 国内构建环境：corepack 默认走 registry.npmjs.org 经常超时，
# 让它走 npmmirror，同时给 npm 也设上兜底镜像
ENV COREPACK_NPM_REGISTRY=https://registry.npmmirror.com \
    NPM_CONFIG_REGISTRY=https://registry.npmmirror.com

# 优先用 corepack（读 package.json 的 packageManager 字段）；
# 若 corepack 拉 pnpm 失败（国内网络偶发 403/超时），直接用 npm 兜底装 pnpm@9.15.0
RUN (corepack enable && corepack prepare pnpm@9.15.0 --activate) \
    || npm install -g pnpm@9.15.0 --registry=https://registry.npmmirror.com

WORKDIR /build

# 先复制依赖清单利用缓存
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc* ./
COPY packages ./packages

RUN pnpm install --frozen-lockfile --registry=https://registry.npmmirror.com \
    || pnpm install --registry=https://registry.npmmirror.com

# 复制全部源码再构建
COPY . .

# 使用 prod 模式构建（会读取 .env.prod，API 走相对路径 /api/v1）
RUN pnpm build

# ============ Stage 2: Serve with nginx ============
FROM nginx:1.27-alpine

RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
