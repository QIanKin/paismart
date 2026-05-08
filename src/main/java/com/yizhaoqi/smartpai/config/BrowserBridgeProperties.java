package com.yizhaoqi.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 浏览器自动化桥接配置。
 *
 * <p>企业 MCN 场景下支持两种浏览器模式：
 * <pre>
 *   managed  → 后端服务器自己启动 Playwright Chromium + 持久 profile
 *   external → 通过 CDP 连接外部已登录 Chrome / Browserless / OpenCLI 兼容浏览器
 * </pre>
 *
 * <p>当前主要用于：
 * <ul>
 *   <li>{@code xhs-pgy-kol} / {@code xhs-pgy-kol-detail}：蒲公英品牌侧 KOL 列表 / 粉丝画像 skill</li>
 *   <li>{@code xhs-qr-login}：扫码采集蒲公英 cookie</li>
 * </ul>
 *
 * <p>公开数据全部走 TikHub 公开 API，不再依赖浏览器自动化。
 *
 * <p>配置项前缀：{@code smartpai.browser}
 */
@Configuration
@ConfigurationProperties(prefix = "smartpai.browser")
public class BrowserBridgeProperties {

    /**
     * 业务员 Chrome 的 CDP endpoint。
     *
     * <p>默认走 9223 端口（业务员本机经过 Node CDP 代理改写 Host 头）。直连
     * Chrome 9222 会被 Chrome 147+ 以"非 localhost 源"为由拒绝，因此必须经
     * 代理。如果后端直接跑在宿主机 JVM 里，可以改成 {@code http://127.0.0.1:9222}。
     */
    private String cdpEndpoint = "http://host.docker.internal:9223";

    /**
     * 浏览器运行模式：
     * <ul>
     *   <li>managed：服务器自建 Chromium profile，适合正式部署。</li>
     *   <li>external：连接已有 CDP endpoint，适合调试或接业务员电脑/OpenCLI。</li>
     * </ul>
     */
    private String mode = "managed";

    /** managed 模式下保存 org/account 浏览器登录态的根目录。 */
    private String profileRoot = "./var/browser-profiles";

    /** managed 模式是否无头运行。生产默认 true；排障可临时改 false。 */
    private boolean headless = true;

    /** 容器内通常需要 --no-sandbox。 */
    private boolean noSandbox = true;

    /** 可选：指定 Chromium/Chrome 可执行文件；留空则让 Playwright 使用自带浏览器。 */
    private String executablePath = "";

    /**
     * NODE_PATH，预装 playwright 的 node_modules 目录。
     * 传给 node 子进程，让 .mjs 能 import 到 playwright。
     */
    private String nodeModulesPath = "/app/skills-bundled/_shared/playwright-runtime/node_modules";

    /** 单个 skill 跑完的上限（秒） */
    private int defaultTimeoutSeconds = 600;

    public String getCdpEndpoint() {
        return cdpEndpoint;
    }

    public void setCdpEndpoint(String cdpEndpoint) {
        this.cdpEndpoint = cdpEndpoint;
    }

    public String getMode() { return mode; }

    public void setMode(String mode) { this.mode = mode; }

    public String getProfileRoot() { return profileRoot; }

    public void setProfileRoot(String profileRoot) { this.profileRoot = profileRoot; }

    public boolean isHeadless() { return headless; }

    public void setHeadless(boolean headless) { this.headless = headless; }

    public boolean isNoSandbox() { return noSandbox; }

    public void setNoSandbox(boolean noSandbox) { this.noSandbox = noSandbox; }

    public String getExecutablePath() { return executablePath; }

    public void setExecutablePath(String executablePath) { this.executablePath = executablePath; }

    public String getNodeModulesPath() {
        return nodeModulesPath;
    }

    public void setNodeModulesPath(String nodeModulesPath) {
        this.nodeModulesPath = nodeModulesPath;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }
}
