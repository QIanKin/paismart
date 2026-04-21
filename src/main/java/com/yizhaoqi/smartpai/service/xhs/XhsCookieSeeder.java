package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.repository.xhs.XhsCookieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 启动时把 Playwright / CDP 导出格式的 cookies.json 一次性导入 {@code xhs_cookies} 表。
 *
 * <p>场景：第一次部署时从 openclaw 备份里搬登录态过来，免得再手动去 admin 页面一条一条贴。
 *
 * <p>触发条件（全部为 true 才跑）：
 * <ol>
 *   <li>{@code smartpai.xhs.seed.enabled=true}</li>
 *   <li>文件存在（默认 {@code classpath:seed/xhs-mcp-cookies.json}）</li>
 *   <li>同 org+platform+accountLabel 还没被导入过（按 accountLabel 幂等）</li>
 * </ol>
 *
 * <p>导入到的位置：{@code ownerOrgTag=default}（公司共享池）、{@code platform=xhs_pc}。
 * 这样所有 org 的用户在找自己 org 没 cookie 时，会自动走
 * {@link XhsCookieService#pickAvailable} 的 SHARED_ORG_TAG fallback。
 */
@Component
public class XhsCookieSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(XhsCookieSeeder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 只认主域的 cookie，避免 acw_tc/www-specific 这些 subdomain 重复覆盖 */
    private static final Set<String> ACCEPT_DOMAINS = Set.of(
            ".xiaohongshu.com", "xiaohongshu.com", "www.xiaohongshu.com");

    /** Spider_XHS 最低需要的关键字段，缺了就不是一份有效的 PC 登录态 */
    private static final Set<String> REQUIRED_NAMES = Set.of("a1", "web_session", "webId");

    private final XhsCookieService service;
    private final XhsCookieRepository repo;
    private final ResourceLoader resourceLoader;

    @Value("${smartpai.xhs.seed.enabled:false}")
    private boolean enabled;

    @Value("${smartpai.xhs.seed.path:classpath:seed/xhs-mcp-cookies.json}")
    private String seedPath;

    @Value("${smartpai.xhs.seed.org:default}")
    private String seedOrg;

    @Value("${smartpai.xhs.seed.platform:xhs_pc}")
    private String seedPlatform;

    @Value("${smartpai.xhs.seed.label:seed-openclaw-backup}")
    private String seedLabel;

    @Value("${smartpai.xhs.seed.note:从 openclaw-backup 导入的种子 cookie（公司共享池）}")
    private String seedNote;

    @Value("${smartpai.xhs.seed.priority:20}")
    private int seedPriority;

    public XhsCookieSeeder(XhsCookieService service,
                           XhsCookieRepository repo,
                           ResourceLoader resourceLoader) {
        this.service = service;
        this.repo = repo;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            Resource res = resourceLoader.getResource(seedPath);
            if (!res.exists()) {
                log.warn("[XhsCookieSeeder] 启用了种子导入但文件不存在: {}", seedPath);
                return;
            }

            Optional<XhsCookie> existing = repo
                    .findFirstByOwnerOrgTagAndPlatformAndAccountLabel(seedOrg, seedPlatform, seedLabel);
            if (existing.isPresent()) {
                log.info("[XhsCookieSeeder] 跳过：org={} platform={} label={} 已存在 (id={})",
                        seedOrg, seedPlatform, seedLabel, existing.get().getId());
                return;
            }

            String cookieStr = parsePlaywrightJson(res);
            if (cookieStr == null || cookieStr.isBlank()) {
                log.warn("[XhsCookieSeeder] 从 {} 解析不出有效的 cookie 字符串，跳过", seedPath);
                return;
            }

            // Seeder 导入的是 xiaohongshu-mcp 备份里的完整 cookies.json，一定带 a1/web_session/webId；
            // 如果用户塞了个裁剪过的 seed 文件导致校验失败，那就应该让它失败，而不是偷偷写库。
            XhsCookie saved = service.create(
                    seedOrg, seedPlatform, cookieStr, seedLabel, seedNote, seedPriority, "seeder");
            log.info("[XhsCookieSeeder] 已导入种子 cookie id={} org={} platform={} label={} 核心字段={}",
                    saved.getId(), seedOrg, seedPlatform, seedLabel,
                    countRequired(cookieStr));
        } catch (Exception e) {
            log.error("[XhsCookieSeeder] 导入失败", e);
        }
    }

    /**
     * 解析 Playwright / CDP 导出的 cookies.json（一个对象数组），
     * 过滤主域，按 name 去重（后面覆盖前面，同名取最后出现的一份），拼成 "k=v; k=v"。
     */
    private String parsePlaywrightJson(Resource res) throws Exception {
        JsonNode root;
        try (InputStream in = res.getInputStream()) {
            root = MAPPER.readTree(in.readAllBytes());
        }
        if (!root.isArray()) {
            log.warn("[XhsCookieSeeder] 期望顶层是数组，但实际是 {}", root.getNodeType());
            return null;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (JsonNode entry : root) {
            String domain = text(entry, "domain");
            if (domain == null || !ACCEPT_DOMAINS.contains(domain)) continue;
            String name = text(entry, "name");
            String value = text(entry, "value");
            if (name == null || name.isBlank() || value == null) continue;
            merged.put(name, value);
        }
        if (merged.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(merged.size() * 32);
        Iterator<Map.Entry<String, String>> it = merged.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            sb.append(e.getKey()).append('=').append(e.getValue());
            if (it.hasNext()) sb.append("; ");
        }
        return sb.toString();
    }

    private int countRequired(String cookieStr) {
        int hit = 0;
        for (String name : REQUIRED_NAMES) {
            if (cookieStr.contains(name + "=")) hit++;
        }
        return hit;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** 让其他 seeder 或测试类可以复用的静态入口（脱离 Spring 调试用）。 */
    public static String parseForDebug(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json.getBytes(StandardCharsets.UTF_8));
        Map<String, String> merged = new LinkedHashMap<>();
        for (JsonNode entry : root) {
            String domain = entry.path("domain").asText("");
            if (!Arrays.asList(".xiaohongshu.com", "xiaohongshu.com", "www.xiaohongshu.com").contains(domain)) continue;
            merged.put(entry.path("name").asText(""), entry.path("value").asText(""));
        }
        StringBuilder sb = new StringBuilder();
        merged.forEach((k, v) -> sb.append(k).append('=').append(v).append("; "));
        return sb.toString().replaceAll("; $", "");
    }
}
