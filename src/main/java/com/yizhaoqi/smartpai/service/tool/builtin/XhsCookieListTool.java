package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code xhs_cookie_list}：列出当前 org 下所有数据源凭证的"健康卡片"，让 Agent 能自检 / 告诉用户哪些该换。
 *
 * <p>明文 cookie 值永远不回传；只有 {@code cookieKeys}（字段名清单）+ 状态指标。
 *
 * <p>权限：所有登录用户可见（cookie 内容本身已脱敏为 preview，不会暴露完整凭证）。
 */
@Component
public class XhsCookieListTool implements Tool {

    private final XhsCookieService service;
    private final JsonNode schema;

    public XhsCookieListTool(XhsCookieService service) {
        this.service = service;
        this.schema = ToolInputSchemas.object()
                .enumProp("platform",
                        "只看某个平台：xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan / xhs_spotlight / xhs_competitor。"
                                + "省略则返回全部。",
                        List.of("xhs_pc", "xhs_creator", "xhs_pgy", "xhs_qianfan",
                                "xhs_spotlight", "xhs_competitor"),
                        false)
                .enumProp("status",
                        "只看某个状态。省略则返回全部。",
                        List.of("ACTIVE", "EXPIRED", "BANNED", "DISABLED"),
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_list"; }

    @Override public String description() {
        return "列出当前企业下所有小红书/聚光/竞品 数据源凭证（健康卡片，不含明文），"
                + "用于 agent 自检数据源状态、指导用户哪些平台该补/该换凭证。所有登录用户可用。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        // Agent godmode：所有登录用户都能让 Agent 查自己 org 下的凭证清单（cookie 内容本身已脱敏）。
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，无法定位当前组织");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        String platformFilter = input.has("platform") ? input.get("platform").asText(null) : null;
        String statusFilter = input.has("status") ? input.get("status").asText(null) : null;

        List<XhsCookie> all = service.list(orgTag);
        List<Map<String, Object>> rows = new ArrayList<>(all.size());
        int active = 0;
        int expired = 0;
        int banned = 0;
        int disabled = 0;

        for (XhsCookie c : all) {
            if (platformFilter != null && !platformFilter.equalsIgnoreCase(c.getPlatform())) continue;
            if (statusFilter != null && c.getStatus() != null
                    && !statusFilter.equalsIgnoreCase(c.getStatus().name())) continue;

            switch (c.getStatus() == null ? XhsCookie.Status.ACTIVE : c.getStatus()) {
                case ACTIVE -> active++;
                case EXPIRED -> expired++;
                case BANNED -> banned++;
                case DISABLED -> disabled++;
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.getId());
            r.put("platform", c.getPlatform());
            r.put("accountLabel", c.getAccountLabel());
            r.put("status", c.getStatus() == null ? null : c.getStatus().name());
            r.put("priority", c.getPriority());
            r.put("successCount", c.getSuccessCount());
            r.put("failCount", c.getFailCount());
            r.put("cookieKeys", c.getCookieKeys());
            r.put("cookiePreview", c.getCookiePreview());
            r.put("source", c.getSource() == null ? null : c.getSource().name());
            r.put("lastUsedAt", c.getLastUsedAt() == null ? null : c.getLastUsedAt().toString());
            r.put("lastCheckedAt", c.getLastCheckedAt() == null ? null : c.getLastCheckedAt().toString());
            r.put("lastError", c.getLastError());
            rows.add(r);
        }

        Map<String, Integer> byStatus = Map.of(
                "ACTIVE", active, "EXPIRED", expired, "BANNED", banned, "DISABLED", disabled);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orgTag", orgTag);
        data.put("filterPlatform", platformFilter);
        data.put("filterStatus", statusFilter);
        data.put("total", rows.size());
        data.put("byStatus", byStatus);
        data.put("cookies", rows);

        String summary = String.format("xhs_cookie_list org=%s → %d 条 (active=%d expired=%d banned=%d disabled=%d)",
                orgTag, rows.size(), active, expired, banned, disabled);
        return ToolResult.of(data, summary);
    }
}
