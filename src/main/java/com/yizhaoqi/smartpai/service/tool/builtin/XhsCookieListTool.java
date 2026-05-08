package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
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
 * xhs_cookie_list：只读工具。让 Agent 能查询当前组织下蒲公英 (PGY) cookie 池的健康度，
 * 在调用 xhs_pgy_* 之前预判是否有可用凭证、是否需要让用户去扫码。
 *
 * <p>当前架构下只剩 {@code xhs_pgy} 一个 platform。其它历史 platform 仍允许返回，便于看到老库残留。
 */
@Component
public class XhsCookieListTool implements Tool {

    private final XhsCookieService cookies;
    private final JsonNode schema;

    public XhsCookieListTool(XhsCookieService cookies) {
        this.cookies = cookies;
        this.schema = ToolInputSchemas.object()
                .stringProp("platform", "可选筛选：xhs_pgy / xhs_spotlight；不传返回全部", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_cookie_list"; }

    @Override public String description() {
        return "查询当前组织 cookie 池状态（蒲公英为主）。返回每条 cookie 的 platform / accountLabel / status / "
                + "成功失败计数 / 上次使用时间，用于诊断 \"调 xhs_pgy_* 失败\" 是不是 cookie 失效。"
                + "**只读工具**，不会修改任何数据。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String platformFilter = input.path("platform").asText("").trim();
        List<XhsCookie> list = cookies.list(ctx.orgTag());
        List<Map<String, Object>> rows = new ArrayList<>();
        int active = 0;
        int expired = 0;
        for (XhsCookie c : list) {
            if (!platformFilter.isBlank() && !platformFilter.equalsIgnoreCase(c.getPlatform())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("platform", c.getPlatform());
            m.put("accountLabel", c.getAccountLabel());
            m.put("status", c.getStatus() == null ? null : c.getStatus().name());
            m.put("priority", c.getPriority());
            m.put("successCount", c.getSuccessCount());
            m.put("failureCount", c.getFailCount());
            m.put("lastUsedAt", c.getLastUsedAt());
            m.put("note", c.getNote());
            rows.add(m);
            if (c.getStatus() == XhsCookie.Status.ACTIVE) active++;
            else if (c.getStatus() == XhsCookie.Status.EXPIRED) expired++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgTag", ctx.orgTag());
        out.put("total", rows.size());
        out.put("active", active);
        out.put("expired", expired);
        out.put("items", rows);
        String summary;
        if (rows.isEmpty()) {
            summary = "cookie 池空。需要在「数据源中心 → 蒲公英」用扫码登录补一条。";
        } else if (active == 0) {
            summary = "共 " + rows.size() + " 条 cookie 但全部失效，需要重新扫码登录。";
        } else {
            summary = "共 " + rows.size() + " 条 cookie，其中 ACTIVE=" + active + "，可用。";
        }
        return ToolResult.of(out, summary);
    }
}
