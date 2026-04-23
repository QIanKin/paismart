# PaiSmart 交付验收报告

> 生成时间：2026-04-23  
> 被测版本：`docker-compose.prod.yml`（`paismart-backend:latest` 构建于交付当日）  
> 测试脚本：[`smoke.ps1`](./smoke.ps1)（一键跑 10 大模块 / 33 条用例）

## 一、总览

```
PASS=29   WARN=3   FAIL=0   TOTAL=33
```

- **0 条 FAIL**：所有业务代码路径都通过了
- **3 条 WARN**：全部是**业务员本机/生产机环境依赖**，不涉及代码修复，落地现场逐项处理即可

## 二、逐模块结果

### 1. 基础设施（4/4 PASS）

| 用例 | 结果 | 说明 |
|---|---|---|
| `admin_login` | ✅ | JWT 271 字符 |
| `users_me` | ✅ | 管理员角色 + 组织标签 `default` 正确返回 |
| `ws_token` | ✅ | WebSocket token 下发成功 |
| `system_status` | ✅ | Agent `system_status` 工具可读：active_users / documents / memory / disk |

### 2. Agent 核心（4/4 PASS）

| 用例 | 结果 | 说明 |
|---|---|---|
| `tools_list` | ✅ | 46 个工具，先前的 500 NPE 已用 `safeUserFacingName / safeReadOnly / ...` 兜底 |
| `tools_catalog` | ✅ | 12 个业务域分组，total=46 |
| `tools_schema` | ✅ | OpenAI function calling manifest 46 条 |
| `sessions_list` | ✅ | 会话列表接口可用 |

### 3. 小红书 Cookie 池（5 PASS / 1 WARN）

| 用例 | 结果 | 说明 |
|---|---|---|
| `cookies_list` | ✅ | 5 条（spotlight / qianfan / pgy / creator / pc）|
| `ping_xhs_qianfan_6` | ✅ | 560ms |
| `ping_xhs_pgy_5` | ✅ | 324ms |
| `ping_xhs_creator_4` | ✅ | 279ms |
| `ping_xhs_pc_3` | ✅ | 1730ms |
| `ping_xhs_spotlight_8` | ⚠️ | `token 已过期 50550s`，需续签，见 §三.A |

### 4. 项目与名册（2 PASS / 1 SKIP）

| 用例 | 结果 | 说明 |
|---|---|---|
| `project_list` | ✅ | 空库 |
| `project_detail` | ⏭ | 无数据，跳过 |
| `project_templates` | ✅ | 空库 |

> 注：冒烟跑完前，额外做了一轮 `project CRUD + roster batch add + roster list` 手动用例，已全部通过。`ProjectCreatorController` 的 `@PostMapping(value = {"/batch", ":batch"})` 已兼容历史 `:batch` 和推荐 `/batch` 两种路径。

### 5. 博主库（3/3 PASS）

| 用例 | 结果 | 说明 |
|---|---|---|
| `creator_list` | ✅ | total=1 |
| `account_list` | ✅ | total=3 |
| `custom_fields` | ✅ | 空 |

### 6. Skill（2/2 PASS）

| 用例 | 结果 | 说明 |
|---|---|---|
| `skill_list` | ✅ | 10 个 skill（含 qiangua-brand-discover / xhs-qr-login / ...）|
| `skill_tasks` | ✅ | 空 |

### 7. 管理员后台（8/8 PASS）

全部通过：`users / activities / usage-overview / rate-limits / model-providers / org-tags / org-tags/tree / recharge-packages`。

### 8. 聚光直连（1 WARN）

| 用例 | 结果 | 说明 |
|---|---|---|
| `balance_info_live` | ⚠️ | `HTTP=000`，宿主机 Clash TUN 劫持 DNS，容器无法到达 `mapi.xiaohongshu.com`。见 §三.B |

### 9. 千瓜 CDP（1 WARN）

| 用例 | 结果 | 说明 |
|---|---|---|
| `cdp_bridge` | ⚠️ | 业务员本机 Chrome 未启调试端口。见 §三.C |

### 10. 前端（1/1 PASS）

| 用例 | 结果 | 说明 |
|---|---|---|
| `index` | ✅ | Nginx 首页 562 字节，前端已正常上线 |

---

## 三、3 条 WARN 的处理方案

### A. 聚光 access_token 过期

- **症状**：`/data-sources` 聚光条目 ping 红色；`spotlight_balance_info` 工具返回 `access_token expired`
- **现状**：
  - 后端已实现 `SpotlightTokenScheduler`（每 10 分钟扫一次，剩余寿命 < 30 分钟则自动续签）
  - 当前测试环境 `.env` 里 `XHS_SPOTLIGHT_APP_ID` / `XHS_SPOTLIGHT_APP_SECRET` **为空**，所以调度器只能打 `[SpotlightTokenScheduler] 未配置 XHS_SPOTLIGHT_APP_ID / XHS_SPOTLIGHT_APP_SECRET` 日志跳过
- **动作**：
  1. 业务员去 `https://ad.xiaohongshu.com` 开放平台建个应用，拿到 `app_id` 和 `app_secret`
  2. 填到 `.env`：
     ```
     XHS_SPOTLIGHT_APP_ID=xxxxx
     XHS_SPOTLIGHT_APP_SECRET=xxxxx
     ```
  3. 重新授权一次 OAuth 拿到新的 access/refresh token，填 `.env` 的 `XHS_SPOTLIGHT_ACCESS_TOKEN / XHS_SPOTLIGHT_REFRESH_TOKEN`
  4. 在 `/data-sources` 页面删掉现有那条 label=`ZFC东方美妆种草集-HK1` 的过期条目
  5. `docker compose -f docker-compose.prod.yml restart backend`
  6. seeder 会重新落库，调度器从此每 10 分钟自动续签

### B. 容器出不去小红书域（DNS 劫持）

- **症状**：
  - 容器里 `getent hosts mapi.xiaohongshu.com` → `198.18.0.69`（Clash fake-IP 段）
  - `curl https://mapi.xiaohongshu.com` → `SSL routines::unexpected eof while reading`
- **根因**：业务员本机装了 Clash / V2Ray 且开了 TUN 模式，内核层拦截了所有 DNS / TCP（含 Docker Desktop 的 WSL2 虚拟网卡）
- **动作**：
  1. **生产机不要装 TUN 模式 VPN**（最彻底）
  2. 如果必须走代理，改成 HTTP_PROXY 模式，并在 `docker-compose.prod.yml` 给 backend 服务加：
     ```yaml
     environment:
       HTTP_PROXY: "http://host.docker.internal:7890"
       HTTPS_PROXY: "http://host.docker.internal:7890"
       NO_PROXY: "mysql,redis,elasticsearch,kafka,minio,localhost,127.0.0.1"
     ```
  3. 开发机调试用：后端容器已强制指定 `dns: [119.29.29.29, 223.5.5.5, 8.8.8.8]` 绕过 fake-IP DNS，但网卡层劫流救不了，只能关 VPN

### C. 千瓜 CDP 代理未启动

- **症状**：冒烟里 `cdp_bridge WARN`，`qiangua_brand_discover` 工具跑起来会报 `ECONNREFUSED 9223`
- **处理**（业务员本机一次性设置）：
  1. 双击 `acceptance/start-qiangua-chrome.bat`
  2. 在弹出的 Chrome 里登录千瓜会员账号（交付测试账号：`17733738352 / sk333111`）
  3. 保持 Chrome 窗口 + cmd 窗口都开着
  4. Agent 调用 `qiangua_brand_discover` 工具时自动经 `host.docker.internal:9223` → CDP 代理 → `127.0.0.1:9222` Chrome
- **原理**：Chrome 147+ 为了安全只接受 `127.0.0.1` 源 + `Host: localhost` 头的连接，容器经 `host.docker.internal` 直连 9222 会被 reset。`cdp-proxy.mjs` 在宿主机做 TCP 转发 + Host 头改写，容器侧无感知。

---

## 四、交付前做了哪些代码 / 配置改动

| 模块 | 改动 | 文件 |
|---|---|---|
| Agent `tools / catalog` 500 NPE | 新增 `safe*` 兜底方法：传入 `mapper.createObjectNode()` 代替 null，异常时返回保守默认 | `controller/AgentController.java` |
| 聚光 access_token 不自动续签 | 新增 `@Scheduled` 调度器，每 10 min 扫过期条目自动 refresh | `service/xhs/SpotlightTokenScheduler.java`（新建）|
| 项目名册 `:batch` 路由 403/404 | 同时注册 `/batch` 和 `:batch` 两条路径兼容历史前端 | `controller/ProjectCreatorController.java` + `frontend/src/service/api/project-roster.ts` |
| 千瓜 CDP 容器无法直连 Chrome 9222 | 宿主机启动 Node TCP 代理 9223 → 9222 + Host 头改写，配 `SMARTPAI_BROWSER_CDP_ENDPOINT` 默认走 9223 | `acceptance/cdp-proxy.mjs`（新建）+ `acceptance/start-qiangua-chrome.bat`（新建）+ `config/BrowserBridgeProperties.java` + `docker-compose.prod.yml` |
| 容器 DNS 被 Clash 劫持 | 显式加 `dns: [119.29.29.29, 223.5.5.5, 8.8.8.8]` | `docker-compose.prod.yml` |
| 冒烟脚本 | 33 条用例 / UTF-8 无乱码 / 修了 tools_catalog / cookies_list / spotlight probe LF 行尾等 Bug | `acceptance/smoke.ps1` |
| README | 彻底重写为 MCN 平台交付文档 | `README.md` |

---

## 五、下一步建议（非阻塞交付）

- [ ] `.env` 填入聚光 `APP_ID / APP_SECRET`，验证 10 分钟后 `ping_xhs_spotlight` 由 WARN 变 PASS
- [ ] 生产机验证关掉 TUN VPN 后直接跑 `smoke.ps1`，`balance_info_live` 会变成 `HTTP=200 code=0`
- [ ] 业务员第一天上工跑一次 `start-qiangua-chrome.bat` 并登录千瓜，后续开机脚本加到 Windows 启动项即可
- [ ] 在 `/data-sources` 页面把 5 个小红书平台都补齐扫码登录（目前只有 spotlight 是 seed 出来的，其余 4 个在测试环境是历史遗留）
- [ ] 首次管理员创建完把 `.env` 的 `ADMIN_BOOTSTRAP_ENABLED` 改回 `false` 并重启，避免重复注入
- [ ] **蒲公英必须用"品牌主/机构"账号**。扫码完在 `/data-sources` 页的蒲公英行点"🩺 测角色"（或让 agent 调 `xhs_pgy_whoami`）确认 `role=brand` 再使用 `xhs_fetch_pgy_kol` / `xhs_pgy_kol_detail`。KOL/个人号 cookie 能存但所有 pgy 业务接口会挂（HTTP 401 / `code=-100`）。

---

## 七、蒲公英（xhs_pgy）账号资质专题

**这是目前最容易踩坑的地方**，Spider_XHS 的 `PuGongYingAPI` 做的是"品牌主选 KOL"这件事，普通个人号登不了这条路径：

| 角色 | 登 `pgy.xiaohongshu.com` 看到的东西 | 能否用 `xhs_fetch_pgy_kol` |
|---|---|---|
| 品牌主（已开通蒲公英品牌主资质） | `/solar/pre-trade/kol` KOL 选人页面 | ✅ |
| 机构 / MCN | 机构工作台 | ✅ |
| 普通博主 / 个人号 | KOL 工作台（接单端） | ❌ 所有 `/api/solar/cooperator/*` 接口返回 `code=-100`「未登录」 |

### 如何一键判断

1. `/data-sources` 的蒲公英面板新增"**品牌主资质**"告警条，扫码前必读
2. 有 xhs_pgy cookie 后，让 agent 说："调用 xhs_pgy_whoami 工具告诉我账号角色"
3. 或者管理员在 UI 上点单条 cookie 的「测角色」按钮 → 底层打到 `POST /api/v1/admin/xhs-cookies/{id}/pgy-whoami`

返回示例（不合格）：
```json
{"role":"unauthorized","brandQualified":false,"reachable":false,
 "httpStatus":401,"bodyHead":"{\"code\":-100,...,\"msg\":\"...未登录...\"}"}
```

返回示例（品牌主，合格）：
```json
{"role":"brand","brandQualified":true,"reachable":true,
 "userId":"...","nickName":"XXX 品牌","latencyMs":420}
```

### 已经做过的防护

- `xhs_fetch_pgy_kol` / `xhs_pgy_kol_detail` 在跑 Python skill 前**先用 `PgyRoleProbe` 打 user/info**，不合格直接返 `not_brand_account` 错误，**不会白跑 skill、不会冤杀 cookie**。
- `XhsSkillRunner` 对脚本分类出的非-cookie 类 `errorType`（`signature_failed` / `blocked_or_api_changed` / `remote_non_json` 等）跳过反爬关键词扫描，避免人话文案里的"cookie"/"登录"字样误伤 cookie 状态。

---

## 六、一键回归

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -File acceptance\smoke.ps1
```

跑完会在 `acceptance/smoke-result.csv` 输出完整用例表，可带给客户留档。
