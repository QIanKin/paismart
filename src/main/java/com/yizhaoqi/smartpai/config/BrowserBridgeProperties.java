package com.yizhaoqi.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 浏览器自动化桥接配置。
 *
 * <p>企业 MCN 场景下，我们**不**在服务器里跑 headless Chromium（反爬易识别 + 镜像太大），
 * 而是通过 CDP 连接业务员本机已登录的 Chrome：
 * <pre>
 *   [业务员电脑 Chrome :9222]  ←── CDP WS ──  [后端容器 skill/.mjs]
 * </pre>
 *
 * <p>涉及这个模式的 skill：
 * <ul>
 *   <li>{@code xhs-outreach-comment}：批量评论触达</li>
 *   <li>{@code qiangua-brand-discover}：千瓜品牌达人发现</li>
 * </ul>
 *
 * <p>配置项前缀：{@code smartpai.browser}
 */
@Configuration
@ConfigurationProperties(prefix = "smartpai.browser")
public class BrowserBridgeProperties {

    /** 业务员 Chrome 的 CDP endpoint，默认假设容器能通过 host.docker.internal 访问宿主机 */
    private String cdpEndpoint = "http://host.docker.internal:9222";

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
