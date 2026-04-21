# PaiSmart · 交接文档（给下一个 AI）

> 背景：这是一个"广告/种草项目管理 + 博主数据库"场景的企业级 AI 应用。老板（用户）一个人维护。
> 本文档只描述**核心业务功能**与**它们当前的状态**，不是技术实现细节手册。下一个 AI 应当把这个文档当成 PRD + 当前快照。

---

## 一、技术栈速览

| 层 | 内容 |
|---|---|
| 后端 | Spring Boot 3.4.2 / Java 17 / Maven；MySQL 8、Redis、ES 8.10、Kafka、MinIO、Apache Tika；JPA（`ddl-auto=update` 自动建表）；WebFlux + WebSocket |
| 大模型 | DeepSeek API 或 Ollama；嵌入模型走豆包 / Ollama |
| Agent | 自研工具 + Skill 系统。33 个内置工具 + 10 个预置 skill。Skill 目录在 `skills-bundled/`，有 `loadMode: reference`（只读注入 AI 上下文）和可执行（python/bash/node）两类 |
| 前端 | Vue 3 + TypeScript + Vite + Naive UI + Pinia；elegant-router 自动生成路由；UnoCSS + SCSS；pnpm |
| 部署 | Docker Compose（`docker-compose.prod.yml`）。前端走 nginx 80 端口，后端 8081。所有容器已定义 healthcheck |
| 抓取 | Spider_XHS（vendored 在 `skills-bundled/_shared/Spider_XHS`）；Node 20 + `crypto-js` + `jsdom@26` 做 xhs 签名；Playwright CDP（浏览器里点） |

**启动命令**（Windows / PowerShell）：
```powershell
cd d:\Project\AI\PaiSmart
docker compose -f docker-compose.prod.yml up -d
# 前端: http://127.0.0.1/   admin / Yyanyyan@666
```

---

## 二、两大核心功能目标

### 功能 1 · 广告项目化

一个"项目"就是一个大上下文。项目内可以有**多种类型**的会话，agent 能在每个会话里做不同的事：

| 会话类型 (`session_type`) | 用途 | agent 常用工具 |
|---|---|---|
| `BLOGGER_BRIEF` | 针对**某个具体博主**做设计方案、人设分析、笔记建议、竞品参考 | `creator_get`, `creator_get_posts`, `knowledge_search`, `use_skill(xhs-note-methodology)` |
| `ALLOCATION` | 项目内"怎么分配博主"的规划性讨论 | `creator_screen`, `creator_search`, `project_roster_list`, `project_roster_add` |
| `CONTENT_REVIEW` | 审稿（审博主发来的文案/图/视频） | `use_skill(xhs-note-methodology)`, `knowledge_search` |
| `DATA_TRACK` | 发布后效果跟踪 | `creator_get_posts`, `xhs_refresh_creator`, `schedule_create` |
| `GENERAL` | 兜底 | 全部工具 |

**数据模型关系（关键）**：
- `agent_projects`：项目本体
- `project_creators`：**项目→博主的名册**（关联表），字段 `stage`(CANDIDATE/SHORTLISTED/LOCKED/SIGNED/PUBLISHED/SETTLED/DROPPED)、`quoted_price`、`currency`、`project_notes`、`custom_fields`（项目内自定义字段）
- `agent_sessions`：会话，含 `project_id`（FK）+ `creator_id`（FK, 可空）+ `session_type`

### 功能 2 · 博主数据库（核心资产）

- **博主主体**（`creators`）：人设信息（`persona_tags_json`、`track_tags_json`、`real_name`、`gender`、`birth_year`、`city`、`cooperation_status`、`price_note`、`internal_notes`、`custom_fields_json`）
- **博主账号**（`creator_accounts`）：一个博主可能有多平台账号，含 `platform`、`platform_user_id`、`followers`、`avg_likes`、`engagement_rate`、`hit_ratio` 等
- **博主笔记**（`creator_posts`）：账号下的笔记，含 `likes/comments/collects/shares/views`、`hashtags_json`、`cover_url`、`published_at`
- **自定义字段**（`creator_custom_fields`）：用户可在设置页定义字段（例如"对外报价"、"档期"），支持 `creator` / `account` / `project` / `project_creator` 四个 entity_type
- **Cookie 池**（`xhs_cookies`）：做抓取必备，密文存储

**用户需求的三个硬动作**：
1. **爬** — 用户点一下刷新按钮 / agent 自主调用，就能把笔记/互动数据拉进库
2. **在项目里查** — 进某项目详情 → 名册里点某博主 → 看他的笔记（不用再跳出去）
3. **导 Excel** — 多选/全选博主一键导出 xlsx

---

## 三、当前已完成的能力（已跑通）

### 3.1 后端 API

| 路径 | 功能 |
|---|---|
| `/api/v1/users/login` | 登录取 JWT |
| `/api/v1/agent/projects` | 项目 CRUD、按模板创建（`/from-template`）、list 模板 |
| `/api/v1/agent/projects/{id}/creators` | 项目名册 CRUD |
| `/api/v1/agent/sessions` | 会话 CRUD，支持按 `projectId`、`sessionType`、`creatorId` 过滤 |
| `/api/v1/agent/sessions/{id}/messages` | 历史消息流 |
| `/api/v1/creators` | 博主 CRUD、筛选、分页 |
| `/api/v1/creators/{id}/accounts` | 博主账号（多平台）CRUD |
| `/api/v1/creators/{accountId}/posts` | 博主某账号的笔记列表 |
| `/api/v1/creators/accounts/{id}/posts/batch-upsert` | 批量写笔记 |
| `/api/v1/creators/accounts/{id}/refresh:xhs` | 一键触发 Spider_XHS 抓取 |
| `/api/v1/creators/custom-fields` | 自定义字段定义 CRUD（`entityType` 参数区分 creator / account / project / project_creator） |
| `/api/v1/creators/export.xlsx` | 导出 Excel（支持 platform/keyword/categoryMain/followers 等过滤、`ids` 精选） |
| `/api/v1/admin/xhs-cookies` | XHS Cookie 池 CRUD |
| `/api/v1/admin/xhs-cookies/validate` | Cookie 预检（返回 `detectedKeys`、`missingRequired`、`ok`） |
| `/api/v1/admin/xhs-cookies/qr-login` | **扫码登录**：POST 起会话 / GET 查状态 / POST `/cancel` 取消。返回 `sessionId` 用来订阅 WS |
| `/ws/xhs-login/{token}?session={sessionId}` | WebSocket 事件流：`snapshot` / `qr_ready` / `status` / `success` / `error` / `closed` / `pong` |
| `/api/v1/chat/...` | WebSocket 聊天入口（老通道） |
| `/api/v1/agent/skills` | Skill 列表 / 启用禁用 |

### 3.2 前端页面

| 路由 | 页面 | 状态 |
|---|---|---|
| `/agent-projects` | 项目列表（卡片 + 新建按钮） | ✅ |
| `/agent-projects/:id` | 项目详情（左：会话列表 / 中：聊天 / 右：roster 名册） | ✅ 最近修复过（elegant-router 路径 `views/agent-project-detail/`） |
| `/creator-hub` | 博主数据库（列表、筛选、多选、导出、刷新、自定义字段设置） | ✅ |
| `/xhs-cookies` | XHS Cookie 管理（含 SOP 教程、三个必填 a1/web_session/webId 实时校验） | ✅ 本轮新做 |
| `/chat` | 普通聊天（老通道） | ✅ |
| `/knowledge-base` | 知识库 | ✅ |
| 用户/组织/充值/监控/邀请码 | 运营后台 | ✅ |

### 3.3 Agent 工具（33 个）

博主业务相关的：
- `creator_get` / `creator_search` / `creator_upsert` / `creator_screen`
- `creator_get_posts`（支持 TTL，过期自动触发刷新）
- `creator_post_batch_upsert`
- `creator_export`
- `project_roster_add` / `project_roster_list`
- `xhs_refresh_creator`（调 Spider_XHS）
- `xhs_search_notes` / `xhs_fetch_pgy_kol` / `xhs_download_video` / `xhs_outreach_comment` / `qiangua_brand_discover`

通用：`knowledge_search`、`web_search`、`web_fetch`、`use_skill`、`list_skills`、`tool_search`、`bash`、`fs_*`、`schedule_*`、`todo_write`、`ask_user_question`、`sleep`

### 3.4 Skill 包（10 个，放 `skills-bundled/`）

- `xhs-user-notes`（拉博主笔记，**本轮修过**分页 bug）
- `xhs-search-notes`
- `xhs-note-detail`
- `xhs-pgy-kol` / `xhs-pgy-kol-detail`
- `xhs-downloader`
- `xhs-outreach-comment`
- `xhs-cookie-refresh`
- `xhs-note-methodology`（reference-only，给 AI 做小红书内容方法论知识注入，**本轮新做**）
- `qiangua-brand-discover`

### 3.5 Cookie 管理已经做好的检查

- `a1`（必填，Spider_XHS 签名用）
- `web_session`（必填，登录态）
- `webId`（必填，反爬）
- `xsecappid`（可选，推荐 `xhs-pc-web`）
- 前端在输入框实时显示已检测到的字段 tag，缺哪个会红字阻止保存
- 后端 `/validate` 预检，`create`/`update` 再校验一次，DB 里用 `cookie_keys` 列记录已解析到的键名以便列表页显示 ✓/✗

---

## 三.5 · 2026-04-20 这轮做了什么（登录采 Cookie + AI 项目上下文）

> 对应需求 1-part1（"产品内登录入口自动抓 cookie"）+ 需求 2-part1（"AI 掌握整个博主数据库 + 项目级上下文"）。
> 设计文档在 `docs/superpowers/specs/2026-04-20-xhs-login-and-ai-context-design.md`，跟代码保持对齐。

### A. 扫码登录采集 Cookie（不用再 F12 复制 a1 / web_session）

**新架构**：前端按"扫码登录采集"→ 后端起一个 **headless Chromium（Playwright）** 打开小红书登录页 → 截取二维码 → 通过 WebSocket 回推前端 → 业务员用小红书 App 扫一扫确认 → 后端再轮询登录态，成功后依次打开 `creator.xiaohongshu.com` / `pgy.xiaohongshu.com` / `qianfan.xiaohongshu.com` 收集 cookie → `bulkUpsertFromLogin` 批量落池（source=`QR_LOGIN`）。**原"手动粘贴"流程保留**，兜底用。

新增/变更的点：

| 文件 | 作用 |
|---|---|
| `src/main/java/.../model/xhs/XhsLoginSession.java` | 会话生命周期（PENDING→QR_READY→SCANNED→CONFIRMED→SUCCESS / FAILED / EXPIRED / CANCELLED） |
| `src/main/java/.../model/xhs/XhsCookie.java` | 新增 `source`（MANUAL/QR_LOGIN/SEED）+ `loginSessionId` 审计字段 |
| `docs/db-migration-2026-04-xhs-login.sql` | 幂等迁移脚本：新建 `xhs_login_sessions` + `ALTER TABLE xhs_cookies ADD COLUMN source/login_session_id`（JPA `ddl-auto=update` 也会建表，这份 SQL 给 DBA 手动备用） |
| `src/main/java/.../config/XhsLoginProperties.java` | 开关、超时、skill 路径、Playwright browsersPath、janitor cron |
| `src/main/java/.../service/xhs/LoginBrowserRunner.java` | **不走 BashExecutor** —— 用 `ProcessBuilder` 直拉 node，实时按行读 stdout NDJSON 并转 `LoginEvent`（需要边扫边推，BashExecutor 只给最终 stdout） |
| `src/main/java/.../service/xhs/XhsLoginSessionService.java` | 会话状态机 + 订阅总线 + 每分钟 janitor reap 超时会话 |
| `src/main/java/.../service/xhs/XhsCookieService.java` | 新增 `bulkUpsertFromLogin(...)`：同 label 覆盖、缺 a1/web_session/webId 自动 skipped、其他平台（spotlight/competitor）不校验 |
| `src/main/java/.../handler/XhsLoginWebSocketHandler.java` | `/ws/xhs-login/{token}?session=xxx`，鉴权 JWT、校验会话归属、转发 snapshot/qr_ready/status/success/error，支持 stdin cancel/ping |
| `src/main/java/.../config/WebSocketConfig.java` | 注册新的 WS 端点（复用 `chatWebSocketHandler` 的 origin 白名单） |
| `src/main/java/.../controller/XhsCookieController.java` | `POST/GET/POST-cancel /qr-login[/{id}]` REST 入口（ROLE_ADMIN） |
| `skills-bundled/xhs-qr-login/` | node 20 + Playwright 脚本：拉 QR → 轮询 SCANNED/CONFIRMED → 跨 subdomain 采 cookie → NDJSON 回传；配 `README.md` 解释协议 |
| `Dockerfile` | 针对 `xhs-qr-login` 单独 `npm install` + `playwright install --with-deps chromium`（其他 skill 仍走 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`） |
| `docker-compose.prod.yml` | backend `shm_size: 2gb`（不然 headless Chromium 崩）+ `SMARTPAI_XHS_LOGIN_*` 环境变量 |
| `frontend/src/views/xhs-cookies/modules/qr-login-modal.vue` | 前端扫码弹窗：拉会话 → 建 `/proxy-ws/ws/xhs-login/...` → 渲染二维码 + 状态 + 倒计时 + 成功/失败动作 |
| `frontend/src/views/xhs-cookies/index.vue` | 右上角加「扫码登录采集」主按钮；原「添加 Cookie」降级成「手动录入」兜底 |
| `frontend/src/service/api/xhs-cookie.ts` + `typings/api.d.ts` | `fetchXhsLoginStart/Status/Cancel` + `Api.Xhs.LoginStatus/LoginStartResponse/LoginStatusResponse/LoginWsFrame` |
| `src/test/java/.../service/xhs/XhsCookieServiceBulkUpsertTest.java` | 5 条单测覆盖"全新入库 / 同 label 覆盖 / 缺字段 skipped / 空输入 / 非 web 平台放行"；**不起 Spring context**，直接 new 服务 + in-memory repo stub + 真实 `CookieCipher`（默认 secret） |

**已验证**：
- `docker run maven:3.9-eclipse-temurin-17 mvn -B test` → 113 tests, 0 failures, BUILD SUCCESS（含本轮新增 5 条）
- `cd frontend && pnpm typecheck` → 0 errors
- `cd frontend && pnpm build` → "Build successful"（unocss 的 icon warn 为旧告警，跟本轮改动无关）

**业务员的新操作路径**：
1. 登录 Cursor 打好 container → `/xhs-cookies`
2. 点"扫码登录采集"→ 等二维码（大约 3-5 秒）→ 用小红书 App 扫
3. 手机确认 → 前端 1-2 秒内刷新出 4 条 cookie（xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan，source 列显示 `QR_LOGIN`）

**已知限制**：
- 千帆（`xhs_qianfan`）不属于小红书 SSO 体系，一次扫码**往往只采到 0-1 个字段**，会在前端"未采集"里列出。业务员仍需进千帆单独登录后再手动粘贴，兜底路径没被破坏。
- headless Chromium 在 CI 环境/无 GPU 环境依赖 `libnss3/libgbm1/...`，已由 `playwright install --with-deps` 一把拉齐；如果你换基础镜像记得重跑 `--with-deps`。

### D. AI 对博主库的"开箱即懂"（项目上下文 + 博主画像注入）

**现状**：之前只有 `BLOGGER_BRIEF` 会话会注入绑定博主的 brief；项目级信息（roster、项目描述）agent 必须调工具才能拿到。**本轮改为**：

1. 新方法 `AgentRuntime.appendProjectContext(scope)`：
   - 任意 session 只要 `scope.project != null`，就在 system prompt 里注入：
     - 项目名字 / id / 描述 / templateCode / customFieldsJson（截断 300~400 字符）
     - 项目名册 top 15（按 priority/id）：每行 `[stage=X, priority=Y] 博主名 (creatorId=N) 赛道=...`
     - 提示 agent 可以用 `project_roster_list` / `creator_search` / `creator_get` / `creator_get_posts` 按需取详
2. 保留原 `buildCreatorBrief` —— session 绑定具体 creator（如 `BLOGGER_BRIEF`）时继续追加该博主全量档案
3. **注入点**：`AgentRuntime.buildSystemPrompt()` 内部调用，每轮都会重算但借助 `CreatorService` / `ProjectCreatorService` 自身的缓存，开销很低

**为什么没做成 `ContextProvider`**：现有 `ContextEngine` 用于动态上下文（检索片段、RAG），但项目/博主这类"稳定、每轮必带"的信息直接拼 system prompt 更直观且省 token budget。后续要做 RAG 式"项目历史记忆"再落回 ContextProvider。

---

## 四、本轮刚刚修掉的坑（下一个 AI 不要重复）

1. **Spider_XHS 依赖**：Ubuntu Jammy 默认 Node 12，但 `jsdom@26` 要 Node ≥ 18。已在 `Dockerfile` 通过 NodeSource 装 Node 20，并在 image 阶段就 `npm install` `skills-bundled/_shared/Spider_XHS/` 的 node_modules。**不要再移除**。

2. **Spider_XHS `get_user_all_notes` bug**：当 URL 不带 query string 时 `urlparse('').query.split('&')=['']` → `['']['='][1]` 越界抛 `list index out of range`。已在 `skills-bundled/xhs-user-notes/scripts/fetch_user_notes.py` 里**绕开**（手动分页调 `get_user_note_info`，用 `xsec_source=pc_search` 可绕过反爬，不需要 `xsec_token`）。

3. **Python skill 的 cwd**：Spider_XHS 的 `apis/*.py` 用相对路径加载 `static/*.js` 签名文件。python skill 入口必须在 `_bootstrap()` 里 `os.chdir(SPIDER_XHS_HOME)`，否则 `FileNotFoundError: static/xhs_main_260411.js`。已修的脚本：`xhs-user-notes` / `xhs-pgy-kol` / `xhs-pgy-kol-detail` / `xhs-note-detail` / `xhs-search-notes`。

4. **elegant-router 覆盖问题**：前端 `views/` 下目录名即路由，如果在 `agent-projects/` 里放 `detail.vue` 会被插件扫掉。已改成 `views/agent-project-detail/index.vue` + `build/plugins/router.ts` 里把路径映射到 `/agent-projects/:id`、菜单高亮 `activeMenu: 'agent-projects'`。

5. **PowerShell/MySQL 编码**：PS 默认不是 UTF-8，PS 里写 Chinese 做 `Invoke-RestMethod` body 会把中文字节搞成 `?`。开发/自动化脚本要 **强制 `[Console]::OutputEncoding = UTF8`** 并用 `--default-character-set=utf8mb4`。或走 SQL 文件 + `docker cp`。

6. **管理员密码**：admin 不是 `admin1234` 而是环境变量里的 `Yyanyyan@666`（见 compose env）。

7. **`user_id` vs `red_id`**：用户提供的 `1073017420` 是小红书的"红薯号"（red_id），Spider_XHS 用的是 profile URL 里的 hex `5fe42a970000000001002f70` 那串。不要把红薯号当 `platform_user_id`。

8. **`Map<?, ?>.getOrDefault(key, defaultValue)` 编译错**：通配 `?` 和具体类型不兼容 javac 会拒（IDE 有时放水）。解析 JSON 解出 `Map` 时要么声明为 `Map<String, Object>`，要么 `cmd.get("type")` 之后再 `String.valueOf(...)`。XhsLoginWebSocketHandler 就踩过这个。

---

## 五、还没做完的事（优先级排序）

### P0 · 体验类（必须做，不做的话用户会觉得"卡住"）

| # | 任务 | 涉及文件 | 验收标准 |
|---|---|---|---|
| 1 | **项目详情页名册 → 点博主 → 该博主笔记 tab** | `frontend/src/views/agent-project-detail/modules/roster-panel.vue` + 新增 `PostsDrawer.vue` | 在名册表格里点博主行展开/抽屉，能在当前项目上下文里直接看该博主的 post 列表（带图/标题/数据），不用跳到 `/creator-hub` |
| 2 | **聊天输入框支持 @ 博主 / @ 项目快捷 context 注入** | `frontend/src/views/agent-project-detail/modules/agent-chat.vue` | 输入 `@博主名` 时弹出下拉（搜索本项目 roster 或全库），选中后自动把 `creator_id` 和 profile URL 作为 hint 拼进 prompt |
| 3 | **博主数据库"刷新一批"批量按钮** | `frontend/src/views/creator-hub/index.vue` | 多选博主 → 右上角「刷新选中 (N)」→ 后端串行/并行触发 `refresh:xhs`；失败的博主在列表标红 |
| 4 | **前端：导出 Excel 时支持自定义列** | `creator-hub/index.vue` | 弹框让用户勾选哪些字段导出（含自定义字段），后端 `export.xlsx` 加 `?columns=` 参数 |

### P1 · 数据类（用户用久了会想要）

| # | 任务 | 说明 |
|---|---|---|
| 5 | **博主快照差分**（`creator_snapshots` 表已经有了，但没人用） | 刷新时除了 upsert，还写一条快照。前端在博主详情显示"过去 7 天粉丝/点赞增长"折线图 |
| 6 | **定时刷新任务（全库）** | `ScheduleCreateTool` 已经在了，但没配默认 job。应该默认把"每天凌晨 3 点全量刷新在项目里的博主"作为默认 schedule |
| 7 | **outreach_records 表接上** | 建外联联系记录的表有了但没 UI 和 API。在 `BLOGGER_BRIEF` 会话里加"记录一次外联"按钮 |
| 8 | **项目看板视图** | `/agent-projects/:id` 增加一个 `kanban` tab：按 `stage` (CANDIDATE/SHORTLISTED/LOCKED/SIGNED/PUBLISHED/SETTLED/DROPPED) 拖拽博主卡片 |

### P2 · Agent 能力类

| # | 任务 | 说明 |
|---|---|---|
| 9 | ~~**BLOGGER_BRIEF 会话的 system_prompt 默认注入**~~ | **本轮已做**（`AgentRuntime.appendProjectContext` + `buildCreatorBrief`）：只要 session 绑定 project，system prompt 自动注入项目基本信息 + 名册 top 15；绑定 creator 时再追加该博主 Creator/Account/最近 3 条爆款摘要。完整档案仍让 agent 走 `creator_get_posts` 按需查询 |
| 10 | **多账号 Cookie 轮换** | `XhsCookieService` 已经有 `selectOne(...)` 的轮询逻辑，但没按 `failCount/successCount` 降权。给"连续失败 3 次的 cookie"自动 `DISABLED` |
| 11 | **Skill marketplace** | `/agent/skills` 页面只列了 bundled + 用户上传。加个"安装 skill"按钮从仓库目录拉包（或上传 zip） |

### P3 · 品质

| # | 任务 | 说明 |
|---|---|---|
| 12 | **清理/规范数据库种子** | `DataSeeder` / `XhsCookieSeeder` 别在生产环境建 mock 博主（现在 seed 的 mock creator 会被 PS 脚本写成乱码） |
| 13 | **日志脱敏** | 现在 backend 日志会打 `cookie_preview`，含部分明文。应该 mask 中间段 |
| 14 | **前端 typecheck + eslint 一键绿** | 本轮有几个 `any`，可以收敛 |

---

## 六、关键目录速查

```
PaiSmart/
├── docker-compose.prod.yml      # 生产编排（正在跑的）
├── Dockerfile                   # 后端镜像（Node 20 + Spider_XHS npm install 已固化）
├── pom.xml
├── docs/
│   ├── HANDOFF.md              # 本文档
│   └── ...                     # 启动后会被复制到 /app/docs 给 agent 做 knowledge_search
├── src/main/java/com/yizhaoqi/smartpai/
│   ├── controller/              # REST 入口
│   ├── service/
│   │   ├── agent/               # ProjectService / ChatSessionService / ContextEngine
│   │   ├── tool/builtin/        # 33 个 Agent 工具
│   │   ├── skill/               # SkillRegistry / SkillLoader / SkillRunner
│   │   ├── xhs/                 # XhsCookieService / XhsSkillRunner / XhsRefreshService
│   │   └── creator/             # CreatorService / CustomFieldService
│   └── model/
│       ├── agent/               # Project, ChatSession, AgentMessage, ProjectCreator, SessionType
│       ├── creator/             # Creator, CreatorAccount, CreatorPost, CustomFieldDefinition
│       └── xhs/                 # XhsCookie
├── skills-bundled/
│   ├── _shared/Spider_XHS/     # 抓取引擎（vendored）
│   ├── xhs-user-notes/         # 拉博主笔记（本轮修了分页）
│   ├── xhs-note-methodology/   # reference-only 知识注入
│   └── ...                     # 其他 xhs-* skill
└── frontend/
    └── src/
        ├── views/
        │   ├── agent-projects/           # 项目列表
        │   ├── agent-project-detail/     # 项目详情（拆分 session-sidebar / agent-chat / roster-panel）
        │   ├── creator-hub/              # 博主数据库
        │   ├── xhs-cookies/              # Cookie 管理
        │   └── chat/                     # 老通道
        ├── service/api/                  # API 客户端
        ├── store/modules/                # Pinia
        ├── typings/api.d.ts              # 后端 DTO 类型
        └── build/plugins/router.ts       # elegant-router 配置（agent-project-detail 特例）
```

---

## 七、给下一个 AI 的工作建议

1. **先读这份文档，再读 `/docs` 目录其他 md（如有）**，不要贸然重构。
2. **改动前先在本地跑一次** `docker compose up -d` → 登录 → 进项目详情 → 聊一条消息，确认当前行为正常，再动手。
3. **UI 改动后 pnpm build 再重建 frontend 容器**（`docker compose build frontend && docker compose up -d frontend`）。
4. **不要直接 `docker compose down -v`**——卷带数据，会丢博主/项目。只清数据时明确写 SQL。
5. **不要给 Spider_XHS 升级**：上游在改签名算法，当前 commit 是能跑的，升级大概率弄坏。
6. **pending 任务的顺序建议**：先做 P0 的 #1（点博主看笔记）和 #2（@ 注入），这俩一做完体验跨越巨大；然后 P2 #9（BLOGGER_BRIEF 自动人设注入），因为这是"项目化 + 数据库"两大目标最关键的粘合点。

---

**最后的 sanity check**（当前版本自测过）：
- ✅ `docker run maven:3.9-eclipse-temurin-17 mvn -B test` → 113 tests, 0 failures
- ✅ `pnpm typecheck` / `pnpm build` → 0 errors
- ✅ 登录 `admin / Yyanyyan@666`
- ✅ XHS Cookie 页面能看到 1 条 ACTIVE cookie，字段齐全（a1 / web_session / webId / xsecappid）
- 🔄 **未完成冒烟（需要人肉到真机跑一次）**：
  - `/xhs-cookies` → 扫码登录采集 → 手机扫码 → 4 条 QR_LOGIN 来源的 cookie 入池
  - 进任意项目的 GENERAL 会话 → 第一句话问 "我们项目现在在做什么、roster 里都有谁" → AI 无需调工具即可基于 system prompt 回答
- ✅ 博主数据库有博主 "小张真不熬夜了"，已落 5 条真实笔记（`creator_posts` 表 id 4-8）
- ✅ 项目 #2 "小红书春季种草 · Demo" 下有 2 个会话（BLOGGER_BRIEF + ALLOCATION），博主 1 已在 roster
- ✅ `/api/v1/creators/export.xlsx` 返回 4 KB xlsx，30 列
- ✅ `creators/accounts/2/refresh:xhs` 能真实抓到小红书笔记数据并落库
