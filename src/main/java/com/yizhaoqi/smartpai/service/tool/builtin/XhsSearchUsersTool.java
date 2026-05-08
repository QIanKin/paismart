package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_search_users：按昵称 / 小红书号关键词搜索小红书用户，返回 (userId, redId, 昵称, 头像, 粉丝数)。
 *
 * <p>这是 TikHub 链路里"通过昵称定位 userId"的入口。Agent 工作流推荐：
 * <pre>
 *   1) 用户给了 userId（16+ 位 hex）或 主页链接 (/user/profile/&lt;userId&gt;) → 跳过本工具，直接用 userId
 *   2) 否则 → 调本工具拿 candidate users → 选最匹配的 userId
 *   3) 用 userId 调 xhs_user_notes / xhs_refresh_creator
 * </pre>
 */
@Component
public class XhsSearchUsersTool implements Tool {

    private final TikhubXhsService tikhubService;
    private final JsonNode schema;

    public XhsSearchUsersTool(TikhubXhsService tikhubService) {
        this.tikhubService = tikhubService;
        this.schema = ToolInputSchemas.object()
                .stringProp("keyword", "搜索关键词（昵称 / 小红书号）。必填", true)
                .integerProp("limit", "最多返回几个候选，默认 10，上限 50", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_search_users"; }

    @Override public String description() {
        return "按昵称/小红书号搜索小红书用户。返回 (userId, redId, 昵称, 头像, 粉丝数, 主页链接) 列表。"
                + "Agent 拿到 userId 后再去调 xhs_user_notes / xhs_refresh_creator；"
                + "如果用户已经直接给了 userId 或主页链接，直接用就行，不需要本工具。"
                + "走 TikHub 公开 API，零 cookie 依赖。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        if (!tikhubService.configured()) {
            return ToolResult.error("provider_not_configured",
                    "TikHub 未配置：请设置 XHS_TIKHUB_ENABLED=true 与 XHS_TIKHUB_API_KEY");
        }
        String keyword = input.path("keyword").asText("");
        if (keyword.isBlank()) return ToolResult.error("bad_input", "keyword 必填");
        int limit = Math.max(1, Math.min(input.path("limit").asInt(10), 50));

        List<Map<String, Object>> rows = new ArrayList<>();
        int page = 1;
        while (rows.size() < limit && page <= 5) {
            TikhubXhsService.UserSearchResult res = tikhubService.searchUsers(keyword, page);
            if (res.users == null || res.users.isEmpty()) break;
            for (TikhubXhsService.UserSummary u : res.users) {
                rows.add(toMap(u));
                if (rows.size() >= limit) break;
            }
            if (!res.hasMore) break;
            page++;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keyword", keyword);
        payload.put("total", rows.size());
        payload.put("items", rows);
        return ToolResult.of(payload,
                String.format("搜索用户 '%s' → 命中 %d 个候选", keyword, rows.size()));
    }

    private static Map<String, Object> toMap(TikhubXhsService.UserSummary u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", u.userId);
        out.put("redId", u.redId);
        out.put("nickname", u.nickname);
        out.put("avatar", u.avatar);
        out.put("fansText", u.fansText);
        out.put("noteCount", u.noteCount);
        out.put("verified", u.verified);
        out.put("xsecToken", u.xsecToken);
        out.put("homepageUrl", "https://www.xiaohongshu.com/user/profile/" + u.userId);
        out.put("link", u.link);
        return out;
    }
}
