package com.yizhaoqi.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 扫码登录采集配置。前缀 {@code smartpai.xhs-login}。
 *
 * <p>和 {@link BrowserBridgeProperties} 的区别：
 * <ul>
 *     <li>BrowserBridgeProperties → 连 <b>业务员本机 Chrome</b>（CDP）做外联评论、达人发现</li>
 *     <li>XhsLoginProperties → 后端容器里跑 <b>自带 Chromium</b>，给业务员弹二维码采 cookie</li>
 * </ul>
 *
 * <p>典型配置：
 * <pre>
 * smartpai:
 *   xhs-login:
 *     enabled: true
 *     skill-path: /app/skills-bundled/xhs-qr-login/run.mjs
 *     node-modules-path: /app/skills-bundled/xhs-qr-login/node_modules
 *     browsers-path: /ms-playwright
 *     expires-seconds: 180
 *     single-flight-per-user: true
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "smartpai.xhs-login")
public class XhsLoginProperties {

    /** 总开关。关闭后 Controller 直接 404，前端应相应隐藏入口。 */
    private boolean enabled = true;

    /** Node 脚本绝对路径。 */
    private String skillPath = "/app/skills-bundled/xhs-qr-login/run.mjs";

    /** Node 子进程可见的 node_modules（用于 import playwright）。 */
    private String nodeModulesPath = "/app/skills-bundled/xhs-qr-login/node_modules";

    /** Playwright 浏览器存储目录（镜像里 install chromium 的目标路径）。 */
    private String browsersPath = "/ms-playwright";

    /** 会话总超时（秒），超过则 EXPIRE，杀掉子进程。 */
    private int expiresSeconds = 180;

    /** 每个用户是否同时只允许一个活跃登录会话（推荐 true）。 */
    private boolean singleFlightPerUser = true;

    /** 回收定时器的 cron（默认每分钟）。 */
    private String janitorCron = "0 * * * * *";

    /** 采集的平台列表（默认四个）。 */
    private String defaultPlatforms = "xhs_pc,xhs_creator,xhs_pgy,xhs_qianfan";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSkillPath() { return skillPath; }
    public void setSkillPath(String skillPath) { this.skillPath = skillPath; }
    public String getNodeModulesPath() { return nodeModulesPath; }
    public void setNodeModulesPath(String nodeModulesPath) { this.nodeModulesPath = nodeModulesPath; }
    public String getBrowsersPath() { return browsersPath; }
    public void setBrowsersPath(String browsersPath) { this.browsersPath = browsersPath; }
    public int getExpiresSeconds() { return expiresSeconds; }
    public void setExpiresSeconds(int expiresSeconds) { this.expiresSeconds = expiresSeconds; }
    public boolean isSingleFlightPerUser() { return singleFlightPerUser; }
    public void setSingleFlightPerUser(boolean singleFlightPerUser) { this.singleFlightPerUser = singleFlightPerUser; }
    public String getJanitorCron() { return janitorCron; }
    public void setJanitorCron(String janitorCron) { this.janitorCron = janitorCron; }
    public String getDefaultPlatforms() { return defaultPlatforms; }
    public void setDefaultPlatforms(String defaultPlatforms) { this.defaultPlatforms = defaultPlatforms; }
}
