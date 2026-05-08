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
 * xhs_trending：拉小红书热搜词列表。走 TikHub 公开 API。
 */
@Component
public class XhsTrendingTool implements Tool {

    private final TikhubXhsService tikhubService;
    private final JsonNode schema;

    public XhsTrendingTool(TikhubXhsService tikhubService) {
        this.tikhubService = tikhubService;
        this.schema = ToolInputSchemas.object()
                .integerProp("limit", "返回条数上限，默认 30", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_trending"; }

    @Override public String description() {
        return "拉小红书搜索热搜词列表（含热度值/分类）。可用于赛道关键词监控。走 TikHub 公开 API。";
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
        int limit = Math.max(1, Math.min(input.path("limit").asInt(30), 200));

        TikhubXhsService.TrendingResult res = tikhubService.fetchTrending();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (res.keywords != null) {
            for (TikhubXhsService.TrendingKeyword k : res.keywords) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("keyword", k.keyword);
                m.put("heat", k.heat);
                m.put("type", k.type);
                rows.add(m);
                if (rows.size() >= limit) break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("items", rows);
        return ToolResult.of(out, String.format("热搜词 → 拿到 %d 条", rows.size()));
    }
}
