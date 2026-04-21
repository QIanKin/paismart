# 2026-04-21 · 数据源中心统一化 + 多平台一键登录

> Status: **Draft → In progress**
> Owners: PaiSmart 前后端
> Related: `docs/HANDOFF.md` § 三.5 / § 五, `docs/superpowers/specs/2026-04-20-xhs-login-and-ai-context-design.md`
> Follow-up to: 2026-04-20 的 QR 登录，本轮完成它未覆盖的"UI 统一 + 多平台"部分

---

## 0. 用户痛点（原话）

> "现在到底有几个数据抓取手段？前后端显示要一致。我根本没有看到任何扫码登录的东西。总之要使用最简单方便。"

诊断：

1. `/xhs-cookies` 和 `/data-sources` **两个页面功能重叠**，扫码登录只挂在前者，后者（截图用户看到的这个）完全没有扫码入口。
2. 菜单同时出现两条入口，用户不知道该点哪个。
3. `qr-login-modal.vue` 被写死在 `views/xhs-cookies/modules/` 下，无法复用。
4. 聚光 OAuth 面板让用户手贴 `accessToken` JSON——对业务员就是"天书"。
5. 千瓜登录根本没有 UI 入口，只有 agent skill 在暗处跑。
6. 前端术语混乱："Cookie" / "凭证" / "数据源" 三个词同页出现。

---

## 1. 目标（本 spec 的 scope）

### P0 · 必做（本轮交付，~1 天工作量）

1. **消灭 `/xhs-cookies` 路由 + 页面**，以 `/data-sources` 为唯一入口，菜单同步清理
2. **把扫码登录（QR）接入 `data-sources/xhs-web-panel.vue`**，主按钮永远是"扫码登录采集"，"手动录入"降级为折叠项
3. `qr-login-modal.vue` 搬到 `views/data-sources/modules/_shared/` 成为可复用组件
4. 统一术语为"**登录 / 凭证**"，去掉 "Cookie" 这个技术词对业务员暴露
5. 中英文 i18n 键统一
6. 全量 `pnpm typecheck` + `mvn test` 通过

### P1 · 本轮带做（1-2 天）

7. 在 `xhs-web-panel` 顶部加 **连通性一键自测** 按钮（调 `/admin/xhs-cookies/ping`，后端新增一个轻量端点用现有 cookie 打一条 `/api/sns/web/v1/user/me` 验活）
8. 聚光面板：把"手贴 accessToken"改成 **"使用 ZFC 聚光账号授权"** 按钮。点了之后：
   - 如果后端 `.env` 配了 `XHS_SPOTLIGHT_APP_ID / APP_SECRET`：直接走 OAuth2 授权码流（`oauth.URL()` → 新窗 → callback）
   - 如果没配：显示友好的"运维先配 AppID"引导，不暴露裸的 token 粘贴框

### P2 · 留给下一 spec（本轮不做）

- 千瓜服务端无头登录（短信验证码往返 UX 比较重，单独 spec）
- Agent 的 cookie 管理工具包（T1/T2 权限，单独 spec）
- 竞品 Supabase 面板的"测试连接"按钮
- HANDOFF.md 与 README.md 的对齐清洗

---

## 2. 总体架构（变化部分）

### 2.1 前端路由 / 菜单

```
移除：
  src/views/xhs-cookies/*                        （整目录）
  router/elegant imports.ts 里 xhs-cookies 条目
  i18n zh-cn.ts / en-us.ts 里 xhs-cookies 相关键
保留并扩展：
  src/views/data-sources/                         （唯一数据源 UI）
    index.vue                                     （tabs 容器，不动）
    modules/
      _shared/
        qr-login-modal.vue                        （从 xhs-cookies/modules/ 搬来）
        platform-labels.ts                        （原常量抽成模块）
      xhs-web-panel.vue                           （+ 扫码登录主按钮 + 连通性自测）
      xhs-spotlight-panel.vue                     （+ OAuth 授权按钮）
      xhs-competitor-panel.vue                    （不动）
```

legacy 路由 `/xhs-cookies` **硬跳转** `/data-sources?tab=xhs_web`，不给 404。

### 2.2 后端接口

不动已有接口。新增一个：

```
POST /api/v1/admin/xhs-cookies/{id}/ping         → 用该 cookie 实际调一次 xhs API，返回 ok/err
```

（聚光 OAuth 授权 URL / 回调端点留给 P1 执行时决定具体路由名。）

---

## 3. 详细方案

### 3.1 `xhs-web-panel.vue` 改造

布局从上到下：

```
┌─ 顶部主操作（固定） ────────────────────────┐
│  [ 📱 扫码登录采集 (主按钮, primary) ]        │
│  [ ✏ 手动录入 ] [ 🔄 刷新 ] [ ⚙ 筛选 ]        │
└──────────────────────────────────────────────┘
┌─ 现有凭证表格 ────────────────────────────┐
│  平台 | 备注 | 字段 | 状态 | 健康 | 操作        │
│  ...                                          │
└──────────────────────────────────────────────┘
┌─ SOP 文档区（可折叠，默认收起） ────────────┐
│  📖 手动粘贴流程 (只在点"手动录入"时展开)    │
│  🤖 Agent 会怎么用这些 Cookie (始终可见)      │
└──────────────────────────────────────────────┘
```

**关键交互**：

- 主按钮"扫码登录采集"直接唤起 `qr-login-modal` → 成功后 `emit('changed')` 触发父组件 reload
- "手动录入"改成二级按钮，原 Modal 不变
- "一键从浏览器捕获 Cookie" 的那个 bookmarklet 折叠到手动录入模态内的可折叠 `<details>` 里——它是高阶用户才用得到的东西，不占首屏
- 表格每一行操作列新增 "▶ 测试"（调 `/ping`）

### 3.2 `xhs-spotlight-panel.vue` 改造

```
如果 data-sources/status 接口里 spotlightOauthConfigured = true：
  顶部：[ 🔐 使用聚光账号授权 ] (主按钮)
         点击 → 新窗打开 POST /oauth/xhs-spotlight/authorize → 回调完成后刷新列表

如果没配：
  顶部显示一张引导卡：
    "运维尚未配置聚光开发者 AppID。目前支持临时粘贴 access token（有效期通常 24h）兜底。
     填 AppID 后端 `.env` 的步骤见 [docs/data-sources-setup.md]"
  下方保留原手贴表单作为兜底（未来可以彻底删）
```

### 3.3 `qr-login-modal.vue` 搬迁

- 从 `views/xhs-cookies/modules/qr-login-modal.vue` 搬到 `views/data-sources/modules/_shared/qr-login-modal.vue`
- 所有 import 路径 refactor
- 组件自身逻辑 **不动**（已经 production-ready）

### 3.4 后端 `/ping` 端点

```java
@PostMapping("/{id}/ping")
public ResponseEntity<?> ping(@PathVariable Long id, @RequestAttribute("orgTag") String orgTag) {
    // 1. 按 id 拉 cookie（越权校验）
    // 2. 根据 platform 调一条轻量验活 API：
    //      xhs_pc/creator    → /api/sns/web/v1/user/me
    //      xhs_pgy           → pgy 自己的 /api/v1/user/info
    //      xhs_qianfan       → qianfan 自己的 /api/v1/session
    //      xhs_spotlight     → oauth token 的 introspect
    //      xhs_competitor    → Supabase /rest/v1/ (HEAD)
    // 3. 返回 {ok:true,latencyMs:123,platformSignal:"user_id=..."}
    //    或 {ok:false,errorType:"cookie_invalid|network|http_4xx",message:"..."}
}
```

实现放 `XhsCookieController` + 新服务类 `XhsCookieHealthService`。

### 3.5 i18n 术语统一

| 旧词 | 新词 |
|------|------|
| XHS Cookie 池 | 数据源 / 登录凭证 |
| Cookie | 登录凭证（业务员面） / cookie（开发者弹窗面） |
| 字段完整性 | 状态自检 |
| 扫码登录采集 | 扫码登录 |

---

## 4. 数据迁移 / 兼容

- `/xhs-cookies` → `/data-sources?tab=xhs_web` 硬跳转，浏览器书签不会 404
- DB 不动（xhs_cookies 表结构完全不变）
- API 路径不动（仍然 `/api/v1/admin/xhs-cookies/*`），只有前端 UI 收口
- 新增的 `/ping` 端点可选使用，不影响现有流程

---

## 5. 测试

1. 删 `/xhs-cookies` 后 `pnpm typecheck` 0 error
2. `pnpm build` 0 error
3. `mvn test` 所有用例通过（包括 2026-04-20 新加的 5 个 XhsCookieService 单测）
4. 手动验证：登录 → `/data-sources` → 点扫码登录 → 手机扫一扫 → 4 个 cookie 入池
5. 老 URL `/xhs-cookies` 自动跳 `/data-sources`
6. `/ping` 返回对每一种 platform 都 ok

---

## 6. 工作分解（执行顺序）

| # | 动作 | 产物 |
|---|------|------|
| 1 | 把 `qr-login-modal.vue` 搬到 `_shared/` 并修所有 import | 1 次 move + 1 次 import 改写 |
| 2 | `xhs-web-panel.vue` 顶部加扫码登录主按钮 + 接入 modal | UI 可见扫码入口 |
| 3 | 删除 `views/xhs-cookies/` 整个目录 + 路由 + i18n 键 | 菜单只剩一个"数据源中心" |
| 4 | 加 `/xhs-cookies` 的重定向路由 | 老书签不挂 |
| 5 | 后端加 `POST /api/v1/admin/xhs-cookies/{id}/ping` 端点 | 能一键自检 |
| 6 | `xhs-web-panel` 表格操作列加"▶ 测试"按钮 + 顶部加"全部自检" | 一眼看谁死了 |
| 7 | 聚光面板加"使用聚光账号授权"按钮 + 未配置时的引导卡 | 业务员不用再贴 token（真正 OAuth 实现见 P1） |
| 8 | typecheck / build / mvn test / 手动冒烟 | 全绿 |

---

## 7. 风险

- `/ping` 可能被反爬风控——加 1 秒最小间隔 + 失败不降权（免得 "测试"反而把 cookie 降级了）
- 聚光真实 OAuth 需要 app_id/secret，如果运维没给，fallback 到老粘贴流程；别把老路径一刀切
- 扫码登录 modal 本轮已 production，不要动内部逻辑

---

## 8. 未竞事项（转下一 spec）

- 千瓜服务端无头登录
- Agent 权限 T1/T2 工具包
- HANDOFF.md 与 README.md 的真相对齐
- 7 步 SOP 的 Step4（博主 Brief 生成器）/ Step5（内容审稿）/ Step7（迭代复盘）
