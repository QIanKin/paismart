#!/usr/bin/env bash
# ============================================================
# PaiSmart 一键部署脚本（Linux / Docker）
#
# 用法：
#   ./deploy.sh            # 交互式首次部署（推荐）
#   ./deploy.sh up         # 构建 + 启动
#   ./deploy.sh down       # 停止并保留数据
#   ./deploy.sh restart    # 重启
#   ./deploy.sh logs [svc] # 查看日志，不传 svc 时看所有
#   ./deploy.sh ps         # 查看容器状态
#   ./deploy.sh clean      # 停止并删除所有数据卷（危险！会清空数据库）
#   ./deploy.sh rebuild    # 重新构建后端/前端镜像
#   ./deploy.sh status     # 健康检查 + 访问地址
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

COMPOSE_FILE="docker-compose.prod.yml"
ENV_FILE=".env"
ENV_TEMPLATE=".env.deploy.example"

# ---------- 颜色输出 ----------
c_red()   { printf "\033[31m%s\033[0m\n" "$*"; }
c_green() { printf "\033[32m%s\033[0m\n" "$*"; }
c_yellow(){ printf "\033[33m%s\033[0m\n" "$*"; }
c_blue()  { printf "\033[34m%s\033[0m\n" "$*"; }

log()   { c_blue   "[$(date +%H:%M:%S)] $*"; }
ok()    { c_green  "[ OK ] $*"; }
warn()  { c_yellow "[WARN] $*"; }
fail()  { c_red    "[FAIL] $*"; exit 1; }

# ---------- 前置检查 ----------
need_root_or_docker_group() {
    if ! docker info >/dev/null 2>&1; then
        fail "当前用户没有 docker 权限。请使用 sudo 运行，或把当前用户加入 docker 组：sudo usermod -aG docker \$USER && newgrp docker"
    fi
}

check_prereq() {
    log "检查依赖..."
    command -v docker >/dev/null 2>&1 || fail "未检测到 docker，请先安装：curl -fsSL https://get.docker.com | sh"

    if docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
    else
        fail "未检测到 docker compose。请安装 Docker Engine 20.10.14+ 自带的 compose 插件"
    fi

    need_root_or_docker_group
    ok "docker / compose 可用（$COMPOSE_CMD）"
}

# ---------- 自动探测服务器公网 IP ----------
detect_server_ip() {
    local ip=""
    # 1) 先尝试外部接口
    ip=$(curl -fsS --max-time 3 ifconfig.me 2>/dev/null || true)
    if [[ -z "$ip" ]]; then
        ip=$(curl -fsS --max-time 3 https://api.ipify.org 2>/dev/null || true)
    fi
    # 2) 否则取本机第一个非 lo 的 IPv4
    if [[ -z "$ip" ]]; then
        ip=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
    fi
    echo "$ip"
}

# ---------- 生成/更新 .env ----------
init_env() {
    if [[ ! -f "$ENV_FILE" ]]; then
        log "首次部署：从模板生成 $ENV_FILE"
        cp "$ENV_TEMPLATE" "$ENV_FILE"
    fi

    # 自动生成 JWT 密钥
    if ! grep -E "^JWT_SECRET_KEY=..+" "$ENV_FILE" >/dev/null; then
        local jwt
        jwt=$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)
        sed -i.bak "s|^JWT_SECRET_KEY=.*|JWT_SECRET_KEY=$jwt|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
        ok "已自动生成 JWT_SECRET_KEY"
    fi

    # 自动生成 XHS cookie 池加密密钥（AES-GCM，≥32 字节）
    if ! grep -E "^XHS_COOKIE_SECRET=..+" "$ENV_FILE" >/dev/null; then
        local xhs_key
        xhs_key=$(openssl rand -base64 48 2>/dev/null || head -c 48 /dev/urandom | base64)
        if grep -qE "^XHS_COOKIE_SECRET=" "$ENV_FILE"; then
            sed -i.bak "s|^XHS_COOKIE_SECRET=.*|XHS_COOKIE_SECRET=$xhs_key|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
        else
            printf "\nXHS_COOKIE_SECRET=%s\n" "$xhs_key" >> "$ENV_FILE"
        fi
        ok "已自动生成 XHS_COOKIE_SECRET（小红书 Cookie 加密密钥）"
    fi

    # 如果 SERVER_IP 为空，自动探测 or 询问
    local cur_ip
    cur_ip=$(grep -E "^SERVER_IP=" "$ENV_FILE" | head -n1 | cut -d= -f2- || echo "")
    if [[ -z "$cur_ip" ]]; then
        local detected
        detected=$(detect_server_ip)
        if [[ -z "$detected" ]]; then
            read -r -p "请输入服务器对外 IP（无法自动检测）: " detected
        else
            read -r -p "检测到服务器 IP 为 [$detected]，按回车确认或输入新的 IP: " input
            detected="${input:-$detected}"
        fi
        [[ -n "$detected" ]] || fail "SERVER_IP 不能为空"
        sed -i.bak "s|^SERVER_IP=.*|SERVER_IP=$detected|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
        ok "SERVER_IP=$detected"
    fi

    # 根据 SERVER_IP 自动填充跨域白名单
    local server_ip web_port
    server_ip=$(grep -E "^SERVER_IP=" "$ENV_FILE" | cut -d= -f2-)
    web_port=$(grep -E "^WEB_PORT=" "$ENV_FILE" | cut -d= -f2-)
    [[ -z "$web_port" ]] && web_port=80
    local origins="http://localhost:*,http://127.0.0.1:*,http://${server_ip}:*,http://${server_ip}"
    if [[ "$web_port" != "80" ]]; then
        origins="$origins,http://${server_ip}:${web_port}"
    fi
    sed -i.bak "s|^SECURITY_ALLOWED_ORIGINS=.*|SECURITY_ALLOWED_ORIGINS=$origins|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"

    # 检查必填的 AI Key
    local deepseek embedding
    deepseek=$(grep -E "^DEEPSEEK_API_KEY=" "$ENV_FILE" | cut -d= -f2-)
    embedding=$(grep -E "^EMBEDDING_API_KEY=" "$ENV_FILE" | cut -d= -f2-)
    if [[ -z "$deepseek" ]]; then
        read -r -p "请输入 DeepSeek API Key（https://platform.deepseek.com/）: " deepseek
        sed -i.bak "s|^DEEPSEEK_API_KEY=.*|DEEPSEEK_API_KEY=$deepseek|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
    fi
    if [[ -z "$embedding" ]]; then
        read -r -p "请输入百炼 DashScope API Key（https://dashscope.console.aliyun.com/）: " embedding
        sed -i.bak "s|^EMBEDDING_API_KEY=.*|EMBEDDING_API_KEY=$embedding|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
    fi

    ok ".env 已就绪"
}

show_banner() {
    cat <<'EOF'
  ____       _ ____                       _
 |  _ \ __ _(_) ___| _ __ ___   __ _ _ __| |_
 | |_) / _` | \___ \| '_ ` _ \ / _` | '__| __|
 |  __/ (_| | |___) | | | | | | (_| | |  | |_
 |_|   \__,_|_|____/|_| |_| |_|\__,_|_|   \__|

 一键部署 · Docker 全栈 · 派聪明 RAG 知识库
EOF
}

cmd_up() {
    log "构建并启动所有容器（首次耗时较久，包含 Maven 拉依赖）..."
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE up -d --build
    ok "容器已启动，使用 './deploy.sh logs backend' 查看后端日志"
    cmd_status
}

cmd_down() {
    log "停止所有容器（保留数据卷）..."
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE down
    ok "已停止"
}

cmd_restart() {
    log "重启容器..."
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE restart
    cmd_status
}

cmd_rebuild() {
    log "重新构建后端和前端镜像..."
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE build --no-cache backend frontend
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE up -d backend frontend
    ok "已更新 backend & frontend"
}

cmd_logs() {
    local svc="${1:-}"
    if [[ -z "$svc" ]]; then
        $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE logs -f --tail=200
    else
        $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE logs -f --tail=200 "$svc"
    fi
}

cmd_ps() {
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE ps
}

cmd_clean() {
    c_red "警告：此操作会删除所有数据卷（MySQL/ES/MinIO 里的数据都会消失）"
    read -r -p "确认继续？输入 YES 继续，其他任意键取消: " ans
    if [[ "$ans" == "YES" ]]; then
        $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE down -v
        ok "已清理"
    else
        warn "已取消"
    fi
}

cmd_status() {
    echo
    c_green "==================== 容器状态 ===================="
    $COMPOSE_CMD -f $COMPOSE_FILE --env-file $ENV_FILE ps
    echo
    local server_ip web_port admin_user minio_console_port minio_api_port
    server_ip=$(grep -E "^SERVER_IP=" "$ENV_FILE" | cut -d= -f2-)
    web_port=$(grep -E "^WEB_PORT=" "$ENV_FILE" | cut -d= -f2-)
    admin_user=$(grep -E "^ADMIN_BOOTSTRAP_USERNAME=" "$ENV_FILE" | cut -d= -f2-)
    minio_console_port=$(grep -E "^MINIO_CONSOLE_PORT=" "$ENV_FILE" | cut -d= -f2-)
    minio_api_port=$(grep -E "^MINIO_API_PORT=" "$ENV_FILE" | cut -d= -f2-)
    [[ -z "$web_port" ]] && web_port=80
    [[ -z "$minio_console_port" ]] && minio_console_port=19001
    [[ -z "$minio_api_port" ]] && minio_api_port=19000

    local web_url="http://${server_ip}"
    [[ "$web_port" != "80" ]] && web_url="http://${server_ip}:${web_port}"

    c_green "==================== 访问地址 ===================="
    echo "前端（浏览器）    : $web_url"
    echo "后端 API          : $web_url/api/v1/"
    echo "MinIO 控制台      : http://${server_ip}:${minio_console_port}"
    echo "MinIO API         : http://${server_ip}:${minio_api_port}"
    echo
    c_green "==================== 初始账号 ===================="
    echo "管理员用户名 : ${admin_user:-admin}"
    echo "管理员密码   : 见 .env 的 ADMIN_BOOTSTRAP_PASSWORD"
    echo
    c_yellow "提示：后端首次启动需要 1~3 分钟完成 ES 插件安装 + Kafka topic 初始化，"
    c_yellow "      若浏览器打开白屏，请先用 './deploy.sh logs backend' 看后端是否 Started。"
}

main() {
    show_banner
    check_prereq

    local cmd="${1:-up}"

    case "$cmd" in
        up|"")
            init_env
            cmd_up
            ;;
        down|stop)    cmd_down ;;
        restart)      cmd_restart ;;
        rebuild)      cmd_rebuild ;;
        logs)         cmd_logs "${2:-}" ;;
        ps)           cmd_ps ;;
        status)       cmd_status ;;
        clean)        cmd_clean ;;
        -h|--help|help)
            grep -E "^# " "$0" | sed 's/^# //'
            ;;
        *)
            fail "未知命令: $cmd （使用 ./deploy.sh help 查看帮助）"
            ;;
    esac
}

main "$@"
