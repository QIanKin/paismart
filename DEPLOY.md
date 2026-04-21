# PaiSmart · Linux + Docker 一键部署指南

> 💡 **如果你的 VPS 跑不动这个项目**（常见于 2C4G 及以下的小机器），推荐看 [DEPLOY-WINDOWS.md](./DEPLOY-WINDOWS.md)：
> 在自己 Windows 电脑上跑 Docker，VPS 只做 frp 端口映射。本文是"所有服务都跑在一台 Linux 服务器上"的方案。

本文是给**没有域名、只用服务器 IP 访问**的场景准备的完整部署流程。跑完之后你会得到：

- `http://服务器IP` —— 前端 Web（浏览器访问入口）
- `http://服务器IP/api/v1/…` —— 后端 REST
- `http://服务器IP:19001` —— MinIO 控制台（管理员用，可选）

全部服务（MySQL / Redis / Kafka / Elasticsearch / MinIO / 后端 / 前端）都跑在 docker 里，不污染宿主机。

---

## 一、服务器要求

| 项目       | 最低       | 推荐           |
| ---------- | ---------- | -------------- |
| CPU        | 2 核       | 4 核           |
| 内存       | 4 GB       | **8 GB**       |
| 硬盘       | 30 GB      | 50 GB+         |
| 系统       | Ubuntu 20.04 / CentOS 7+ / Debian 11+ |            |
| 带宽       | 拉取镜像时 ≥ 3 Mbps |    |

> ES + Kafka + MySQL 加起来挺吃内存，**低于 4G 的服务器强烈不建议**，容易 OOM。

---

## 二、安装 Docker（如果还没装）

```bash
# 通用一键脚本（Ubuntu / Debian / CentOS 都可用）
curl -fsSL https://get.docker.com | sh

# 启动 docker
sudo systemctl enable --now docker

# 把当前用户加入 docker 组（免 sudo）
sudo usermod -aG docker $USER
# 重新登录一次，或者执行：
newgrp docker

# 验证
docker version
docker compose version
```

---

## 三、部署步骤（三步搞定）

### 1. 把项目上传到服务器

任选一种：

```bash
# 方式 A：git clone
git clone <你的仓库地址> PaiSmart
cd PaiSmart

# 方式 B：本地打包上传（Windows 用 WinSCP / scp 都行）
scp -r D:/Project/AI/PaiSmart root@服务器IP:/root/
ssh root@服务器IP
cd /root/PaiSmart
```

### 2. 配置环境变量

```bash
cp .env.deploy.example .env
# 至少填这两个 API Key（必填）：
#   DEEPSEEK_API_KEY  —— https://platform.deepseek.com/
#   EMBEDDING_API_KEY —— https://dashscope.console.aliyun.com/
vi .env
```

> `deploy.sh` 在首次运行时会检测这两个 key，没填会交互式询问你。所以其实也可以不改直接跑脚本。

### 3. 一键部署

```bash
chmod +x deploy.sh
./deploy.sh
```

脚本会自动：

1. 检查 Docker 是否可用
2. 自动探测服务器公网 IP（可手动改）
3. 自动生成 JWT 密钥
4. 自动填好跨域白名单
5. 提示你输入 AI API Key（如未填）
6. 构建后端镜像（多阶段 Maven 构建，首次 **5~10 分钟**）
7. 构建前端镜像（pnpm build，**2~4 分钟**）
8. 拉取 MySQL / Redis / Kafka / ES / MinIO 镜像并启动
9. 后端等中间件健康后自动启动
10. 打印访问地址 + 初始账号

---

## 四、访问和注册

部署成功后打开：

```
http://服务器IP
```

### 账号体系说明

项目支持三种注册策略（`.env` 里的 `APP_AUTH_REGISTRATION_MODE`）：

| 模式            | 说明 | 谁能注册 |
| --------------- | ---- | -------- |
| `OPEN` **（默认）** | 开放注册 | 任何人在登录页点"注册"即可 |
| `INVITE_ONLY`   | 邀请码制 | 必须有管理员发的邀请码 |
| `CLOSED`        | 关闭注册 | 只有管理员后台能建号 |

一键部署默认是 **`OPEN`**，所以：

**✅ 可以直接在页面上点"注册"填用户名密码即可注册普通用户**。

### 管理员账号

`.env` 里的配置：

```env
ADMIN_BOOTSTRAP_ENABLED=true          # 首次启动创建 admin
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=Admin@2025   # 强烈建议改成你自己的强密码
```

后端启动后会自动创建管理员账号。登录后进入管理员能看到"知识库管理/用户监控/邀请码"等管理员页面。

> **建议**：admin 创建成功后，把 `ADMIN_BOOTSTRAP_ENABLED` 改成 `false`，然后 `./deploy.sh restart`，避免每次启动都触发引导逻辑。

### 如果想改成邀请码制

```env
APP_AUTH_REGISTRATION_MODE=INVITE_ONLY
APP_AUTH_INVITE_REQUIRED=true
```

然后 `./deploy.sh restart`。以 admin 登录后在"邀请码"页面生成邀请码，发给其他人注册时使用。

---

## 五、常用运维命令

```bash
./deploy.sh              # 首次/常规部署（= up）
./deploy.sh up           # 构建 + 启动
./deploy.sh down         # 停止容器（保留数据）
./deploy.sh restart      # 重启
./deploy.sh rebuild      # 代码改动后，重新构建后端/前端镜像
./deploy.sh logs         # 看全部日志
./deploy.sh logs backend # 只看后端日志
./deploy.sh ps           # 看容器状态
./deploy.sh status       # 看状态 + 访问地址
./deploy.sh clean        # ⚠️ 停止并删除所有数据卷（会清空数据库）
```

代码更新后想重新部署：

```bash
git pull
./deploy.sh rebuild
```

---

## 六、端口一览 & 防火墙

一键部署会在宿主机开这些端口：

| 端口  | 服务        | 是否需要开放公网 |
| ----- | ----------- | ---------------- |
| 80    | 前端（Nginx） | ✅ 必须          |
| 8081  | 后端直连（调试用） | 可关            |
| 19000 | MinIO API   | ✅ 必须（前端下载文件用 presigned URL） |
| 19001 | MinIO 控制台 | 建议只让自己的 IP 访问 |

云服务器安全组 / 防火墙记得放行 **80 / 19000**（最少）。

```bash
# ufw 示例（Ubuntu）
sudo ufw allow 80/tcp
sudo ufw allow 19000/tcp
sudo ufw reload
```

> 如果你 80 端口被占用（比如装了 nginx 或 Apache），把 `.env` 里 `WEB_PORT` 改成 `8080`，然后用 `http://服务器IP:8080` 访问。脚本会自动更新跨域白名单。

---

## 七、常见问题排查

### 1. 浏览器白屏 / 登录后没反应

多半是后端还没起来。

```bash
./deploy.sh logs backend
```

看到 `Started SmartPaiApplication` 才算真正就绪。**首次启动 1~3 分钟很正常**，ES 要装 IK 分词插件 + Kafka 要建 topic。

### 2. ES 起不来，日志是 `max virtual memory areas vm.max_map_count [65530] is too low`

```bash
sudo sysctl -w vm.max_map_count=262144
# 永久生效
echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
```

然后 `./deploy.sh restart`。

### 3. 上传文件后下载不了

说明浏览器拿到的 MinIO 预签名 URL 是内网地址。检查 `.env`：

```env
SERVER_IP=你的真实公网IP
```

改完后 `./deploy.sh restart`。

### 4. 服务器内存不够 / 部署后很卡

改小 JVM 和 ES 堆：

```env
ES_HEAP_SIZE=512m
JAVA_XMS=256m
JAVA_XMX=512m
```

然后 `./deploy.sh restart`。

### 5. WebSocket 聊天连不上

检查浏览器控制台。Nginx 已经配了 `/proxy-ws` 代理，如果 `SECURITY_ALLOWED_ORIGINS` 里没有你的访问地址，后端会拒连。

```bash
# 查看 .env
grep ALLOWED_ORIGINS .env
# 应该包含 http://你的IP:*
```

改完 `./deploy.sh restart`。

### 6. 想完全重来

```bash
./deploy.sh clean   # 停容器 + 删数据卷
rm .env
./deploy.sh         # 重新跑
```

---

## 八、升级 / 修改代码后重新部署

```bash
# 拉最新代码
git pull

# 重建后端 + 前端镜像（其他中间件不动，不会清数据）
./deploy.sh rebuild
```

如果只改了 `.env`：

```bash
./deploy.sh restart
```

---

## 九、备份数据

数据全部在 docker volume 里（`mysql-data` / `minio-data` / `es-data` / `kafka-data` / `redis-data`）。

```bash
# 查看所有卷
docker volume ls | grep paismart

# 备份 mysql（示例）
docker exec paismart-mysql mysqldump -uroot -pPaiSmart2025 PaiSmart > backup-$(date +%F).sql

# 备份 minio 上传的文件
docker run --rm -v paismart_minio-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/minio-$(date +%F).tar.gz -C /data .
```

---

## 十、安全加固（上线到公网前务必做）

1. 把 `.env` 里所有 `PaiSmart2025` 默认密码都改掉
2. `ADMIN_BOOTSTRAP_PASSWORD` 改成强密码
3. admin 创建完后把 `ADMIN_BOOTSTRAP_ENABLED` 改成 `false`
4. 如果不想开放注册，把 `APP_AUTH_REGISTRATION_MODE` 改成 `INVITE_ONLY`
5. 云服务器安全组只开 80 + 19000（MinIO Console 19001 只允许自己 IP）
6. 有精力的话后续可以加 Cloudflare / Nginx 反代 + HTTPS

---

部署跑完就能直接用了。有问题先看 `./deploy.sh logs backend`，90% 的问题都能直接看出原因。
