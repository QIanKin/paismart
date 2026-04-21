# Playwright CDP 运行时（企业浏览器自动化桥接）

## 设计

所有小红书 / 千瓜 / 蒲公英的**登录态敏感的浏览器自动化** skill，都通过 CDP 连到业务员本机的 Chrome，而**不是**在服务器上自己启 headless Chromium。

```
[业务员电脑]                                   [后端容器]
  └── Chrome --remote-debugging-port=9222    ←──── CDP WS ─── [xhs-outreach-comment skill]
        + 小红书已登录                                         通过 playwright.chromium.connectOverCDP(CDP_ENDPOINT)
        + 千瓜已登录
```

**为什么这样？**

1. **反爬友好**：真实用户的 Chrome 指纹 / 历史 / Cookie 完整，反爬检测很难识别；headless 秒被识破。
2. **合规**：操作全部在业务员自己的账号下，出了事能查到人；不会用公司服务器直接发评论。
3. **镜像轻**：后端不用装 Chromium 二进制（节省 ~300MB）。

## 部署

### 服务器侧（已在 Dockerfile 里配置）

- `nodejs` + `npm`
- `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`（不要下 chromium）
- `npm install playwright` 到这个目录

### 业务员侧

macOS：
```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
    --remote-debugging-port=9222 \
    --user-data-dir="$HOME/ChromeProfile-XHS"
```

Windows：
```powershell
& "C:\Program Files\Google\Chrome\Application\chrome.exe" `
    --remote-debugging-port=9222 `
    --user-data-dir="$env:USERPROFILE\ChromeProfile-XHS"
```

然后登录小红书 / 千瓜 / 蒲公英。

### 隧道（可选，后端和业务员不在同一内网时）

业务员本机跑：
```bash
cpolar tcp 9222            # 或 ngrok tcp 9222 / frp
```
把得到的公网 endpoint 配到后端 `SMARTPAI_BROWSER_CDP_ENDPOINT`。

## skill 怎么调用

每个 .mjs 脚本开头都是：
```js
import { chromium } from 'playwright';
const endpoint = process.env.CDP_ENDPOINT || 'http://localhost:9222';
const browser = await chromium.connectOverCDP(endpoint);
```

`CDP_ENDPOINT` 由 `XhsSkillRunner` 从配置项注入，默认指向 `host.docker.internal:9222`。
