package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieHealthService;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code xhs_cookie_ping}：对单条或多条数据源凭证做一次真实的连通性自检。
 *
 * <p>内部包装 {@link XhsCookieHealthService}，每条耗时上限 10s；失败只更新 lastCheckedAt/lastError，
 * 不增加 failCount、不标 EXPIRED（与 REST 端点 {@code POST /admin/xhs-cookies/{id}/ping} 同语义）。
 *
 * <p>调用约定：{@code ids} 和 {@code platform} 至少提供一个。
 * <ul>
 *     <li>给 {@code ids=[1,2,3]}：按 id 逐条 ping；没有权限的（跨 org）直接返回 not_found。</li>
 *     <li>给 {@code platform=xhs_pc}：对本 org 下该 platform 的所有 ACTIVE 记录批量 ping。</li>
 * </ul>
 *
 * <p>权限：仅管理员可用。
 */
@Component
public class XhsCookiePingTool implements Tool {

    private final XhsCookieHealthService health;
    private final XhsCookieService cookies;
    private final JsonNode schema;

    /** 单次调用最多 ping 多少条，避免 agent 一口气把十几条账号全打一遍阻塞 UI。 */
    private static final int MAX_BATCH = 10;

    public XhsCookiePingTool(XhsCookieHealthService health, XhsCookieService cookies) {
        this.health = health;
        this.cookies = cookies;
        ObjectNode idsItem = ToolInputSchemas.mapper().createObjectNode();
        idsItem.put("type", "integer");
        this.schema = ToolInputSchemas.object()
                .arrayProp("ids",
                        "指定要 ping 的 cookie id 数组（最多 " + MAX_BATCH + " 条）。",
                        idsItem,
                        false)
                .enumProp("platform",
                        "批量 ping 该 platform 下本 org 的所有 ACTIVE 凭证。与 ids 二选一。",
                        List.of("xhs_pc", "xhs_creator", "xhs_pgy", "xhs_qianfan",
                                "xhs_spotlight", "xhs_competitor"),
                        false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_ping"; }

    @Override public String description() {
        return "对小红书/聚光/竞品 数据源凭证做连通性自检。可按 ids 精准 ping，也可按 platform 批量 ping。"
                + "失败不会降权 / 不会标 EXPIRED —— 只用于诊断。仅管理员可用。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    /** ping 会更新 lastCheckedAt 但不改任何业务指标，从 agent 调度角度视作只读。 */
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        if (!"admin".equalsIgnoreCase(ctx.role())) {
            return PermissionResult.deny("xhs_cookie_ping 仅管理员可用，当前 role=" + ctx.role());
        }
        if (ctx.orgTag() == null || ctx.orgTag().isBlank()) {
            return PermissionResult.deny("缺少 orgTag，拒绝");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        List<Long> targetIds = resolveTargets(orgTag, input);
        if (targetIds.isEmpty()) {
            return ToolResult.error("没有命中可 ping 的 cookie。请提供 ids 或 platform（本 org 下该 platform 没有 ACTIVE 记录也会返回空）。");
        }
        if (targetIds.size() > MAX_BATCH) {
            targetIds = targetIds.subList(0, MAX_BATCH);
        }

        List<Map<String, Object>> results = new ArrayList<>(targetIds.size());
        int okCount = 0;
        int failCount = 0;
        for (Long id : targetIds) {
            XhsCookieHealthService.PingResult r = health.ping(id, orgTag);
            if (r.ok()) okCount++; else failCount++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("ok", r.ok());
            row.put("latencyMs", r.latencyMs());
            row.put("errorType", r.errorType());
            row.put("message", r.message());
            row.put("platformSignal", r.platformSignal());
            row.put("checkedAt", r.checkedAt() == null ? null : r.checkedAt().toString());
            results.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orgTag", orgTag);
        data.put("pinged", targetIds);
        data.put("okCount", okCount);
        data.put("failCount", failCount);
        data.put("results", results);
        String summary = String.format("xhs_cookie_ping → %d OK / %d FAIL (共 %d 条)",
                okCount, failCount, results.size());
        return ToolResult.of(data, summary);
    }

    private List<Long> resolveTargets(String orgTag, JsonNode input) {
        List<Long> ids = new ArrayList<>();
        if (input.has("ids") && input.get("ids").isArray()) {
            for (JsonNode n : input.get("ids")) {
                if (n.canConvertToLong()) ids.add(n.asLong());
            }
        }
        if (!ids.isEmpty()) return ids;
        if (input.has("platform")) {
            String platform = input.get("platform").asText(null);
            if (platform == null || platform.isBlank()) return ids;
            List<XhsCookie> list = cookies.list(orgTag);
            for (XhsCookie c : list) {
                if (!platform.equalsIgnoreCase(c.getPlatform())) continue;
                if (c.getStatus() != XhsCookie.Status.ACTIVE) continue;
                ids.add(c.getId());
            }
        }
        return ids;
    }
}
