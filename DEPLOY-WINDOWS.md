# PaiSmart · Windows 本机部署 + VPS 端口映射

适用场景：**VPS 配置低（1C2G/2C4G）跑不动完整项目，但想对外提供公网访问**。
解决方案：用 Windows PC 当"服务器"跑 Docker，VPS 只做 **frp 端口映射网关**。

## 架构一览

```
 浏览器/用户
    │  访问 http://VPS_IP
    ▼
┌──────────────┐        ┌────────────────────────┐
│ VPS（公网IP）│ frps  │ Windows PC（你的电脑）  │
│  frps:7000   │<──────│  frpc.exe              │
│  :80 (web)   │ ◁───▶ │  :80 Nginx (容器)       │
│  :19000 MinIO│ ◁───▶ │  :19000 MinIO (容器)   │
└──────────────┘  隧道 │  + 后端/MySQL/ES/Kafka │
                       └────────────────────────┘
```

> 你 Windows PC 不需要公网 IP，不需要路由器端口映射。
> 只要 **Windows PC 能访问 VPS 的 7000 端口**（出站连接），整个链路就通。

---

## 一、前置要求

### Windows PC（跑服务的机器）

| 项目 | 要求 | 说明 |
|------|------|------|
| 系统 | Windows 10/11 **专业版/企业版/教育版** | Docker Desktop 需要 Hyper-V 或 WSL2 |
| 内存 | **最少 8GB，推荐 16GB** | ES + Kafka + MySQL 比较吃内存 |
| 硬盘 | 30GB 可用 | 镜像 + 数据卷 |
| 网络 | 能访问 docker hub 和 VPS | |
| 软件 | [Docker Desktop](https://www.docker.com/products/docker-desktop/) | **必装**，建议开启 WSL2 后端 |
| 可选 | PowerShell 7 | 老 Windows PowerShell 5.1 也能跑 |

> **Windows 家庭版** 也能装 Docker Desktop（新版本支持 WSL2 后端），可以试试。

### VPS（当网关用）

| 项目 | 要求 |
|------|------|
| 系统 | Linux（Ubuntu/Debian/CentOS 随意） |
| 内存 | **512MB 就够了**（frps 很轻量） |
| 带宽 | 看你对带宽的需求；所有流量都走 VPS |
| 端口 | 需放行 `80`、`19000`、`19001`、`7000`（frp 控制通道） |

---

## 二、整体步骤总览

1. 在 **Windows PC** 上用 Docker Desktop 把项目跑起来（访问 `http://localhost` 能开）
2. 在 **VPS** 上装 frp server（frps）
3. 在 **Windows PC** 上装 frp client（frpc）连上 VPS
4. 修改 `.env` 里的 `SERVER_IP` 为 **VPS 公网 IP**，重启容器
5. 浏览器访问 `http://VPS_IP`，搞定

下面逐步来。

---

## 三、第一步：Windows 本机启动项目

### 3.1 安装 Docker Desktop

[官网下载](https://www.docker.com/products/docker-desktop/) → 一路 Next → 启动 → 右下角 docker 图标变绿。

打开 PowerShell 验证：

```powershell
docker version
docker compose version
```

### 3.2 克隆项目（或把本地项目复制过去）

```powershell
cd D:\Project\AI
git clone <你的仓库地址> PaiSmart
cd PaiSmart
```

### 3.3 一键部署

```powershell
# 如果遇到执行策略报错，先开放一次（管理员 PowerShell）：
# Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

.\deploy.ps1
```

脚本会交互式问你：

- **SERVER_IP** → 这一步先填 `127.0.0.1`（后面配好 frp 再改成 VPS 公网 IP）
- **DeepSeek API Key**
- **DashScope API Key**

然后开始构建（首次 **5~15 分钟**）。

构建完成后浏览器打开 `http://localhost` 应该能看到登录页。

> **如果 80 端口被占用**（IIS / 其他服务），编辑 `.env` 把 `WEB_PORT=80` 改成 `8080`，`.\deploy.ps1 restart`，然后访问 `http://localhost:8080`。

### 3.4 验证本机能用

访问 `http://localhost`（或你配的 WEB_PORT）：

- 能看到登录页 → 本机 OK，进行下一步
- 页面白屏 → 跑 `.\deploy.ps1 logs backend`，等到看到 `Started SmartPaiApplication` 再刷新

---

## 四、第二步：VPS 上部署 frp 服务端

### 4.1 下载 frp

SSH 登录你的 VPS：

```bash
# 找最新版：https://github.com/fatedier/frp/releases/latest
cd /opt
wget https://github.com/fatedier/frp/releases/download/v0.61.1/frp_0.61.1_linux_amd64.tar.gz
tar -xzf frp_0.61.1_linux_amd64.tar.gz
mv frp_0.61.1_linux_amd64 frp
cd frp

# 把 frps 放到 /usr/local/bin 方便 systemd 调用
sudo cp frps /usr/local/bin/
sudo mkdir -p /etc/frp
```

### 4.2 配置 frps

把项目里 `deploy-windows/frps.toml.example` 上传到 VPS：

```bash
# 方式 A：scp 从本地传（在 Windows PowerShell）
scp D:\Project\AI\PaiSmart\deploy-windows\frps.toml.example root@VPS_IP:/etc/frp/frps.toml

# 方式 B：在 VPS 上手动创建
sudo nano /etc/frp/frps.toml   # 把 frps.toml.example 的内容贴进去
```

**必须修改** `auth.token` 为一个长随机字符串（记下来，client 端要填同一个）：

```bash
# 生成一个随机 token
openssl rand -base64 32
```

### 4.3 注册成 systemd 服务

```bash
sudo cp /opt/frp/frps.service.example /etc/systemd/system/frps.service
# 如果你没上传这个文件，参考项目里 deploy-windows/frps.service.example 手动创建

sudo systemctl daemon-reload
sudo systemctl enable --now frps
sudo systemctl status frps      # 看到 active (running) 就对了
```

### 4.4 防火墙放行

```bash
# ufw（Ubuntu/Debian）
sudo ufw allow 7000/tcp     # frp 控制通道
sudo ufw allow 80/tcp       # 前端 web
sudo ufw allow 19000/tcp    # MinIO API（浏览器下载文件要走这个）
sudo ufw allow 19001/tcp    # MinIO 控制台（可选）
sudo ufw allow 7500/tcp     # frp 面板（可选，想在浏览器看连接状态时开）
sudo ufw reload

# 云服务商（阿里云/腾讯云/AWS）的安全组也要放行上面这些端口！
```

---

## 五、第三步：Windows 上装 frp 客户端

### 5.1 下载 frp Windows 版

- 去 https://github.com/fatedier/frp/releases/latest
- 下载 `frp_X.X.X_windows_amd64.zip`
- 解压到随便哪里，比如 `D:\frp\`

### 5.2 配置 frpc

把项目里 `deploy-windows/frpc.toml.example` 复制到 `D:\frp\frpc.toml`：

```powershell
Copy-Item D:\Project\AI\PaiSmart\deploy-windows\frpc.toml.example D:\frp\frpc.toml
```

编辑 `D:\frp\frpc.toml`，**必改**：

```toml
serverAddr = "你的VPS公网IP"
auth.token = "和VPS上frps.toml里一模一样的那个token"
```

### 5.3 启动 frpc

```powershell
cd D:\frp
.\frpc.exe -c frpc.toml
```

看到日志里有 `start proxy success` 三次（web / minio-api / minio-console）就说明连通了。

保持这个窗口开着（或按下面做成开机自启）。

### 5.4 让 frpc 随开机启动（推荐）

**方案 A：启动文件夹（简单）**

1. `Win + R` 输入 `shell:startup` 回车
2. 把 `D:\Project\AI\PaiSmart\deploy-windows\start-frpc.bat` 的**快捷方式**丢进去
3. 记得先把这个 bat 复制到 `D:\frp\` 里，或者改 bat 里的路径

**方案 B：注册成 Windows 服务（稳定）**

用 [NSSM](https://nssm.cc/download)：

```powershell
# 下载 nssm.exe 放到 D:\frp\
cd D:\frp
.\nssm.exe install frpc
# 在弹出窗口里：
#   Path:            D:\frp\frpc.exe
#   Arguments:       -c D:\frp\frpc.toml
#   Startup type:    Automatic
# Install service → 完成

.\nssm.exe start frpc
```

以后开机就自动连了，连 PC 都不用登录也能跑（只要系统启动）。

---

## 六、第四步：让后端知道自己对外是 VPS IP

现在流量链路通了，但后端生成的 **MinIO 预签名 URL** 还是指向 `127.0.0.1`，浏览器下载会失败。

回到 Windows PC 项目目录，改 `.env`：

```env
SERVER_IP=你的VPS公网IP
```

然后重启：

```powershell
.\deploy.ps1 restart
```

这一步会重新：

- 生成跨域白名单（`SECURITY_ALLOWED_ORIGINS` 自动加上 VPS IP）
- MinIO 的 `publicUrl` 变成 `http://VPS_IP:19000`，浏览器下载文件时能走通

---

## 七、验证整个链路

### 在自己手机 4G（不走家里网）或另一台电脑上打开

```
http://你的VPS公网IP
```

能看到 PaiSmart 登录页 → 注册一个账号 → 聊天 → 上传文件 → 下载 → 全通 = 完工。

---

## 八、关于注册

默认 `.env.deploy.example` 里：

```env
APP_AUTH_REGISTRATION_MODE=OPEN     # 开放注册
APP_AUTH_INVITE_REQUIRED=false
```

所以 **任何访问到 `http://VPS_IP` 的人都能点"注册"直接建号**。如果不想被陌生人白嫖：

```env
APP_AUTH_REGISTRATION_MODE=INVITE_ONLY
APP_AUTH_INVITE_REQUIRED=true
```

`.\deploy.ps1 restart` 后，只有 admin 生成的邀请码才能注册。

管理员账号：`.env` 里的 `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD`，容器首次启动会自动创建。创建好后建议把 `ADMIN_BOOTSTRAP_ENABLED` 改成 `false` 再重启一次。

---

## 九、性能和带宽说明

所有公网访问流量都要经过 VPS：

- **聊天对话**：流量很小（几 KB/请求），VPS 带宽 1Mbps 也够
- **文件上传**：50MB 文件走 1Mbps 带宽需要 ~7 分钟，**这是瓶颈**
- **文件下载**：同上，受 VPS 上传带宽限制

建议 VPS 带宽至少 **5Mbps**，国内小水管（1~2Mbps）体验会比较差。

> 如果 VPS 是按流量付费，注意文件上传/下载都会双向计费（进 VPS 一次，出 VPS 一次）。

---

## 十、日常运维

### Windows PC 上

```powershell
cd D:\Project\AI\PaiSmart

.\deploy.ps1 status       # 看容器状态 + 访问地址
.\deploy.ps1 logs backend # 看后端日志
.\deploy.ps1 restart      # 改完 .env 后重启
.\deploy.ps1 rebuild      # 代码改动后重建镜像
.\deploy.ps1 down         # 停止（保留数据）
.\deploy.ps1 clean        # ⚠️ 清空所有数据重来
```

### VPS 上

```bash
sudo systemctl status frps   # 看 frp server 状态
sudo journalctl -u frps -f   # 看 frp server 日志
sudo systemctl restart frps  # 重启
```

### Windows PC frp client

```powershell
# 如果用 NSSM 装成了服务
nssm.exe restart frpc
nssm.exe status frpc

# 如果是命令行跑的，Ctrl+C 停，然后重新 .\frpc.exe -c frpc.toml
```

---

## 十一、常见问题

### 1. `frpc` 连不上 VPS

```
[E] control connection: dial tcp ... i/o timeout
```

- VPS 安全组 / 防火墙没放行 `7000` 端口
- `serverAddr` 填错了，要填 VPS **公网** IP 不是内网
- VPS 本身网络问题，`ssh VPS_IP` 试下

### 2. `frpc` 日志里报 `invalid authentication`

`frpc.toml` 和 `frps.toml` 的 `auth.token` 不一样。复制时注意别多空格。

### 3. 浏览器能打开 http://VPS_IP 但登录报错

F12 看网络请求：

- 请求 `http://VPS_IP/api/...` 404 → nginx 转发没生效，检查 Windows 上容器状态 `.\deploy.ps1 ps`
- 请求 CORS 报错 → `.env` 里 `SERVER_IP` 还是 `127.0.0.1`，改成 VPS IP 后 `restart`
- 请求 502 / 504 → 后端没起来，`.\deploy.ps1 logs backend`

### 4. 上传文件失败 / 下载 404

`.env` 里 `SERVER_IP` 必须是 VPS IP，且 VPS 上 19000 端口要放行。测试：

```bash
# 在另一台机子上
curl -v http://VPS_IP:19000/minio/health/live
# 应该返回 200
```

### 5. WebSocket 聊天连不上

F12 看 WS 连接。如果是 `wss://` 而不是 `ws://`，说明你通过 https 访问的。当前方案不含 HTTPS，浏览器会默认只允许同域的 ws：// —— 用 `http://VPS_IP` 而不是 `https://`。

### 6. Docker Desktop 内存占用飙升

调小 `.env` 里：

```env
ES_HEAP_SIZE=512m
JAVA_XMS=256m
JAVA_XMX=512m
```

`.\deploy.ps1 restart`。

也可以在 Docker Desktop Settings → Resources 里给 WSL 2 加内存上限。

### 7. Windows 睡眠后 frpc 断了

设置 → 电源 → 睡眠改成 "永不"。或者至少保证关屏不休眠。

### 8. 我换了家里宽带 / 重启了路由器，frp 还能连吗

能。frp 会自动重连到 VPS，跟你家 IP 变没关系，只要出站能到 VPS:7000 即可。

---

## 十二、安全建议

1. `.env` 里所有默认密码（`PaiSmart2025`）全改成强密码
2. `frps.toml` 和 `frpc.toml` 的 `auth.token` 用 `openssl rand -base64 32` 生成
3. VPS 上 **MinIO 控制台端口 19001** 建议只放行你自己的 IP（安全组白名单），或者 frpc 干脆不穿透这个端口
4. `ADMIN_BOOTSTRAP_PASSWORD` 改成强密码，登录过一次后把 `ADMIN_BOOTSTRAP_ENABLED` 改成 `false`
5. 如果不想被陌生人注册，用 `INVITE_ONLY` 模式

---

## 附录：一张图快速理解

```
用户浏览器
    │  http://VPS_IP   or   http://VPS_IP:19000/xxx
    ▼
┌────────────────────────────┐
│ VPS (Linux)                │
│  frps 监听 80 / 19000      │
└─────────┬──────────────────┘
          │ TCP 隧道（走 7000 端口）
          ▼
┌────────────────────────────┐
│ Windows PC (Docker Desktop)│
│  frpc ──▶ :80 (Nginx)     │──▶ :8081 (Backend)  ──┐
│         ──▶ :19000 (MinIO)                          │
│                                                     ▼
│   MySQL / Redis / Kafka / ES / MinIO（数据存本地）  │
└────────────────────────────┘
```

部署完跑起来了就可以直接用，有问题先 `.\deploy.ps1 logs backend`，大部分问题从日志就能看出来。
