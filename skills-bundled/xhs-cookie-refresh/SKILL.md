---
name: xhs-cookie-refresh
description: 指南：当 xhs-* skill 报 cookie_invalid 时，如何让运维从浏览器导出新 cookie 并通过小蜜蜂后台更新到 cookie 池。
version: "0.1.0"
metadata:
  bee:
    tags: [xhs, cookie, ops, runbook]
    requires:
      bins: []
---

# xhs-cookie-refresh（运维操作指引）

本 skill 不是一个可执行脚本，而是 **agent 遇到 cookie 失效时，输出给用户/管理员的指引**。

## 触发条件

当其它 xhs-* skill 输出 `{"ok": false, "errorType": "cookie_invalid"}` 时，Java 协调 tool
会做两件事：

1. 调 `XhsCookieService.reportFailure(cookieId, error)` 自动降权 / 达阈值后标 EXPIRED
2. 返回给 agent 的消息里附带 "需要管理员重新录入 cookie" 的提示

Agent 此时应调用 `use_skill({"name": "xhs-cookie-refresh"})` 拿到本文档，
原样贴给用户（通常是 admin）作为下一步操作指引。

## 对 admin 的操作指引

### xhs_pc（小红书 PC 端）

1. 使用需要的**品牌/测试账号**登录 <https://www.xiaohongshu.com>
2. F12 打开开发者工具 → Network → XHR → 点击任意 `/api/...` 请求
3. 在请求头里复制 `cookie` 字段的完整值
4. 打开小蜜蜂后台 → 左侧菜单 "XHS Cookie 池"
5. 点击 "新增 Cookie"，选择 platform=`xhs_pc`，粘贴完整 cookie，点保存
6. 把旧的失效 cookie 状态改为 `DISABLED`

### xhs_creator（创作者平台，用于发布/查看作品）

1. 登录 <https://creator.xiaohongshu.com>
2. 同上 F12 复制 cookie
3. 后台新增 platform=`xhs_creator`

### xhs_pgy（蒲公英平台，用于 KOL 发掘）

1. 登录 <https://pgy.xiaohongshu.com>（需要**品牌方**账号）
2. 同上 F12 复制 cookie
3. 后台新增 platform=`xhs_pgy`

### xhs_qianfan（千帆平台，用于分销商数据）

1. 登录 <https://qianfan.xiaohongshu.com>
2. 同上 F12 复制 cookie
3. 后台新增 platform=`xhs_qianfan`

## Cookie 有效期与节奏

- 各平台 cookie 一般**2~4 周**失效，取决于当前账号活跃度与风控策略
- 建议为每个平台**至少准备 2 个账号**以支持轮转，避免单账号频控
- 对于高频使用场景（每天 > 200 次 API 调用），建议 **3-5 个账号**

## 安全

- 所有 cookie 在 MySQL 里以 AES/GCM 加密存储（Key 来自 `XHS_COOKIE_SECRET` 环境变量）
- 前端列表页**永不展示** cookie 明文，仅展示前 16 + `...` + 末 8 字符的预览
- 管理员可以覆盖（重新粘贴）但不能反解明文

## 自动化（未来）

规划里有 `xhs-cookie-refresh-auto` skill，计划基于 Playwright 在服务器侧启无头浏览器，
扫码/短信验证码登录后自动把新 cookie 回写到 DB。目前先走人工节奏，避免触发风控。
