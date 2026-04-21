# xhs-qr-login

后端容器内运行的扫码登录代理。

## 怎么被调用

- 不是 AI 可直接 `use_skill` 的 skill（没挂 `SkillRegistry`）
- 入口是 Java 侧 `LoginBrowserRunner.start(...)`，以
  `node run.mjs --session <uuid> --platforms <csv> --timeout <sec>` 方式 fork
- 子进程通过 stdout NDJSON（每行一条 JSON）把事件回传给 Java

## stdout 协议

```jsonc
// 二维码已就绪，Java 立刻 push 给前端让用户扫码
{ "type": "qr_ready", "dataUrl": "data:image/png;base64,..." }

// 生命周期状态（可选，会映射到 XhsLoginSession.Status）
{ "type": "status", "status": "SCANNED" }
{ "type": "status", "status": "CONFIRMED" }

// 成功：四个平台尽力而为抓到的 cookie（缺失平台由 Java 进入 missingPlatforms）
{
  "type": "success",
  "cookies": {
    "xhs_pc":      "a1=...; web_session=...; webId=...",
    "xhs_creator": "a1=...; web_session=...; ...",
    "xhs_pgy":     "...",
    "xhs_qianfan": "..."
  }
}

// 失败
{ "type": "error", "errorType": "qr_not_found", "message": "首页没找到二维码元素" }
```

## stdin 指令

- `{"type":"cancel"}` → 优雅结束 Chromium 并退出（退出码 0）

## 环境变量

- `NODE_PATH`：指到 `skills-bundled/xhs-qr-login/node_modules`，让 `import 'playwright'` 能找到
- `PLAYWRIGHT_BROWSERS_PATH`：Chromium 安装路径，与镜像里 `npx playwright install chromium` 使用的路径一致
- `CHROMIUM_EXECUTABLE`（可选）：显式指定 Chromium 可执行文件，覆盖 Playwright 默认查找

## 覆盖平台

- `xhs_pc`：`https://www.xiaohongshu.com`
- `xhs_creator`：`https://creator.xiaohongshu.com`
- `xhs_pgy`：`https://pgy.xiaohongshu.com`（小红书蒲公英 → 通常与 xhs_pc SSO）
- `xhs_qianfan`：`https://qianfan.xiaohongshu.com`（千帆，独立体系，**不一定能通过单次扫码**，能抓到就抓，抓不到打入 missing）

## 本地开发

```bash
cd skills-bundled/xhs-qr-login
npm install
npx playwright install chromium
node run.mjs --session dev-local --platforms xhs_pc,xhs_creator --timeout 120
```

扫码后 stdout 会依次打出 qr_ready → status → success。
