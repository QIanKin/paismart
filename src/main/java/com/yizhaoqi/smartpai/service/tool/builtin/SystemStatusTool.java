package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code system_status}：给 Agent 一个"一眼看清系统配置是否齐全"的自检接口。
 *
 * <p>返回维度：
 * <ul>
 *     <li><b>dataSources</b>：各 platform 下的 cookie 数量（按状态分桶）+ 是否有 ACTIVE 记录</li>
 *     <li><b>agentCapabilities</b>：注册的 tool / skill 数量，当前 ctx 的 role/orgTag/projectId</li>
 *     <li><b>missingConfig</b>：枚举已知关键配置项（cdp-endpoint、spotlight 凭证、共享 cookie 池）是否缺失，
 *         附上修复建议文本供 LLM 直接转发给用户</li>
 * </ul>
 *
 * <p>本工具只读，不碰数据库写路径；Phase 4c 起对所有登录用户开放。
 */
@Component
public class SystemStatusTool implements Tool {

    private final XhsCookieService cookies;
    private final SkillRegistry skills;
    private final ToolRegistry tools;
    private final JsonNode schema;

    @Value("${smartpai.browser.cdp-endpoint:}")
    private String cdpEndpoint;

    @Value("${smartpai.xhs.spotlight-seed.enabled:false}")
    private boolean spotlightSeedEnabled;

    @Value("${smartpai.xhs.spotlight-seed.advertiser-id:}")
    private String spotlightAdvertiserId;

    public SystemStatusTool(XhsCookieService cookies, SkillRegistry skills, @Lazy ToolRegistry tools) {
        // ToolRegistry itself depends on the full List<Tool>, which includes this bean, forming a cycle.
        // Resolve by letting Spring inject a lazy proxy; the real ToolRegistry is only resolved on first call().
        this.cookies = cookies;
        this.skills = skills;
        this.tools = tools;
        this.schema = ToolInputSchemas.object()
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "system_status"; }

    @Override public String description() {
        return "返回系统自检快照：数据源 cookie 按 platform/状态的分桶、skill/tool 数量、关键配置是否缺失。"
                + "在用户问『现在系统状况如何/数据抓取能用吗/缺什么配置』时先调这个。只读，所有用户可用。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        // Agent godmode：所有登录用户都能让 Agent 看当前 org 的系统运行状态（只读聚合，无敏感凭证内容）。
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();

        // 1. 数据源聚合
        Map<String, Map<String, Integer>> byPlatform = new TreeMap<>();
        // 确保就算本 org 一条记录都没有，常见平台也会出现在报告里，便于 LLM 发现"这个平台啥都没配"
        for (String p : XhsCookieService.PLATFORMS) {
            byPlatform.put(p, emptyStatusBucket());
        }
        List<XhsCookie> orgCookies = orgTag == null ? List.of() : cookies.list(orgTag);
        for (XhsCookie c : orgCookies) {
            Map<String, Integer> bucket = byPlatform.computeIfAbsent(c.getPlatform(), k -> emptyStatusBucket());
            String key = c.getStatus() == null ? "ACTIVE" : c.getStatus().name();
            bucket.merge(key, 1, Integer::sum);
            bucket.merge("total", 1, Integer::sum);
        }

        // 2. Agent 能力
        Collection<Tool> allTools = tools.all();
        List<LoadedSkill> visibleSkills = skills.listVisible(orgTag);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("toolCount", allTools.size());
        capabilities.put("skillCount", visibleSkills.size());
        capabilities.put("role", ctx.role());
        capabilities.put("orgTag", orgTag);
        capabilities.put("projectId", ctx.projectId());
        capabilities.put("sessionId", ctx.sessionId());

        // 3. 关键配置自检
        List<Map<String, String>> missing = new ArrayList<>();

        if (cdpEndpoint == null || cdpEndpoint.isBlank()) {
            missing.add(issue("browser.cdp-endpoint",
                    "浏览器 CDP 未配置",
                    "千瓜发现 / 撤回评论 等依赖真实登录态的 skill 将无法运行。"
                            + "在 .env 里填 SMARTPAI_BROWSER_CDP_ENDPOINT=http://<业务员机器>:9222。"));
        }

        long spotlightActive = orgCookies.stream()
                .filter(c -> "xhs_spotlight".equalsIgnoreCase(c.getPlatform()))
                .filter(c -> c.getStatus() == XhsCookie.Status.ACTIVE)
                .count();
        if (spotlightActive == 0) {
            String hint = spotlightSeedEnabled
                    ? ("XhsSpotlightSeeder 已启用但没落成 ACTIVE 记录，检查 XHS_SPOTLIGHT_ADVERTISER_ID="
                            + maskOrDash(spotlightAdvertiserId)
                            + " / ACCESS_TOKEN / REFRESH_TOKEN 是否都非空。")
                    : "开 XHS_SPOTLIGHT_SEED_ENABLED=true + 填 3 个 token 后重启自动落库，或手工在 /data-sources 聚光面板录入。";
            missing.add(issue("xhs_spotlight.credential",
                    "聚光 MarketingAPI 当前 org 无 ACTIVE 凭证",
                    hint));
        }

        long sharedPool = cookies.list(XhsCookieService.SHARED_ORG_TAG).stream()
                .filter(c -> XhsCookieService.isXhsWebCookiePlatform(c.getPlatform()))
                .filter(c -> c.getStatus() == XhsCookie.Status.ACTIVE)
                .count();
        if (sharedPool == 0) {
            missing.add(issue("xhs_web.shared-pool",
                    "共享 org (=default) 里没有任何 ACTIVE 的 xhs web cookie",
                    "跨租户用户找不到自己 org 的 cookie 时会回退到共享池，建议让管理员在 default org 下至少保留 1 条。"));
        }

        // 4. 汇总返回
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dataSources", byPlatform);
        data.put("agentCapabilities", capabilities);
        data.put("missingConfig", missing);
        data.put("missingCount", missing.size());

        String summary = String.format(
                "system_status: tools=%d skills=%d cookies=%d missingConfig=%d",
                allTools.size(), visibleSkills.size(), orgCookies.size(), missing.size());
        return ToolResult.of(data, summary);
    }

    private static Map<String, Integer> emptyStatusBucket() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("total", 0);
        m.put("ACTIVE", 0);
        m.put("EXPIRED", 0);
        m.put("BANNED", 0);
        m.put("DISABLED", 0);
        return m;
    }

    private static Map<String, String> issue(String code, String title, String hint) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("title", title);
        m.put("hint", hint);
        return m;
    }

    private static String maskOrDash(String s) {
        if (s == null || s.isBlank()) return "<empty>";
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
