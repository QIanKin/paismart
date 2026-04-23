# PaiSmart — MCN 智能投放与达人运营平台

PaiSmart 是一套面向 MCN / 品牌方 / 广告代理的「达人全链路作业台」，在一个 Docker 栈里同时提供：

- **AI 全自动 Agent**：用自然语言下达指令，由 LLM 驱动自主调用 46+ 个工具完成建库 / 筛号 / 建单 / 投放 / 复盘
- **小红书数据**：内容搜索、博主深度、报价、笔记、爆款分析（支持蒲公英 / 创作中心 / 千帆 / 聚光 / 千瓜五平台 cookie 池）
- **聚光广告 MAPI**：余额 / 计划 / 单元 / 投放报表一键拉取，OAuth2 access_token 定时自动续签
- **千瓜品牌达人发现**：通过业务员本机 Chrome + CDP 桥接，安全复用业务员千瓜会员登录态
- **项目管理**：项目 / 阶段 / 博主名册 / 自定义字段 / 阶段流转 / 导出
- **博主库**：多维度字段、真人 vs 商号识别、批量 upsert、导出
- **技能（Skill）系统**：扫码登录、报价采集、评论触达、千瓜发现等可插拔的 Node.js 任务
- **企业级权限**：JWT 认证、管理员 / 组织标签、速率限制、审计日志
- **前端**：Vue 3 + Naive UI + Pinia + UnoCSS，支持 SSE 流式 Agent 对话、WebSocket 实时推送

全栈使用 Spring Boot 3 + Java 17 + MySQL 8 + Redis 7 + Elasticsearch 8 + Kafka 3 + MinIO + Vue 3 / TypeScript。

---

## 一、一键部署

### 1.1 前置

交付机只需要：

- Docker / Docker Desktop 24+ 并启用 Compose v2
- 4C / 8G 内存起步（2G 给 Elasticsearch + 1G 给后端 JVM）
- 可联外网拉取镜像；生产机**不要**跑 Clash / V2Ray TUN 模式 VPN，否则容器内 DNS 会被劫持到 `198.18.0.0/16` fake-IP（见 [§5 已知环境坑](#五已知环境坑与排障)）

### 1.2 启动

```bash
# 1. 配置
cp .env.deploy.example .env   # 按注释改：SERVER_IP、数据库密码、AI Key、聚光凭证
# 2. 起栈（首次会自动构建后端 / 前端镜像，约 5 分钟）
./deploy.sh
# 或手动
docker compose -f docker-compose.prod.yml up -d --build
```

默认端口：

| 端口 | 用途 |
|---|---|
| `80` | 前端 Nginx（浏览器入口） |
| `8081` | 后端 Spring Boot（前端已通过 nginx 代理 `/api`） |
| `19000 / 19001` | MinIO S3 API / 控制台 |

首次启动日志会输出：

```
[AdminUserInitializer] 创建管理员: admin / <你在 .env 里设的 ADMIN_BOOTSTRAP_PASSWORD>
[XhsSpotlightSeeder]  已种入聚光凭证 label=xxx org=default id=8
```

浏览器打开 `http://SERVER_IP/` → 用 `admin` 登录即可。

### 1.3 `.env` 关键字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `SERVER_IP` | ✅ | 对外 IP，用于 MinIO 预签名 + 跨域白名单 |
| `DEEPSEEK_API_URL / KEY / MODEL` | ✅ | 驱动 Agent 的 LLM（兼容 OpenAI 协议，可接 SiliconFlow / DeepSeek / 通义千问等） |
| `EMBEDDING_API_URL / KEY / MODEL` | ✅ | 向量模型（RAG / 知识库） |
| `EMBEDDING_DIMENSION` | ✅ | 向量维度，换模型必须同时改 + 删除 ES 旧索引 |
| `JWT_SECRET_KEY` | ✅ | `openssl rand -base64 32` 生成，`deploy.sh` 首次会自动写 |
| `XHS_COOKIE_SECRET` | ✅ | cookie 池 AES-GCM 密钥，`deploy.sh` 首次会自动写，**换密钥会导致已入库 cookie 无法解密** |
| `ADMIN_BOOTSTRAP_*` | ✅（首启） | 首次管理员账号；登录成功后把 `ENABLED=false` 再重启 |
| `XHS_SPOTLIGHT_ADVERTISER_ID / ACCESS_TOKEN / REFRESH_TOKEN` | ⚪ | 聚光 OAuth2 凭证，打开 `XHS_SPOTLIGHT_SEED_ENABLED=true` 后重启即可自动落库 |
| `XHS_SPOTLIGHT_APP_ID / APP_SECRET` | ⚪ | 聚光开放平台应用凭证，**配上才能自动续签 access_token**；不配 agent 工具 `spotlight_oauth_refresh` 会返回 `config_missing` |

---

## 二、数据源：小红书 Cookie 池

### 2.1 支持的 5 个平台

| Platform | 用途 | 获取方式 |
|---|---|---|
| `xhs_pc` | 小红书主站（搜索笔记、博主） | Agent 扫码登录 / 手动粘贴 |
| `xhs_creator` | 创作者中心（查自家笔记数据） | Agent 扫码登录 / 手动粘贴 |
| `xhs_pgy` | 蒲公英（博主报价、商单） | Agent 扫码登录 / 手动粘贴 |
| `xhs_qianfan` | 千帆（企业号后台） | Agent 扫码登录 / 手动粘贴 |
| `xhs_spotlight` | 聚光 MAPI（OAuth2 Access Token） | `.env` 自动种入 / 管理端粘贴 |

### 2.2 扫码登录（推荐）

进入 `/data-sources` 页面 → 「添加账号」→ 选平台 → 扫二维码 → 登录完成后 cookie 自动加密入库。

扫码登录由内置 skill `xhs-qr-login`（Playwright + headless Chromium）完成，**后端容器已预装 2G `/dev/shm` + `/ms-playwright` 浏览器**，业务员本机不需要任何配置。

### 2.3 聚光凭证自动续签

后端 `SpotlightTokenScheduler` 每 10 分钟扫一次 `xhs_cookies` 表：

- 所有 `platform=xhs_spotlight` 且剩余寿命 < 30 分钟的记录
- 通过 `spotlight_oauth_refresh` 工具调用 MAPI `/oauth2/refresh_token`
- 成功：更新 `access_token` + `refresh_token` + `expires_at`
- 失败（refresh_token 过期）：标记 `status=EXPIRED` 并打日志，业务员在 `/data-sources` 页面重新录入

前置：`.env` 里必须配 `XHS_SPOTLIGHT_APP_ID / APP_SECRET`。

---

## 三、数据源：千瓜（浏览器 CDP 桥接）

千瓜（qian-gua.com）没有官方开放 API，但有严格的会员付费墙。PaiSmart 的做法是：

```
[业务员电脑 Chrome :9222]  ─(TCP)→  [CDP 代理 :9223]  ─(HTTP 桥)→  [后端容器]
```

**不用上传账号密码**；业务员本机手动登录一次千瓜，后端通过 CDP 协议远程操作该 Chrome 标签页完成抓取。

### 3.1 一次性启动业务员端

```cmd
# 双击运行：PaiSmart\acceptance\start-qiangua-chrome.bat
```

脚本会自动：

1. 用独立用户目录 `%LOCALAPPDATA%\PaiSmart\chrome-profile-qiangua` 启动一个**专用 Chrome** 实例（不影响日常浏览器）
2. 监听 `127.0.0.1:9222` 调试端口
3. 启动 Node 代理把 `0.0.0.0:9223 → 127.0.0.1:9222`（Chrome 147+ 拒绝非 localhost 源直连 9222，必须走代理改写 Host 头）
4. 自动打开 `https://www.qian-gua.com/` 登录页

业务员用千瓜会员账号登录一次（目前交付测试账号：`17733738352 / sk333111`），保持 Chrome 窗口 + cmd 窗口不要关。后端侧 `BrowserBridgeProperties.cdpEndpoint` 默认走 `http://host.docker.internal:9223`，无需额外配置。

### 3.2 Agent 调用

业务员聊天时说：「查一下 Dior 最近投的小红书博主都有谁」，Agent 会自动：

1. 定位 `qiangua_brand_discover` 工具
2. 在 Chrome 里打开品牌详情页
3. 抽取相关达人列表（含粉丝 / 分类 / 互动率 / 均赞 / 合作得分）
4. 调用 `creator_post_batch_upsert` 灌入博主库 + 可选 `creator_screen` 做真人筛选

---

## 四、Agent 能力全景

### 4.1 工具域（46 个工具，`GET /api/v1/agent/tools/catalog`）

| 域 | 工具数 | 代表工具 |
|---|---|---|
| 数据源与凭证 | 7 | `xhs_cookie_list / delete / test`、`spotlight_oauth_refresh`、`xhs_qr_login_*` |
| 小红书内容抓取 | 6 | `xhs_search_notes`、`xhs_fetch_pgy_kol`、`xhs_pgy_kol_detail`、`xhs_refresh_creator`、`xhs_download_video`、`xhs_outreach_comment` |
| 聚光广告数据 | 4 | `spotlight_balance_info`、`spotlight_campaign_list`、`spotlight_unit_list`、`spotlight_report_offline_advertiser` |
| 千瓜数据 | 1 | `qiangua_brand_discover` |
| 创作者库 | 8 | `creator_list / get / upsert / post_batch_upsert / screen / export / account_list / account_posts` |
| 项目名册 | 5 | `project_list / get / upsert / roster_add / roster_list` |
| 任务调度 | 4 | `schedule_list / upsert / delete / run_now` |
| 知识库与搜索 | 3 | `knowledge_search`、`web_search`、`web_fetch` |
| 对话与流程 | 5 | `ask_user_question`、`todo_write`、`use_skill`、`sleep`、`tool_search` |
| 系统与运维 | 1 | `system_status` |
| 文件系统（危险） | 5 | `file_read / write / edit`、`glob / grep / bash` |

### 4.2 Skill 系统（10 个 skill）

Skill 是"打包的可执行任务"（Node.js / Playwright），放在 `skills-bundled/` 或 `skills/`（热加载）下。Agent 通过 `use_skill` 工具调用。

| Skill | 作用 |
|---|---|
| `xhs-qr-login` | 扫码登录采 cookie |
| `xhs-pc-search-notes` | 主站笔记搜索 |
| `xhs-pgy-kol` / `xhs-pgy-kol-detail` | 蒲公英博主 / 报价 |
| `xhs-creator-refresh` | 创作中心数据 |
| `xhs-outreach-comment` | 批量评论触达 |
| `qiangua-brand-discover` | 千瓜品牌达人发现（CDP）|
| `xhs-download-video` | 无水印下载 |
| 其余内部 skill | 知识库预处理等 |

### 4.3 自主运维

Agent 自己有权限：

- 读系统健康（`system_status`）
- 读 cookie 池状态 + 试 ping（`xhs_cookie_list / test`）
- 读自家 / 建 / 改 / 删定时任务（`schedule_*`）
- 调用其他 agent 工具 / skill
- 向业务员反问（`ask_user_question`）
- 写 TODO（`todo_write`）进入 ReAct 循环

---

## 五、已知环境坑与排障

### 5.1 业务员电脑开了 Clash / V2Ray TUN 模式 ⚠️

**现象**：

- 所有聚光 / 蒲公英 / 小红书 API 从容器调用都失败（HTTP 000 / SSL `unexpected eof`）
- 容器里 `getent hosts mapi.xiaohongshu.com` 返回 `198.18.x.x`（Clash fake-IP 段）
- `host.docker.internal` 也可能被劫持到 `198.18.0.77`

**原因**：Clash TUN 在内核层拦截所有 DNS / TCP，包括 Docker Desktop 的 WSL2 虚拟网卡，Docker 容器拿到假 IP 后走不出去。

**处理**：

- 生产机：**不要装 Clash / V2Ray TUN 模式** VPN。如需代理请用 HTTP_PROXY 环境变量
- 开发调试：`docker-compose.prod.yml` 已显式给后端加 `dns: [119.29.29.29, 223.5.5.5, 8.8.8.8]` 绕过 fake-IP DNS；如果 Clash TUN 在网卡层截流，这一层也救不了，只能关 VPN

### 5.2 Chrome 147+ 拒绝容器直连 9222

**现象**：容器 `curl host.docker.internal:9222/json/version` 返回 `Empty reply from server`

**原因**：Chrome 安全策略：remote debugging port 只允许 `127.0.0.1` 源 + `Host: localhost|127.0.0.1` 头

**处理**：不要直连 9222，用 `acceptance/start-qiangua-chrome.bat` 启动的 9223 代理（已在默认配置 `SMARTPAI_BROWSER_CDP_ENDPOINT` 里指向 9223）

### 5.3 聚光 access_token 过期

**现象**：`/data-sources` 里聚光条目 `ping` 红色，`cookie_invalid :: token 已过期 xxxs`

**处理**：

- 如果 `.env` 里配了 `XHS_SPOTLIGHT_APP_ID / APP_SECRET`，`SpotlightTokenScheduler` 会在过期前 30 分钟自动续签
- 如果没配或者 refresh_token 也死了（聚光 refresh_token 默认 30 天），去 `https://ad.xiaohongshu.com` 开放平台重新授权，把新的三个凭证写进 `.env` 的 `XHS_SPOTLIGHT_*` 字段 → 在 `/data-sources` 删掉旧条目 → `docker compose restart backend`

---

## 六、验收测试（交付前必跑）

项目根目录有一键冒烟脚本，覆盖 10 大模块 / 33 条用例：

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -File acceptance\smoke.ps1
```

当前交付版本基准：**PASS=29 / WARN=3 / FAIL=0**。

3 条 WARN 都是环境依赖：

| WARN | 原因 | 解决 |
|---|---|---|
| `ping_xhs_spotlight` | 聚光 access_token 过期 | 见 §5.3 |
| `balance_info_live HTTP=000` | 业务员电脑开了 Clash TUN | 见 §5.1 |
| `cdp_bridge` | 业务员本机 Chrome 没启调试端口 | 见 §3.1 |

细节见 [`acceptance/DELIVERY.md`](acceptance/DELIVERY.md)。

---

## 七、目录结构

```
PaiSmart/
├── .env                          # 部署配置（不进 git）
├── docker-compose.prod.yml       # 生产栈
├── deploy.sh                     # 一键部署入口
├── Dockerfile                    # 后端镜像
├── pom.xml
├── src/main/java/com/yizhaoqi/smartpai/
│   ├── SmartPaiApplication.java
│   ├── controller/              # REST API（agent / cookie / project / creator / admin / ...）
│   ├── service/
│   │   ├── agent/               # Agent 核心（ReAct 循环 / SSE / 工具调度）
│   │   ├── tool/builtin/        # 46 个内置工具
│   │   ├── skill/               # Skill 加载器 / 运行器
│   │   └── xhs/                 # 小红书相关（cookie 池 / 扫码 / 聚光 / 蒲公英 / 千瓜）
│   ├── config/                  # 配置类（Security / 限流 / CDP 桥 / 调度器）
│   ├── entity/ & repository/    # JPA
│   └── ...
├── frontend/                    # Vue 3 前端
│   ├── src/views/
│   │   ├── chat/                # Agent 聊天（SSE + 工具调用可视化）
│   │   ├── data-sources/        # cookie 池管理
│   │   ├── creator/             # 博主库
│   │   ├── project/             # 项目 / 名册
│   │   └── admin/               # 管理员后台
│   └── ...
├── skills-bundled/              # 内置 skill（随镜像发布）
│   ├── xhs-qr-login/
│   ├── qiangua-brand-discover/
│   ├── xhs-pgy-kol/
│   └── _shared/                 # Playwright runtime / Spider_XHS / 报告模板
├── skills/                      # 用户自定义 skill（挂载到容器热加载）
└── acceptance/                  # 交付物
    ├── smoke.ps1                # 一键冒烟
    ├── cdp-proxy.mjs            # Chrome CDP 代理
    ├── start-qiangua-chrome.bat # 业务员端一键启动
    └── DELIVERY.md              # 最新验收报告
```

---

## 八、开发模式

### 后端

```bash
# 只起基础依赖（MySQL / Redis / Kafka / ES / MinIO）
docker compose -f docker-compose.prod.yml up -d mysql redis kafka elasticsearch minio
# 本地跑后端
mvn spring-boot:run
```

IDE 直接跑 `SmartPaiApplication.java` 也行，`DotenvEnvironmentPostProcessor` 会自动读 `.env`。

### 前端

```bash
cd frontend
pnpm install
pnpm run dev            # vite --mode test，连 http://localhost:8081/api/v1
```

### Skill 调试

自定义 skill 只要放进 `./skills/<name>/SKILL.md + run.mjs`，挂载路径 `/app/skills` 会被 SkillLoader 每 30s 热加载，无需重启后端。

---

## 九、安全与权限

- JWT 认证（`/api/v1/auth/login` → access_token，HS256）
- 管理员 / 普通用户两级 + 组织标签多租户
- 所有破坏性工具（`file_write / bash / project_delete / creator_delete / ...`）在 Agent 侧标注 `destructive=true`，前端会弹确认
- 速率限制（Redis 滑窗，按用户 + endpoint）
- cookie 池用 AES-GCM 加密，密钥由 `XHS_COOKIE_SECRET` 控制

---

## 十、许可证 & 致谢

内部闭源交付版本。依赖第三方库遵守各自许可证：Spring Boot / Vue / Playwright / Spider_XHS / Naive UI 等。
