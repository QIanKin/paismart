# ============ Stage 1: Build jar with Maven ============
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 国内构建时使用阿里云 maven 镜像，避免与官方仓库握手被重置
COPY settings-docker.xml /root/.m2/settings.xml

# 先单独拷贝 pom.xml 利用 docker 层缓存
COPY pom.xml .
RUN mvn -B -e -q dependency:go-offline

# 再拷贝源码并打包
COPY src ./src
COPY docs ./docs
RUN mvn -B -e clean package spring-boot:repackage -DskipTests

# ============ Stage 2: Runtime ============
# 使用 JDK 镜像（Ubuntu jammy 底座）而不是 JRE alpine，
# 因为 skill 系统会在容器里跑 python/bash，alpine 的 musl 不兼容 opencv/numpy wheel。
FROM eclipse-temurin:17-jdk-jammy

# 安装 curl/tzdata/python3/nodejs/ffmpeg
#   - curl          : healthcheck + pip + nodesource 安装脚本
#   - python3/pip   : Spider_XHS 依赖
#   - nodejs >= 18  : Spider_XHS 的 JS 签名依赖 jsdom@26（Node 18+），Ubuntu Jammy 自带 Node 12 不够
#                     -> 用 NodeSource 官方仓库装 Node 20 LTS
#   - ffmpeg        : xhs-downloader skill 抽音频
#   - tzdata        : 中国时区
# 清理 apt 缓存减小 image 体积
# 换成清华 HTTPS 源：中国网络下最稳 + 绕过某些环境把 80 端口劫持到 TUN 的情况
RUN sed -i 's|http://archive.ubuntu.com/ubuntu/|https://mirrors.tuna.tsinghua.edu.cn/ubuntu/|g; s|http://security.ubuntu.com/ubuntu/|https://mirrors.tuna.tsinghua.edu.cn/ubuntu/|g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
       curl tzdata ca-certificates gnupg \
       python3 python3-pip python3-venv \
       ffmpeg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && node --version && npm --version \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 拷贝 jar 与引导知识库
COPY --from=builder /build/target/SmartPAI-*.jar /app/app.jar
COPY --from=builder /build/docs /app/docs

# 拷贝技能目录（含 Spider_XHS 共享库 + xhs-* skills）
# 用户自定义 skill 建议在 compose 里挂 ./skills:/app/skills 持久化
COPY skills-bundled /app/skills-bundled

# 预装 Spider_XHS 的 Python 依赖（避免冷启动拉包失败）
# 走清华 pypi 镜像；--break-system-packages 是 pip 23+ 在 PEP 668 保护下的系统级安装开关
ENV PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn
RUN pip3 install --no-cache-dir --break-system-packages \
       -r /app/skills-bundled/_shared/Spider_XHS/requirements.txt \
    || pip3 install --no-cache-dir \
       -r /app/skills-bundled/_shared/Spider_XHS/requirements.txt

# xhs-downloader skill 用的 yt-dlp（视频抓取，小而频繁更新，故走 pip 而不是 apt）
RUN pip3 install --no-cache-dir --break-system-packages yt-dlp \
    || pip3 install --no-cache-dir yt-dlp

# Playwright CDP 运行时（xhs-outreach-comment / qiangua-brand-discover 的 .mjs 需要）
# PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 跳过 chromium 二进制下载（300MB+），
# 因为我们只做 connectOverCDP 连业务员本机的 Chrome，不需要服务器端 Chromium
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    NPM_CONFIG_REGISTRY=https://registry.npmmirror.com
RUN cd /app/skills-bundled/_shared/playwright-runtime \
    && npm install --omit=dev --no-audit --no-fund \
    && npm cache clean --force

# xhs-qr-login skill 需要**后端自带 headless Chromium** 去采扫码登录 cookie。
# 这是整个项目唯一需要服务器端浏览器的场景，因此只在本 skill 目录内打开 SKIP 开关并 install chromium。
# 系统依赖（libnss3 / libatk1.0-0 / libgbm1 / libasound2 等）由 `npx playwright install-deps chromium` 拉起。
#
# Playwright 默认从 playwright.azureedge.net 下载 chromium，国内几乎必超时。
# 通过 PLAYWRIGHT_DOWNLOAD_HOST 切到 npmmirror 镜像，避免把整个 build 卡死。
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_DOWNLOAD_HOST=https://registry.npmmirror.com/-/binary/playwright
RUN cd /app/skills-bundled/xhs-qr-login \
    && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=0 npm install --omit=dev --no-audit --no-fund \
    && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=0 PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
         node ./node_modules/playwright/cli.js install --with-deps chromium \
    && npm cache clean --force

# Spider_XHS 的 Node 依赖（crypto-js + jsdom），供 PyExecJS 调 xhs_main_*.js / xhs_xray.js 跑签名
# 没有这些模块，get_user_info / search_some_note 会抛 "Cannot find module 'crypto-js'"
# 或 "window.mnsv2 is not a function"
RUN cd /app/skills-bundled/_shared/Spider_XHS \
    && npm install --no-audit --no-fund \
    && npm cache clean --force

# sandbox 目录（compose 会挂成 volume 做持久化/隔离）
RUN mkdir -p /app/var/agent-sandbox /app/skills \
    && chmod -R 755 /app/var /app/skills

EXPOSE 8081

ENV JAVA_XMS=512m \
    JAVA_XMX=1g \
    SPRING_PROFILES_ACTIVE=docker \
    TZ=Asia/Shanghai \
    BASH_SANDBOX_ROOT=/app/var/agent-sandbox \
    SKILLS_ROOT_USER=/app/skills \
    SKILLS_ROOT_BUNDLED=/app/skills-bundled \
    PYTHONIOENCODING=utf-8 \
    PYTHONUNBUFFERED=1

ENTRYPOINT ["sh", "-c", "exec java -server -Xms${JAVA_XMS} -Xmx${JAVA_XMX} -Dspring.devtools.restart.enabled=false -XX:-OmitStackTraceInFastThrow -jar /app/app.jar"]
