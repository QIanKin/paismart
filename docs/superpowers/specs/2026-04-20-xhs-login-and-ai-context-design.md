# 2026-04-20 · 登录式 Cookie 采集 + AI 博主库掌握 / BLOGGER_BRIEF 自动画像注入

> Status: **Draft**, waiting for user review
> Owners: PaiSmart backend + frontend
> Related: `docs/HANDOFF.md` § 五 P0 #1/#2/#3, P2 #9, P3 #13
> Scope: **A + D 一轮交付**。B / C / E / F 留给后续 spec。

---

## 1. 目标

把广告公司工作台里"博主 cookie 谁维护、怎么维护"和"AI 是不是真的懂博主"这两个关键痛点一次解决：

### A · 登录式 Cookie 采集
取代现在"打开 DevTools 复制粘贴 a1 / web_session / webId"的人工流程。用户在 PaiSmart 页面点"扫码登录"，后端拉起 Chromium，把 xhs 登录页 QR 码推到前端，用户手机扫码 → 系统自动在四个 xhs 子域（主站 / 创作者 / 蒲公英 / 千帆）捞齐 cookie 并按 `platform` 分别入 `xhs_cookies` 表。

### D · AI 博主库掌握 + 自动画像注入
- 默认 system prompt 明确告诉 AI "你能随时 `creator_search` / `creator_screen` / `project_roster_list` / `creator_get` / `creator_get_posts` 查整个博主库与任何项目名册"。
- 任何项目下的会话，system prompt 自动注入 `<PROJECT_CONTEXT>` 片段（项目元信息 + 名册 top 10 + stage 分布）。
- `BLOGGER_BRIEF` / `DATA_TRACK` / `CONTENT_REVIEW` 会话且 `creator_id` 非空时，再注入 `<CREATOR_CONTEXT>` 片段（博主人设、标签、近 10 条笔记数据）。
- 不新增工具，只改造 `ContextEngine`。

### 非目标（本 spec 不做）
- 项目级"本项目视频链接"抓取（子项目 C，下一 spec）。
- Cookie 到期自动预警 / 轮换降权（子项目 B）。
- `@博主 / @项目` 快捷注入（子项目 E）。
- 代码全量 review（子项目 F）。

---

## 2. 总体架构

### 2.1 A 的数据流

```
[前端] xhs-cookies 页 → 点"扫码登录"
        │
        │ POST /api/v1/admin/xhs-cookies/qr-login
        ▼
[后端] XhsLoginSessionService.create(orgTag, userId)
        │
        ├─ 并发闸（同 org 一次只能一个活跃会话）
        ├─ 生成 session_id (UUID)，写 xhs_login_sessions (status=PENDING)
        ├─ 异步启 LoginBrowserRunner
        │     └─ 用 BashExecutor 起：node skills-bundled/xhs-qr-login/run.mjs --session <id>
        │          1. chromium.launch({ headless:'new', args:[--no-sandbox] })
        │          2. page.goto('https://www.xiaohongshu.com/login')
        │          3. 抓 QR canvas.toDataURL → stdout: {"event":"qr_ready","dataUrl":"..."}
        │          4. 5 min 内 QR 可能刷新：监听 DOM 变化再发一次 qr_ready
        │          5. 轮询 cookies：出现 web_session = 登录成功
        │             stdout: {"event":"scanned"} → {"event":"confirmed"}
        │          6. 逐域 goto creator.xiaohongshu.com / pgy.xiaohongshu.com / ad.xiaohongshu.com
        │             每捞到一个：{"event":"platform_captured","platform":"xhs_pgy"}
        │          7. 最终：{"event":"success","payloads":[{platform,cookieString}...]}
        └─ LoginStreamBridge 逐行消费 stdout：
              ├─ 转成 WS 事件广播给 /ws/xhs-login/{sessionId}
              └─ success 时调 XhsCookieService.bulkUpsertFromLogin(...)

[前端] Modal 显示 QR + 进度 + 最终结果，关弹窗刷新 cookie 列表
```

### 2.2 D 的注入链路

```
AgentRuntime.prepareRequest
    └─ ContextEngine.assembleSystemPrompt(session, project)
         ├─ 原有：全局指令 + skill 指南 + 工具目录
         ├─ ✨ ProjectContextProvider                       ← 每个会话都注入
         │    ├─ 60s TTL Caffeine, key=projectId
         │    └─ 片段 ~400~800 tokens
         └─ ✨ CreatorProfileContextProvider                ← 仅 creator 绑定类会话
              ├─ 60s TTL Caffeine, key=(creatorId, orgTag)
              └─ 片段 ~1200~2000 tokens
```

默认 system prompt 最末补一句：

> 你可以随时调用 `creator_search` / `creator_screen` / `creator_get` / `creator_get_posts` / `project_roster_list` / `project_roster_add` 查询整个博主数据库和任何项目的名册。**遇到选人、对比、追数据请主动查，不要让用户告诉你。**

---

## 3. 组件分解

### 3.1 A · 后端新组件

| 文件 | 职责 |
|---|---|
| `model/xhs/XhsLoginSession.java` (new) | JPA 实体 |
| `repository/xhs/XhsLoginSessionRepository.java` (new) | |
| `service/xhs/XhsLoginSessionService.java` (new) | 生命周期管理 / 并发闸 / 超时清理 / 重启恢复 |
| `service/xhs/LoginBrowserRunner.java` (new) | 封装 node 子进程调用 skill，提供逐行 `Flux<JsonNode>` 输出 |
| `service/xhs/LoginStreamBridge.java` (new) | 消费 runner 输出 → 更新 session 状态 + WS 推送 + cookie 入库 |
| `handler/XhsLoginWebSocketHandler.java` (new) | `/ws/xhs-login/{sid}` 订阅/心跳 |
| `controller/admin/XhsLoginController.java` (new) | REST 入口，`/api/v1/admin/xhs-cookies/qr-login` 下 |
| `config/XhsLoginProperties.java` (new) | 配置项（超时、Chromium 启动参数、feature flag）|
| `service/xhs/XhsCookieService.java` | 加 `bulkUpsertFromLogin` 方法；加 `source` / `loginSessionId` 字段处理 |
| `model/xhs/XhsCookie.java` | 加 `source`, `loginSessionId` 列 |

### 3.2 A · 新 skill

```
skills-bundled/xhs-qr-login/
├── skill.yaml             # name=xhs-qr-login, loadMode: private, runner: node
├── README.md              # 给 AI 和运维读，说明怎么被后端调用
├── run.mjs                # 主脚本，依赖 playwright
└── package.json           # { "dependencies": { "playwright": "1.47.x" } }
```

- **放在 `skills-bundled/` 只为了和其他 node + playwright 脚本保持目录约定**，并不通过 `SkillRegistry` 注册给 AI。
- **为什么不走现有的 skill runner**：这个流程强绑定 WS 推送、session 生命周期、cookie 入库，走正规 `BrowserSkillRunner` 会丢掉流式事件。`LoginBrowserRunner` 直接 fork + 逐行解析 stdout 更干净。
- **依赖**：复用 `skills-bundled/_shared/playwright-runtime/node_modules`（已有），但 `playwright@1.47` 的 chromium 要装到 `~/.cache/ms-playwright` —— Docker 镜像 build 阶段加一次 `npx playwright install chromium --with-deps`。

### 3.3 A · 前端

| 文件 | 变化 |
|---|---|
| `frontend/src/views/xhs-cookies/index.vue` | 顶部行加"扫码登录"主按钮；旧"添加 Cookie"降级为次按钮（依然保留，兜底） |
| `frontend/src/views/xhs-cookies/modules/qr-login-modal.vue` (new) | 登录弹窗：QR 图、倒计时、进度条、四个平台勾选状态、错误 |
| `frontend/src/service/api/xhs-cookie.ts` | 加 `fetchXhsLoginCreate` / `fetchXhsLoginStatus` / `fetchXhsLoginCancel` |
| `frontend/src/service/websocket/xhs-login.ts` (new) | WS 客户端封装（断线重连 + 兜底 REST 轮询） |
| `frontend/src/typings/api.d.ts` | 加 `Api.Xhs.LoginSession`、`Api.Xhs.LoginEvent` 联合类型 |

### 3.4 D · 后端

| 文件 | 变化 |
|---|---|
| `service/agent/context/ContextEngine.java` | 加两个 provider 注入点 |
| `service/agent/context/ProjectContextProvider.java` (new) | 生成 `<PROJECT_CONTEXT>` 片段 |
| `service/agent/context/CreatorProfileContextProvider.java` (new) | 生成 `<CREATOR_CONTEXT>` 片段 |
| `service/agent/context/ContextCache.java` (new) | 共用 Caffeine 缓存包装 |
| `resources/prompts/default-system.md`（若无则新建） | 末尾加"主动查博主库"那段 |
| `resources/prompts/blogger-brief-system.md`（若无则新建） | BLOGGER_BRIEF 专用片段模板 |

### 3.5 D · 前端

无。D 是纯后端改造，前端不感知。

---

## 4. 数据模型

### 4.1 新表 `xhs_login_sessions`

```sql
CREATE TABLE xhs_login_sessions (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id            VARCHAR(64)  NOT NULL UNIQUE,
  owner_org_tag         VARCHAR(64)  NOT NULL,
  created_by_user_id    VARCHAR(64)  NOT NULL,
  status                VARCHAR(16)  NOT NULL COMMENT 'PENDING/QR_READY/SCANNED/CONFIRMED/SUCCESS/FAILED/EXPIRED/CANCELLED',
  qr_data_url           TEXT         NULL,
  captured_platforms    VARCHAR(128) NULL,
  missing_platforms     VARCHAR(128) NULL,
  error_message         VARCHAR(255) NULL,
  started_at            DATETIME     NOT NULL,
  finished_at           DATETIME     NULL,
  expires_at            DATETIME     NOT NULL,
  INDEX idx_org_status (owner_org_tag, status),
  INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.2 `xhs_cookies` 加列

```sql
ALTER TABLE xhs_cookies
  ADD COLUMN source             VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/QR_LOGIN/SEED',
  ADD COLUMN login_session_id   VARCHAR(64)  NULL;

UPDATE xhs_cookies SET source = 'MANUAL' WHERE source IS NULL;
```

迁移文件：`docs/db-migration-2026-04-xhs-login.sql`，幂等（用 `information_schema.COLUMNS` 判断是否已加）。

### 4.3 既有表读取

D 只读，涉及：
- `agent_projects`、`project_creators`（名册）
- `creators`、`creator_accounts`、`creator_posts`

无模型变更。

---

## 5. API 契约

### 5.1 POST `/api/v1/admin/xhs-cookies/qr-login`

**Request**
```json
{ "platforms": ["xhs_pc","xhs_creator","xhs_pgy","xhs_qianfan"] }
```
`platforms` 可省略，省略等于全要。

**Response 201**
```json
{
  "sessionId": "3f9b6c30-...",
  "wsUrl": "/ws/xhs-login/3f9b6c30-...",
  "expiresAt": "2026-04-20T14:10:00",
  "platforms": ["xhs_pc","xhs_creator","xhs_pgy","xhs_qianfan"]
}
```

**Response 409**（本 org 已有活跃会话）
```json
{ "code":"LOGIN_IN_PROGRESS", "sessionId":"<existing>", "wsUrl":"..." }
```
前端直接复用返回的 sessionId 续上。

### 5.2 GET `/api/v1/admin/xhs-cookies/qr-login/{sessionId}`

返回当前 status + `qrDataUrl`（如还在有效期）+ `capturedPlatforms` + `missingPlatforms` + `errorMessage`。WS 断线兜底用。

### 5.3 DELETE `/api/v1/admin/xhs-cookies/qr-login/{sessionId}`

状态变 `CANCELLED`，kill Chromium 进程。返回 204。

### 5.4 WS `/ws/xhs-login/{sessionId}`

消息格式 `{type, payload, ts}`：

| type | payload 字段 | 什么时候发 |
|---|---|---|
| `qr_ready` | `dataUrl`, `expiresIn` | QR 截出来 / 刷新 |
| `scanned` | — | 轮询发现用户扫了 |
| `confirmed` | — | web_session 出现 |
| `platform_captured` | `platform` | 每成功一个子域 |
| `success` | `capturedPlatforms`, `missingPlatforms` | 全部结束 |
| `failed` | `errorMessage` | 任何致命错误 |
| `expired` | — | 整个会话 10 分钟超时 |

### 5.5 WS 握手

和现有 `/ws/chat` 保持一致：JWT 放 URL query 参数（`?token=...`）或 `Sec-WebSocket-Protocol` header，`XhsLoginWebSocketHandler` 解析 JWT 后校验 `orgTag` 匹配 `xhs_login_sessions.owner_org_tag`，不匹配直接关闭 connection。

### 5.6 权限

所有上述接口默认需要 `ROLE_ADMIN`（由 `SecurityConfig` 拦截），后续如果想放给各 org leader，加一个 role 即可。

---

## 6. AI 注入片段模板

### 6.1 `<PROJECT_CONTEXT>`

```
━━━━ <PROJECT_CONTEXT> ━━━━
项目 #{id}: {name}
  类目: {categoryMain} / 人群: {audience}
  brief 核心诉求: {briefDigest}  (<=80 字，若无则写"未对齐")
  时间窗口: {startDate} ~ {endDate}
名册 {rosterSize}/{rosterBudget} 人 · stage 分布:
  CANDIDATE {x} / SHORTLISTED {x} / LOCKED {x} / SIGNED {x} / PUBLISHED {x} / SETTLED {x}
Top 10 博主 (按 stage 权重 + followers 排序):
  - {nickname}  粉丝{followers}  均赞{avgLikes}  赛道{track}  stage={stage}
  - ...
━━━━━━━━━━━━━━━━━━━━━━━━━
```

### 6.2 `<CREATOR_CONTEXT>`（仅 BLOGGER_BRIEF / DATA_TRACK / CONTENT_REVIEW）

```
━━━━ <CREATOR_CONTEXT> ━━━━
博主 #{id}: {nickname}  ({realName})
  性别: {gender}  出生年: {birthYear}  城市: {city}
  赛道标签: {trackTags}
  人设标签: {personaTags}
  合作状态: {cooperationStatus}   报价备注: {priceNote}
  历史合作次数: {cooperationCount}  平均 ROI: {avgRoi}  内部备注: {internalNotes}
账号:
  - xhs @{handle}  粉丝{followers}  均赞{avgLikes}  互动率{engagementRate}%  命中率{hitRatio}%
  - ...
近 10 条笔记（按发布时间倒序）:
  [{publishedAt}] {title}  点赞{likes} 评论{comments} 收藏{collects} 分享{shares} 曝光{views}
  ...
━━━━━━━━━━━━━━━━━━━━━━━━━
```

字段缺失时显示 `—`，不整行删（保持稳定结构方便 AI 对齐）。

### 6.3 Token 预算

- `<PROJECT_CONTEXT>`: 按 400~800 tokens 上限，名册 top 超过 10 人就截断
- `<CREATOR_CONTEXT>`: 按 1200~2000 tokens 上限，账号超过 3 个、笔记超过 10 条就截断
- 两段合计 ≤ 3000 tokens，占 DeepSeek-V3 上下文窗口 (64k) 的 <5%

---

## 7. 错误处理矩阵

| 场景 | 处理 | 最终状态 |
|---|---|---|
| 用户 10 min 未完成扫码 | session `expired`；Chromium kill | EXPIRED |
| xhs QR 本身 ~5 min 过期 | skill 捕获 DOM → 点"刷新"按钮 → 再发 `qr_ready` | 继续 PENDING→QR_READY 循环 |
| 扫码后某个子域跳登录 / 无权限 | skill 标 `missing.push(platform)`；其他域继续；最终 `success` 但 missing 非空 | SUCCESS (部分) |
| Chromium 崩溃 / node 退出 | Java 侧 `Process.waitFor()` 捕获非 0 退出码，session 标 `FAILED` | FAILED |
| 后端重启 | 启动时 `XhsLoginSessionService.cleanupOnStartup()` 把 PENDING/QR_READY/... 全部转 FAILED(reason=backend_restart) | FAILED |
| WS 掉线 | 客户端走 REST 轮询 GET /qr-login/{sid}；10 秒一次 | 透明 |
| 同 org 已有活跃会话 | 409 + existingSessionId，前端复用 | - |
| bulkUpsert 写库报错 | 事务回滚，session FAILED(reason=db_write_failed) | FAILED |
| xhs 风控检测到 headless | skill 用 `--disable-blink-features=AutomationControlled` + `playwright-extra-stealth` 前置；真风控进入就 fallback 到 failed 让用户重试或手动粘贴 | 报告 |

---

## 8. 安全 & 审计

- **Cookie 明文**：仅存在 Chromium 进程内存 + XhsCookieService 入库时的短暂内存。WS / REST / 日志一律不出明文。
- **`cookie_preview`**：顺手应用 HANDOFF P3 #13，只保留前 16 + 后 4 字符，中段 mask 成 `...`。
- **审计**：`xhs_login_sessions` 全量留痕，保留 `created_by_user_id`。
- **feature flag**：`smartpai.xhs-login.enabled`（默认 `true`），关闭后 API 返回 501，前端按钮隐藏。

---

## 9. Docker / 运行环境变更

### 9.1 Dockerfile
- 在 apt 阶段加：`libnss3 libnspr4 libatk1.0-0 libcups2 libxss1 libgbm1 libasound2 libxtst6 libxshmfence1 libxkbcommon0`（playwright chromium 运行时依赖）。
- 在应用构建后、启动前执行一次：
  ```
  cd /app/skills-bundled/xhs-qr-login \
    && npm install --omit=dev \
    && npx playwright install chromium
  ```
- 镜像增重约 330 MB（chromium + 库），可接受。

### 9.2 docker-compose.prod.yml
- `backend` service 加 `shm_size: '1gb'`（Chromium 共享内存需求）。
- 不需要 sidecar，不需要 noVNC。

### 9.3 Spring 配置
```yaml
smartpai:
  xhs-login:
    enabled: true
    session-timeout-minutes: 10
    chromium:
      headless: true
      extra-args: ["--no-sandbox","--disable-dev-shm-usage","--disable-blink-features=AutomationControlled"]
```

---

## 10. 测试计划

### 10.1 后端单元
- `XhsLoginSessionServiceTest`
  - `createSession_concurrentSameOrg_returnsExisting`
  - `cleanupOnStartup_marksActiveAsFailed`
  - `expireTimer_triggersStatusExpired`
- `LoginStreamBridgeTest`
  - `parseEvent_validJsonLine_updatesSession`
  - `parseEvent_garbledOutput_ignoresLine`
- `XhsCookieServiceTest`
  - `bulkUpsertFromLogin_newOrg_insertsAll`
  - `bulkUpsertFromLogin_existingPlatform_updatesInPlace`
  - `bulkUpsertFromLogin_missingRequiredFields_skipsRow`
- `ProjectContextProviderTest`
  - `render_emptyRoster_producesStableFallback`
  - `render_largeRoster_truncatesToTop10`
- `CreatorProfileContextProviderTest`
  - `render_multipleAccounts_limitsTo3`
  - `render_noPosts_producesDashLine`

### 10.2 后端集成
- `@SpringBootTest` + `@Tag("slow")`
- `XhsLoginFlowIntegrationTest`
  - 把 `LoginBrowserRunner` mock 成发 `qr_ready` → `success` 的 stub
  - 打全流程 REST + WS 事件断言

### 10.3 前端
- `qr-login-modal.vue` 组件测试（vitest + @vue/test-utils）
  - WS 事件接收 → 状态机过度
  - 断线 → 降级到 REST 轮询
- `typecheck` + `eslint` 必须绿

### 10.4 手测（纳入 HANDOFF § 最后 sanity check）
- 登录 admin → xhs-cookies 页 → 扫码登录 → 手机扫 xhs 码 → 四个平台全绿 → `xhs_cookies` 表有 4 条 `source=QR_LOGIN` 记录。
- 建 BLOGGER_BRIEF 会话选"小张真不熬夜了" → 第一条消息 AI 回复明显带博主画像 / 数据 / 赛道意识（不需要用户告诉它博主是谁）。
- 建 ALLOCATION 会话 → system prompt 只含 PROJECT_CONTEXT 不含 CREATOR_CONTEXT。

---

## 11. 回滚 & 迁移

- DB 迁移脚本 `docs/db-migration-2026-04-xhs-login.sql` 幂等，可重复执行；不需要 down script（新表删了也不影响老功能）。
- feature flag 关掉 = A 全部功能回退到旧的"手动粘贴" UI。
- D 的 ProviderChain 里所有 provider 都有空兜底（查不到就 skip 片段），关不关等同于少注入一段文本，不会让对话崩。
- 首次部署后第一件事：跑现有 HANDOFF sanity check（admin 登录、cookie 页、博主数据库、export.xlsx）。

---

## 12. 开放问题 / 待实现时再定

- xhs 风控检测 headless 概率多大？需要实装后实测，若 >20% 考虑加入 `playwright-extra-stealth`。
- `shm_size 1gb` 在用户的宿主机上 OK 吗？等部署环境确认后再调（可以降到 512m，chromium 单页够用）。
- token 预算里 `<CREATOR_CONTEXT>` 上限 2000 tokens 是估算，实装后测 3 次取实际均值再调。

---

## 13. 下一步

此 spec 提交用户 review 后，进入 `writing-plans` skill 产出分步实现计划。
